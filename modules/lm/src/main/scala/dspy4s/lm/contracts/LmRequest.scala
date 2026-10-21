package dspy4s.lm.contracts

import dspy4s.core.contracts.ToolCall
import zio.blocks.schema.DynamicValue

enum LmMode derives CanEqual:
  case Chat
  case Text
  case Responses

enum MessageRole derives CanEqual:
  case System
  case User
  case Assistant

  /** The result of one tool call. The message answers the call that [[Message.toolCallId]] names. */
  case Tool

final case class ContentPart(kind: String, payload: String, metadata: Map[String, String] = Map.empty)

/** One message of a request.
  *
  * @param toolCalls
  *   for an assistant message: the tool calls of that turn. Each call must have an id, because the provider matches a
  *   tool message with its call by the id.
  * @param toolCallId
  *   for a tool message: the id of the call that this message answers
  * @param name
  *   for a tool message: the name of the tool
  */
final case class Message(
    role      : MessageRole,
    text      : Option[String]      = None,
    parts     : Vector[ContentPart] = Vector.empty,
    metadata  : Map[String, String] = Map.empty,
    toolCalls : Vector[ToolCall]    = Vector.empty,
    toolCallId: Option[String]      = None,
    name      : Option[String]      = None
)

/** One request to a model.
  *
  * @param cacheIdentity
  *   the cache identity of the model ([[LanguageModel.cacheIdentity]]). It is a field of the cache key only:
  *   `ManagedLanguageModel` sets it on the key from its delegate, and the provider never sends it. The key text has it
  *   only when it is not empty, so each other key stays as it was.
  */
final case class LmRequest(
    model    : String,
    mode     : LmMode              = LmMode.Chat,
    messages : Vector[Message]     = Vector.empty,
    options  : DynamicValue.Record = DynamicValue.Record.empty,
    requestId: Option[String]      = None,
    // Framework-only control field (cache-busting for repeated samples), NOT a provider parameter: it is folded
    // into the cache key but never serialized into the request body. Contrast `options`, which is spread verbatim
    // to the provider. See `RequestHash` / `ProviderRequestNormalizer`.
    rolloutId    : Option[Int]         = None,
    cacheIdentity: DynamicValue.Record = DynamicValue.Record.empty
)
