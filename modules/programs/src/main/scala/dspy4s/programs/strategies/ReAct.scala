package dspy4s.programs.strategies

import dspy4s.programs.{IterationLimit, SignatureInput}
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.internal.ToolText
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.runtime.AgentLoop
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue

import scala.collection.immutable.VectorMap

/** ReAct ("Reasoning and Acting"), the tool-using agent paradigm. Port of Python DSPy's `dspy.ReAct`, generalized over
  * any signature.
  *
  * Each iteration, the LM is shown the task inputs and the trajectory so far and emits three output fields —
  * `next_thought` (its reasoning), `next_tool_name` (the tool to call), and `next_tool_args` (the JSON arguments).
  * ReAct runs the named tool, appends the observation to the trajectory, and repeats until the LM selects the injected
  * `finish` tool (or `maxIterations` is reached). A separate reasoning-augmented extractor then reads the full
  * trajectory and produces the user-visible outputs declared in `baseSignature`.
  *
  * `ReAct[I, O]` is a `Module[I, ChainOfThought.WithReasoning[O]]`: the input is encoded from `I`, and the extractor's
  * reply is decoded into the base outputs `O` with a `reasoning: String` prepended (always a named tuple; see
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
  *
  * A `next_tool_args` value that is not a dict (upstream type `dict[str, Any]`) also fails the whole call, and no tool
  * runs. The port finds this problem in the decode of [[ReActProtocol.reactStepShape]], after the adapter, so this
  * error has no JSON fallback.
  */
