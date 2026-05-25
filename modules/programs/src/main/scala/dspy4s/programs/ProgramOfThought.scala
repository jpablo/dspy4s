package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.BasePredictProgram

import scala.util.matching.Regex

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
  * Unlike [[CodeAct]], this is **not** iterative code-building: each
  * iteration is a fresh attempt at the whole computation, retried only on
  * failure. The LM produces one self-contained snippet that runs to
  * completion (or raises), and we read its stdout once.
  *
  * '''Behavioral delta from Python parity.''' Python's ProgramOfThought
  * preloads a `SUBMIT(...)` function into the Pyodide environment so the
  * LM's code can return a structured dict directly. dspy4s's
  * [[CodeInterpreter]] contract is plain stdout-capture, so this port
  * instructs the LM to **print** its result (typically as JSON) instead
  * of calling SUBMIT. The downstream answer step parses the printed
  * output. Functionally equivalent for the common case; explicit SUBMIT
  * semantics will return when the Deno+Pyodide interpreter lands.
  *
  * Lifecycle: caller owns the interpreter — `ProgramOfThought.execute`
  * does **not** call `interpreter.close()`. The same interpreter can be
  * reused across multiple `run(...)` invocations.
  */
final case class ProgramOfThought(
    baseSignature: SignatureLayout,
    interpreter: CodeInterpreter,
    maxIterations: Int = 3
) extends BasePredictProgram(moduleName = "program_of_thought"):
  require(maxIterations > 0, "maxIterations must be greater than 0")

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
    val withInputs = extraInputs.foldLeft(baseSignature)(_.append(_))
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
      val outputs = baseSignature.outputFields.map(f => s"`${f.name}`").mkString(", ")
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
    baseSignature
      .append(finalGeneratedCodeField)
      .append(codeOutputField)
      .withInstructions(Some({
        val outputs = baseSignature.outputFields.map(f => s"`${f.name}`").mkString(", ")
        s"Given the final Python code and its printed output, produce the final $outputs."
      }))

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    for
      generatorLayout   <- ChainOfThought.augmentLayout(generateSignature)
      regeneratorLayout <- ChainOfThought.augmentLayout(regenerateSignature)
      answerLayout      <- ChainOfThought.augmentLayout(answerSignature)
      generator = DynamicPredict(layout = generatorLayout, runtime = SignatureProgramRuntime)
      regenerator = DynamicPredict(layout = regeneratorLayout, runtime = SignatureProgramRuntime)
      answerer = DynamicPredict(layout = answerLayout, runtime = SignatureProgramRuntime)
      result <- tryIteration(call, generator, regenerator, attempt = 1).flatMap { case (code, codeOutput) =>
        val extractInputs = call.inputs
          .updated("final_generated_code", code)
          .updated("code_output", codeOutput)
        answerer.run(call.copy(inputs = extractInputs))
      }
    yield result

  private def tryIteration(
      call: ProgramCall,
      generator: DynamicPredict,
      regenerator: DynamicPredict,
      attempt: Int,
      previous: Option[(String, String)] = None // (code, error) from last attempt
  )(using RuntimeContext): Either[DspyError, (String, String)] =
    val predict = previous match
      case None        => generator
      case Some(_)     => regenerator
    val baseInputs = call.inputs
    val inputs = previous match
      case None                  => baseInputs
      case Some((code, error))   =>
        baseInputs.updated("previous_code", code).updated("error", error)

    predict.run(call.copy(inputs = inputs)).flatMap { prediction =>
      val rawCode = prediction.values.getOrElse("generated_code", "").toString
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

  /** Use the default settings-based runtime resolution for the inner
    * DynamicPredict programs. */
  private object SignatureProgramRuntime extends dspy4s.programs.runtime.SettingsProgramRuntime

object ProgramOfThought:
  /** Matches a fenced code block, optionally tagged ```python. Captures the
    * snippet body in group 1. Multiline-aware. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r
