package dspy4s.programs.strategies

import dspy4s.programs.AttemptCount
import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.{
  OptimizableParameters,
  OptimizableStructure,
  optimizableParameters,
  optimizableView,
  withOptimizableParameters
}
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
  *   the default temperature of the comparison call (Python's default 0.7). The comparison predict holds it as module
  *   config, thus a per-call value overrides it (upstream `Predict(signature, temperature=temperature, **config)`).
  */
final case class MultiChainComparison[I, O](
    baseSignature: Signature[I, O],
    m            : AttemptCount = AttemptCount(3),
    temperature  : Double       = 0.7,
    /** Optional optimizable parameters for the comparison predictor. The executable predictor itself is built
      * internally over this instance's path-branded, arity-validated attempt carrier, so callers cannot replace it with
      * a shape that accepts arbitrary vectors. Optimizer replacement writes only this lawful parameter surface.
      */
    comparePredictParametersOverride: Option[OptimizableParameters] = None
)(using
    // A `val`, so that a `copy` keeps this evidence (see `multiChainComparisonOptimizableStructure`).
    private[programs] val prepend: PrependField.Of["rationale", String, O]
) extends Module[MultiChainInput[I], MultiChainComparison.WithRationale[O]]:

  /** The output type — `rationale: String` prepended to `O`'s named-tuple view (always a named tuple). */
  type Out = MultiChainComparison.WithRationale[O]

  override val moduleName: String = "multi_chain_comparison"

  /** The output field used to render the "prediction" part of each attempt line: the last output field in
    * `baseSignature`.
    */
  private val lastOutputName: Option[String] = baseSignature.layout.outputFields.lastOption.map(_.name)

  /** The `m` attempt inputs, one for each expected candidate, with the prefixes `Student Attempt #i:`.
    * [[Signature.appendInputs]] adds them to the layout and to the input shape in one call.
    */
  private val attemptFields: Vector[FieldSpec] = (1 to m).toVector.map { idx =>
    FieldSpec(
      name = s"reasoning_attempt_$idx",
      description = Some(MultiChainComparison.AttemptDescription),
      prefix = Some(s"Student Attempt #$idx:")
    )
  }

  private val rationaleField: FieldSpec = FieldSpec(
    name = MultiChainComparison.rationaleName,
    description = Some(MultiChainComparison.RationaleDescription),
    prefix = Some(MultiChainComparison.RationalePrefix)
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
    val base = Predict(signature = compareSignature, config = DynamicValues.record("temperature" := temperature))
    comparePredictParametersOverride.fold(base)(base.withOptimizableParameters)

  override protected val lifecycle: ModuleLifecycle[MultiChainInput[I]] = ModuleLifecycle.typed(
    // Preserve the existing observation surface: attempts affect execution but are not copied into trace inputs.
    call => baseSignature.inputShape.encode(call.input.baseInput)
  )

  override protected def forward(
      call: ProgramCall[MultiChainInput[I]]
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    val input = call.input
    if input.attempts.sizeIs != attemptFields.size then
      Left(ValidationError(
        s"Number of attempts (${input.attempts.size}) doesn't match the configured m ($m). Pass exactly $m candidates."
      ))
    else
      for
        lines    <- Traverse.either(input.attempts)(formatAttempt)
        attempts <- attemptInputs.validate(lines)
        result   <- comparePredict(call.mapInput(_ => (input.baseInput, attempts)))
      yield result

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
    *
    * The rationale is `c.get("rationale", c.get("reasoning"))`: a present `rationale` wins, also when it is empty. The
    * answer is the value of the last output field of the base signature. A `Left` comes before the model call when an
    * attempt has no answer, or no rationale and no reasoning (upstream raises `KeyError` and `AttributeError`). A null
    * rationale is a `Left` too, because upstream calls `.strip()` on it. A null answer is the text `None`, as `str`.
    */
  private def formatAttempt(attempt: RawPrediction): Either[DspyError, String] =
    val row = attempt.values
    for
      rationale <- DynamicValues.recordGet(row, MultiChainComparison.rationaleName)
                     .orElse(DynamicValues.recordGet(row, "reasoning"))
                     .filterNot(DynamicValues.isNull)
                     .toRight(ValidationError("An attempt has no rationale and no reasoning value."))
      answer <- lastOutputName.flatMap(name => DynamicValues.recordGet(row, name))
                  .toRight(ValidationError(s"An attempt has no value for the output ${lastOutputName.getOrElse("")}."))
    yield s"«I'm trying to ${firstLine(rationale)} I'm not sure but my prediction is ${firstLine(answer)}»"

  /** Python `str(value).strip().split("\n")[0].strip()`. Only `\n` ends the line. */
  private def firstLine(value: DynamicValue): String =
    PythonText.strip(PythonText.strip(PythonText.str(value)).takeWhile(_ != '\n'))

object MultiChainComparison:
  private[programs] val rationaleName: "rationale" = "rationale"

  /** One optimizable part, the predictor `compare`. A `copy` gets the `PrependField` evidence of the program. This
    * instance does not know `O`, thus a new search for the evidence finds only the fallback, which makes no augmented
    * output.
    */
  given multiChainComparisonOptimizableStructure[I, O]: OptimizableStructure.Of[MultiChainComparison[I, O], 1] =
    OptimizableStructure.leaf(
      "MultiChainComparison",
      "compare",
      _.comparePredict.optimizableView,
      (program, updated) =>
        if updated == program.comparePredict.optimizableParameters then program
        else program.copy(comparePredictParametersOverride = Some(updated))(using program.prepend)
    )

  // The texts of upstream `dspy/predict/multi_chain_comparison.py`.
  private val RationalePrefix: String      = "Accurate Reasoning: Thank you everyone. Let's now holistically"
  private val RationaleDescription: String = "${corrected reasoning}"
  private val AttemptDescription: String   = "${reasoning attempt}"

  /** The output type: `rationale: String` prepended to `O`'s named-tuple view, idempotently. */
  type WithRationale[O] = OutputAugmentation.WithField[O, "rationale", String]
