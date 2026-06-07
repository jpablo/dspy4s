package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.{OutputAugmentation, Prediction, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The call argument for [[MultiChainComparison]]: the base typed input `I` **plus** the candidate completions to
  * compare. Unlike a plain `TypedCall`, MCC's real input includes the `attempts`, mirroring Python's
  * `forward(completions, **kwargs)`. Carrying them in the call object means the real work flows through the
  * wrapped `Module.apply` (callbacks / trace / history) rather than a side method that bypasses it. */
final case class MultiChainCall[I](
    input: I,
    attempts: Vector[DynamicPrediction],
    config: DynamicValue.Record = DynamicValue.Record.empty,
    traceEnabled: Boolean = true,
    rolloutId: Option[Int] = None
)

/** Compares multiple candidate reasoning chains for the same task and asks an LM to produce a corrected
  * reasoning + final answer. Typed port of Python DSPy's `dspy.MultiChainComparison`. The flow:
  *
  *   1. Take the user's `baseSignature` (e.g. `question -> answer`).
  *   2. Append `m` `reasoning_attempt_i` input fields to its layout.
  *   3. Prepend a `rationale` output field for the corrected reasoning.
  *   4. Render each candidate completion as
  *      `«I'm trying to <rationale>. I'm not sure but my prediction is <answer>»` and feed them as the new
  *      attempt inputs.
  *   5. Run the augmented predict, then decode the reply into `Prediction[WithRationale[O]]` — the base output
  *      with a typed `rationale: String` prepended (always a named tuple; see [[OutputAugmentation]]).
  *
  * `MultiChainComparison[I, O]` is a `Module[MultiChainCall[I], Prediction[WithRationale[O]]]`. Callers normally
  * use the [[compare]] convenience, which builds the call.
  *
  * @param baseSignature the original task signature
  * @param m number of expected attempts (validated against `attempts.length`)
  * @param temperature temperature for the comparison call (Python's default 0.7)
  */
final case class MultiChainComparison[I, O](
    baseSignature: Signature[I, O],
    m: Int = 3,
    temperature: Double = 0.7,
    rationalePrefix: String = "Accurate Reasoning: Thank you everyone. Let's now holistically",
    rationaleDescription: String = "${corrected reasoning}",
    attemptDescription: String = "${reasoning attempt}",
    answerFieldOverride: Option[String] = None,
    /** Optional override for the comparison predict. When `None` (the default), it is built from
      * [[augmentedSignatureLayout]]. Carrying it as a defaulted, `copy`-reachable field makes the learnable
      * sub-predict addressable + immutably replaceable (see the `Predictors[MultiChainComparison]` instance). */
    comparePredictOverride: Option[DynamicPredict] = None
)(using
    prepend: OutputAugmentation.PrependField.Aux["rationale", String, O, MultiChainComparison.WithRationale[O]]
) extends Module[MultiChainCall[I], Prediction[MultiChainComparison.WithRationale[O]]]:

  /** The output type — `rationale: String` prepended to `O`'s named-tuple view (always a named tuple). */
  type Out = MultiChainComparison.WithRationale[O]

  override val moduleName: String = "multi_chain_comparison"

  /** The output field used to render the "prediction" part of each attempt line. Defaults to the last output
    * field in `baseSignature`. */
  private val lastOutputName: Option[String] =
    answerFieldOverride.orElse(baseSignature.layout.outputFields.lastOption.map(_.name))

  /** The augmented layout: `baseSignature` plus `m` attempt-input fields appended, plus a `rationale` output
    * field prepended (idempotent; matches Python field ordering). */
  val augmentedSignatureLayout: SignatureLayout =
    val withAttempts = (1 to m).foldLeft(baseSignature.layout) { (sig, idx) =>
      sig.append(FieldSpec(
        name        = s"reasoning_attempt_$idx",
        role        = FieldRole.Input,
        description = Some(attemptDescription),
        prefix      = Some(s"Student Attempt #$idx:")
      ))
    }
    if withAttempts.outputFields.exists(_.name == MultiChainComparison.rationaleName) then withAttempts
    else
      withAttempts.prepend(FieldSpec(
        name        = MultiChainComparison.rationaleName,
        role        = FieldRole.Output,
        description = Some(rationaleDescription),
        prefix      = Some(rationalePrefix)
      ))

  /** The comparison predict, built once from [[augmentedSignatureLayout]] (mirrors Python's `self.compare =
    * Predict(...)` in `__init__`). Addressable + tunable via [[comparePredictOverride]]; `forward` uses this
    * member rather than rebuilding a local each call. Left unnamed to preserve the prior on-the-wire behaviour. */
  val comparePredict: DynamicPredict =
    comparePredictOverride.getOrElse(DynamicPredict(layout = augmentedSignatureLayout))

  override protected def callInputs(call: MultiChainCall[I]): DynamicValue.Record =
    baseSignature.inputShape.encode(call.input)
  override protected def callTraceEnabled(call: MultiChainCall[I]): Boolean = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[Out]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: MultiChainCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    if call.attempts.size != m then
      Left(ValidationError(
        s"Number of attempts (${call.attempts.size}) doesn't match the configured m ($m). Pass exactly $m candidates."
      ))
    else
      val baseInputs = baseSignature.inputShape.encode(call.input)
      val appended = call.attempts.iterator.zipWithIndex.map { (attempt, idx) =>
        s"reasoning_attempt_${idx + 1}" ->
          (DynamicValue.Primitive(PrimitiveValue.String(formatAttempt(attempt))): DynamicValue)
      }.toSeq
      val augmentedInputs = DynamicValue.Record(Chunk.from(baseInputs.fields.iterator.toSeq ++ appended))
      for
        raw <- comparePredict.apply(ProgramCall(
                 inputs       = augmentedInputs,
                 config       = call.config.updated("temperature", DynamicValues.fromAny(temperature)),
                 traceEnabled = call.traceEnabled,
                 rolloutId    = call.rolloutId
               ))
        rationale <- extractRationale(raw.values)
        baseOut   <- baseSignature.outputShape.decode(raw.values)
        augmented <- prepend.prepend(rationale, baseOut).toRight(unsupportedOutputShape(baseOut))
      yield Prediction(augmented, raw)

  /** Convenience entry: supply the base input and the candidate completions directly. Mirrors Python's
    * `compare_answers(completions, question=...)`. */
  def compare(
      input: I,
      attempts: Vector[DynamicPrediction],
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(MultiChainCall(input, attempts, config, traceEnabled))

  /** Renders a single attempt as Python does:
    * `«I'm trying to {rationale}. I'm not sure but my prediction is {answer}»`. */
  private def formatAttempt(attempt: DynamicPrediction): String =
    val row       = attempt.values
    val rationale = firstNonEmpty(row, Seq("rationale", "reasoning"))
    val answer    = lastOutputName.flatMap(name => DynamicValues.recordGet(row, name))
      .map(DynamicValues.renderText).getOrElse("")
    s"«I'm trying to $rationale I'm not sure but my prediction is $answer»"

  private def firstNonEmpty(row: DynamicValue.Record, keys: Seq[String]): String =
    keys.iterator
      .flatMap(k => DynamicValues.recordGet(row, k).map(DynamicValues.renderText))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.linesIterator.next().trim)
      .nextOption()
      .getOrElse("")

  private def extractRationale(values: DynamicValue.Record): Either[DspyError, String] =
    DynamicValues.recordGet(values, MultiChainComparison.rationaleName) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
      case Some(other) =>
        Left(ValidationError(s"MultiChainComparison rationale must be a String, got: $other"))
      case None =>
        Left(NotFoundError(
          resource = "prediction_field",
          message  = "Required field 'rationale' is missing from the comparison prediction"
        ))

  private def unsupportedOutputShape(baseOut: O): DspyError =
    ValidationError(
      s"MultiChainComparison requires a product output (named tuple or case class); the signature " +
      s"'${baseSignature.name}' has a fieldless output (got ${baseOut.getClass.getSimpleName}). " +
      s"Use a typed signature (Signature.of / Signature.derived / Signature.fromType)."
    )

object MultiChainComparison:
  private[programs] val rationaleName: "rationale" = "rationale"

  /** The output type: `rationale: String` prepended to `O`'s named-tuple view, idempotently. */
  type WithRationale[O] = OutputAugmentation.WithField[O, "rationale", String]
