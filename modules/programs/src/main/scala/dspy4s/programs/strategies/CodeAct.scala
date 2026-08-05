package dspy4s.programs.strategies

import dspy4s.programs.IterationLimit
import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome}
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.InterpretedTrajectoryAgent
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{ActionDecision, ActionPreparation, StepGeneration}
import dspy4s.programs.runtime.{GeneratedPython, SandboxToolBridge}
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.signatures.{InputAugmentation, OutputAugmentation, Shape, Signature}

/** Iterative code-generation agent. Port of Python DSPy's `dspy.CodeAct`.
  *
  * The flow per iteration:
  *   1. Ask the LM to produce a `generated_code` Python snippet plus a `finished: bool` flag, given the original task
  *      inputs and the accumulated `trajectory` so far.
  *   2. Strip the fenced Python code block from the LM's output.
  *   3. Run that code via the configured [[CodeInterpreter]]; capture stdout (or stderr on failure).
  *   4. Append the snippet and observation to `trajectory`. Exit early if the LM set `finished=true`.
  *
  * After the loop, a reasoning-augmented [[DynamicPredict]] extractor reads the full trajectory and produces the
  * user-visible outputs declared in `baseSignature`. `CodeAct[I, O]` is a `Module[I, Prediction[WithReasoning[O]]]`: it
  * encodes the typed input, runs the loop + extractor internally over the data-bag layer, and decodes the reply into
  * the base outputs `O` with `reasoning: String` prepended (see [[OutputAugmentation]]). The rendered `trajectory` is
  * kept on `.raw`.
  *
  * '''Tools-inside-code.''' Python `CodeAct` lets the user pass functions the LM's generated Python can call. Pass them
  * as [[tools]]: they are listed in the codeact instructions (so the LM knows they exist), and on a sandboxed
  * [[dspy4s.core.runtime.DenoPyodideInterpreter]] the SAME vector is wired into the sandbox via [[sandboxTools]] so the
  * calls execute (`new DenoPyodideInterpreter(tools = program.sandboxTools)`). The plain
  * [[dspy4s.core.runtime.SubprocessPythonInterpreter]] has no bridge — there, pre-load tools into the environment or go
  * without. (Upstream injects each tool's Python SOURCE into the interpreter, which is why it only accepts plain
  * functions; the dspy4s bridge is RPC, so Scala-implemented tools work too.)
  *
  * '''Per-call iteration override.''' Python accepts `max_iters` as a call kwarg; the dspy4s idiom is the immutable
  * copy — `program.copy(maxIterations = IterationLimit(3))(...)` — rather than a magic key in the per-call config bag
  * (which is reserved for provider options). Runtime values cross the boundary through `IterationLimit.either(n)`.
  *
  * '''Persistent REPL state.''' The default [[dspy4s.core.runtime.SubprocessPythonInterpreter]] is stateless across
  * snippets; the trajectory carries earlier code/output as PROMPT context, so the LM regenerates what it needs. On the
  * stateful Deno+Pyodide interpreter, variables genuinely persist between iterations (upstream behavior).
  *
  * '''Closing the interpreter.''' CodeAct does **not** call `interpreter.close()` itself — the caller owns lifecycle.
  * (Upstream shuts the interpreter down at the end of every `forward`, even a caller-supplied one — a delta we
  * deliberately do not copy.)
  */
