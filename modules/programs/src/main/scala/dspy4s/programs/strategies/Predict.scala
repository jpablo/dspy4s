package dspy4s.programs.strategies

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, PredictorKey, RuntimeContext, SignatureLayout}
import dspy4s.core.data.Example
import dspy4s.core.decisions.DecisionSettings
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.{Module, ModuleLifecycle, ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.{PredictEngine, SettingsProgramRuntime}
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.Signature
import dspy4s.programs.SignatureInput
import zio.blocks.schema.DynamicValue

/** The fundamental prediction module: given a `Signature[I, O]`, `apply` takes an input `I`, formats and dispatches a
  * language-model request through the configured adapter and model, and returns a `Prediction[O]`. Other programs
  * (`ChainOfThought`, `ReAct`, ...) build on it.
  *
  * The signature's shapes bracket the call:
  *
  *   1. Inputs are encoded through `signature.inputShape` before the request is built. 2. The model's reply is decoded
  *      through `signature.outputShape` into a `Prediction[O]`; decode failures surface as `Left(DspyError)` from
  *      `apply`, never via lazy field access.
  *
  * The parse step of the call also runs the decode on each output of the reply (upstream `parse_value` validates each
  * field in the parse step). Thus a value that the decode rejects is a [[dspy4s.core.contracts.ParseError]] with the
  * raw response, and it gets the fallback of the adapter.
  *
  * `Predict[I, O]` is a `Module[I, O]` — the domain-valued analog of `dspy.Predict`. It is a *sibling* of the
  * record-valued [[DynamicPredict]] rather than a wrapper around it: both are thin `Module`s over the shared
  * [[dspy4s.programs.runtime.PredictEngine PredictEngine]]. So a `Predict` call emits exactly one module event (named
  * by this `Predict`), and the whole encode → LM call → decode runs inside `Module.apply`'s lifecycle wrapping —
  * adapter selection, caching, retries, callbacks, and trace/history are all bracketed around the schema boundary. The
  * raw prediction (raw completions and LM usage) is preserved on `Prediction.raw`.
  */
final case class Predict[I, O](
    signature: Signature[I, O],
    demos    : Vector[Example] = Vector.empty,
    name     : Option[String]  = None,
    runtime  : ProgramRuntime  = SettingsProgramRuntime.default,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`. Merged
      * *under* the per-call `config` (per-call keys win on collision), so it supplies defaults a call may override.
      * Empty by default.
      */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (the immutable analogue of Python's `set_lm`/`get_lm`). When set, this predictor
      * uses it in preference to the ambient `RuntimeContext` LM, so a program can pin different models to different
      * predictors. `None` (the default) falls back to ambient resolution. See PORT_GAPS G-3.
      */
    lm: Option[LanguageModel] = None,
    /** The tools of the model, which go to the adapter. They apply only to a signature with a tool calls output
      * (`Vector[ToolCall]`). When native function calling is active, the request has them as provider tools. Else the
      * prompt shows the text of each tool as an input field `tools` ([[dspy4s.adapters.contracts.NativeTools]]). Pure
      * [[ToolSpec]] data; not serialized state. See PORT_GAPS G-7b.
      */
    tools: Vector[ToolSpec] = Vector.empty,
    /** Experimental. The explicit settings of the decision outputs, by output name (upstream `Predict.fields`). Each
      * rich decision output (`Noul`, `Score`, `Choice`) uses evidence decoding with its defaults; a native `Boolean` or
      * enum output uses it only with an entry here. The call validates the settings before the request. See
      * [[dspy4s.core.decisions]].
      */
    decisions: Map[String, DecisionSettings] = Map.empty
) extends Module[I, O],
      DecisionOutputs[Predict[I, O]],
      SignatureInput[I]:

  override val moduleName: String = name.getOrElse("predict")

  private[programs] def baseSignature: Signature[I, O] = signature

  // The names of the inputs with no default that are not optional, computed once: `forward` checks them on every call.
  // These are wire names. An adapter gives an absent input with a default its default (`AdapterTextSupport.withInputDefaults`).
  private val requiredInputs: Set[String] = signature.layout.inputFields.iterator.filterNot(field =>
    field.optional || field.defaultValue.isDefined
  ).map(_.name).toSet

  // The same engine `DynamicPredict` builds; `Predict` adds only the encode/decode around it.
  private val engine = PredictEngine(
    layout = signature.layout,
    demos = demos,
    moduleName = moduleName,
    runtime = runtime,
    outputJsonSchema = signature.outputShape.jsonSchema,
    config = config,
    lm = lm,
    tools = tools,
    decisions = decisions,
    nestedDecisionFields = signature.outputShape.nestedDecisionFields,
    checkOutput = values => signature.outputShape.decode(values).map(_ => ())
  )

  /** Pin a bound LM to this predictor (immutable `set_lm`): returns a copy that resolves to `model` instead of the
    * ambient `RuntimeContext` LM. The field [[lm]] is the bound LM (`get_lm`).
    */
  def withLm(model: LanguageModel): Predict[I, O] = copy(lm = Some(model))

  override protected def decisionLayout: SignatureLayout = signature.layout

  override protected def withDecisions(updated: Map[String, DecisionSettings]): Predict[I, O] =
    copy(decisions = updated)

  // The key is the metadata of this leaf, so an optimizer finds the trace entries of the leaf.
  override protected val lifecycle: ModuleLifecycle[I] =
    ModuleLifecycle.predictor(signature.inputShape, PredictorKey.from(moduleName, signature.layout))

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val inputRecord = call.encodedInput(signature.inputShape)
    // Defensive: shape implementations that don't statically guarantee full coverage of declared input fields
    // (notably the Map-based shape used by trait specs) could let a caller silently omit a required input.
    // Validate before spending an LM call. A case-class derivation gives a complete record, except for a field with
    // `Modifier.encodeTransient`, so the check fires only for such a field with no default; cost is one Set.diff per call.
    val presentInputs = DynamicValues.recordKeys(inputRecord).toSet
    val missing       = requiredInputs.diff(presentInputs)
    if missing.nonEmpty then
      Left(NotFoundError(
        resource = "program_input",
        message = s"Missing required inputs for '${signature.name}': ${missing.toVector.sorted.mkString(", ")}"
      ))
    else
      engine
        .execute(call.encoded(signature.inputShape))
        .flatMap(raw => Prediction.from(raw, signature.outputShape))
