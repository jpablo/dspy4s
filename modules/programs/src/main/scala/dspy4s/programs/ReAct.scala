package dspy4s.programs

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.updated
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.AgentLoop
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.programs.runtime.TrajectoryAgent
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.typed.OutputAugmentation.PrependField
import dspy4s.typed.{InputAugmentation, OutputAugmentation, Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized over
  * any typed signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the injected
  * `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the full
  * trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * `ReAct[I, O]` is a `Module[I, WithReasoning[O]]`: the input is encoded from `I`, and the
  * extractor's reply is decoded into the base outputs `O` with a `reasoning: String` prepended (always a named tuple;
  * see [[OutputAugmentation]]). The full rendered `trajectory` is kept on `.raw` for inspection. The loop's tool
  * protocol runs internally over the data-bag layer (a `Streamable[ReAct[I, O]]` instance lets it stream).
  *
  * Tool selection is via output fields (the canonical DSPy mechanism) — not provider-native function-calling. Tool
  * failures (unknown tool, invocation error) are recorded as trajectory observations rather than failing the program,
  * mirroring Python; an LM-call failure in the react or extract step propagates as `Left`.
  */
final case class ReAct[I, O](
    baseSignature: Signature[I, O],
    tools: Vector[ToolFunction],
    maxIterations: IterationLimit = IterationLimit(5),
    reactProgramName: String = ReActKeys.reactModule,
    extractorProgramName: String = ReActKeys.extractModule,
    /** Optional override for the per-iteration react predict — a TYPED `Predict` over the base input plus the
      * rendered trajectory, producing a lenient [[ReAct.ReactStep]]. When `None` (the default), the predict is built
      * from [[reactSignature]]. Carrying it as a defaulted, `copy`-reachable field is what makes the learnable
      * sub-predict addressable + immutably replaceable (see the `OptimizableTraversal[ReAct]` instance).
      */
    reactPredictOverride: Option[Predict[(I, String), ReAct.ReactStep]] = None,
    /** Optional override for the final extractor predict (CoT-augmented, typed over the base input plus the rendered
      * trajectory). When `None` (the default), it is built fail-fast from [[extractorSignature]] at construction; see
      * [[extractorPredict]].
      */
    extractorPredictOverride: Option[Predict[(I, String), ReAct.WithReasoning[O]]] = None
)(using
    prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
) extends Module[I, ReAct.WithReasoning[O]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = ReAct.WithReasoning[O]

  override val moduleName: String = ReActKeys.reactModule
  private val baseLayout: SignatureLayout = baseSignature.layout

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop. */
  private val allTools: Vector[ToolFunction] = tools :+ ReAct.finishTool(baseLayout)
  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** Per-iteration signature: base inputs + `trajectory` -> `next_thought` / `next_tool_name` / `next_tool_args`. The
    * base output fields are intentionally dropped here — they are produced by the extractor, not the loop.
    */
  val reactSignature: SignatureLayout =
    baseLayout
      .appendInput(ReAct.loopTrajectoryField)
      .replaceOutputs(Vector(ReAct.nextThoughtField, ReAct.nextToolNameField, ReAct.nextToolArgsField))
      .withInstructions(Some(buildInstructions))

  /** Final extractor signature: base inputs + base outputs + `trajectory`; reasoning is added by ChainOfThought. */
  val extractorSignature: SignatureLayout =
    baseLayout.appendInput(ReAct.extractTrajectoryField)

  /** The per-iteration react predict, built once from [[reactSignature]] (mirrors Python's `self.react = Predict(...)`
    * in `__init__`) — a TYPED `Predict[(I, String), ReactStep]`: the base input plus the rendered trajectory in, a
    * leniently-decoded [[ReAct.ReactStep]] out (see [[ReAct.reactStepShape]]). The layout (prompt rendering, field
    * descriptions, tool-listing instructions) is unchanged. Addressable + tunable via [[reactPredictOverride]];
    * `forward` uses this member rather than rebuilding a local each call.
    */
  val reactPredict: Predict[(I, String), ReAct.ReactStep] =
    reactPredictOverride.getOrElse(Predict(
      signature = Signature(
        name        = baseSignature.name,
        layout      = reactSignature,
        inputShape  = InputAugmentation.appendedStringInput(baseSignature.inputShape, ReAct.loopTrajectoryField, "ReAct"),
        outputShape = ReAct.reactStepShape
      ),
      name = Some(reactProgramName)
    ))

  /** The final extractor predict, built once from the CoT-augmented [[extractorSignature]] — a TYPED
    * `Predict[(I, String), WithReasoning[O]]`, so the reasoning-prepended decode happens inside the predict (the
    * `prepend` evidence this class already carries). Tunable via [[extractorPredictOverride]].
    */
  val extractorPredict: Predict[(I, String), ReAct.WithReasoning[O]] =
    extractorPredictOverride.getOrElse(Predict(
      signature = Signature(
        name   = baseSignature.name,
        layout = ChainOfThought.augmentLayout(extractorSignature),
        inputShape = InputAugmentation
          .appendedStringInput(baseSignature.inputShape, ReAct.extractTrajectoryField, "ReAct extractor"),
        outputShape = OutputAugmentation.prependedStringShape(
          baseSignature.outputShape,
          ChainOfThought.reasoningField,
          ChainOfThought.reasoningName,
          "ReAct extractor",
          baseSignature.name
        )
      ),
      name = Some(extractorProgramName)
    ))

  /** System-prompt instructions for the react step. Mirrors Python's shape: states the task I/O, explains the
    * next_thought / next_tool_name / next_tool_args protocol, and lists the selectable tools (name + description).
    */
  private def buildInstructions: String =
    val inputs = baseLayout.inputFields.map(field => s"`${field.name}`").mkString(", ")
    val outputs = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    val taskPrelude = baseLayout.instructions.fold("")(_ + "\n")
    val toolList = allTools.zipWithIndex.map { case (tool, idx) =>
      val args = if tool.argSchema.nonEmpty then
        tool.argSchema.map((argName, typeRef) => s"$argName: ${typeRef.repr}").mkString("(", ", ", ")")
      else ""
      val desc = if tool.description.nonEmpty then s": ${tool.description}" else ""
      s"(${idx + 1}) `${tool.name}`$args$desc"
    }.mkString("\n")
    s"""${taskPrelude}You are an Agent. In each episode you receive the fields $inputs as input, along with your past trajectory.
       |Your goal is to use one or more of the supplied tools to collect the information needed to produce $outputs.
       |Each turn, emit next_thought (your reasoning), next_tool_name (the tool to call), and next_tool_args (its arguments as a JSON object).
       |After each tool call you receive an observation, which is appended to your trajectory.
       |Select `finish` as next_tool_name once you have everything needed to produce the outputs.
       |next_tool_name must be one of:
       |$toolList""".stripMargin

  override protected val lifecycle: ModuleLifecycle[I, Out] =
    ModuleLifecycle.typed(baseSignature.inputShape)

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    for
      // Gather the trajectory (the react step truncates + may break on overflow) then run the extractor; the
      // bounded loop + extractor truncation are the shared TrajectoryAgent flow (same as CodeAct). Both inner
      // predicts are typed: the trajectory pairs with the TYPED base input, and each predict encodes/decodes
      // through its own signature (the reasoning-prepended decode happens inside the extractor).
      extractedAndTrajectory <- TrajectoryAgent.runAndExtract[ReAct.TrajectoryEntry, Prediction[Out]](
        maxIterations,
        ReAct.renderTrajectory
      )(reactStep(call)) { rendered =>
        extractorPredict.apply(call.mapInput(input => (input, rendered)))
      }
      (extracted, rendered) = extractedAndTrajectory
    yield Prediction(
      output = extracted.output,
      // Attach the (complete) trajectory to the raw prediction so callers can inspect the agent's reasoning.
      raw = RawPrediction(
        values = extracted.raw.values
          .updated(ReActKeys.trajectory, DynamicValue.Primitive(PrimitiveValue.String(rendered))),
        completions = extracted.raw.completions,
        lmUsage     = extracted.raw.lmUsage
      )
    )

  /** One react iteration as a [[TrajectoryAgent]] step: run the react predict (truncating + possibly breaking on a
    * persistent context-window overflow), then run the chosen tool and append the observation. `finish` (or a step that
    * named no tool) ends the loop. The `AgentLoop` skeleton owns the iteration count + budget.
    */
  private def reactStep(call: ProgramCall[I])(using
      RuntimeContext
  ): (Vector[ReAct.TrajectoryEntry], Int) => Either[DspyError, TrajectoryAgent.Step[ReAct.TrajectoryEntry]] =
    (view, iteration) =>
      reactWithTruncation(call, view, remaining = 3).map {
        case (None, truncated) =>
          // Persistent context-window overflow: upstream logs a warning and BREAKS the loop — the extractor
          // still runs over whatever (truncated) trajectory remains, rather than failing the call.
          AgentLoop.Step.Done(truncated)
        case (Some(step), used) =>
          val toolName    = step.nextToolName.trim
          val observation = runTool(toolName, step.nextToolArgs)
          val entry       = ReAct.TrajectoryEntry(iteration, step.nextThought, toolName, step.nextToolArgs, observation)
          // `finish` (or a step that named no tool) ends the loop; otherwise gather more.
          if toolName == ReAct.FinishToolName || toolName.isEmpty then AgentLoop.Step.Done(used :+ entry)
          else AgentLoop.Step.Continue(used :+ entry)
      }

  /** Run the react predict over the trajectory, truncating the OLDEST step and retrying (up to `remaining` attempts
    * total) on a context-window overflow — Python's `_call_with_potential_trajectory_truncation` around `self.react`.
    * Returns the typed step plus the (possibly truncated) view: truncation is DURABLE — later iterations and the
    * extractor build on the truncated trajectory, as upstream mutates the shared dict. `(None, view)` means the
    * overflow persisted (attempts exhausted, or nothing left to drop) — upstream's `ValueError` path, which the loop
    * converts into a break rather than a failure.
    */
  private def reactWithTruncation(
      call: ProgramCall[I],
      view: Vector[ReAct.TrajectoryEntry],
      remaining: Int
  )(using RuntimeContext): Either[DspyError, (Option[ReAct.ReactStep], Vector[ReAct.TrajectoryEntry])] =
    val (result, used) = truncateOnOverflow(view, remaining)(ReAct.renderTrajectory) { rendered =>
      reactPredict.apply(call.mapInput(input => (input, rendered)))
    }
    result match
      case Right(prediction)                   => Right((Some(prediction.output), used))
      case Left(_: ContextWindowExceededError) => Right((None, used))
      case Left(error)                         => Left(error)

  /** Execute the named tool and render its result as an observation. Tool problems never fail the program: an unknown
    * tool or an invocation error becomes an error observation the LM sees on the next turn (as in Python).
    */
  private def runTool(name: String, args: DynamicValue.Record)(using RuntimeContext): String =
    if name.isEmpty then "No tool was selected."
    else if !toolsByName.contains(name) then s"Execution error: tool `$name` does not exist."
    else
      ToolExecutor.invoke(ToolCallRequest(name, args), allTools) match
        case Right(callResult) =>
          callResult.result match
            case Right(value) => DynamicValues.renderText(value)
            case Left(error)  => s"Execution error in `$name`: ${error.message}"
        case Left(error) => s"Execution error in `$name`: ${error.message}"

object ReAct:
  val FinishToolName: String = "finish"

  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = ChainOfThought.WithReasoning[O]

  // ── The loop signature's hand-declared fields (static; hoisted so the typed shapes and the layout share them) ──
  private[programs] val loopTrajectoryField: FieldSpec = FieldSpec(
    name        = ReActKeys.trajectory,
    typeRef     = TypeRef.string,
    description = Some("The sequence of thoughts, tool calls, and observations so far.")
  )
  private[programs] val extractTrajectoryField: FieldSpec = FieldSpec(
    name        = ReActKeys.trajectory,
    typeRef     = TypeRef.string,
    description = Some("The completed sequence of thoughts, tool calls, and observations.")
  )
  private[programs] val nextThoughtField: FieldSpec = FieldSpec(
    name        = ReActKeys.nextThought,
    typeRef     = TypeRef.string,
    description = Some("Reasoning about the current situation and what to do next.")
  )
  private[programs] val nextToolNameField: FieldSpec = FieldSpec(
    name        = ReActKeys.nextToolName,
    typeRef     = TypeRef.string,
    description = Some("The name of the tool to call next; use `finish` when ready to produce the outputs.")
  )
  private[programs] val nextToolArgsField: FieldSpec = FieldSpec(
    name        = ReActKeys.nextToolArgs,
    typeRef     = TypeRef.json,
    description = Some("Arguments for the next tool, as a JSON object.")
  )

  /** The typed output of one react loop step. */
  final case class ReactStep(
      nextThought: String,
      nextToolName: String,
      nextToolArgs: DynamicValue.Record
  )

  /** Hand-written LENIENT output shape mirroring the prior dynamic reads EXACTLY: a missing `next_thought` /
    * `next_tool_name` renders as "" (a step that names no tool ends the loop rather than failing the call), and
    * `next_tool_args` accepts a JSON object (JSONAdapter), a JSON-object string (ChatAdapter has no `json`
    * coercion), or nothing (empty record). Decode never fails; `jsonSchemaString` stays `None` for parity with the
    * prior direct `DynamicPredict` construction. */
  private[programs] val reactStepShape: Shape[ReactStep] = new Shape[ReactStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(nextThoughtField, nextToolNameField, nextToolArgsField)

    def encode(value: ReactStep): DynamicValue.Record =
      DynamicValue.Record(Chunk.from(Seq(
        ReActKeys.nextThought  -> DynamicValue.Primitive(PrimitiveValue.String(value.nextThought)),
        ReActKeys.nextToolName -> DynamicValue.Primitive(PrimitiveValue.String(value.nextToolName)),
        ReActKeys.nextToolArgs -> (value.nextToolArgs: DynamicValue)
      )))

    def decode(raw: DynamicValue.Record): Either[DspyError, ReactStep] =
      Right(ReactStep(
        nextThought  = DynamicValues.recordGet(raw, ReActKeys.nextThought).map(DynamicValues.renderText).getOrElse(""),
        nextToolName = DynamicValues.recordGet(raw, ReActKeys.nextToolName).map(DynamicValues.renderText).getOrElse(""),
        nextToolArgs = toolArgsRecord(DynamicValues.recordGet(raw, ReActKeys.nextToolArgs))
      ))

  /** Normalize the `next_tool_args` output into the `Record` a tool receives. JSONAdapter yields a `Record` directly;
    * ChatAdapter yields the raw JSON text as a `String` (it has no `json` coercion), so parse that.
    */
  private def toolArgsRecord(value: Option[DynamicValue]): DynamicValue.Record =
    value match
      case Some(rec: DynamicValue.Record)                         => rec
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => parseJsonRecord(s)
      case _                                                      => DynamicValue.Record.empty

  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a JSON-object string (as ChatAdapter surfaces a `json` field) into a `Record`; non-objects / blanks / parse
    * failures yield the empty record.
    */
  private def parseJsonRecord(text: String): DynamicValue.Record =
    if text.trim.isEmpty then DynamicValue.Record.empty
    else
      dynamicJsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
        case Right(rec: DynamicValue.Record) => rec
        case _                               => DynamicValue.Record.empty

  /** The injected tool the model selects to end the loop. It does no work — selecting it signals "I have enough to
    * produce the outputs"; the observation is a fixed marker and the extractor then produces the real outputs.
    */
  private def finishTool(baseLayout: SignatureLayout): ToolFunction =
    val outputs = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    new ToolFunction:
      override val name: String = FinishToolName
      override val description: String =
        s"Marks the task complete: signals that all information needed to produce $outputs is now available."
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        Right(ToolFunction.result("Completed."))

  /** One step of the agent's trajectory: its thought, the tool it chose with arguments, and the observation. */
  final case class TrajectoryEntry(
      iteration: Int,
      thought: String,
      toolName: String,
      toolArgs: DynamicValue.Record,
      observation: String
  )

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        s"""## Step ${entry.iteration + 1}
           |thought: ${entry.thought}
           |tool_name: ${entry.toolName}
           |tool_args: ${DynamicValues.renderText(entry.toolArgs)}
           |observation: ${entry.observation}""".stripMargin
      }.mkString("\n\n")

/** Names ReAct hard-codes: its module / sub-predict names, and the field-name keys it adds to the augmented signatures
  * and reads back from predictions. Named rather than scattered as string literals. (Prose — field descriptions,
  * instructions, observations — stays inline; only the keys/identifiers are constants.)
  */
private object ReActKeys:
  val reactModule: String   = "react"
  val extractModule: String = "react_extract"

  val trajectory: String   = "trajectory"
  val nextThought: String  = "next_thought"
  val nextToolName: String = "next_tool_name"
  val nextToolArgs: String = "next_tool_args"
