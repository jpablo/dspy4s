package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, Example, NotFoundError, RuntimeContext}
import dspy4s.programs.contracts.{Module, ProgramCall, ProgramRuntime, TypedCall}
import dspy4s.programs.runtime.{PredictEngine, SettingsProgramRuntime}
import dspy4s.typed.{Prediction, Signature}
import zio.blocks.schema.DynamicValue

/** The fundamental typed prediction module: given a `Signature[I, O]`, `apply`
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
  *      from `apply`, never via lazy field access.
  *
  * `Predict[I, O]` is a `Module[TypedCall[I], Prediction[O]]` — the typed analog of `dspy.Predict`. It is a
  * *sibling* of the untyped [[DynamicPredict]] rather than a wrapper around it: both are thin `Module`s over the
  * shared [[dspy4s.programs.runtime.PredictEngine PredictEngine]]. So a typed call emits exactly one module
  * event (named by this `Predict`), and the whole encode → LM call → decode runs inside `Module.apply`'s
  * lifecycle wrapping — adapter selection, caching, retries, callbacks, and trace/history are all bracketed
  * around the typed boundary. The untyped prediction (raw completions and LM usage) is preserved on
  * `Prediction.raw`. */
final case class Predict[I, O](
    signature: Signature[I, O],
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
) extends Module[TypedCall[I], Prediction[O]]:

  override val moduleName: String = name.getOrElse("predict")

  // The same engine `DynamicPredict` builds; `Predict` adds only the typed encode/decode around it.
  private val engine = PredictEngine(
    layout           = signature.layout,
    demos            = demos,
    moduleName       = moduleName,
    runtime          = runtime,
    outputJsonSchema = signature.outputShape.jsonSchemaString
  )

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record =
    signature.inputShape.encode(call.input)

  override protected def callTraceEnabled(call: TypedCall[I]): Boolean = call.traceEnabled

  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record =
    prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputRecord = signature.inputShape.encode(call.input)
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
      engine
        .execute(ProgramCall(
          inputs       = inputRecord,
          config       = call.config,
          traceEnabled = call.traceEnabled,
          rolloutId    = call.rolloutId
        ))
        .flatMap(raw => Prediction.from(raw, signature.outputShape))

  /** Convenience entry mirroring the prior caller signature. Encodes `input` into a [[TypedCall]] and dispatches
    * through the wrapped [[apply]].
    *
    * `config` is forwarded as `LmRequest.options` -- per-call LM options, cache / rollout controls, and anything
    * else the underlying provider understands. `traceEnabled` controls whether this call writes a trace entry. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))
