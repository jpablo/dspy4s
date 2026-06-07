package dspy4s.lm.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LanguageModelRef
import dspy4s.core.contracts.RuntimeContext
import zio.blocks.schema.DynamicValue

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

enum LmMode derives CanEqual:
  case Chat
  case Text
  case Responses

enum MessageRole derives CanEqual:
  case System
  case User
  case Assistant

final case class ContentPart(kind: String, payload: String, metadata: Map[String, String] = Map.empty)

final case class Message(
    role: MessageRole,
    text: Option[String] = None,
    parts: Vector[ContentPart] = Vector.empty,
    metadata: Map[String, String] = Map.empty
)

final case class LmRequest(
    model: String,
    mode: LmMode = LmMode.Chat,
    messages: Vector[Message] = Vector.empty,
    options: DynamicValue.Record = DynamicValue.Record.empty,
    requestId: Option[String] = None,
    // Framework-only control field (cache-busting for repeated samples), NOT a provider parameter: it is folded
    // into the cache key but never serialized into the request body. Contrast `options`, which is spread verbatim
    // to the provider. See `RequestHash` / `ProviderRequestNormalizer`.
    rolloutId: Option[Int] = None
)

/** Token accounting for an LM call. The universal counters are typed fields; `extras` carries the open set of
  * provider-specific categories (e.g. cached / reasoning tokens), keyed by [[TokenCategory]] so there are no magic
  * string keys and the core counts are never duplicated into it. */
final case class LmUsage(
    totalTokens: Long = 0L,
    promptTokens: Long = 0L,
    completionTokens: Long = 0L,
    extras: Map[TokenCategory, Long] = Map.empty
)

/** A tool invocation requested by the model. `args` is the call's `arguments` object, decoded from the
  * provider's JSON into a `DynamicValue.Record` at the parse boundary (see `ToolCallAssembler` /
  * `ProviderLanguageModel`), so it travels the tool pipeline as `DynamicValue` without a lossy `Any` round-trip. */
final case class ToolCall(name: String, args: DynamicValue.Record)

final case class LmOutput(
    text: String,
    toolCalls: Vector[ToolCall] = Vector.empty,
    metadata: DynamicValue.Record = DynamicValue.Record.empty
)

final case class LmResponse(
    outputs: Vector[LmOutput],
    usage: Option[LmUsage] = None,
    modelName: Option[String] = None,
    cacheHit: Boolean = false
)

trait LmCache:
  def get(request: LmRequest): Option[LmResponse]
  def put(request: LmRequest, response: LmResponse): Unit

trait RetryPolicy:
  def shouldRetry(attempt: Int, error: DspyError): Boolean
  def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long = 0L

trait LanguageModel extends LanguageModelRef:
  def id: String
  def mode: LmMode

  /** Whether this model can be invoked with tool/function definitions and may return [[ToolCall]]s.
    * Defaults to `false`; providers that support the chat-completions tool protocol override to `true`. */
  def supportsFunctionCalling: Boolean = false

  /** Whether this model can be constrained to a structured/JSON response schema (e.g. OpenAI's
    * `response_format`). Defaults to `false`. */
  def supportsResponseSchema: Boolean = false

  /** Whether this model exposes reasoning/thinking output (e.g. reasoning-token models). Defaults to `false`. */
  def supportsReasoning: Boolean = false

  def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse]

  @nowarn("msg=unused")
  def acall(request: LmRequest)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, LmResponse]] =
    Future.successful(call(request))
