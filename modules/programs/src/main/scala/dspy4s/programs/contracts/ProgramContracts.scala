package dspy4s.programs.contracts

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.schema.{DynamicValue, Schema}

/** `inputs` is the spine record passed to the adapter and codec layers. `config` stays a plain `Map[String, Any]`
  * because it's an opaque option bag forwarded *verbatim to the LM provider* (model overrides, sampling params) —
  * it has no codec story and the provider's API defines its keys. Framework-only control values do NOT live here:
  * they are typed fields (e.g. [[rolloutId]]) so the provider bag stays purely provider-bound. */
final case class ProgramCall(
    inputs: DynamicValue.Record,
    config: Map[String, Any] = Map.empty,
    traceEnabled: Boolean = true,
    // Cache-busting selector for repeated samples (used by `BestOfN`). Threaded into `LmRequest.rolloutId`; a
    // framework concern, never forwarded to the provider. Was previously smuggled as `config("rollout_id")`.
    rolloutId: Option[Int] = None
)

trait ProgramRuntime:
  def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel]
  def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter]

trait PredictProgram extends Module[ProgramCall, DynamicPrediction]:
  /** Convenience overload of `run` so call sites can write:
    *
    *   predict.run("comment" := comment, "lang" := "en")
    *
    * instead of `run(ProgramCall(inputs = Map(...)))`. The inherited
    * `run(input: ProgramCall)` remains available when `config` or
    * `traceEnabled` need to be customized. */
  def run(inputs: (String, DynamicValue)*)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    run(ProgramCall(inputs = DynamicValues.recordFromEntries(inputs)))

/** A callable tool exposed to tool-using programs (`ReAct`, ...). `invoke` receives the call arguments as a
  * `DynamicValue.Record` (the named params parsed from the LM's tool call) and returns its result as a
  * `DynamicValue`, so both ends travel the spine without a lossy `Any` round-trip. Extract args with
  * `DynamicValues.recordGet` / a `Schema`-backed decode; build the result with [[ToolFunction.result]]. */
trait ToolFunction:
  def name: String
  def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue]

object ToolFunction:
  /** Lift a `Schema`-typed return value into the `DynamicValue` a tool yields, so authors can write
    * `Right(ToolFunction.result("Brussels"))` (or return any case class with a `Schema`) instead of
    * hand-constructing `DynamicValue.Primitive(...)`. */
  def result[A](value: A)(using schema: Schema[A]): DynamicValue =
    schema.toDynamicValue(value)

final case class ToolCallRequest(name: String, args: DynamicValue.Record)
final case class ToolCallResult(name: String, result: Either[DspyError, DynamicValue])
