package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.{OutputAugmentation, Prediction, Signature}
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.matching.Regex

/** Iterative code-generation agent. Port of Python DSPy's `dspy.CodeAct`.
  *
  * The flow per iteration:
  *   1. Ask the LM to produce a `generated_code` Python snippet plus a
  *      `finished: bool` flag, given the original task inputs and the
  *      accumulated `trajectory` so far.
  *   2. Strip the fenced ```python code block from the LM's output.
  *   3. Run that code via the configured [[CodeInterpreter]]; capture stdout
  *      (or stderr on failure).
  *   4. Append the snippet + observation to `trajectory`. Exit early if the
  *      LM set `finished=true`.
  *
  * After the loop, a reasoning-augmented [[DynamicPredict]] extractor reads the full trajectory and produces the
  * user-visible outputs declared in `baseSignature`. `CodeAct[I, O]` is a
  * `Module[TypedCall[I], Prediction[WithReasoning[O]]]`: it encodes the typed input, runs the loop + extractor
  * internally over the data-bag layer, and decodes the reply into the base outputs `O` with `reasoning: String`
  * prepended (see [[OutputAugmentation]]). The rendered `trajectory` is kept on `.raw`.
  *
  * '''Scope of this scaffolding.''' The wiring + prompt are present and
  * exercisable end-to-end against a real LM. What is **not** in this v1:
  *
  *   - **Tools-inside-code.** Python `CodeAct` lets the user pass Scala
  *     functions that the LM's generated Python can call. That requires a
  *     Scala↔Python RPC bridge — deferred until the Deno+Pyodide interpreter
  *     lands.
  *   - **Persistent REPL state.** The default
  *     [[dspy4s.core.runtime.SubprocessPythonInterpreter]] is stateless;
  *     CodeAct compensates by carrying the accumulated code in the trajectory.
  *
  * '''Closing the interpreter.''' CodeAct does **not** call `interpreter.close()` itself — the caller owns
  * lifecycle.
  */
