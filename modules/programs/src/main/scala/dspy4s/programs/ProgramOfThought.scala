package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
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

private def stringDv(s: String): DynamicValue =
  DynamicValue.Primitive(PrimitiveValue.String(s))

/** Generate Python code that programmatically computes the answer, run it,
  * and feed the output back to the LM for a structured response. Port of
  * Python DSPy's `dspy.ProgramOfThought`.
  *
  * Three reasoning-augmented [[DynamicPredict]] passes:
  *
  *   1. **generate** — inputs → `generated_code` (Python source)
  *   2. **regenerate** — on execution error, the LM gets `previous_code` +
  *      `error` and emits a fix. Loops up to `maxIterations`.
  *   3. **answer** — inputs + `final_generated_code` + `code_output` →
  *      original outputs declared in `baseSignature`.
  *
  * `ProgramOfThought[I, O]` is a `Module[TypedCall[I], Prediction[WithReasoning[O]]]`: it encodes the typed input,
  * runs the three passes internally over the data-bag layer, and decodes the final answer step into the base
  * outputs `O` with `reasoning: String` prepended (see [[OutputAugmentation]]).
  *
  * '''Behavioral delta from Python parity.''' Python preloads a `SUBMIT(...)` function into Pyodide so the LM's
  * code can return a structured dict; dspy4s's [[CodeInterpreter]] is plain stdout-capture, so this port instructs
  * the LM to **print** its result (typically JSON), and the answer step parses the printed output.
  *
  * Lifecycle: caller owns the interpreter — this does **not** call `interpreter.close()`.
  */
