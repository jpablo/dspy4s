package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, Example, NotFoundError, RuntimeContext}
import dspy4s.programs.contracts.{ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{Prediction, Signature}

/** The fundamental typed prediction module: given a `Signature[I, O]`, `run`
  * takes a typed input `I`, formats and dispatches a language-model request
  * through the configured adapter and model, and returns a typed
  * `Prediction[O]`. Other programs (`ChainOfThought`, `ReAct`, ...) build on it.
  *
  * The signature's shapes bracket the call:
  *
  *   1. Inputs are encoded through `signature.inputShape` before the request
  *      is built.
  *   2. The model's reply is decoded through `signature.outputShape` into a
  *      typed `Prediction[O]`; decode failures surface as `Left(DspyError)`
  *      from `run`, never via lazy field access.
  *
  * Adapter selection, caching, retries, callbacks, and tracing are handled by
  * the shared program runtime. The untyped prediction (including the raw
  * completions and LM usage) is preserved on `Prediction.raw` for callers that
  * need it.
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

  /** Encode `input`, dispatch the language-model request, then decode the reply
    * into `Prediction[O]`.
    *
    * `config` is forwarded as `LmRequest.options` -- per-call LM options, cache /
    * rollout controls, and anything else the underlying provider understands.
    * `traceEnabled` controls whether this call writes a trace entry.
    *
    * Note: callbacks, trace, and history record the model call as soon as it
    * succeeds, before the typed decode runs. So if the model responds but the
    * reply can't be decoded into `O`, `run` returns `Left(DspyError)` even
    * though those events still show a successful call. */
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
