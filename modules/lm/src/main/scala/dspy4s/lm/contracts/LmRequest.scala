package dspy4s.lm.contracts

import zio.blocks.schema.DynamicValue

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
    role    : MessageRole,
    text    : Option[String]      = None,
    parts   : Vector[ContentPart] = Vector.empty,
    metadata: Map[String, String] = Map.empty
)

final case class LmRequest(
    model    : String,
    mode     : LmMode              = LmMode.Chat,
    messages : Vector[Message]     = Vector.empty,
    options  : DynamicValue.Record = DynamicValue.Record.empty,
    requestId: Option[String]      = None,
    // Framework-only control field (cache-busting for repeated samples), NOT a provider parameter: it is folded
    // into the cache key but never serialized into the request body. Contrast `options`, which is spread verbatim
    // to the provider. See `RequestHash` / `ProviderRequestNormalizer`.
    rolloutId: Option[Int] = None
)
