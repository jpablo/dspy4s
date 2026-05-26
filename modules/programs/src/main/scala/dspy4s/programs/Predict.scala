package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, Example, NotFoundError, RuntimeContext}
import dspy4s.programs.contracts.{ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{Prediction, Signature}

/** Typed counterpart to `DynamicPredict`. Wraps a `Signature[I, O]` and
  * delegates execution to an internal `DynamicPredict`, so all
  * adapter/model/callback/cache/trace behavior is unchanged. The typed
  * layer adds two boundaries:
  *
  *   1. Inputs are encoded through `signature.inputShape` before reaching
  *      `ProgramCall`.
  *   2. Outputs are decoded through `signature.outputShape` into a typed
  *      `Prediction[O]`; decode failures surface as `Left(DspyError)`
  *      at this `run` boundary, never via lazy field access.
  *
  * The raw `DynamicPrediction` (including completions and LM usage) is preserved
  * on `Prediction.raw` for callers that need it.
  */
final case class Predict[I, O](
    signature: Signature[I, O],
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
):

  // Memoized rather than allocated per `run` -- a `Predict` is typically
  // wired into a program once and called many times, and the inner
  // `DynamicPredict` is a pure function of the case-class fields.
  private lazy val inner: DynamicPredict =
    DynamicPredict(
      layout           = signature.layout,
      demos            = demos,
      name             = name,
      runtime          = runtime,
      outputJsonSchema = signature.outputShape.jsonSchemaString
    )

  /** Encode `input`, dispatch through the existing `DynamicPredict` runtime, then
    * decode the resulting prediction into `Prediction[O]`.
    *
    * `config` is forwarded into `ProgramCall.config`, which `DynamicPredict`
    * surfaces as `LmRequest.options` (per-call LM options, cache /
    * rollout controls, anything the underlying provider understands).
    * `traceEnabled` controls whether the inner `DynamicPredict` writes a trace
    * entry for this call.
    *
    * **Known limitation (Phase 4):** when the inner `DynamicPredict` succeeds
    * but the typed decode fails, callbacks / trace / history still
    * record the inner predict as a successful module call. The
    * `Left(DspyError)` returned here does not retroactively un-record
    * those events. Wrapping the typed boundary in its own
    * callback/trace scope is a design decision deferred to Phase 5+. */
  def run(
      input: I,
      config: Map[String, Any] = Map.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputRecord = signature.inputShape.encode(input)
    // Defensive: shape implementations that don't statically guarantee full coverage of declared input fields
    // (notably the Map-based shape used by trait specs) could let a caller silently omit a required input.
    // Validate before spending an LM call. Case-class derivations always produce a complete record, so the
    // check never fires for them; cost is one Set.diff per call.
    val requiredInputs = signature.layout.inputFields.iterator.map(_.name).toSet
    val presentInputs  = DynamicValues.recordKeys(inputRecord).toSet
    val missing        = requiredInputs.diff(presentInputs)
    if missing.nonEmpty then
      Left(NotFoundError(
        resource = "program_input",
        message  = s"Missing required inputs for '${signature.name}': ${missing.toVector.sorted.mkString(", ")}"
      ))
    else
      inner
        .run(ProgramCall(inputs = inputRecord, config = config, traceEnabled = traceEnabled))
        .flatMap(raw => Prediction.from(raw, signature.outputShape))