final case class CodeAct[I, O](
    baseSignature: Signature[I, O],
    interpreter  : CodeInterpreter,
    /** Tools the generated Python may call (Python `CodeAct(tools=...)`). They are listed in the codeact instructions
      * (so the LM knows they exist) and should ALSO be wired into the sandbox via [[sandboxTools]] (so the calls
      * actually execute) — same vector, both sides.
      */
    tools                     : Vector[dspy4s.programs.contracts.ToolFunction] = Vector.empty,
    override val maxIterations: IterationLimit                                 = IterationLimit(5),
    codeActProgramName        : String                                         = "codeact",
    extractorProgramName      : String                                         = "codeact_extract",
    /** Optional override for the per-iteration code-generator predict — a TYPED `Predict` over the base input plus the
      * rendered trajectory, producing a lenient [[CodeAct.CodeStep]]. When `None` (the default), it is built from
      * [[codeActSignature]]. Carrying it as a defaulted, `copy`-reachable field makes the learnable sub-predict
      * addressable + immutably replaceable (see the `OptimizableTraversal[CodeAct]` instance).
      */
    codeActPredictOverride: Option[Predict[(I, String), CodeAct.CodeStep]] = None,
    /** Optional override for the final extractor predict (CoT-augmented, typed over the base input plus the rendered
      * trajectory). When `None` (the default), it is built fail-fast from [[extractorSignature]] at construction; see
      * [[extractorPredict]].
      */
    extractorPredictOverride: Option[Predict[(I, String), CodeAct.WithReasoning[O]]] = None
)(using
    prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
) extends InterpretedTrajectoryAgent[I, CodeAct.WithReasoning[O], CodeAct.TrajectoryEntry]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out                  = CodeAct.WithReasoning[O]
  override type ModelStep   = CodeAct.CodeStep
  override type Action      = String
  override type Observation = String

  override val moduleName: String         = "code_act"
  private val baseLayout: SignatureLayout = baseSignature.layout

  /** Interpreter for CodeAct's action language. Python exceptions and runtime-interpreter failures become recoverable
    * observations; other infrastructure errors remain fatal and propagate as `Left`.
    */
  override protected val actionInterpreter: ActionInterpreter[String, String] = new ActionInterpreter[String, String]:
    override def execute(code: String)(using RuntimeContext): Either[DspyError, ActionOutcome[String]] =
      interpreter.execute(code) match
        case Right(result) if result.exitCode == 0 => Right(ActionOutcome.Succeeded(result.stdout.stripTrailing))
        case Right(result)                         =>
          Right(ActionOutcome.Failed(s"Failed to execute the generated code: ${result.stderr.stripTrailing}"))
        case Left(err: RuntimeError) =>
          Right(ActionOutcome.Failed(s"Interpreter failure (${err.component}): ${err.message}"))
        case Left(other) => Left(other)

  /** SignatureLayout for the per-iteration code generator. Mirrors Python: inputs: baseSignature.inputs ∪ {trajectory}
    * outputs: {generated_code, finished}
    */
  val codeActSignature: SignatureLayout = baseLayout
    // Replace any user-supplied output fields on the codeact signature with just generated_code + finished.
    // The original outputs are produced by the extractor.
    .appendInput(CodeAct.loopTrajectoryField)
    .replaceOutputs(Vector(CodeAct.generatedCodeField, CodeAct.finishedField))
    .withInstructions(Some(buildInstructions))

  /** SignatureLayout for the final extractor. Mirrors Python: inputs: baseSignature.inputs ∪ {trajectory} outputs:
    * baseSignature.outputs
    */
  val extractorSignature: SignatureLayout = baseLayout.appendInput(CodeAct.extractTrajectoryField)

  /** The per-iteration code-generator predict, built once from [[codeActSignature]] (mirrors Python's `self.code =
    * Predict(...)` in `__init__`) — a TYPED `Predict[(I, String), CodeStep]`: the base input plus the rendered
    * trajectory in, a leniently-decoded [[CodeAct.CodeStep]] out (see [[CodeAct.codeStepShape]]). Addressable + tunable
    * via [[codeActPredictOverride]]; `forward` uses this member rather than rebuilding a local each call.
    */
  val codeActPredict: Predict[(I, String), CodeAct.CodeStep] = codeActPredictOverride.getOrElse(Predict(
    signature = Signature(
      name = baseSignature.name,
      layout = codeActSignature,
      inputShape =
        InputAugmentation.appendedStringInput(baseSignature.inputShape, CodeAct.loopTrajectoryField, "CodeAct"),
      outputShape = CodeAct.codeStepShape
    ),
    name = Some(codeActProgramName)
  ))

  /** The final extractor predict, built once from the CoT-augmented [[extractorSignature]] — a TYPED
    * `Predict[(I, String), WithReasoning[O]]`, so the reasoning-prepended decode happens inside the predict (the
    * `prepend` evidence this class already carries). Tunable via [[extractorPredictOverride]].
    */
  override val extractorPredict: Predict[(I, String), CodeAct.WithReasoning[O]] =
    extractorPredictOverride.getOrElse(Predict(
      signature = Signature(
        name = baseSignature.name,
        layout = ChainOfThought.augmentLayout(extractorSignature),
        inputShape = InputAugmentation
          .appendedStringInput(baseSignature.inputShape, CodeAct.extractTrajectoryField, "CodeAct extractor"),
        outputShape = OutputAugmentation.prependedStringShape(
          baseSignature.outputShape,
          ChainOfThought.reasoningField,
          ChainOfThought.reasoningName,
          "CodeAct extractor",
          baseSignature.name
        )
      ),
      name = Some(extractorProgramName)
    ))

  /** System-prompt instructions handed to the codeact DynamicPredict. Mirrors Python's `_build_instructions` shape
    * verbatim, including the numbered tool list (upstream's `Tool.__str__` rendering: name, `<desc>`-wrapped
    * description, argument schema).
    */
  private def buildInstructions: String =
    val inputs      = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs     = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    val taskPrelude = baseLayout.instructions.fold("")(_ + "\n")
    val library     =
      if tools.isEmpty then "You have access to the Python Standard Library."
      else "You have access to the Python Standard Library and the following functions:"
    val toolLines = tools.zipWithIndex.map { case (tool, idx) => s"(${idx + 1}) ${CodeAct.renderTool(tool)}" }
    (Vector(
      s"""${taskPrelude}You are an intelligent agent. For each episode, you will receive the fields $inputs as input.
         |Your goal is to generate executable Python code that collects any necessary information for producing $outputs.
         |For each iteration, you will generate a code snippet that either solves the task or progresses towards the solution.
         |Ensure any output you wish to extract from the code is printed to the console. The code should be enclosed in a fenced code block.
         |When all information for producing the outputs ($outputs) are available to be extracted, mark `finished=true` besides the final Python code.
         |$library""".stripMargin
    ) ++ toolLines).mkString("\n")

  override protected val lifecycle: ModuleLifecycle[I, Out] = ModuleLifecycle.typed(baseSignature.inputShape)

  override protected val trajectoryKey: String = CodeAct.extractTrajectoryField.name

  override protected def renderTrajectory(trajectory: Vector[CodeAct.TrajectoryEntry]): String =
    CodeAct.renderTrajectory(trajectory)

  /** This program's [[tools]] bridged for a sandboxed interpreter — pass as `new DenoPyodideInterpreter(tools =
    * program.sandboxTools)` so the prompt's tool list and the sandbox's callable surface come from the same vector. See
    * [[CodeAct.sandboxTools]].
    */
  def sandboxTools(using RuntimeContext): Vector[dspy4s.core.contracts.SandboxTool] =
    SandboxToolBridge.fromToolFunctions(tools)

  /** Generate the next typed code step from the original input and current rendered trajectory.
    */
  override protected def generateStep(
      call      : ProgramCall[I],
      trajectory: Vector[CodeAct.TrajectoryEntry]
  )(using RuntimeContext): Either[DspyError, StepGeneration[CodeAct.CodeStep, CodeAct.TrajectoryEntry]] =
    val rendered = CodeAct.renderTrajectory(trajectory)
    val stepCall = call.mapInput(input => (input, rendered))
    codeActPredict(stepCall).map(prediction => StepGeneration.Generated(prediction.output, trajectory))

  /** Parse the generated Python into an executable action. Upstream `continue`s after a parse failure, so rejection
    * consumes the iteration, records an error entry, and deliberately ignores the model's `finished` flag.
    */
  override protected def prepareAction(step: CodeAct.CodeStep): ActionPreparation[String, String] =
    GeneratedPython.parse(step.generatedCode) match
      case Left(parseError) => ActionPreparation.Rejected(s"Failed to parse the generated code: $parseError")
      case Right(code)      => ActionPreparation.Ready(code)

  /** Preserve upstream behavior: a successfully parsed action may finish the loop even when interpretation reports a
    * recoverable failure. The shared transition records the outcome before applying the model's `finished` decision.
    */
  override protected def decide(
      step                      : CodeAct.CodeStep,
      @annotation.unused action : String,
      @annotation.unused outcome: ActionOutcome[String]
  ): ActionDecision =
    if step.finished then ActionDecision.Stop else ActionDecision.Continue

  override protected def recordRejection(
      iteration              : Int,
      @annotation.unused step: CodeAct.CodeStep,
      observation            : String
  ): CodeAct.TrajectoryEntry =
    CodeAct.TrajectoryEntry(
      iteration,
      code = "",
      observation = observation,
      isError = true
    )

  override protected def recordOutcome(
      iteration              : Int,
      @annotation.unused step: CodeAct.CodeStep,
      action                 : String,
      outcome                : ActionOutcome[String]
  ): CodeAct.TrajectoryEntry =
    CodeAct.TrajectoryEntry(
      iteration,
      code = action,
      observation = outcome.observation,
      isError = outcome.isError
    )