final case class ReAct[I, O](
    baseSignature: Signature[I, O],
    tools        : Vector[ToolFunction],
    maxIterations: IterationLimit = IterationLimit(20),
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
    extractorPredictOverride: Option[Predict[(I, String), ChainOfThought.WithReasoning[O]]] = None
)(using
    // A `val`, so that a `copy` keeps this evidence (see `reactOptimizableStructure`).
    private[programs] val prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
) extends Module[I, ChainOfThought.WithReasoning[O]], SignatureInput[I]:
  import ReAct.TrajectoryEntry

  override val moduleName: String         = ReActKeys.reactModule
  private val baseLayout: SignatureLayout = baseSignature.layout

  /** The supplied tools plus the injected `finish` tool the LM selects to end the loop ([[ToolFunction.byName]]). A
    * user tool named `finish` gives its place to the injected tool.
    */
  private val toolsByName: VectorMap[String, ToolFunction] =
    ToolFunction.byName(tools :+ ReActProtocol.finishTool(baseLayout))
  private val allTools: Vector[ToolFunction] = toolsByName.values.toVector

  /** The base signature with the `trajectory` input after the base inputs. The react step and the extractor read it. */
  private val withTrajectory: Signature[(I, String), O] = baseSignature.appendInput(ReActProtocol.trajectoryField)

  /** The signature of one loop step: the base inputs and `trajectory`, and the outputs `next_thought`,
    * `next_tool_name`, and `next_tool_args`. The step does not have the base outputs, because the extractor gives them.
    * The values of `next_tool_name` are the names of the tools (upstream `Literal[tuple(tools.keys())]`). The output
    * shape is the lenient [[ReActProtocol.reactStepShape]].
    */
  val reactSignature: Signature[(I, String), ReAct.ReactStep] = withTrajectory
    .withOutputs(
      Vector(
        ReActProtocol.nextThoughtField,
        ReActProtocol.nextToolNameField(allTools.map(_.name)),
        ReActProtocol.nextToolArgsField
      ),
      ReActProtocol.reactStepShape
    )
    .withInstructions(Some(buildInstructions))

  /** The signature of the extractor: the base inputs and `trajectory`, and the outputs `reasoning` (as in
    * ChainOfThought) and the base outputs. It keeps the effective instructions of the base signature (upstream
    * `fallback_signature`).
    */
  val extractorSignature: Signature[(I, String), ChainOfThought.WithReasoning[O]] =
    withTrajectory.prependOutput(ChainOfThought.reasoningName, ChainOfThought.reasoningField)

  /** The react predict of each loop step (upstream `self.react = Predict(...)` in `__init__`). The class builds it one
    * time from [[reactSignature]]. Its input is the base input and the trajectory text, and its output is a
    * [[ReAct.ReactStep]] with a lenient decode (see [[ReActProtocol.reactStepShape]]). [[reactPredictOverride]]
    * replaces it, and `forward` uses this member.
    */
  val reactPredict: Predict[(I, String), ReAct.ReactStep] =
    reactPredictOverride.getOrElse(Predict(signature = reactSignature, name = Some(ReActKeys.reactModule)))

  /** The extractor predict, built one time from [[extractorSignature]]. Its output is `WithReasoning[O]`, thus the
    * predict decodes the `reasoning` output with the `prepend` evidence of this class. [[extractorPredictOverride]]
    * replaces it.
    */
  val extractorPredict: Predict[(I, String), ChainOfThought.WithReasoning[O]] =
    extractorPredictOverride.getOrElse(Predict(signature = extractorSignature, name = Some(ReActKeys.extractModule)))

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

  override protected val lifecycle: ModuleLifecycle[I, ChainOfThought.WithReasoning[O]] =
    ModuleLifecycle.typed(baseSignature.inputShape)

  /** Run the loop ([[AgentLoop.run]]), then the extractor one time over its trajectory. An error of a step ends the
    * call with no extract. The extractor truncates its own view on a context-window overflow, but the result keeps the
    * complete trajectory of the loop on `.raw`. The other parts of the extractor result stay, for example its
    * completions. The LM usage of the result is the total of the usage scope of the call, which `Module.apply` opens:
    * each model call of the loop, of the tools, and of the extractor (upstream `Module.__call__`,
    * `dspy/primitives/module.py`).
    */
  override protected def forward(call: ProgramCall[I])(using
      RuntimeContext
  ): Either[DspyError, Prediction[ChainOfThought.WithReasoning[O]]] =
    for
      trajectory <- AgentLoop.run(Vector.empty[TrajectoryEntry], maxIterations)(Right(_))(step(call))
      rendered    = ReActProtocol.renderTrajectory(trajectory)
      extracted  <- truncateOnOverflow(trajectory) { view =>
                     val text = if view eq trajectory then rendered else ReActProtocol.renderTrajectory(view)
                     extractorPredict(call.mapInput((_, text)))
                   }._1
    yield extracted.copy(raw = extracted.raw.withRawValue(ReActKeys.trajectory, rendered))

  /** One iteration of the loop: run the react predict over the trajectory, run the selected tool, and append the entry.
    *
    * On a context-window overflow, the predict removes the oldest step and tries again ([[truncateOnOverflow]];
    * upstream `_call_with_potential_trajectory_truncation` around `self.react`). The removal is durable: later
    * iterations and the extractor use the shorter trajectory, as upstream changes the shared dict. When the overflow
    * stays (no attempts are left, or no step can go), the loop ends with no entry and the extractor runs. This is the
    * upstream `ValueError` path, which the loop changes into a break, not a failure. The last entry always stays, so
    * the react predict never runs again on an empty view, and the extractor keeps that entry as evidence.
    *
    * The `finish` tool and an empty tool name end the loop after their entry, also when the tool fails. Each other
    * error of the react predict ends the call.
    */
  private def step(call: ProgramCall[I])(
      trajectory: Vector[TrajectoryEntry],
      iteration : Int
  )(using RuntimeContext): Either[DspyError, AgentLoop.Step[Vector[TrajectoryEntry], Vector[TrajectoryEntry]]] =
    val (result, view) = truncateOnOverflow(trajectory) { entries =>
      reactPredict(call.mapInput((_, ReActProtocol.renderTrajectory(entries))))
    }
    result match
      case Left(_: ContextWindowExceededError) => Right(AgentLoop.Step.Done(view))
      case Left(error)                         => Left(error)
      case Right(prediction)                   =>
        val chosen   = prediction.output
        val toolName = chosen.nextToolName.trim
        val args     = chosen.nextToolArgs
        val next     = view :+ TrajectoryEntry(iteration, chosen.nextThought, toolName, args, observe(toolName, args))
        Right(
          if toolName == ReAct.FinishToolName || toolName.isEmpty then AgentLoop.Step.Done(next)
          else AgentLoop.Step.Continue(next)
        )

  /** The observation of one tool call: its text in the trajectory. A tool selection or a tool error is an observation
    * too, so the LM can react to it on its next turn. An error of a tool gives `Execution error in <name>: <message>`
    * (upstream `ReAct.forward`). An error of a tool is a `Left` of the tool or a non-fatal exception of the tool
    * (upstream `except Exception`; see [[ToolExecutor.run]]). A fatal error of a tool goes up to the caller.
    *
    * The parsers of the port reject an empty name and a name that is not a tool (decision D7), so the observations for
    * these names come only from an adapter that does not check the allowed values of `next_tool_name`.
    */
  private def observe(toolName: String, args: DynamicValue.Record)(using RuntimeContext): String =
    if toolName.isEmpty then "No tool was selected."
    else
      toolsByName.get(toolName) match
        case None       => s"Execution error: tool `$toolName` does not exist."
        case Some(tool) =>
          ToolExecutor.run(tool, args).fold(ToolExecutor.errorText(toolName, _), ReActProtocol.observationText)

object ReAct:
  val FinishToolName: String = "finish"

  /** Two optimizable parts: the step predictor `react` and the final `extractor`. A `copy` gets the `PrependField`
    * evidence of the program. This instance does not know `O`, thus a new search for the evidence finds only the
    * fallback, which makes no augmented output.
    */
  given reactOptimizableStructure[I, O]: OptimizableStructure.Of[ReAct[I, O], 2] =
    OptimizableStructure.overridePair("ReAct", "react", "extractor")(
      _.reactPredict,
      _.reactPredictOverride,
      _.extractorPredict,
      _.extractorPredictOverride,
      (program, react, extractor) =>
        program.copy(reactPredictOverride = react, extractorPredictOverride = extractor)(using program.prepend)
    )

  /** The output of one react loop step. */
  final case class ReactStep(
      nextThought : String,
      nextToolName: String,
      nextToolArgs: DynamicValue.Record
  )

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