final case class CodeAct[I, O](
    baseSignature: Signature[I, O],
    interpreter: CodeInterpreter,
    maxIterations: Int = 5,
    codeActProgramName: String = "codeact",
    extractorProgramName: String = "codeact_extract",
    /** Optional override for the per-iteration code-generator predict. When `None` (the default), it is built from
      * [[codeActSignature]]. Carrying it as a defaulted, `copy`-reachable field makes the learnable sub-predict
      * addressable + immutably replaceable (see the `Predictors[CodeAct]` instance). */
    codeActPredictOverride: Option[DynamicPredict] = None,
    /** Optional override for the final extractor predict (CoT-augmented). When `None` (the default), it is built
      * fail-fast from [[extractorSignature]] at construction; see [[extractorPredict]]. */
    extractorPredictOverride: Option[DynamicPredict] = None
)(using
    prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, CodeAct.WithReasoning[O]]
) extends Module[TypedCall[I], Prediction[CodeAct.WithReasoning[O]]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = CodeAct.WithReasoning[O]

  override val moduleName: String = "code_act"
  require(maxIterations > 0, "maxIterations must be greater than 0")

  private val baseLayout: SignatureLayout = baseSignature.layout

  /** SignatureLayout for the per-iteration code generator. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: {generated_code, finished} */
  val codeActSignature: SignatureLayout =
    baseLayout
      // Replace any user-supplied output fields on the codeact signature with just generated_code + finished.
      // The original outputs are produced by the extractor.
      .withFields(
        baseLayout.inputFields ++
          Vector(
            FieldSpec(
              name = "trajectory",
              role = FieldRole.Input,
              typeRef = TypeRef.string,
              description = Some("History of generated code and observations so far.")
            ),
            FieldSpec(
              name = "generated_code",
              role = FieldRole.Output,
              typeRef = TypeRef.string,
              description = Some("Python code that, when executed, produces output relevant to answering the question.")
            ),
            FieldSpec(
              name = "finished",
              role = FieldRole.Output,
              typeRef = TypeRef.bool,
              description = Some("Set to true once enough information has been collected to produce the final outputs.")
            )
          )
      )
      .withInstructions(Some(buildInstructions))

  /** SignatureLayout for the final extractor. Mirrors Python:
    *   inputs:  baseSignature.inputs ∪ {trajectory}
    *   outputs: baseSignature.outputs */
  val extractorSignature: SignatureLayout =
    baseLayout.append(FieldSpec(
      name = "trajectory",
      role = FieldRole.Input,
      typeRef = TypeRef.string,
      description = Some("History of generated code and observations.")
    ))

  /** The per-iteration code-generator predict, built once from [[codeActSignature]] (mirrors Python's
    * `self.code = Predict(...)` in `__init__`). Addressable + tunable via [[codeActPredictOverride]]; `forward`
    * uses this member rather than rebuilding a local each call. */
  val codeActPredict: DynamicPredict =
    codeActPredictOverride.getOrElse(DynamicPredict(layout = codeActSignature, name = Some(codeActProgramName)))

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

  /** System-prompt instructions handed to the codeact DynamicPredict. Mirrors
    * Python's `_build_instructions` shape verbatim. */
  private def buildInstructions: String =
    val inputs = baseLayout.inputFields.map(f => s"`${f.name}`").mkString(", ")
    val outputs = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
    val taskPrelude = baseLayout.instructions.fold("")(_ + "\n")
    s"""${taskPrelude}You are an intelligent agent. For each episode, you will receive the fields $inputs as input.
       |Your goal is to generate executable Python code that collects any necessary information for producing $outputs.
       |For each iteration, you will generate a code snippet that either solves the task or progresses towards the solution.
       |Ensure any output you wish to extract from the code is printed to the console. The code should be enclosed in a fenced code block.
       |When all information for producing the outputs ($outputs) are available to be extracted, mark `finished=true` besides the final Python code.
       |You have access to the Python Standard Library.""".stripMargin

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
      trajectory <- runIterations(baseCall, codeActPredict, trajectory = Vector.empty, iteration = 0)
      rendered = DynamicValue.Primitive(PrimitiveValue.String(trajectory.render))
      extracted <- extractorPredict.apply(baseCall.copy(inputs = inputs.updated("trajectory", rendered)))
      reasoning <- extractReasoning(extracted.values)
      baseOut   <- baseSignature.outputShape.decode(extracted.values)
      augmented <- prepend.prepend(reasoning, baseOut).toRight(unsupportedOutputShape(baseOut))
    yield Prediction(
      output = augmented,
      raw = DynamicPrediction(
        values      = extracted.values.updated("trajectory", rendered),
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

  /** Recursive iteration loop. `maxIterations` bounds depth. */
  private def runIterations(
      call: ProgramCall,
      codeActPredict: DynamicPredict,
      trajectory: Vector[CodeAct.TrajectoryEntry],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, Vector[CodeAct.TrajectoryEntry]] =
    if iteration >= maxIterations then Right(trajectory)
    else
      val stepInputs = call.inputs.updated(
        "trajectory",
        DynamicValue.Primitive(PrimitiveValue.String(CodeAct.renderTrajectory(trajectory)))
      )
      codeActPredict.apply(call.copy(inputs = stepInputs)).flatMap { prediction =>
        val rawCode  = prediction.get("generated_code").map(DynamicValues.renderText).getOrElse("")
        val finished = isFinished(prediction.get("finished"))
        val code     = extractCode(rawCode).getOrElse(rawCode.trim)

        if code.isEmpty then
          val entry = CodeAct.TrajectoryEntry(iteration, code = "", observation = "Failed to parse the generated code: empty code", isError = true)
          if finished then Right(trajectory :+ entry)
          else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
        else
          interpreter.execute(code) match
            case Right(result) if result.exitCode == 0 =>
              val entry = CodeAct.TrajectoryEntry(iteration, code = code, observation = result.stdout.stripTrailing, isError = false)
              if finished then Right(trajectory :+ entry)
              else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
            case Right(result) =>
              val entry = CodeAct.TrajectoryEntry(
                iteration,
                code = code,
                observation = s"Failed to execute the generated code: ${result.stderr.stripTrailing}",
                isError = true
              )
              if finished then Right(trajectory :+ entry)
              else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
            case Left(err: RuntimeError) =>
              val entry = CodeAct.TrajectoryEntry(
                iteration,
                code = code,
                observation = s"Interpreter failure (${err.component}): ${err.message}",
                isError = true
              )
              if finished then Right(trajectory :+ entry)
              else runIterations(call, codeActPredict, trajectory :+ entry, iteration + 1)
            case Left(other) => Left(other)
      }

  private def isFinished(value: Option[DynamicValue]): Boolean =
    value match
      case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(b))) => b
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s)))  => s.trim.equalsIgnoreCase("true")
      case _                                                       => false

  /** Strip a fenced ```python / ``` block from the LM's `generated_code`
    * field. The LM is instructed to emit fenced code, but it sometimes
    * emits the raw snippet — both shapes are accepted. */
  private def extractCode(raw: String): Option[String] =
    val trimmed = raw.trim
    CodeAct.FencedBlock.findFirstMatchIn(trimmed) match
      case Some(m) => Some(m.group(1).trim)
      case None    =>
        if trimmed.nonEmpty then Some(trimmed) else None

  private def extractReasoning(values: DynamicValue.Record): Either[DspyError, String] =
    DynamicValues.recordGet(values, "reasoning") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
      case Some(other) =>
        Left(ValidationError(s"CodeAct reasoning field must be a String, got: $other"))
      case None =>
        Left(NotFoundError(
          resource = "prediction_field",
          message  = "Required field 'reasoning' is missing from the CodeAct extractor prediction"
        ))

  private def unsupportedOutputShape(baseOut: O): DspyError =
    ValidationError(
      s"CodeAct requires a product output (named tuple or case class); the signature '${baseSignature.name}' has " +
      s"a fieldless output (got ${baseOut.getClass.getSimpleName}). Use a typed signature " +
      s"(Signature.of / Signature.derived / Signature.fromType / a literal Signature.fromString)."
    )

object CodeAct:
  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]

  /** Matches a fenced code block, optionally tagged ```python. Captures the
    * snippet body in group 1. Multiline-aware. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r

  /** One step in the CodeAct trajectory. `code` is what we ran; `observation`
    * is either the captured stdout (success) or an explanation of what
    * failed (parse, execute, or interpreter error). */
  final case class TrajectoryEntry(
      iteration: Int,
      code: String,
      observation: String,
      isError: Boolean
  )

  extension (entries: Vector[TrajectoryEntry])
    def render: String = CodeAct.renderTrajectory(entries)

  private[programs] def renderTrajectory(entries: Vector[TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        val codeBlock =
          if entry.code.isEmpty then "(no code)"
          else s"```python\n${entry.code}\n```"
        val obsLabel = if entry.isError then "observation" else "code_output"
        s"## Iteration ${entry.iteration + 1}\n$codeBlock\n${obsLabel}_${entry.iteration}: ${entry.observation}"
      }.mkString("\n\n")