object CodeAct:
  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = ChainOfThought.WithReasoning[O]

  // ── The loop signature's hand-declared fields (static; hoisted so the typed shapes and the layout share them) ──
  private[programs] val loopTrajectoryField: FieldSpec    = CodeActProtocol.loopTrajectoryField
  private[programs] val extractTrajectoryField: FieldSpec = CodeActProtocol.extractTrajectoryField
  private[programs] val generatedCodeField: FieldSpec     = CodeActProtocol.generatedCodeField
  private[programs] val finishedField: FieldSpec          = CodeActProtocol.finishedField

  /** The typed output of one codeact loop step. */
  final case class CodeStep(generatedCode: String, finished: Boolean)

  /** Hand-written LENIENT output shape mirroring the prior dynamic reads EXACTLY: a missing `generated_code` renders as
    * "" (the parse-failure path records the observation and continues), and `finished` accepts a Boolean primitive or
    * the string "true" (case-insensitive), anything else — including absence — reading as `false`. Decode never fails;
    * `jsonSchemaString` stays `None` for parity with the prior direct `DynamicPredict` construction.
    */
  private[programs] val codeStepShape: Shape[CodeStep] = CodeActProtocol.codeStepShape

  /** Bridge [[dspy4s.programs.contracts.ToolFunction]]s into [[dspy4s.core.contracts.SandboxTool]]s so the LM's
    * generated Python can call them BY NAME from inside a sandboxed interpreter — Python `CodeAct`'s tools-inside-code,
    * enabled by [[dspy4s.core.runtime.DenoPyodideInterpreter]]:
    *
    * {{{
    * val interpreter = new DenoPyodideInterpreter(tools = CodeAct.sandboxTools(myTools))
    * val program     = CodeAct(signature, interpreter)
    * }}}
    *
    * The ambient [[RuntimeContext]] is captured NOW and used for every sandbox-initiated invocation (the bridge call
    * arrives outside any dspy4s call stack). Wire-type `argSchema` entries map to Python type hints where a direct
    * equivalent exists.
    */
  def sandboxTools(tools: Vector[dspy4s.programs.contracts.ToolFunction])(using
      ctx: dspy4s.core.contracts.RuntimeContext
  ): Vector[dspy4s.core.contracts.SandboxTool] =
    SandboxToolBridge.fromToolFunctions(tools)

  /** Render one tool for the instruction list — upstream `Tool.__str__`: name, `<desc>`-wrapped description (newlines
    * flattened), and the argument schema. Args render as `{name: wireType, …}` from
    * [[dspy4s.programs.contracts.ToolFunction.argSchema]] (upstream renders its JSON-schema dict).
    */
  private[programs] def renderTool(tool: dspy4s.programs.contracts.ToolFunction): String =
    CodeActProtocol.renderTool(tool)

  /** One step in the CodeAct trajectory. `code` is what we ran; `observation` is either the captured stdout (success)
    * or an explanation of what failed (parse, execute, or interpreter error).
    */
  final case class TrajectoryEntry(
      iteration  : Int,
      code       : String,
      observation: String,
      isError    : Boolean
  )

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    CodeActProtocol.renderTrajectory(entries)
