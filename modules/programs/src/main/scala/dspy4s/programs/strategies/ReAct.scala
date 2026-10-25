package dspy4s.programs.strategies

import dspy4s.programs.IterationLimit
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome}
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.internal.ToolText
import dspy4s.programs.runtime.InterpretedTrajectoryAgent
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{ActionDecision, ActionPreparation, StepGeneration}
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.DynamicValue

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized over
  * any signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the injected
  * `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the full
  * trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * `ReAct[I, O]` is a `Module[I, WithReasoning[O]]`: the input is encoded from `I`, and the extractor's reply is
  * decoded into the base outputs `O` with a `reasoning: String` prepended (always a named tuple; see
  * [[dspy4s.signatures.OutputAugmentation]]). The full rendered `trajectory` is kept on `.raw` for inspection, and the
  * LM usage on `.raw` is the total of each model call of the call: the react calls, the model calls of the tools, and
  * the extractor call (upstream `Module.__call__`; `Module.apply` opens the usage scope). The loop's tool protocol runs
  * internally over the data-bag layer (a `Streamable[ReAct[I, O]]` instance lets it stream).
  *
  * Tool selection is via output fields (the canonical DSPy mechanism) — not provider-native function-calling. Tool
  * failures (invocation error, non-fatal exception of a tool) are recorded as trajectory observations rather than
  * failing the program, mirroring Python; an LM-call failure in the react or extract step propagates as `Left`.
  *
  * The tool names are the allowed values of `next_tool_name` (upstream `Literal[tuple(tools.keys())]`). The adapters of
  * the port reject a name that is not a tool, and an empty name, as upstream `parse_value` does (decision D7). That
  * parse error of the step gives the JSON fallback of the chat adapter, and when that also fails, the error goes up:
  * the whole call fails, with no extract step. Upstream does the same: `AdapterParseError` is a `DSPyError`, not a
  * `ValueError`, so the `except ValueError` of `ReAct.forward` (`dspy/predict/react.py:104`) does not catch it.
  */
final case class ReAct[I, O](
    baseSignature             : Signature[I, O],
    tools                     : Vector[ToolFunction],
    override val maxIterations: IterationLimit = IterationLimit(20),
    reactProgramName          : String         = ReActKeys.reactModule,
    extractorProgramName      : String         = ReActKeys.extractModule,
    /** Optional override for the per-iteration react predict — a `Predict` over the base input plus the rendered
      * trajectory, producing a lenient [[ReAct.ReactStep]]. When `None` (the default), the predict is built from
      * [[reactSignature]]. Carrying it as a defaulted, `copy`-reachable field is what makes the learnable sub-predict
      * addressable + immutably replaceable (see the `OptimizableStructure[ReAct]` instance).
      */
    reactPredictOverride: Option[Predict[(I, String), ReAct.ReactStep]] = None,
    /** Optional override for the final extractor predict (CoT-augmented, over the base input plus the rendered
      * trajectory). When `None` (the default), it is built fail-fast from [[extractorSignature]] at construction; see
      * [[extractorPredict]].
      */
    extractorPredictOverride: Option[Predict[(I, String), ReAct.WithReasoning[O]]] = None
)(using
    // A `val`, so that a `copy` keeps this evidence (see `CompositeOptimizableStructureInstances`).
    private[programs] val prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
) extends InterpretedTrajectoryAgent[I, ReAct.WithReasoning[O], ReAct.TrajectoryEntry]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out                  = ReAct.WithReasoning[O]
  override type ModelStep   = ReAct.ReactStep
  override type Action      = ToolCallRequest
  override type Observation = String

  override val moduleName: String         = ReActKeys.reactModule
  private val baseLayout: SignatureLayout = baseSignature.layout

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop. The list has one tool for each
    * name, as the dict of upstream `ReAct.__init__`: a later tool with a name takes the place of the earlier one, so a
    * user tool named `finish` gives its place to the injected tool.
    */
  private val allTools
      : Vector[ToolFunction] = (tools :+ ReAct.finishTool(baseLayout)).foldLeft(Vector.empty[ToolFunction]) {
    (unique, tool) =>
      unique.indexWhere(_.name == tool.name) match
        case -1    => unique :+ tool
        case index => unique.updated(index, tool)
  }
  private val toolsByName: Map[String, ToolFunction] = allTools.map(tool => tool.name -> tool).toMap

  /** Interpreter for ReAct's small action language: one named tool call with a record of arguments. Tool selection and
    * invocation failures are recoverable observations, so the LM can react to them on its next turn. The observation of
    * a result is its text in the trajectory, and an error of a tool gives `Execution error in <name>: <message>`
    * (upstream `ReAct.forward`). An error of a tool is a `Left` of the tool or a non-fatal exception of the tool
    * (upstream `except Exception`; see [[ToolExecutor.run]]). A fatal error of a tool goes up to the caller.
    *
    * The parsers of the port reject an empty name and a name that is not a tool (decision D7), so the observations for
    * these names come only from an adapter that does not check the allowed values of `next_tool_name`.
    */
  override protected val actionInterpreter: ActionInterpreter[ToolCallRequest, String] =
    new ActionInterpreter[ToolCallRequest, String]:
      override def execute(request: ToolCallRequest)(using
          RuntimeContext
      ): Either[DspyError, ActionOutcome[String]] =
        if request.name.isEmpty then
          Right(ActionOutcome.Failed("No tool was selected."))
        else if !toolsByName.contains(request.name) then
          Right(ActionOutcome.Failed(s"Execution error: tool `${request.name}` does not exist."))
        else
          ToolExecutor.invoke(request, allTools).map { callResult =>
            callResult.result match
              case Right(value) => ActionOutcome.Succeeded(ReActProtocol.observationText(value))
              case Left(error)  => ActionOutcome.Failed(s"Execution error in ${request.name}: ${error.message}")
          }

  /** The base signature with the `trajectory` input after the base inputs. The react step and the extractor read it. */
  private val withTrajectory: Signature[(I, String), O] = baseSignature.appendInput(ReAct.trajectoryField)

  /** The signature of one loop step: the base inputs and `trajectory`, and the outputs `next_thought`,
    * `next_tool_name`, and `next_tool_args`. The step does not have the base outputs, because the extractor gives them.
    * The values of `next_tool_name` are the names of the tools (upstream `Literal[tuple(tools.keys())]`). The output
    * shape is the lenient [[ReAct.reactStepShape]].
    */
  val reactSignature: Signature[(I, String), ReAct.ReactStep] = withTrajectory
    .withOutputs(
      Vector(ReAct.nextThoughtField, ReActProtocol.nextToolNameField(allTools.map(_.name)), ReAct.nextToolArgsField),
      ReAct.reactStepShape
    )
    .withInstructions(Some(buildInstructions))

  /** The signature of the extractor: the base inputs and `trajectory`, and the outputs `reasoning` (as in
    * ChainOfThought) and the base outputs. It keeps the effective instructions of the base signature (upstream
    * `fallback_signature`).
    */
  val extractorSignature: Signature[(I, String), ReAct.WithReasoning[O]] =
    withTrajectory.prependOutput(ChainOfThought.reasoningName, ChainOfThought.reasoningField)

  /** The react predict of each loop step (upstream `self.react = Predict(...)` in `__init__`). The class builds it one
    * time from [[reactSignature]]. Its input is the base input and the trajectory text, and its output is a
    * [[ReAct.ReactStep]] with a lenient decode (see [[ReAct.reactStepShape]]). [[reactPredictOverride]] replaces it,
    * and `forward` uses this member.
    */
  val reactPredict: Predict[(I, String), ReAct.ReactStep] =
    reactPredictOverride.getOrElse(Predict(signature = reactSignature, name = Some(reactProgramName)))

  /** The extractor predict, built one time from [[extractorSignature]]. Its output is `WithReasoning[O]`, thus the
    * predict decodes the `reasoning` output with the `prepend` evidence of this class. [[extractorPredictOverride]]
    * replaces it.
    */
  override val extractorPredict: Predict[(I, String), ReAct.WithReasoning[O]] =
    extractorPredictOverride.getOrElse(Predict(signature = extractorSignature, name = Some(extractorProgramName)))

  /** The instructions of the react step (upstream `ReAct.__init__`): the effective instructions of the base signature,
    * the protocol of `next_thought`, `next_tool_name`, and `next_tool_args`, and the numbered list of the tools in the
    * text of [[ToolText.describe]] (upstream `Tool.__str__`).
    */
  private def buildInstructions: String =
    val inputs  = baseLayout.inputFields.map(field => s"`${field.name}`").mkString(", ")
    val outputs = baseLayout.outputFields.map(field => s"`${field.name}`").mkString(", ")
    val task    = Option(baseLayout.effectiveInstructions).filter(_.nonEmpty).map(text => s"$text\n").toVector
    val lines   = task ++ Vector(
      s"You are an Agent. In each episode, you will be given the fields $inputs as input. And you can see your past " +
        "trajectory so far.",
      s"Your goal is to use one or more of the supplied tools to collect any necessary information for producing " +
        s"$outputs.\n",
      "To do this, you will interleave next_thought, next_tool_name, and next_tool_args in each turn, and also when " +
        "finishing the task.",
      "After each tool call, you receive a resulting observation, which gets appended to your trajectory.\n",
      "When writing next_thought, you may reason about the current situation and plan for future steps.",
      "When selecting the next_tool_name and its next_tool_args, the tool must be one of:\n"
    ) ++ allTools.zipWithIndex.map((tool, index) => s"(${index + 1}) ${ToolText.describe(tool)}") :+
      "When providing `next_tool_args`, the value inside the field must be in JSON format"
    lines.mkString("\n")

  override protected val lifecycle: ModuleLifecycle[I, Out] = ModuleLifecycle.typed(baseSignature.inputShape)

  override protected val trajectoryKey: String = ReActKeys.trajectory

  override protected def renderTrajectory(trajectory: Vector[ReAct.TrajectoryEntry])(using RuntimeContext): String =
    ReAct.renderTrajectory(trajectory)

  /** Generate one ReAct step, durably truncating the oldest trajectory entry on a context-window overflow. A persistent
    * overflow halts the action loop so final extraction can still run over the remaining view.
    */
  override protected def generateStep(
      call      : ProgramCall[I],
      trajectory: Vector[ReAct.TrajectoryEntry]
  )(using RuntimeContext): Either[DspyError, StepGeneration[ReAct.ReactStep, ReAct.TrajectoryEntry]] =
    reactWithTruncation(call, trajectory, remaining = 3).map {
      case (Some(step), used) => StepGeneration.Generated(step, used)
      case (None, used)       => StepGeneration.Halted(used)
    }

  /** Lower ReAct's model step to its small tool-call language. */
  override protected def prepareAction(step: ReAct.ReactStep): ActionPreparation[ToolCallRequest, String] =
    val toolName = step.nextToolName.trim
    ActionPreparation.Ready(ToolCallRequest(toolName, step.nextToolArgs))

  /** `finish` and a missing tool name both execute once and then end the loop, regardless of their interpreted outcome.
    * The shared transition records that outcome before applying this decision.
    */
  override protected def decide(
      @annotation.unused step   : ReAct.ReactStep,
      action                    : ToolCallRequest,
      @annotation.unused outcome: ActionOutcome[String]
  ): ActionDecision =
    if action.name == ReAct.FinishToolName || action.name.isEmpty then ActionDecision.Stop
    else ActionDecision.Continue

  override protected def recordRejection(
      iteration  : Int,
      step       : ReAct.ReactStep,
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
      step     : ReAct.ReactStep,
      action   : ToolCallRequest,
      outcome  : ActionOutcome[String]
  ): ReAct.TrajectoryEntry =
    ReAct.TrajectoryEntry(
      iteration,
      step.nextThought,
      action.name,
      action.args,
      outcome.observation
    )

  /** Run the react predict over the trajectory. On a context-window overflow, remove the oldest step and try again, up
    * to `remaining` attempts in total (upstream `_call_with_potential_trajectory_truncation` around `self.react`). The
    * result is the step and the view, which can be shorter. The removal is durable: later iterations and the extractor
    * use the shorter trajectory, as upstream changes the shared dict. `(None, view)` means that the overflow stayed (no
    * attempts are left, or no step can go). This is the upstream `ValueError` path, which the loop changes into a
    * break, not a failure. The last entry always stays, so the react predict never runs again on an empty view, and the
    * extractor keeps that entry as evidence.
    */
  private def reactWithTruncation(
      call     : ProgramCall[I],
      view     : Vector[ReAct.TrajectoryEntry],
      remaining: Int
  )(using RuntimeContext): Either[DspyError, (Option[ReAct.ReactStep], Vector[ReAct.TrajectoryEntry])] =
    val (result, used) = truncateOnOverflow(view, remaining)(entries => ReAct.renderTrajectory(entries)) { rendered =>
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

  // ── The loop signature's hand-declared fields (static; hoisted so the shapes and the layout share them) ──
  private[programs] val trajectoryField: FieldSpec   = ReActProtocol.trajectoryField
  private[programs] val nextThoughtField: FieldSpec  = ReActProtocol.nextThoughtField
  private[programs] val nextToolArgsField: FieldSpec = ReActProtocol.nextToolArgsField

  /** The output of one react loop step. */
  final case class ReactStep(
      nextThought : String,
      nextToolName: String,
      nextToolArgs: DynamicValue.Record
  )

  /** Hand-written LENIENT output shape mirroring the prior dynamic reads EXACTLY: a missing `next_thought` /
    * `next_tool_name` renders as "" (a step that names no tool ends the loop rather than failing the call), and
    * `next_tool_args` accepts a JSON object (JSONAdapter), a JSON-object string (ChatAdapter has no `json` coercion),
    * or nothing (empty record). Decode never fails; `jsonSchemaString` stays `None` for parity with the prior direct
    * `DynamicPredict` construction. The parsers of the port give a parse error before this decode for an empty or an
    * unknown tool name (decision D7).
    */
  private[programs] val reactStepShape: Shape[ReactStep] = ReActProtocol.reactStepShape

  /** The injected tool the model selects to end the loop. It does no work — selecting it signals "I have enough to
    * produce the outputs"; the observation is a fixed marker and the extractor then produces the real outputs.
    */
  private def finishTool(baseLayout: SignatureLayout): ToolFunction =
    ReActProtocol.finishTool(baseLayout)

  /** One step of the agent's trajectory: its thought, the tool it chose with arguments, and the observation. The
    * observation is the text that the trajectory shows.
    */
  final case class TrajectoryEntry(
      iteration  : Int,
      thought    : String,
      toolName   : String,
      toolArgs   : DynamicValue.Record,
      observation: String
  )

  /** The trajectory text (upstream `ReAct._format_trajectory`). See [[ReActProtocol.renderTrajectory]]. */
  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry])(using RuntimeContext): String =
    ReActProtocol.renderTrajectory(entries)
