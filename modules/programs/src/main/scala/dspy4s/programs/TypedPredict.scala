package dspy4s.programs

import dspy4s.core.contracts.{DspyError, Example, RuntimeContext}
import dspy4s.programs.contracts.{ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{TypedPrediction, TypedSignature}

/** Typed counterpart to `Predict`. Wraps a `TypedSignature[I, O]` and
  * delegates execution to the underlying `Predict(signature.untyped, ...)`,
  * so all adapter/model/callback/cache/trace behavior is unchanged. The
  * typed layer adds two boundaries:
  *
  *   1. Inputs are encoded through `signature.inputShape` before reaching
  *      `ProgramCall`.
  *   2. Outputs are decoded through `signature.outputShape` into a typed
  *      `TypedPrediction[O]`; decode failures surface as `Left(DspyError)`
  *      at this `run` boundary, never via lazy field access.
  *
  * The raw `Prediction` (including completions and LM usage) is preserved
  * on `TypedPrediction.raw` for callers that need it.
  */
final case class TypedPredict[I, O](
    signature: TypedSignature[I, O],
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
):

  /** Encode `input`, dispatch through the existing `Predict` runtime, then
    * decode the resulting prediction into `TypedPrediction[O]`.
    *
    * `config` is forwarded into `ProgramCall.config`, which `Predict`
    * surfaces as `LmRequest.options` (per-call LM options, cache /
    * rollout controls, anything the underlying provider understands).
    * `traceEnabled` controls whether the inner `Predict` writes a trace
    * entry for this call.
    *
    * **Known limitation (Phase 4):** when the inner `Predict` succeeds
    * but the typed decode fails, callbacks / trace / history still
    * record the inner predict as a successful module call. The
    * `Left(DspyError)` returned here does not retroactively un-record
    * those events. Wrapping the typed boundary in its own
    * callback/trace scope is a design decision deferred to Phase 5+. */
  def run(
      input: I,
      config: Map[String, Any] = Map.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, TypedPrediction[O]] =
    val inputMap = signature.inputShape.encode(input)
    val program  = Predict(signature.untyped, demos, name, runtime)
    program
      .run(ProgramCall(inputs = inputMap, config = config, traceEnabled = traceEnabled))
      .flatMap(raw => TypedPrediction.from(raw, signature.outputShape))
