package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import dspy4s.programs.contracts.Module
import dspy4s.programs.runtime.PredictEngine
import dspy4s.programs.runtime.SettingsProgramRuntime

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
  */
final case class DynamicPredict(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {},
    /** Optional pre-rendered JSON Schema string for the output, threaded into [[AdapterInvocation]]. The typed
      * `Predict[I, O]` path provides this from its `signature.outputShape.jsonSchemaString`; users who
      * construct `DynamicPredict` directly leave it `None` and adapters fall back to their default behavior. */
    outputJsonSchema: Option[String] = None
) extends Module:

  override val moduleName: String = name.getOrElse("predict")

  private val engine = PredictEngine(layout, demos, moduleName, runtime, outputJsonSchema)

  override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    engine.execute(call)
