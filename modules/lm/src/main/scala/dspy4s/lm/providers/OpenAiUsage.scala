package dspy4s.lm.providers

import dspy4s.lm.contracts.LmUsage
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.NameMapper
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** Typed wire model of an OpenAI token-usage block, shared by the streaming chunk parser and the (non-streaming)
  * response parser. Decoded by a derived `JsonCodec`, so absent fields and `null`s become `None` and unknown keys
  * are ignored — no by-hand `DynamicValue` navigation. Both the Chat (`prompt_tokens`/`completion_tokens`) and the
  * Responses (`input_tokens`/`output_tokens`) naming conventions are modeled; `toLmUsage` reconciles them. */
private[lm] final case class OpenAiUsage(
    promptTokens: Option[Long] = None,
    completionTokens: Option[Long] = None,
    totalTokens: Option[Long] = None,
    inputTokens: Option[Long] = None,
    outputTokens: Option[Long] = None
) derives Schema:

  /** `details` carries the token counts actually present on the wire (the prior parsers collected the top-level
    * numeric usage fields, which for OpenAI are exactly these). `totalTokens` falls back to prompt + completion. */
  def toLmUsage: LmUsage =
    val prompt = promptTokens.orElse(inputTokens).getOrElse(0L)
    val completion = completionTokens.orElse(outputTokens).getOrElse(0L)
    val details = Vector(
      "prompt_tokens"     -> promptTokens,
      "completion_tokens" -> completionTokens,
      "total_tokens"      -> totalTokens,
      "input_tokens"      -> inputTokens,
      "output_tokens"     -> outputTokens
    ).collect { case (k, Some(v)) => k -> v }.toMap
    LmUsage(
      totalTokens = totalTokens.getOrElse(prompt + completion),
      promptTokens = prompt,
      completionTokens = completion,
      details = details
    )

private[lm] object OpenAiUsage:
  private val codec = Schema[OpenAiUsage].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

  /** Decode a usage record (a subtree of an already-parsed response/chunk) into the typed model. Lenient: a usage
    * block that fails to decode yields an empty `OpenAiUsage` rather than failing the whole response. */
  def fromDynamic(usage: DynamicValue): OpenAiUsage =
    codec.decode(usage.toJson).getOrElse(OpenAiUsage())
