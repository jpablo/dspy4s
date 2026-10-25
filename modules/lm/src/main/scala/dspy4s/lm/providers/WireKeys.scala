package dspy4s.lm.providers

/** The JSON keys of the OpenAI-compatible chat and Responses wires that the code reads or writes by name. The wires
  * ([[ChatWire]], [[ResponsesWire]]), the stream client, and the embedder use them, so each key has one name in the
  * code.
  *
  * The records of this package ([[OpenAiUsage]], [[OpenAiStreamChunk]]) get their keys from the field names of their
  * `Schema`, with a name mapper. Thus their keys are not here. The reader of a usage record
  * ([[OpenAiUsage.fromDynamic]]) has the usage keys next to it. Only the other keys that the code reads or writes by
  * hand are here.
  */
private[lm] object WireKeys:
  // The request body.
  final val model    = "model"
  final val messages = "messages"
  final val prompt   = "prompt"
  final val role     = "role"
  final val `type`   = "type"

  // The options of a stream.
  final val stream        = "stream"
  final val streamOptions = "stream_options"
  final val includeUsage  = "include_usage"

  // The keys of a stream chunk. The codec of `OpenAiStreamChunk` derives these keys, and its lenient reader uses them.
  final val delta        = "delta"
  final val finishReason = "finish_reason"
  final val index        = "index"
  final val error        = "error"

  // The reply, and the keys that the request and the reply share.
  final val choices   = "choices"
  final val output    = "output"
  final val message   = "message"
  final val text      = "text"
  final val content   = "content"
  final val metadata  = "metadata"
  final val usage     = "usage"
  final val toolCalls = "tool_calls"
  final val function  = "function"
  final val name      = "name"
  final val arguments = "arguments"

  // The id of a tool call. The Responses API names the id of a call `call_id`. A tool message names the call that it
  // answers with `tool_call_id`.
  final val id         = "id"
  final val callId     = "call_id"
  final val toolCallId = "tool_call_id"

  // The key of the texts in the body of an embeddings request.
  final val input = "input"
