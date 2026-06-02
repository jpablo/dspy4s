package dspy4s.lm.providers

/** JSON field names for the OpenAI-compatible chat / responses wire format, shared by the request normalizer, the
  * response parser, and the streaming client so these navigation/serialization keys are named rather than scattered
  * string literals.
  *
  * The typed DTOs in this package ([[OpenAiUsage]], [[OpenAiStreamChunk]]) derive their keys from the `Schema`
  * field-name mapper instead, so their wire keys do not appear here — only the hand-navigated/handwritten ones do. */
private[lm] object WireKeys:
  // Request payload
  final val model     = "model"
  final val mode      = "mode"
  final val requestId = "request_id"
  final val messages  = "messages"
  final val prompt    = "prompt"
  final val role      = "role"
  final val `type`    = "type"

  // Streaming controls
  final val stream        = "stream"
  final val streamOptions = "stream_options"
  final val includeUsage  = "include_usage"

  // Response / shared structure
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

  // Synthetic keys used to wrap tool-call arguments that aren't a JSON object
  final val input = "input"
  final val value = "value"
