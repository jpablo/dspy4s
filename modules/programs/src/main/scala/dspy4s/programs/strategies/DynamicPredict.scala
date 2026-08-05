package dspy4s.programs.strategies

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.runtime.PredictEngine
import dspy4s.programs.runtime.SettingsProgramRuntime
import zio.blocks.schema.DynamicValue

/** The dynamic prediction module: the data-bag counterpart to [[Predict]]. Given a
  * [[dspy4s.core.contracts.SignatureLayout SignatureLayout]] (input/output cohorts and wire types known only at
  * runtime), it runs the full adapter -> language-model -> parse pipeline and returns a
  * `Prediction[DynamicValue.Record]`. Its semantic output is the parsed value record; its `raw` field retains the
  * [[dspy4s.core.data.RawPrediction RawPrediction]] with completions and LM usage. The actual execution lives in
  * [[dspy4s.programs.runtime.PredictEngine PredictEngine]]; [[DynamicModule]] lifts that engine result through
  * `Prediction.dynamic`, and the surrounding [[dspy4s.programs.contracts.Module Module]] adds callbacks, tracing, and
  * history. Mirrors DSPy's `dspy.Predict` at the dynamic boundary.
  *
  * Why it exists separately from [[Predict]]: `Predict[I, O]` carries Scala-known input and output types;
  * `DynamicPredict` is the executable data-bag surface for signatures whose Scala input/output types are not known.
  * They are siblings rather than wrappers: each configures its own shared [[PredictEngine]]. The dynamic surface is
  * needed wherever there is no static `I`/`O` to carry, including:
  *
  *   - composite programs whose internal generations have runtime-built signatures, such as [[CodeAct]],
  *     [[ProgramOfThought]], [[MultiChainComparison]], and the internal passes in [[ReAct]];
  *   - optimizer helper generations whose proposed signature exists only as a [[SignatureLayout]].
  *
  * For USER programs over runtime-string signatures, prefer [[DynamicSignature]] (`parse` + `predict()`): it mints
  * fresh input/output types with their decoder, so the program composes and optimizes through the same machinery as
  * programs with statically known I/O. `DynamicPredict` is the record-valued substrate those helpers run on.
  *
  * @param layout
  *   the signature whose input/output fields drive encoding, prompting, and parsing
  * @param demos
  *   few-shot examples rendered into the prompt by the adapter
  * @param name
  *   module name used in callbacks/trace/history (defaults to `"predict"`)
  * @param runtime
  *   resolves the model and adapter from the ambient [[dspy4s.core.contracts.RuntimeContext]]
  * @param outputJsonSchema
  *   see field comment below
  * @param config
  *   module-level LM option bag (see field comment below)
  */
final case class DynamicPredict(
    layout : SignatureLayout,
    demos  : Vector[Example] = Vector.empty,
    name   : Option[String]  = None,
    runtime: ProgramRuntime  = new SettingsProgramRuntime {},
    /** Optional pre-rendered JSON Schema string for the output, threaded into [[AdapterInvocation]]. A [[Predict]]
      * derives the same input for its own engine from `signature.outputShape.jsonSchemaString`; users who construct
      * `DynamicPredict` directly usually leave it `None`.
      */
    outputJsonSchema: Option[String] = None,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`. Merged
      * *under* the per-call `ProgramCall.config` (per-call keys win on collision), so it supplies defaults a call may
      * override. Empty by default — then the merged options are exactly the per-call config.
      */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (Python's `set_lm`/`get_lm`). When set, this predictor uses it in preference to the
      * ambient `RuntimeContext` LM, so different predictors in one program can pin different models. `None` (the
      * default) falls back to ambient resolution. Not part of the serialized learnable state (it's a binding, like
      * `runtime`). See PORT_GAPS G-3.
      */
    lm: Option[LanguageModel] = None,
    /** Tool schemas this predictor exposes to the model. Passed through to the adapter; only an adapter with native
      * function-calling enabled and a `tool_calls` output field acts on them. Pure [[ToolSpec]] data (no invoke
      * closures) — the executable bodies stay on the calling program. Not serialized state. See G-7b.
      */
    tools: Vector[ToolSpec] = Vector.empty
) extends DynamicModule:

  override val moduleName: String = name.getOrElse("predict")

  private val engine = PredictEngine(layout, demos, moduleName, runtime, outputJsonSchema, config, lm, tools)

  override protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    engine.execute(call)
