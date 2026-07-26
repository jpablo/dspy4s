package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AgentLoop
import dspy4s.typed.OutputAugmentation.PrependField
import dspy4s.typed.{InputAugmentation, OutputAugmentation, Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Generate Python code that programmatically computes the answer, run it, and feed the output back to the LM for a
  * structured response. Port of Python DSPy's `dspy.ProgramOfThought`.
  *
  * Three reasoning-augmented [[DynamicPredict]] passes:
  *
  *   1. **generate** — inputs → `generated_code` (Python source) 2. **regenerate** — on execution error, the LM gets
  *      `previous_code` + `error` and emits a fix. Loops up to `maxIterations`. 3. **answer** — inputs +
  *      `final_generated_code` + `code_output` → original outputs declared in `baseSignature`.
  *
  * `ProgramOfThought[I, O]` is a `Module[I, Prediction[WithReasoning[O]]]`: it encodes the typed input,
  * runs the three passes internally over the data-bag layer, and decodes the final answer step into the base outputs
  * `O` with `reasoning: String` prepended (see [[OutputAugmentation]]).
  *
  * '''SUBMIT vs print.''' This port instructs the LM to **print** its result (typically JSON) and the answer step
  * parses the printed output — the convention every [[CodeInterpreter]] supports. When the interpreter is
  * SUBMIT-capable ([[dspy4s.core.runtime.DenoPyodideInterpreter]], which preloads `SUBMIT(...)` like upstream's Pyodide
  * setup), a structured early-exit in [[dspy4s.core.contracts.CodeResult.finalOutput]] is preferred over stdout,
  * restoring full Python parity.
  *
  * Lifecycle: caller owns the interpreter — this does **not** call `interpreter.close()`.
  */
final case class ProgramOfThought[I, O](
    baseSignature: Signature[I, O],
    interpreter: CodeInterpreter,
    maxIterations: IterationLimit = IterationLimit(3),
    /** Optional override for the initial code-generation predict — a TYPED `Predict` over the base input, producing
      * an explicit [[ProgramOfThought.CodeOut]]. When `None` (the default), it is built from [[generateSignature]].
      * Carrying it as a defaulted, `copy`-reachable field makes this learnable sub-predict addressable + immutably
      * replaceable (see the `Predictors[ProgramOfThought]` instance).
      */
    generatorPredictOverride: Option[Predict[I, ProgramOfThought.CodeOut]] = None,
    /** Optional override for the code-regeneration predict used after a failed attempt (typed over the base input
      * plus `previous_code` and `error`).
      */
    regeneratorPredictOverride: Option[Predict[((I, String), String), ProgramOfThought.CodeOut]] = None,
    /** Optional override for the final answer-extraction predict (CoT-augmented, typed over the base input plus
      * `final_generated_code` and `code_output`).
      */
    answererPredictOverride: Option[Predict[((I, String), String), ProgramOfThought.WithReasoning[O]]] = None
)(using
    prepend: PrependField.Of["reasoning", String, O]
) extends Module[I, Prediction[ProgramOfThought.WithReasoning[O]]]:

  /** The output type — `reasoning: String` prepended to the base outputs `O` (always a named tuple). */
  type Out = ProgramOfThought.WithReasoning[O]

  override val moduleName: String = "program_of_thought"
  private val baseLayout: SignatureLayout = baseSignature.layout

  import ProgramOfThought.{codeOutputField, errorField, finalGeneratedCodeField, generatedCodeField, previousCodeField}

  private def buildSig(extraInputs: Vector[FieldSpec], extraOutputs: Vector[FieldSpec]): SignatureLayout =
    val withInputs = extraInputs.foldLeft(baseLayout)(_.append(_))
    // The generate / regenerate signatures discard the user's outputs —
    // generated_code is the only output of those steps.
    val withoutUserOutputs =
      withInputs.withFields(withInputs.fields.filterNot(_.role == FieldRole.Output))
    extraOutputs.foldLeft(withoutUserOutputs)(_.append(_))

  /** SignatureLayout for the initial code-generation step. Inputs from the user's signature; outputs a `generated_code`
    * string.
    */
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

  /** SignatureLayout for the retry step when execution failed. Adds `previous_code` and `error` as inputs.
    */
  val regenerateSignature: SignatureLayout =
    buildSig(
      extraInputs = Vector(previousCodeField, errorField),
      extraOutputs = Vector(generatedCodeField)
    ).withInstructions(Some(
      """You are given the previous Python code and the error message it produced.
        |Your task is to fix the error and emit corrected `generated_code`.
        |The corrected code must still print its result as a JSON object.""".stripMargin
    ))

  /** SignatureLayout for the final answer-extraction step. Has the user's original outputs plus `final_generated_code`
    * + `code_output` as inputs.
    */
  val answerSignature: SignatureLayout =
    baseLayout
      .append(finalGeneratedCodeField)
      .append(codeOutputField)
      .withInstructions(Some({
        val outputs = baseLayout.outputFields.map(f => s"`${f.name}`").mkString(", ")
        s"Given the final Python code and its printed output, produce the final $outputs."
      }))

  /** The initial code-generation predict, built once from the CoT-augmented [[generateSignature]] and exposed as
    * stable optimizer state — a TYPED `Predict[I, CodeOut]` (the base input shape unchanged, with missing code modeled
    * explicitly by [[ProgramOfThought.codeOutShape]]). Addressable + tunable via [[generatorPredictOverride]]; [[forward]]
    * executes this member rather than rebuilding a local predictor for each call.
    */
  val generatorPredict: Predict[I, ProgramOfThought.CodeOut] =
    generatorPredictOverride.getOrElse(Predict(
      signature = Signature(
        name        = baseSignature.name,
        layout      = ProgramOfThought.augmented(generateSignature),
        inputShape  = baseSignature.inputShape,
        outputShape = ProgramOfThought.codeOutShape
      ),
      name    = Some(ProgramOfThought.generatorModuleName),
      runtime = ProgramOfThought.SignatureProgramRuntime
    ))

  /** The retry code-regeneration predict, built once from the CoT-augmented [[regenerateSignature]] — typed over the
    * base input plus `previous_code` and `error` (two input appends).
    */
  val regeneratorPredict: Predict[((I, String), String), ProgramOfThought.CodeOut] =
    regeneratorPredictOverride.getOrElse(Predict(
      signature = Signature(
        name   = baseSignature.name,
        layout = ProgramOfThought.augmented(regenerateSignature),
        inputShape = InputAugmentation.appendedStringInput(
          InputAugmentation.appendedStringInput(baseSignature.inputShape, previousCodeField, "ProgramOfThought"),
          errorField,
          "ProgramOfThought"
        ),
        outputShape = ProgramOfThought.codeOutShape
      ),
      name    = Some(ProgramOfThought.regeneratorModuleName),
      runtime = ProgramOfThought.SignatureProgramRuntime
    ))

  /** The final answer-extraction predict, built once from the CoT-augmented [[answerSignature]] — typed over the base
    * input plus `final_generated_code` and `code_output`, with the reasoning-prepended decode inside the predict (the
    * `prepend` evidence this class already carries).
    */
  val answererPredict: Predict[((I, String), String), ProgramOfThought.WithReasoning[O]] =
    answererPredictOverride.getOrElse(Predict(
      signature = Signature(
        name   = baseSignature.name,
        layout = ProgramOfThought.augmented(answerSignature),
        inputShape = InputAugmentation.appendedStringInput(
          InputAugmentation.appendedStringInput(baseSignature.inputShape, finalGeneratedCodeField, "ProgramOfThought"),
          codeOutputField,
          "ProgramOfThought"
        ),
        outputShape = OutputAugmentation.prependedStringShape(
          baseSignature.outputShape,
          ChainOfThought.reasoningField,
          "reasoning",
          "ProgramOfThought",
          baseSignature.name
        )
      ),
      name    = Some(ProgramOfThought.answererModuleName),
      runtime = ProgramOfThought.SignatureProgramRuntime
    ))

  override protected val lifecycle: ModuleLifecycle[I, Prediction[Out]] =
    ModuleLifecycle.typed(baseSignature.inputShape)

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    for
      codeAndOutput <- runCode(call)
      (code, codeOutput) = codeAndOutput
      result <- answererPredict.apply(
        ProgramCall(((call.input, code), codeOutput), call.config, call.traceEnabled, call.rolloutId)
                )
    yield Prediction(output = result.output, raw = result.raw)

  /** Convenience entry mirroring the typed caller signature; builds a [[ProgramCall]] and dispatches through the
    * wrapped [[apply]].
    */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(ProgramCall(input, config, traceEnabled))

  /** The regenerate-until-execution-succeeds loop (the `retryUntil` shape of Algebra 2, on the shared [[AgentLoop]]
    * skeleton): the first attempt runs `generator`; each failure (parse or non-zero exit) carries `(previous_code,
    * error)` into `regenerator` for the next attempt; the first success wins; exhausting the budget surfaces the last
    * failure. An interpreter-level failure aborts immediately (no LM fix).
    */
  private def runCode(
      call: ProgramCall[I]
  )(using RuntimeContext): Either[DspyError, (String, String)] =
    AgentLoop.run[Option[ProgramOfThought.Attempt], (String, String)](None, 0, maxIterations)(
      onExhausted = {
        case Some(attempt) => Left(RuntimeError("program_of_thought", attempt.exhaustionMessage))
        case None          => Left(RuntimeError("program_of_thought", s"Max attempts ($maxIterations) reached."))
      }
    )(potStep(call))

  /** One code-generation attempt as an [[AgentLoop]] step: pick generator (first attempt) or regenerator (carrying the
    * prior failure), generate + parse + execute, and either finish with `(code, output)` or carry the failure to the
    * next attempt.
    */
  private def potStep(
      call: ProgramCall[I]
  )(using
      RuntimeContext
  ): (
      Option[ProgramOfThought.Attempt],
      Int
  ) => Either[DspyError, AgentLoop.Step[Option[ProgramOfThought.Attempt], (String, String)]] =
    (previous, _) =>
      // Generator on the first attempt, regenerator (carrying the prior failure) thereafter. The two predicts
      // have different typed inputs, so the dispatch happens at the call rather than on a shared predict value.
      val generated = previous match
        case None =>
          generatorPredict.apply(ProgramCall(call.input, call.config, call.traceEnabled, call.rolloutId))
        case Some(attempt) =>
          regeneratorPredict.apply(ProgramCall(
            ((call.input, attempt.code), attempt.error),
            call.config,
            call.traceEnabled,
            call.rolloutId
          ))

      generated.flatMap { prediction =>
        prediction.output.generatedCode match
          case None =>
            val missing = "The model response did not contain the required generated_code field."
            Right(AgentLoop.Step.Continue(Some(ProgramOfThought.Attempt(
              code = "",
              error = missing,
              exhaustionMessage = s"Max attempts ($maxIterations) reached. Last generation error: $missing"
            ))))
          case Some(rawCode) =>
            // Shared with CodeAct — upstream's `_parse_code` is one function serving both programs, so both get
            // the same LM-output tolerance (fence stripping, `---` truncation, trailing-assignment echo).
            CodeAct.parseCode(rawCode) match
              case Left(parseErr) =>
                Right(AgentLoop.Step.Continue(Some(ProgramOfThought.Attempt(
                  rawCode,
                  parseErr,
                  s"Max attempts ($maxIterations) reached. Last parse error: $parseErr"
                ))))
              case Right(code) =>
                interpreter.execute(code) match
                  case Right(result) if result.exitCode == 0 =>
                    // A SUBMIT-capable interpreter (DenoPyodideInterpreter) surfaces a structured early-exit in
                    // `finalOutput`; prefer it over printed stdout (Python-parity: upstream PoT reads SUBMIT).
                    // Print-based interpreters leave it None, so the print convention keeps working unchanged.
                    Right(AgentLoop.Step.Done(code -> result.finalOutput.getOrElse(result.stdout.stripTrailing)))
                  case Right(result) =>
                    val stderr = result.stderr.stripTrailing
                    Right(AgentLoop.Step.Continue(Some(ProgramOfThought.Attempt(
                      code,
                      stderr,
                      s"Max attempts ($maxIterations) reached. Last execution error: $stderr"
                    ))))
                  case Left(interpreterErr) =>
                    // Interpreter itself failed (process couldn't start, timed out, …). Don't retry — no LM fix.
                    Left(interpreterErr)
      }

object ProgramOfThought:
  /** The output type: base outputs `O` with `reasoning: String` prepended (idempotent; always a named tuple). */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]

  /** A failed code attempt carried into the next regenerate step: the `code` that failed and its `error` (fed to the
    * regenerator as `previous_code` / `error`), plus the pre-built message used if the budget is exhausted.
    */
  private[programs] final case class Attempt(code: String, error: String, exhaustionMessage: String)

  private[programs] val generatorModuleName   = "program_of_thought_generate"
  private[programs] val regeneratorModuleName = "program_of_thought_regenerate"
  private[programs] val answererModuleName    = "program_of_thought_answer"

  /** Use one stateless settings-based runtime for default inner predictors. Keeping it in the companion means a
    * state-only `copy` rebuild retains the same execution resource.
    */
  private[programs] object SignatureProgramRuntime extends dspy4s.programs.runtime.SettingsProgramRuntime

  /** CoT-augment a step layout (prepend `reasoning`), failing fast at construction like the prior defaultPredict. */
  private[programs] def augmented(layout: SignatureLayout): SignatureLayout =
    ChainOfThought
      .augmentLayout(layout)
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  // ── The step signatures' hand-declared fields (static; hoisted so the typed shapes and the layouts share them).
  // dspy 3.2.1 alignment (item P3): the hardcoded `prefix =` markers were dropped. `FieldSpec.normalize` derives
  // the marker from the field NAME (title-case via inferPrefix): generated_code -> "Generated Code:",
  // previous_code -> "Previous Code:", error -> "Error:", final_generated_code -> "Final Generated Code:",
  // code_output -> "Code Output:". The old code reused "Code:" on two distinct fields; derivation disambiguates. ──
  private[programs] val generatedCodeField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name = "generated_code",
    role = FieldRole.Output,
    typeRef = TypeRef.string,
    description = Some("Python code that, when executed, computes the answer and prints it as JSON.")
  ))
  private[programs] val previousCodeField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name = "previous_code",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The Python code from the previous attempt that errored.")
  ))
  private[programs] val errorField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name = "error",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("Error message produced by the previous Python code.")
  ))
  private[programs] val finalGeneratedCodeField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name = "final_generated_code",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The final Python code that produced the answer.")
  ))
  private[programs] val codeOutputField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name = "code_output",
    role = FieldRole.Input,
    typeRef = TypeRef.string,
    description = Some("The printed output of the final Python code.")
  ))

  /** The typed output consumed by the generate / regenerate loop. The augmented layout also asks the LM for
    * `reasoning`, but that field is execution evidence rather than part of this semantic output. Missing code is an
    * explicit `None`, never a manufactured empty string.
    */
  final case class CodeOut(generatedCode: Option[String])

  /** Hand-written output shape for the one semantic field the loop consumes. Missing code decodes to `None` so the
    * retry state can represent the failure honestly; a present non-string value is rejected in the ordinary error
    * channel. `jsonSchemaString` stays `None` for parity with the prior direct `DynamicPredict` construction.
    */
  private[programs] val codeOutShape: Shape[CodeOut] = new Shape[CodeOut]:
    val fieldSpecs: Vector[FieldSpec] = Vector(generatedCodeField)

    def encode(value: CodeOut): DynamicValue.Record =
      value.generatedCode match
        case Some(code) => DynamicValue.Record(Chunk(
          "generated_code" -> DynamicValue.Primitive(PrimitiveValue.String(code))
        ))
        case None => DynamicValue.Record.empty

    def decode(raw: DynamicValue.Record): Either[DspyError, CodeOut] =
      DynamicValues.recordGet(raw, "generated_code") match
        case None => Right(CodeOut(None))
        case Some(DynamicValue.Primitive(PrimitiveValue.String(code))) => Right(CodeOut(Some(code)))
        case Some(other) => Left(ValidationError(
          s"ProgramOfThought generated_code must be a String, got: $other"
        ))
