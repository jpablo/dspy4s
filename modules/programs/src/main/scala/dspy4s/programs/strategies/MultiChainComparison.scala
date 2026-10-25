package dspy4s.programs.strategies

import dspy4s.programs.AttemptCount
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.{OptimizableParameters, withOptimizableParameters}
import dspy4s.signatures.OutputAugmentation.PrependField
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.{OutputAugmentation, Signature}
import zio.blocks.schema.DynamicValue

/** The semantic input to [[MultiChainComparison]]: the base input `I` plus the candidate completions to compare.
  * Invocation controls live in the enclosing [[ProgramCall]], just as they do for every other [[Module]] boundary.
  * Keeping `attempts` here reflects that changing them changes the comparison result; they are input data rather than
  * execution metadata.
  */
final case class MultiChainInput[I](
    baseInput: I,
    attempts : Vector[RawPrediction]
)

/** Compares multiple candidate reasoning chains for the same task and asks an LM to produce a corrected reasoning +
  * final answer. Port of Python DSPy's `dspy.MultiChainComparison`. The flow:
  *
  *   1. Take the user's `baseSignature` (e.g. `question -> answer`). 2. Append `m` `reasoning_attempt_i` input fields
  *      to its layout. 3. Prepend a `rationale` output field for the corrected reasoning. 4. Render each candidate
  *      completion as `«I'm trying to <rationale>. I'm not sure but my prediction is <answer>»` and feed them as the
  *      new attempt inputs. 5. Run the augmented predict, then decode the reply into `Prediction[WithRationale[O]]` —
  *      the base output with a `rationale: String` prepended (always a named tuple; see [[OutputAugmentation]]).
  *
  * `MultiChainComparison[I, O]` is a `Module[MultiChainInput[I], WithRationale[O]]`. Callers normally use the
  * [[compare]] convenience, which builds the semantic input and its uniform [[ProgramCall]] envelope.
  *
  * @param baseSignature
  *   the original task signature
  * @param m
  *   positive number of expected attempts (validated against `attempts.length`)
  * @param temperature
  *   temperature for the comparison call (Python's default 0.7)
  */
final case class MultiChainComparison[I, O](
    baseSignature       : Signature[I, O],
    m                   : AttemptCount   = AttemptCount(3),
    temperature         : Double         = 0.7,
    rationalePrefix     : String         = "Accurate Reasoning: Thank you everyone. Let's now holistically",
    rationaleDescription: String         = "${corrected reasoning}",
    attemptDescription  : String         = "${reasoning attempt}",
    answerFieldOverride : Option[String] = None,
    /** Optional optimizable parameters for the comparison predictor. The executable predictor itself is built
      * internally over this instance's path-branded, arity-validated attempt carrier, so callers cannot replace it with
      * a shape that accepts arbitrary vectors. Optimizer replacement writes only this lawful parameter surface.
      */
    comparePredictParametersOverride: Option[OptimizableParameters] = None
)(using
    // A `val`, so that a `copy` keeps this evidence (see `CompositeOptimizableStructureInstances`).
    private[programs] val prepend: PrependField.Of["rationale", String, O]
) extends Module[MultiChainInput[I], MultiChainComparison.WithRationale[O]]:

  /** The output type — `rationale: String` prepended to `O`'s named-tuple view (always a named tuple). */
  type Out = MultiChainComparison.WithRationale[O]

  override val moduleName: String = "multi_chain_comparison"

  /** The output field used to render the "prediction" part of each attempt line. Defaults to the last output field in
    * `baseSignature`.
    */
  private val lastOutputName: Option[String] =
    answerFieldOverride.orElse(baseSignature.layout.outputFields.lastOption.map(_.name))

  /** The `m` attempt inputs, one for each expected candidate, with the descriptions of this instance and the prefixes
    * `Student Attempt #i:`. [[Signature.appendInputs]] adds them to the layout and to the input shape in one call.
    */
  private val attemptFields: Vector[FieldSpec] = (1 to m).toVector.map { idx =>
    FieldSpec(
      name = s"reasoning_attempt_$idx",
      description = Some(attemptDescription),
      prefix = Some(s"Student Attempt #$idx:")
    )
  }

  private val rationaleField: FieldSpec = FieldSpec(
    name = MultiChainComparison.rationaleName,
    description = Some(rationaleDescription),
    prefix = Some(rationalePrefix)
  )

  /** The base signature with the `m` attempt inputs after the base inputs, and the type of their values. */
  private[programs] val attemptInputs: Signature.AppendedStringInputs[I, O] = baseSignature.appendInputs(attemptFields)

  private[programs] type Attempts = attemptInputs.Values

  /** The comparison signature: the base inputs and the `m` attempts, and the outputs `rationale` and the base outputs
    * (the field order of upstream). The output keeps a base `rationale` field, and does not add a second one.
    */
  private val compareSignature: Signature[(I, Attempts), Out] =
    attemptInputs.signature.prependOutput(MultiChainComparison.rationaleName, rationaleField)

  /** The layout of the comparison signature. */
  val augmentedSignatureLayout: SignatureLayout = compareSignature.layout

  /** The comparison predict, built one time from the comparison signature over the opaque [[Attempts]] carrier of this
    * instance. Only [[attemptInputs.validate]] makes that carrier, thus each input encodes exactly `m` numbered fields.
    * The optimizers inspect and replace it, thus it is package-private. A public call goes through [[compare]].
    */
  private[programs] val comparePredict: Predict[(I, Attempts), MultiChainComparison.WithRationale[O]] =
    val base = Predict(signature = compareSignature)
    comparePredictParametersOverride.fold(base)(base.withOptimizableParameters)

  override protected val lifecycle: ModuleLifecycle[MultiChainInput[I], Out] = ModuleLifecycle.observed(
    // Preserve the existing observation surface: attempts affect execution but are not copied into trace inputs.
    call => baseSignature.inputShape.encode(call.input.baseInput),
    _.traceEnabled,
    _.raw.values
  )

  override protected def forward(
      call: ProgramCall[MultiChainInput[I]]
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    val input = call.input
    attemptInputs
      .validate(input.attempts.map(formatAttempt))
      .left.map(_ =>
        ValidationError(
          s"Number of attempts (${input.attempts.size}) doesn't match the configured m ($m). Pass exactly $m candidates."
        )
      )
      .flatMap { attempts =>
        comparePredict(
          call
            .mapInput(_ => (input.baseInput, attempts))
            .copy(config = call.config.updated("temperature", DynamicValues.fromAny(temperature)))
        )
      }
      .map(result => Prediction(result.output, result.raw))

  /** Convenience entry: supply the base input and the candidate completions directly. Mirrors Python's
    * `compare_answers(completions, question=...)`.
    */
  def compare(
      input       : I,
      attempts    : Vector[RawPrediction],
      config      : DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean             = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(ProgramCall(MultiChainInput(input, attempts), config, traceEnabled))

  /** Renders a single attempt verbatim as Python does (no period after the rationale, matching
    * `multi_chain_comparison.py`): `«I'm trying to {rationale} I'm not sure but my prediction is {answer}»`.
    */
  private def formatAttempt(attempt: RawPrediction): String =
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

object MultiChainComparison:
  private[programs] val rationaleName: "rationale" = "rationale"

  /** The output type: `rationale: String` prepended to `O`'s named-tuple view, idempotently. */
  type WithRationale[O] = OutputAugmentation.WithField[O, "rationale", String]
