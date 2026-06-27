package dspy4s.programs

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.contracts.TypedCall
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.typed.{OutputAugmentation, Prediction, Signature}
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized
  * over any typed signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the
  * injected `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the
  * full trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * `ReAct[I, O]` is a `Module[TypedCall[I], Prediction[WithReasoning[O]]]`: the input is encoded from `I`, and the
  * extractor's reply is decoded into the base outputs `O` with a `reasoning: String` prepended (always a named
  * tuple; see [[OutputAugmentation]]). The full rendered `trajectory` is kept on `.raw` for inspection. The loop's
  * tool protocol runs internally over the data-bag layer (a `Streamable[ReAct[I, O]]` instance lets it stream).
  *
  * Tool selection is via output fields (the canonical DSPy mechanism) — not provider-native function-calling.
  * Tool failures (unknown tool, invocation error) are recorded as trajectory observations rather than failing the
  * program, mirroring Python; an LM-call failure in the react or extract step propagates as `Left`.
  */
final case class ReAct[I, O](
    baseSignature: Signature[I, O],
    tools: Vector[ToolFunction],
    maxIterations: Int = 5,
    reactProgramName: String = ReActKeys.reactModule,
    extractorProgramName: String = ReActKeys.extractModule,
    /** Optional override for the per-iteration react predict. When `None` (the default), the predict is built
      * from [[reactSignature]]. Carrying it as a defaulted, `copy`-reachable field is what makes the learnable
      * sub-predict addressable + immutably replaceable (see the `Predictors[ReAct]` instance). */
    reactPredictOverride: Option[DynamicPredict] = None,
    /** Optional override for the final extractor predict (CoT-augmented). When `None` (the default), it is built
      * fail-fast from [[extractorSignature]] at construction; see [[extractorPredict]]. */
    extractorPredictOverride: Option[DynamicPredict] = None
)(using
    prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, ReAct.WithReasoning[O]]
) extends Module[TypedCall[I], Prediction[ReAct.WithReasoning[O]]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = ReAct.WithReasoning[O]

  override val moduleName: String = ReActKeys.reactModule
  require(maxIterations > 0, "maxIterations must be greater than 0")

  private val baseLayout: SignatureLayout = baseSignature.layout

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop. */
  private val allTools: Vector[ToolFunction] = tools :+ ReAct.finishTool(baseLayout)
  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** Per-iteration signature: base inputs + `trajectory` -> `next_thought` / `next_tool_name` / `next_tool_args`.
    * The base output fields are intentionally dropped here — they are produced by the extractor, not the loop. */
  val reactSignature: SignatureLayout =
    baseLayout
      .appendInput(
        FieldSpec(
          name = ReActKeys.trajectory,
          role = FieldRole.Input,
          typeRef = TypeRef.string,
          description = Some("The sequence of thoughts, tool calls, and observations so far.")
        )
      )
      .replaceOutputs(Vector(
        FieldSpec(
          name = ReActKeys.nextThought,
          role = FieldRole.Output,
          typeRef = TypeRef.string,
          description = Some("Reasoning about the current situation and what to do next.")
        ),
        FieldSpec(
          name = ReActKeys.nextToolName,
          role = FieldRole.Output,
          typeRef = TypeRef.string,
          description = Some("The name of the tool to call next; use `finish` when ready to produce the outputs.")
        ),
        FieldSpec(
          name = ReActKeys.nextToolArgs,
          role = FieldRole.Output,
          typeRef = TypeRef.json,
          description = Some("Arguments for the next tool, as a JSON object.")
        )
      ))
      .withInstructions(Some(buildInstructions))

  /** Final extractor signature: base inputs + base outputs + `trajectory`; reasoning is added by ChainOfThought. */
  val extractorSignature: SignatureLayout =
    baseLayout.appendInput(
      FieldSpec(
        name = ReActKeys.trajectory,
        role = FieldRole.Input,
        typeRef = TypeRef.string,
        description = Some("The completed sequence of thoughts, tool calls, and observations.")
      )
    )

  /** The per-iteration react predict, built once from [[reactSignature]] (mirrors Python's `self.react =
    * Predict(...)` in `__init__`). Addressable + tunable via [[reactPredictOverride]]; `forward` uses this member
    * rather than rebuilding a local each call. */
  val reactPredict: DynamicPredict =
    reactPredictOverride.getOrElse(DynamicPredict(layout = reactSignature, name = Some(reactProgramName)))

  /** The final extractor predict, built once from the CoT-augmented [[extractorSignature]]. Built fail-fast at
    * construction (mirroring `require`): if the augmentation fails the error surfaces deterministically here, so
    * both sub-predicts are always present and addressable. Tunable via [[extractorPredictOverride]]. */
  val extractorPredict: DynamicPredict =
    extractorPredictOverride.getOrElse(
      DynamicPredict(
        layout = ChainOfThought
          .augmentLayout(extractorSignature)
          .fold(error => throw new IllegalArgumentException(error.message), identity),
        name = Some(extractorProgramName)
      )
    )

  /** System-prompt instructions for the react step. Mirrors Python's shape: states the task I/O, explains the
    * next_thought / next_tool_name / next_tool_args protocol, and lists the selectable tools (name + description). */
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

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record =
    baseSignature.inputShape.encode(call.input)
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[Out]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    val inputs = baseSignature.inputShape.encode(call.input)
    val baseCall = ProgramCall(
      inputs       = inputs,
      config       = call.config,
      traceEnabled = call.traceEnabled,
      rolloutId    = call.rolloutId
    )
    for
      trajectory <- runIterations(baseCall, reactPredict, Vector.empty, iteration = 0)
      rendered = DynamicValue.Primitive(PrimitiveValue.String(ReAct.renderTrajectory(trajectory)))
      extracted <- extractWithTruncation(baseCall, inputs, trajectory)
      // Decode the extractor's reply into the typed output: base `O` with `reasoning` prepended.
      reasoning <- extractReasoning(extracted.values)
      baseOut   <- baseSignature.outputShape.decode(extracted.values)
      augmented <- prepend.prepend(reasoning, baseOut).toRight(unsupportedOutputShape(baseOut))
    yield Prediction(
      output = augmented,
      // Attach the trajectory to the raw prediction so callers can inspect the agent's reasoning.
      raw = DynamicPrediction(
        values      = extracted.values.updated(ReActKeys.trajectory, rendered),
        completions = extracted.completions,
        lmUsage     = extracted.lmUsage
      )
    )

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(TypedCall(input, config, traceEnabled))

  @tailrec
  private def runIterations(
      call: ProgramCall,
      reactPredict: DynamicPredict,
      trajectory: Vector[ReAct.TrajectoryEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Vector[ReAct.TrajectoryEntry]] =
    if iteration >= maxIterations then Right(trajectory)
    else
      reactWithTruncation(call, reactPredict, trajectory, remaining = 3) match
        case Left(error) => Left(error)
        case Right((None, view)) =>
          // Persistent context-window overflow: upstream logs a warning and BREAKS the loop — the extractor
          // still runs over whatever (truncated) trajectory remains, rather than failing the call.
          Right(view)
        case Right((Some(prediction), view)) =>
          val thought = prediction.get(ReActKeys.nextThought).map(DynamicValues.renderText).getOrElse("")
          val toolName = prediction.get(ReActKeys.nextToolName).map(DynamicValues.renderText).getOrElse("").trim
          val toolArgs = toolArgsRecord(prediction.get(ReActKeys.nextToolArgs))
          val observation = runTool(toolName, toolArgs)
          val entry = ReAct.TrajectoryEntry(iteration, thought, toolName, toolArgs, observation)
          // `finish` (or a step that named no tool) ends the loop; otherwise gather more.
          if toolName == ReAct.FinishToolName || toolName.isEmpty then Right(view :+ entry)
          else runIterations(call, reactPredict, view :+ entry, iteration + 1)

  /** Run the react predict over the trajectory, truncating the OLDEST step and retrying (up to `remaining`
    * attempts total) on a context-window overflow — Python's `_call_with_potential_trajectory_truncation` around
    * `self.react`. Returns the prediction plus the (possibly truncated) view: truncation is DURABLE — later
    * iterations and the extractor build on the truncated trajectory, as upstream mutates the shared dict.
    * `(None, view)` means the overflow persisted (attempts exhausted, or nothing left to drop) — upstream's
    * `ValueError` path, which the loop converts into a break rather than a failure. */
  @tailrec
  private def reactWithTruncation(
      call: ProgramCall,
      reactPredict: DynamicPredict,
      view: Vector[ReAct.TrajectoryEntry],
      remaining: Int
  )(using RuntimeContext): Either[DspyError, (Option[DynamicPrediction], Vector[ReAct.TrajectoryEntry])] =
    val rendered = DynamicValue.Primitive(PrimitiveValue.String(ReAct.renderTrajectory(view)))
    reactPredict.apply(call.copy(inputs = call.inputs.updated(ReActKeys.trajectory, rendered))) match
      case Left(_: ContextWindowExceededError) if remaining > 1 && view.nonEmpty =>
        reactWithTruncation(call, reactPredict, view.drop(1), remaining - 1)
      case Left(_: ContextWindowExceededError) => Right((None, view))
      case Left(error)                         => Left(error)
      case Right(prediction)                   => Right((Some(prediction), view))

  /** Run the final extractor over the trajectory, truncating the OLDEST step and retrying (up to 3 attempts) on
    * a context-window overflow — the extract-side `_call_with_potential_trajectory_truncation`. Unlike the react
    * step, a persistent overflow here FAILS the call (upstream's uncaught `ValueError`). Delta: only the
    * extractor's view truncates; the returned prediction's `trajectory` stays complete (upstream's in-place pops
    * shrink the returned trajectory too — ours is strictly more informative). */
  private def extractWithTruncation(
      baseCall: ProgramCall,
      inputs: DynamicValue.Record,
      trajectory: Vector[ReAct.TrajectoryEntry]
  )(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    @tailrec
    def attempt(view: Vector[ReAct.TrajectoryEntry], remaining: Int): Either[DspyError, DynamicPrediction] =
      val rendered = DynamicValue.Primitive(PrimitiveValue.String(ReAct.renderTrajectory(view)))
      extractorPredict.apply(baseCall.copy(inputs = inputs.updated(ReActKeys.trajectory, rendered))) match
        case Left(_: ContextWindowExceededError) if remaining > 1 && view.nonEmpty =>
          attempt(view.drop(1), remaining - 1)
        case other => other
    attempt(trajectory, remaining = 3)

  /** Execute the named tool and render its result as an observation. Tool problems never fail the program: an
    * unknown tool or an invocation error becomes an error observation the LM sees on the next turn (as in Python). */
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

  /** Normalize the `next_tool_args` output into the `Record` a tool receives. JSONAdapter yields a `Record`
    * directly; ChatAdapter yields the raw JSON text as a `String` (it has no `json` coercion), so parse that. */
  private def toolArgsRecord(value: Option[DynamicValue]): DynamicValue.Record =
    value match
      case Some(rec: DynamicValue.Record)                         => rec
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => ReAct.parseJsonRecord(s)
      case _                                                      => DynamicValue.Record.empty

  private def extractReasoning(values: DynamicValue.Record): Either[DspyError, String] =
    DynamicValues.requireString(values, "reasoning", "ReAct extractor")

  private def unsupportedOutputShape(baseOut: O): DspyError =
    ValidationError(
      s"ReAct requires a product output (named tuple or case class); the signature '${baseSignature.name}' has " +
      s"a fieldless output (got ${baseOut.getClass.getSimpleName}). Use a typed signature " +
      s"(Signature.of / Signature.derived / Signature.fromType / a literal Signature.fromString)."
    )

object ReAct:
  val FinishToolName: String = "finish"

  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]

  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a JSON-object string (as ChatAdapter surfaces a `json` field) into a `Record`; non-objects / blanks /
    * parse failures yield the empty record. */
  private def parseJsonRecord(text: String): DynamicValue.Record =
    if text.trim.isEmpty then DynamicValue.Record.empty
    else
      dynamicJsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
        case Right(rec: DynamicValue.Record) => rec
        case _                               => DynamicValue.Record.empty

  /** The injected tool the model selects to end the loop. It does no work — selecting it signals "I have enough to
    * produce the outputs"; the observation is a fixed marker and the extractor then produces the real outputs. */
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

/** Names ReAct hard-codes: its module / sub-predict names, and the field-name keys it adds to the augmented
  * signatures and reads back from predictions. Named rather than scattered as string literals. (Prose — field
  * descriptions, instructions, observations — stays inline; only the keys/identifiers are constants.) */
private object ReActKeys:
  val reactModule: String   = "react"
  val extractModule: String = "react_extract"

  val trajectory: String   = "trajectory"
  val nextThought: String  = "next_thought"
  val nextToolName: String = "next_tool_name"
  val nextToolArgs: String = "next_tool_args"
