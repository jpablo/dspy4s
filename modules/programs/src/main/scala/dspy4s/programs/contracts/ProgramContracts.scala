package dspy4s.programs.contracts

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.schema.{DynamicValue, Schema}

/** `inputs` is the spine record passed to the adapter and codec layers. `config` is the provider option bag
  * (model overrides, sampling params) forwarded *verbatim to the LM provider* — its keys are defined by the
  * provider's API. It is a `DynamicValue.Record` (built with `:=`), i.e. JSON-shaped: exactly as permissive as
  * the wire, serialized losslessly, with no `Any`. Framework-only control values do NOT live here: they are
  * typed fields (e.g. [[rolloutId]]) so the provider bag stays purely provider-bound. */
final case class ProgramCall(
    inputs: DynamicValue.Record,
    config: DynamicValue.Record = DynamicValue.Record.empty,
    traceEnabled: Boolean = true,
    // Cache-busting selector for repeated samples (used by `BestOfN`). Threaded into `LmRequest.rolloutId`; a
    // framework concern, never forwarded to the provider. Was previously smuggled as `config("rollout_id")`.
    rolloutId: Option[Int] = None
)

/** The typed-layer counterpart to [[ProgramCall]]: the single call argument for `Module[TypedCall[I], O]`
  * (`Predict[I, O]` / `ChainOfThought[I, O]`). It carries the typed input `I` alongside the same per-call knobs
  * `ProgramCall` exposes (`config`, `traceEnabled`), so the typed and untyped layers share one uniform
  * `apply(call)` entry on `Module`. Callers normally use the convenience `apply(input, config, traceEnabled)`
  * overload, which builds a `TypedCall` and dispatches through the wrapped `apply`. */
final case class TypedCall[I](
    input: I,
    config: DynamicValue.Record = DynamicValue.Record.empty,
    traceEnabled: Boolean = true
)

trait ProgramRuntime:
  def resolveModel(using RuntimeContext): Either[DspyError, LanguageModel]
  def resolveAdapter(using RuntimeContext): Either[DspyError, Adapter]

/** A callable tool exposed to tool-using programs (`ReAct`, ...). `invoke` receives the call arguments as a
  * `DynamicValue.Record` (the named params parsed from the LM's tool call) and returns its result as a
  * `DynamicValue`, so both ends travel the spine without a lossy `Any` round-trip. Extract args with
  * `DynamicValues.recordGet` / a `Schema`-backed decode; build the result with [[ToolFunction.result]]. */
trait ToolFunction:
  def name: String

  /** Human-readable description of what the tool does, surfaced in tool-using programs' prompts (e.g. ReAct lists
    * each tool's name + description so the model knows when to call it). Defaults to empty. */
  def description: String = ""

  def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue]

object ToolFunction:
  /** Lift a `Schema`-typed return value into the `DynamicValue` a tool yields, so authors can write
    * `Right(ToolFunction.result("Brussels"))` (or return any case class with a `Schema`) instead of
    * hand-constructing `DynamicValue.Primitive(...)`. */
  def result[A](value: A)(using schema: Schema[A]): DynamicValue =
    schema.toDynamicValue(value)

final case class ToolCallRequest(name: String, args: DynamicValue.Record)
final case class ToolCallResult(name: String, result: Either[DspyError, DynamicValue])
