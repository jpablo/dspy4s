package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.runtime.PredictEngine
import dspy4s.programs.runtime.SettingsProgramRuntime
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

/** The untyped prediction module: the data-bag counterpart to typed [[Predict]]. Given a
  * [[dspy4s.core.contracts.SignatureLayout SignatureLayout]] (field names, roles, and wire types known only at
  * runtime), it runs the full adapter -> language-model -> parse pipeline and returns a
  * [[dspy4s.core.contracts.DynamicPrediction DynamicPrediction]] (a `DynamicValue.Record` of output fields plus raw
  * completions and LM usage). The actual execution lives in [[dspy4s.programs.runtime.PredictEngine PredictEngine]];
  * the surrounding [[dspy4s.programs.contracts.Module Module]] adds callbacks, tracing, and
  * history. Mirrors DSPy's `dspy.Predict` at the dynamic boundary.
  *
  * Why it exists separately from [[Predict]]: `Predict[I, O]` is the user-facing, statically-typed surface, but it
  * only encodes its input to a record, delegates to a memoized `DynamicPredict`, and decodes the reply back to `O`.
  * The dynamic layer is needed wherever there is no static `I`/`O` to carry:
  *
  *   - the runtime substrate that every typed `Predict[I, O]` delegates to (its private `inner`);
  *   - composite programs that build or augment their signatures at runtime and have no compile-time output type --
  *     [[ChainOfThought]] (prepends `reasoning`), [[CodeAct]], [[ProgramOfThought]], [[MultiChainComparison]], and the
  *     extractor passes in [[ReAct]]. These construct `DynamicPredict(layout = ...)` directly.
  *
  * @param layout           the signature whose input/output fields drive encoding, prompting, and parsing
  * @param demos            few-shot examples rendered into the prompt by the adapter
  * @param name             module name used in callbacks/trace/history (defaults to `"predict"`)
  * @param runtime          resolves the model and adapter from the ambient [[dspy4s.core.contracts.RuntimeContext]]
  * @param outputJsonSchema see field comment below
  * @param config           module-level LM option bag (see field comment below)
  */
final case class DynamicPredict(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {},
    /** Optional pre-rendered JSON Schema string for the output, threaded into [[AdapterInvocation]]. The typed
      * `Predict[I, O]` path provides this from its `signature.outputShape.jsonSchemaString`; users who
      * construct `DynamicPredict` directly leave it `None` and adapters fall back to their default behavior. */
    outputJsonSchema: Option[String] = None,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`.
      * Merged *under* the per-call `ProgramCall.config` (per-call keys win on collision), so it supplies
      * defaults a call may override. Empty by default — then the merged options are exactly the per-call config. */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (Python's `set_lm`/`get_lm`). When set, this predictor uses it in preference
      * to the ambient `RuntimeContext` LM, so different predictors in one program can pin different models.
      * `None` (the default) falls back to ambient resolution. Not part of the serialized learnable state (it's a
      * binding, like `runtime`). See PORT_GAPS G-3. */
    lm: Option[LanguageModel] = None,
    /** Tool schemas this predictor exposes to the model. Passed through to the adapter; only an adapter with
      * native function-calling enabled and a `tool_calls` output field acts on them. Pure [[ToolSpec]] data (no
      * invoke closures) — the executable bodies stay on the calling program. Not serialized state. See G-7b. */
    tools: Vector[ToolSpec] = Vector.empty
) extends DynamicModule:

  override val moduleName: String = name.getOrElse("predict")

  private val engine = PredictEngine(layout, demos, moduleName, runtime, outputJsonSchema, config, lm, tools)

  override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    engine.execute(call)

  /** Serialize this predictor's learnable state to a `DynamicValue.Record` -- the codec spine. The record has
    * three fields: `signature` (the layout via [[SignatureLayout.dumpState]]), `demos` (a sequence of
    * [[Example.dumpState]] records), and `config` (the module-level option bag verbatim). Round-trips with
    * [[DynamicPredict.fromState]]. `name` and `runtime` are intentionally not serialized -- they are
    * environment/identity concerns, restored to defaults on re-hydration. */
  def dumpState: DynamicValue.Record =
    val demoStates: Seq[DynamicValue] = demos.map(d => d.dumpState: DynamicValue)
    DynamicValue.Record(Chunk.from(Seq(
      "signature" -> (layout.dumpState: DynamicValue),
      "demos"     -> DynamicValue.Sequence(Chunk.from(demoStates)),
      "config"    -> (config: DynamicValue)
    )))

object DynamicPredict:

  /** Re-hydrate a [[DynamicPredict]] from the `DynamicValue.Record` produced by [[DynamicPredict.dumpState]]. The
    * `signature` is rebuilt via [[SignatureLayout.fromState]], `demos` via [[Example.fromState]], and `config`
    * read verbatim (defaulting to an empty record when absent). `name` and `runtime` are restored to their
    * defaults -- they are not part of the serialized state. */
  def fromState(state: DynamicValue.Record): Either[DspyError, DynamicPredict] =
    def readSignature: Either[DspyError, SignatureLayout] =
      DynamicValues.recordGet(state, "signature") match
        case Some(rec: DynamicValue.Record) => SignatureLayout.fromState(rec)
        case _ => Left(ValidationError("DynamicPredict state is missing a record 'signature'"))

    def readDemos: Either[DspyError, Vector[Example]] =
      DynamicValues.recordGet(state, "demos") match
        case None | Some(_: DynamicValue.Null.type) => Right(Vector.empty)
        case Some(seq: DynamicValue.Sequence) =>
          seq.elements.iterator.foldLeft[Either[DspyError, Vector[Example]]](Right(Vector.empty)) { (acc, raw) =>
            for
              demos <- acc
              demo  <- raw match
                         case rec: DynamicValue.Record => Example.fromState(rec)
                         case _ => Left(ValidationError("DynamicPredict state 'demos' must be records"))
            yield demos :+ demo
          }
        case Some(_) => Left(ValidationError("DynamicPredict state 'demos' must be a sequence"))

    def readConfig: Either[DspyError, DynamicValue.Record] =
      DynamicValues.recordGet(state, "config") match
        case None | Some(_: DynamicValue.Null.type) => Right(DynamicValue.Record.empty)
        case Some(rec: DynamicValue.Record)         => Right(rec)
        case Some(_) => Left(ValidationError("DynamicPredict state 'config' must be a record"))

    for
      layout <- readSignature
      demos  <- readDemos
      config <- readConfig
    yield DynamicPredict(layout = layout, demos = demos, config = config)
