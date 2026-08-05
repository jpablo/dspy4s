package dspy4s.programs.strategies

import dspy4s.programs.IterationLimit
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome}
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.InterpretedTrajectoryAgent
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{ActionDecision, ActionPreparation, StepGeneration}
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.typed.OutputAugmentation.PrependField
import dspy4s.typed.{InputAugmentation, OutputAugmentation, Shape, Signature}
import zio.blocks.schema.DynamicValue

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized over
  * any typed signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the injected
  * `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the full
  * trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * `ReAct[I, O]` is a `Module[I, WithReasoning[O]]`: the input is encoded from `I`, and the extractor's reply is
  * decoded into the base outputs `O` with a `reasoning: String` prepended (always a named tuple; see
  * [[OutputAugmentation]]). The full rendered `trajectory` is kept on `.raw` for inspection. The loop's tool protocol
  * runs internally over the data-bag layer (a `Streamable[ReAct[I, O]]` instance lets it stream).
  *
  * Tool selection is via output fields (the canonical DSPy mechanism) — not provider-native function-calling. Tool
  * failures (unknown tool, invocation error) are recorded as trajectory observations rather than failing the program,
  * mirroring Python; an LM-call failure in the react or extract step propagates as `Left`.
  */
final case class ReAct[I, O](
    baseSignature: Signature[I, O],
    tools: Vector[ToolFunction],
    override val maxIterations: IterationLimit = IterationLimit(5),
    reactProgramName: String = ReActKeys.reactModule,
    extractorProgramName: String = ReActKeys.extractModule,
    /** Optional override for the per-iteration react predict — a TYPED `Predict` over the base input plus the rendered
      * trajectory, producing a lenient [[ReAct.ReactStep]]. When `None` (the default), the predict is built from
      * [[reactSignature]]. Carrying it as a defaulted, `copy`-reachable field is what makes the learnable sub-predict
      * addressable + immutably replaceable (see the `OptimizableTraversal[ReAct]` instance).
      */
    reactPredictOverride: Option[Predict[(I, String), ReAct.ReactStep]] = None,
    /** Optional override for the final extractor predict (CoT-augmented, typed over the base input plus the rendered
      * trajectory). When `None` (the default), it is built fail-fast from [[extractorSignature]] at construction; see
      * [[extractorPredict]].
      */
    extractorPredictOverride: Option[Predict[(I, String), ReAct.WithReasoning[O]]] = None
)(using
    prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
) extends InterpretedTrajectoryAgent[I, ReAct.WithReasoning[O], ReAct.TrajectoryEntry]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out                  = ReAct.WithReasoning[O]
  override type ModelStep   = ReAct.ReactStep
  override type Action      = ToolCallRequest
  override type Observation = String

  override val moduleName: String         = ReActKeys.reactModule
  private val baseLayout: SignatureLayout = baseSignature.layout

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop. */
  private val allTools: Vector[ToolFunction]         = tools :+ ReAct.finishTool(baseLayout)
  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** Interpreter for ReAct's small action language: one named tool call with a record of arguments. Tool selection and
    * invocation failures are recoverable observations, so the LM can react to them on its next turn.
    */
  override protected val actionInterpreter: ActionInterpreter[ToolCallRequest, String] =
    new ActionInterpreter[ToolCallRequest, String]:
      override def execute(request: ToolCallRequest)(using
          RuntimeContext
      ): Either[DspyError, ActionOutcome[String]] =
        if request.name.isEmpty then Right(ActionOutcome.Failed("No tool was selected."))
        else if !toolsByName.contains(request.name) then
          Right(ActionOutcome.Failed(s"Execution error: tool `${request.name}` does not exist."))
        else
          ToolExecutor.invoke(request, allTools).map { callResult =>
            callResult.result match
              case Right(value) => ActionOutcome.Succeeded(DynamicValues.renderText(value))
              case Left(error)  => ActionOutcome.Failed(s"Execution error in `${request.name}`: ${error.message}")
          }

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
        name = baseSignature.name,
        layout = reactSignature,
        inputShape =
          InputAugmentation.appendedStringInput(baseSignature.inputShape, ReAct.loopTrajectoryField, "ReAct"),
        outputShape = ReAct.reactStepShape
      ),
      name = Some(reactProgramName)
    ))

  /** The final extractor predict, built once from the CoT-augmented [[extractorSignature]] — a TYPED
    * `Predict[(I, String), WithReasoning[O]]`, so the reasoning-prepended decode happens inside the predict (the
    * `prepend` evidence this class already carries). Tunable via [[extractorPredictOverride]].
    */
  override val extractorPredict: Predict[(I, String), ReAct.WithReasoning[O]] =
    extractorPredictOverride.getOrElse(Predict(
      signature = Signature(
        name = baseSignature.name,
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
    val inputs      = baseLayout.inputFields.map(field => s"`${field.name}`").mkString(", ")
    val outputs     = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    val taskPrelude = baseLayout.instructions.fold("")(_ + "\n")
    val toolList    = allTools.zipWithIndex.map { case (tool, idx) =>
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

  override protected val trajectoryKey: String = ReActKeys.trajectory

  override protected def renderTrajectory(trajectory: Vector[ReAct.TrajectoryEntry]): String =
    ReAct.renderTrajectory(trajectory)

  /** Generate one typed ReAct step, durably truncating the oldest trajectory entry on a context-window overflow. A
    * persistent overflow halts the action loop so final extraction can still run over the remaining view.
    */
  override protected def generateStep(
      call: ProgramCall[I],
      trajectory: Vector[ReAct.TrajectoryEntry]
  )(using RuntimeContext): Either[DspyError, StepGeneration[ReAct.ReactStep, ReAct.TrajectoryEntry]] =
    reactWithTruncation(call, trajectory, remaining = 3).map {
      case (Some(step), used) => StepGeneration.Generated(step, used)
      case (None, used)       => StepGeneration.Halted(used)
    }

  /** Lower ReAct's typed model step to its small tool-call language. */
  override protected def prepareAction(step: ReAct.ReactStep): ActionPreparation[ToolCallRequest, String] =
    val toolName = step.nextToolName.trim
    ActionPreparation.Ready(ToolCallRequest(toolName, step.nextToolArgs))

  /** `finish` and a missing tool name both execute once and then end the loop, regardless of their interpreted outcome.
    * The shared transition records that outcome before applying this decision.
    */
  override protected def decide(
      @annotation.unused step: ReAct.ReactStep,
      action: ToolCallRequest,
      @annotation.unused outcome: ActionOutcome[String]
  ): ActionDecision =
    if action.name == ReAct.FinishToolName || action.name.isEmpty then ActionDecision.Stop
    else ActionDecision.Continue

  override protected def recordRejection(
      iteration: Int,
      step: ReAct.ReactStep,
      observation: String
  ): ReAct.TrajectoryEntry =
    ReAct.TrajectoryEntry(
      iteration,
      step.nextThought,
      step.nextToolName.trim,
      step.nextToolArgs,
      observation
    )

  override protected def recordOutcome(
      iteration: Int,
      step: ReAct.ReactStep,
      action: ToolCallRequest,
      outcome: ActionOutcome[String]
  ): ReAct.TrajectoryEntry =
    ReAct.TrajectoryEntry(
      iteration,
      step.nextThought,
      action.name,
      action.args,
      outcome.observation
    )

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
      reactPredict(call.mapInput(input => (input, rendered)))
    }
    result match
      case Right(prediction)                   => Right((Some(prediction.output), used))
      case Left(_: ContextWindowExceededError) => Right((None, used))
      case Left(error)                         => Left(error)

object ReAct:
  val FinishToolName: String = "finish"

  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = ChainOfThought.WithReasoning[O]

  // ── The loop signature's hand-declared fields (static; hoisted so the typed shapes and the layout share them) ──
  private[programs] val loopTrajectoryField: FieldSpec    = ReActProtocol.loopTrajectoryField
  private[programs] val extractTrajectoryField: FieldSpec = ReActProtocol.extractTrajectoryField
  private[programs] val nextThoughtField: FieldSpec       = ReActProtocol.nextThoughtField
  private[programs] val nextToolNameField: FieldSpec      = ReActProtocol.nextToolNameField
  private[programs] val nextToolArgsField: FieldSpec      = ReActProtocol.nextToolArgsField

  /** The typed output of one react loop step. */
  final case class ReactStep(
      nextThought: String,
      nextToolName: String,
      nextToolArgs: DynamicValue.Record
  )

  /** Hand-written LENIENT output shape mirroring the prior dynamic reads EXACTLY: a missing `next_thought` /
    * `next_tool_name` renders as "" (a step that names no tool ends the loop rather than failing the call), and
    * `next_tool_args` accepts a JSON object (JSONAdapter), a JSON-object string (ChatAdapter has no `json` coercion),
    * or nothing (empty record). Decode never fails; `jsonSchemaString` stays `None` for parity with the prior direct
    * `DynamicPredict` construction.
    */
  private[programs] val reactStepShape: Shape[ReactStep] = ReActProtocol.reactStepShape

  /** The injected tool the model selects to end the loop. It does no work — selecting it signals "I have enough to
    * produce the outputs"; the observation is a fixed marker and the extractor then produces the real outputs.
    */
  private def finishTool(baseLayout: SignatureLayout): ToolFunction =
    ReActProtocol.finishTool(baseLayout)

  /** One step of the agent's trajectory: its thought, the tool it chose with arguments, and the observation. */
  final case class TrajectoryEntry(
      iteration: Int,
      thought: String,
      toolName: String,
      toolArgs: DynamicValue.Record,
      observation: String
  )

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    ReActProtocol.renderTrajectory(entries)
