package dspy4s.lm.contracts

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ToolCall
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

enum LmMode derives CanEqual, Schema:
  case Chat
  case Text
  case Responses

enum MessageRole derives CanEqual, Schema:
  case System
  case User
  case Assistant

  /** The result of one tool call. The message answers the call that [[Message.toolCallId]] names. */
  case Tool

final case class ContentPart(kind: String, payload: String, metadata: Map[String, String] = Map.empty) derives Schema

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
) derives Schema

/** One request to a model.
  *
  * @param mode
  *   the mode of the model that gets the request. `Predict` copies [[LanguageModel.mode]] into it. The history entry
  *   reads it. The cache key uses the mode of the model, not this field. A provider does not read it: the mode of the
  *   provider selects its wire.
  * @param options
  *   the provider parameters (for example `temperature`). The provider sends them as they are.
  * @param rolloutId
  *   a control value of the framework, not a provider parameter: it selects a new cache entry for a repeated sample.
  *   The cache key has it, and the provider never sends it.
  */
final case class LmRequest(
    model    : String,
    mode     : LmMode              = LmMode.Chat,
    messages : Vector[Message]     = Vector.empty,
    options  : DynamicValue.Record = DynamicValue.Record.empty,
    rolloutId: Option[Int]         = None
):

  /** The option bag that the provider sends: `defaultOptions` overlaid by [[options]], with no option whose value is
    * null. Thus a null option of the request removes a default option (upstream `_canonical` in
    * `dspy/clients/execution.py` drops each `None` after the merge). The wires of the providers and the cache key of
    * `ManagedLanguageModel` share this merge, so the key covers the options on the wire.
    */
  def effectiveOptions(defaultOptions: DynamicValue.Record): DynamicValue.Record =
    val merged = DynamicValues.mergeRecords(defaultOptions, options)
    DynamicValue.Record(merged.fields.filter {
      case (_, _: DynamicValue.Null.type) => false
      case _                              => true
    })
