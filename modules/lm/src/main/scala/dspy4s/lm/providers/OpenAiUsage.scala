package dspy4s.lm.providers

import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.TokenCategory
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.NameMapper
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** Typed wire model of an OpenAI token-usage block, shared by the streaming chunk parser and the (non-streaming)
  * response parser. Decoded by a derived `JsonCodec`, so absent fields and `null`s become `None` and unknown keys
  * are ignored — no by-hand `DynamicValue` navigation. Both the Chat (`prompt_tokens`/`completion_tokens`) and the
  * Responses (`input_tokens`/`output_tokens`) naming conventions are modeled; the nested `*_tokens_details` blocks
  * carry the provider-specific extras. `toLmUsage` reconciles all of this into the domain `LmUsage`. */
private[lm] final case class OpenAiUsage(
    promptTokens: Option[Long] = None,
    completionTokens: Option[Long] = None,
    totalTokens: Option[Long] = None,
    inputTokens: Option[Long] = None,
    outputTokens: Option[Long] = None,
    promptTokensDetails: Option[OpenAiTokenDetails] = None,
    completionTokensDetails: Option[OpenAiTokenDetails] = None
) derives Schema:

  /** `totalTokens` falls back to prompt + completion when absent. `extras` is the union of the prompt- and
    * completion-side detail breakdowns (a category appearing on both sides, e.g. `audio_tokens`, is summed). */
  def toLmUsage: LmUsage =
    val prompt = promptTokens.orElse(inputTokens).getOrElse(0L)
    val completion = completionTokens.orElse(outputTokens).getOrElse(0L)
    val extras = mergeCounts(
      promptTokensDetails.map(_.toCategoryCounts).getOrElse(Map.empty),
      completionTokensDetails.map(_.toCategoryCounts).getOrElse(Map.empty)
    )
    LmUsage(
      totalTokens = totalTokens.getOrElse(prompt + completion),
      promptTokens = prompt,
      completionTokens = completion,
      extras = extras
    )

  private def mergeCounts(
      a: Map[TokenCategory, Long],
      b: Map[TokenCategory, Long]
  ): Map[TokenCategory, Long] =
    b.foldLeft(a) { case (acc, (category, value)) => acc.updated(category, acc.getOrElse(category, 0L) + value) }

/** One of OpenAI's `prompt_tokens_details` / `completion_tokens_details` sub-objects. */
private[lm] final case class OpenAiTokenDetails(
    cachedTokens: Option[Long] = None,
    audioTokens: Option[Long] = None,
    reasoningTokens: Option[Long] = None,
    acceptedPredictionTokens: Option[Long] = None,
    rejectedPredictionTokens: Option[Long] = None
) derives Schema:

  def toCategoryCounts: Map[TokenCategory, Long] =
    Vector(
      TokenCategory.Cached             -> cachedTokens,
      TokenCategory.Audio              -> audioTokens,
      TokenCategory.Reasoning          -> reasoningTokens,
      TokenCategory.AcceptedPrediction -> acceptedPredictionTokens,
      TokenCategory.RejectedPrediction -> rejectedPredictionTokens
    ).collect { case (category, Some(value)) => category -> value }.toMap

private[lm] object OpenAiUsage:
  private val codec = Schema[OpenAiUsage].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

  /** Decode a usage record (a subtree of an already-parsed response/chunk) into the typed model. Lenient: a usage
    * block that fails to decode yields an empty `OpenAiUsage` rather than failing the whole response. */
  def fromDynamic(usage: DynamicValue): OpenAiUsage =
    codec.decode(usage.toJson).getOrElse(OpenAiUsage())
