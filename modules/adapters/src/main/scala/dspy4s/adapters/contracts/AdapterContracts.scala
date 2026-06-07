package dspy4s.adapters.contracts

import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message

/** Optional pre-rendered JSON Schema describing the signature's output fields.
  *
  * Populated by the typed `Predict[I, O]` path (which has a static `Schema[O]` available and can render it
  * via zio-blocks' `Schema.toJsonSchema`); left `None` by `DynamicPredict` (where there's no static schema to
  * render from). Adapters that understand structured-output hints (currently [[JSONAdapter]]) inline this
  * string into their prompt instruction; adapters that don't ignore it. */
final case class AdapterInvocation(
    layout: SignatureLayout,
    demos: Vector[Example],
    inputs: Example,
    request: LmRequest,
    outputJsonSchema: Option[String] = None
)

/** The rendered prompt an adapter produces from an [[AdapterInvocation]].
  *
  * `requestOptions` (G-7) is the seam by which an adapter contributes provider request fields — the option bag
  * that gets merged into the outgoing [[dspy4s.lm.contracts.LmRequest.options]]. v1 carries native structured
  * outputs (OpenAI's `response_format: {type:"json_schema", json_schema:{...}}`, emitted by [[JSONAdapter]] when
  * the resolved LM declares `supportsResponseSchema`). The engine merges this map UNDER the existing per-call /
  * module options, so explicit user/module config wins on key collision.
  *
  * Follow-up (documented, not implemented here): native FUNCTION CALLING reuses this same seam — an adapter would
  * inject `tools` / `tool_choice` (from `ToolSchemaBridge`) into `requestOptions` when `supportsFunctionCalling`.
  * That additionally needs response-side parsing of native `tool_calls` and ReAct rewiring, which `requestOptions`
  * alone does not cover. */
final case class FormattedPrompt(
    messages: Vector[Message],
    metadata: Map[String, Any] = Map.empty,
    requestOptions: zio.blocks.schema.DynamicValue.Record = zio.blocks.schema.DynamicValue.Record.empty
)

object FormattedPrompt:
  /** Merge adapter-contributed `requestOptions` UNDER `requestOptions` already present on the request, so the
    * latter (per-call / module config) wins on key collision. Mirrors the engine's `mergeConfig` style: start
    * from the adapter options and upsert each request option by name (later wins, preserving insertion order).
    *
    * Scope (v1): exactly ONE adapter contributes options today -- `JSONAdapter`'s `response_format`. The merge is
    * therefore a flat last-key-wins upsert; there is no cross-adapter composition to reconcile. When native
    * function-calling lands (G-7b) and a second contributor injects e.g. a `tools` array, this seam will need
    * explicit per-key semantics (arrays concatenate, scalars overwrite) -- deferred until then rather than built
    * speculatively against a single contributor. */
  def mergeOptions(
      adapterOptions: zio.blocks.schema.DynamicValue.Record,
      requestOptions: zio.blocks.schema.DynamicValue.Record
  ): zio.blocks.schema.DynamicValue.Record =
    requestOptions.fields.iterator.foldLeft(adapterOptions)((acc, kv) => acc.updated(kv._1, kv._2))

/** Adapter parse result. `values` is the structured record of output field values produced from the LM completion;
  * `metadata` is a free-form bag of debug / adapter-specific annotations (e.g. `{"adapter" -> "json", "fallback"
  * -> "text"}`) and stays a plain Map. */
final case class ParsedOutput(
    values: zio.blocks.schema.DynamicValue.Record,
    rawText: Option[String] = None,
    metadata: Map[String, Any] = Map.empty
)

/** A chunk of output text routed to a specific signature field by an
  * [[AdapterStreamingState]]. `isLast = true` marks the final emission for the
  * field (either because the adapter detected the next field's boundary or
  * because the stream is finishing).
  */
final case class FieldChunk(fieldName: String, text: String, isLast: Boolean = false)

/** Per-call state machine that consumes streamed LM text fragments and emits
  * per-field chunks based on the adapter's framing.
  *
  *   - `receive(textDelta)` appends a fresh token fragment and returns the
  *     chunks that have become safe to emit (i.e. not held back to disambiguate
  *     a partial field marker).
  *   - `finish()` flushes any remaining buffered content and must mark the
  *     final emitted chunk with `isLast = true`.
  *
  * Implementations are single-use per LM call; a fresh instance is created per
  * [[dspy4s.streaming.Streamify]] producer thread.
  */
trait AdapterStreamingState:
  def receive(textDelta: String): Vector[FieldChunk]
  def finish(): Vector[FieldChunk]

trait Adapter extends AdapterRef:
  def name: String

  def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt]

  def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput]

  /** Streaming-aware adapters override this to return a per-call state
    * machine. The default returns [[None]] and the streaming pipeline falls
    * back to emitting raw tokens with an empty field name.
    */
  def streamingState(layout: SignatureLayout): Option[AdapterStreamingState] = None

  def execute(languageModel: LanguageModel, invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, Vector[ParsedOutput]] =
    for
      prompt <- format(invocation)
      // Merge adapter-contributed requestOptions UNDER the request's existing options (per-call/module wins).
      mergedOptions = FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
      response <- languageModel.call(invocation.request.copy(messages = prompt.messages, options = mergedOptions))
      parsed <- parseOutputs(invocation.layout, response.outputs)
    yield parsed

  private def parseOutputs(layout: SignatureLayout, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.foldLeft(Right(Vector.empty): Either[DspyError, Vector[ParsedOutput]]) { (acc, output) =>
      for
        soFar <- acc
        parsed <- parse(layout, output)
      yield soFar :+ parsed
    }

trait AdapterFallbackPolicy:
  def fallbackFor(error: DspyError, attemptedAdapter: String): Option[String]

object AdapterErrors:
  def missingField(fieldName: String): DspyError =
    ParseError(component = "adapter", message = s"Missing required output field: $fieldName")
