package dspy4s.programs

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeContext}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.{Module, ModuleLifecycle, ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.{PredictEngine, SettingsProgramRuntime}
import dspy4s.typed.{Prediction, Signature}
import zio.blocks.schema.DynamicValue

/** The fundamental typed prediction module: given a `Signature[I, O]`, `apply` takes a typed input `I`, formats and
  * dispatches a language-model request through the configured adapter and model, and returns a typed `Prediction[O]`.
  * Other programs (`ChainOfThought`, `ReAct`, ...) build on it.
  *
  * The signature's shapes bracket the call:
  *
  *   1. Inputs are encoded through `signature.inputShape` before the request is built. 2. The model's reply is decoded
  *      through `signature.outputShape` into a typed `Prediction[O]`; decode failures surface as `Left(DspyError)` from
  *      `apply`, never via lazy field access.
  *
  * `Predict[I, O]` is a `Module[I, O]` — the typed analog of `dspy.Predict`. It is a *sibling*
  * of the untyped [[DynamicPredict]] rather than a wrapper around it: both are thin `Module`s over the shared
  * [[dspy4s.programs.runtime.PredictEngine PredictEngine]]. So a typed call emits exactly one module event (named by
  * this `Predict`), and the whole encode → LM call → decode runs inside `Module.apply`'s lifecycle wrapping — adapter
  * selection, caching, retries, callbacks, and trace/history are all bracketed around the typed boundary. The untyped
  * prediction (raw completions and LM usage) is preserved on `Prediction.raw`.
  */
final case class Predict[I, O](
    signature: Signature[I, O],
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {},
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`. Merged
      * *under* the per-call `config` (per-call keys win on collision), so it supplies defaults a call may override.
      * Empty by default — then the merged options are exactly the per-call config.
      */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (the immutable analogue of Python's `set_lm`/`get_lm`). When set, this predictor
      * uses it in preference to the ambient `RuntimeContext` LM, so a program can pin different models to different
      * predictors. `None` (the default) falls back to ambient resolution. See PORT_GAPS G-3.
      */
    lm: Option[LanguageModel] = None,
    /** Tool schemas exposed to the model, passed through to the adapter. Acted on only by an adapter with native
      * function-calling enabled and a `tool_calls` output field in the signature. Pure [[ToolSpec]] data; not
      * serialized state. See PORT_GAPS G-7b.
      */
    tools: Vector[ToolSpec] = Vector.empty
) extends Module[I, O]:

  override val moduleName: String = name.getOrElse("predict")

  // The same engine `DynamicPredict` builds; `Predict` adds only the typed encode/decode around it.
  private val engine = PredictEngine(
    layout           = signature.layout,
    demos            = demos,
    moduleName       = moduleName,
    runtime          = runtime,
    outputJsonSchema = signature.outputShape.jsonSchemaString,
    config           = config,
    lm               = lm,
    tools            = tools
  )

  /** Pin a bound LM to this predictor (immutable `set_lm`): returns a copy that resolves to `model` instead of the
    * ambient `RuntimeContext` LM.
    */
  def withLm(model: LanguageModel): Predict[I, O] = copy(lm = Some(model))

  /** The bound LM, if any (`get_lm`); `None` means the predictor uses the ambient `RuntimeContext` LM. */
  def boundLm: Option[LanguageModel] = lm

  /** Forget the static input/output shapes and expose this predictor on the dynamic record spine.
    *
    * This is a one-way, immutable snapshot: it preserves the complete engine configuration, including the output JSON
    * schema derived from `O`, but the returned module no longer validates or decodes typed values. For every input
    * whose encoded record and model output satisfy this signature, `apply(input).map(_.raw)` and the erased module's
    * result are equal.
    */
  def erase: DynamicPredict =
    DynamicPredict(
      layout = signature.layout,
      demos = demos,
      name = name,
      runtime = runtime,
      outputJsonSchema = signature.outputShape.jsonSchemaString,
      config = config,
      lm = lm,
      tools = tools
    )

  override protected val lifecycle: ModuleLifecycle[I, O] =
    ModuleLifecycle.typed(signature.inputShape)

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputRecord = call.encodedInput(signature.inputShape)
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
        .execute(call.encoded(signature.inputShape))
        .flatMap(raw => Prediction.from(raw, signature.outputShape))