final case class ProgramOfThought[I, O](
    baseSignature: Signature[I, O],
    interpreter: CodeInterpreter,
    maxIterations: Int = 3
)(using
    prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, ProgramOfThought.WithReasoning[O]]
) extends Module[TypedCall[I], Prediction[ProgramOfThought.WithReasoning[O]]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = ProgramOfThought.WithReasoning[O]

  override val moduleName: String = "program_of_thought"
  require(maxIterations > 0, "maxIterations must be greater than 0")

  private val baseLayout: SignatureLayout = baseSignature.layout

  // ── Helper field definitions (declared first so the signature vals below
  // can reference them without hitting an init-order NPE) ────────────────

  private val generatedCodeField = FieldSpec(
    name = "generated_code",
    role = FieldRole.Output,
    typeRef = TypeRef.string,
    description = Some("Python code that, when executed, computes the answer and prints it as JSON."),
    prefix = Some("Code:")
  )
  private val previousCodeField = FieldSpec(
    name = "previous_code",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The Python code from the previous attempt that errored."),
    prefix = Some("Previous Code:")
  )
  private val errorField = FieldSpec(
    name = "error",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("Error message produced by the previous Python code."),
    prefix = Some("Error:")
  )
  private val finalGeneratedCodeField = FieldSpec(
    name = "final_generated_code",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The final Python code that produced the answer."),
    prefix = Some("Code:")
  )
  private val codeOutputField = FieldSpec(
    name = "code_output",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The printed output of the final Python code."),
    prefix = Some("Code Output:")
  )

  private def buildSig(extraInputs: Vector[FieldSpec], extraOutputs: Vector[FieldSpec]): SignatureLayout =
    val withInputs = extraInputs.foldLeft(baseLayout)(_.append(_))
    // The generate / regenerate signatures discard the user's outputs —
    // generated_code is the only output of those steps.
    val withoutUserOutputs =
      withInputs.withFields(withInputs.fields.filterNot(_.role == FieldRole.Output))
    extraOutputs.foldLeft(withoutUserOutputs)(_.append(_))

  /** SignatureLayout for the initial code-generation step. Inputs from the user's
    * signature; outputs a `generated_code` string. */
  val generateSignature: SignatureLayout =
    buildSig(
      extraInputs = Vector.empty,
      extraOutputs = Vector(generatedCodeField)
    ).withInstructions(Some({
      val outputs = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
      s"""You will be given the input fields and you will respond with `generated_code`.
         |Generate executable Python code that programmatically computes the correct $outputs.
         |Print the result as a JSON object whose keys are the output field names — for example
         |    print(json.dumps({"answer": value, ...}))
         |The downstream step parses this output and produces the final structured response.""".stripMargin
    }))

  /** SignatureLayout for the retry step when execution failed. Adds
    * `previous_code` and `error` as inputs. */
  val regenerateSignature: SignatureLayout =
    buildSig(
      extraInputs = Vector(previousCodeField, errorField),
      extraOutputs = Vector(generatedCodeField)
    ).withInstructions(Some(
      """You are given the previous Python code and the error message it produced.
        |Your task is to fix the error and emit corrected `generated_code`.
        |The corrected code must still print its result as a JSON object.""".stripMargin
    ))

  /** SignatureLayout for the final answer-extraction step. Has the user's
    * original outputs plus `final_generated_code` + `code_output` as
    * inputs. */
  val answerSignature: SignatureLayout =
    baseLayout
      .append(finalGeneratedCodeField)
      .append(codeOutputField)
      .withInstructions(Some({
        val outputs = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
        s"Given the final Python code and its printed output, produce the final $outputs."
      }))

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
      generatorLayout   <- ChainOfThought.augmentLayout(generateSignature)
      regeneratorLayout <- ChainOfThought.augmentLayout(regenerateSignature)
      answerLayout      <- ChainOfThought.augmentLayout(answerSignature)
      generator   = DynamicPredict(layout = generatorLayout, runtime = SignatureProgramRuntime)
      regenerator = DynamicPredict(layout = regeneratorLayout, runtime = SignatureProgramRuntime)
      answerer    = DynamicPredict(layout = answerLayout, runtime = SignatureProgramRuntime)
      codeAndOutput <- tryIteration(baseCall, generator, regenerator, attempt = 1)
      (code, codeOutput) = codeAndOutput
      extractInputs = inputs.updated("final_generated_code", stringDv(code)).updated("code_output", stringDv(codeOutput))
      result    <- answerer.apply(baseCall.copy(inputs = extractInputs))
      reasoning <- extractReasoning(result.values)
      baseOut   <- baseSignature.outputShape.decode(result.values)
      augmented <- prepend.prepend(reasoning, baseOut).toRight(unsupportedOutputShape(baseOut))
    yield Prediction(output = augmented, raw = result)

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(TypedCall(input, config, traceEnabled))

  private def tryIteration(
      call: ProgramCall,
      generator: DynamicPredict,
      regenerator: DynamicPredict,
      attempt: Int,
      previous: Option[(String, String)] = None // (code, error) from last attempt
  )(using RuntimeContext): Either[DspyError, (String, String)] =
    val predict = previous match
      case None    => generator
      case Some(_) => regenerator
    val baseInputs = call.inputs
    val inputs = previous match
      case None                => baseInputs
      case Some((code, error)) =>
        baseInputs
          .updated("previous_code", stringDv(code))
          .updated("error", stringDv(error))

    predict.apply(call.copy(inputs = inputs)).flatMap { prediction =>
      val rawCode = prediction.get("generated_code").map(DynamicValues.renderText).getOrElse("")
      val parsed = extractCode(rawCode)

      parsed match
        case Left(parseErr) =>
          if attempt >= maxIterations then
            Left(RuntimeError(
              "program_of_thought",
              s"Max attempts ($maxIterations) reached. Last parse error: $parseErr"
            ))
          else
            tryIteration(call, generator, regenerator, attempt + 1, Some(rawCode -> parseErr))

        case Right(code) =>
          interpreter.execute(code) match
            case Right(result) if result.exitCode == 0 =>
              Right(code -> result.stdout.stripTrailing)
            case Right(result) =>
              if attempt >= maxIterations then
                Left(RuntimeError(
                  "program_of_thought",
                  s"Max attempts ($maxIterations) reached. Last execution error: ${result.stderr.stripTrailing}"
                ))
              else
                tryIteration(call, generator, regenerator, attempt + 1, Some(code -> result.stderr.stripTrailing))
            case Left(interpreterErr) =>
              // Interpreter itself failed (process couldn't start, timed out,
              // …). Don't retry — there's no LM-level fix for that.
              Left(interpreterErr)
    }

  private def extractCode(raw: String): Either[String, String] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("Empty code after parsing.")
    else
      ProgramOfThought.FencedBlock.findFirstMatchIn(trimmed) match
        case Some(m) =>
          val body = m.group(1).trim
          if body.isEmpty then Left("Empty code after parsing.")
          else Right(body)
        case None =>
          Right(trimmed)

  private def extractReasoning(values: DynamicValue.Record): Either[DspyError, String] =
    DynamicValues.recordGet(values, "reasoning") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
      case Some(other) =>
        Left(ValidationError(s"ProgramOfThought reasoning field must be a String, got: $other"))
      case None =>
        Left(NotFoundError(
          resource = "prediction_field",
          message  = "Required field 'reasoning' is missing from the ProgramOfThought answer prediction"
        ))

  private def unsupportedOutputShape(baseOut: O): DspyError =
    ValidationError(
      s"ProgramOfThought requires a product output (named tuple or case class); the signature " +
      s"'${baseSignature.name}' has a fieldless output (got ${baseOut.getClass.getSimpleName}). Use a typed " +
      s"signature (Signature.of / Signature.derived / Signature.fromType / a literal Signature.fromString)."
    )

  /** Use the default settings-based runtime resolution for the inner
    * DynamicPredict programs. */
  private object SignatureProgramRuntime extends dspy4s.programs.runtime.SettingsProgramRuntime

object ProgramOfThought:
  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]

  /** Matches a fenced code block, optionally tagged ```python. Captures the
    * snippet body in group 1. Multiline-aware. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r
