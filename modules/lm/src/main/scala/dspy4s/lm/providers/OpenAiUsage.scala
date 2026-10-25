package dspy4s.lm.providers

import dspy4s.core.contracts.{LmUsage, TokenCategory}
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.NameMapper
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

/** The token usage of an OpenAI reply. The parser of the stream chunks and the parser of a plain reply use it. A
  * derived `JsonCodec` decodes it: an absent field or a `null` gives `None`, and the codec ignores the keys that it
  * does not know. The record has the names of the chat usage (`prompt_tokens`, `completion_tokens`) and of the
  * Responses usage (`input_tokens`, `output_tokens`). The `*_tokens_details` blocks hold the extra counts of the
  * provider. [[toLmUsage]] gives the `LmUsage` of all these fields.
  *
  * The chat usage has the blocks `prompt_tokens_details` and `completion_tokens_details` (upstream `_usage_from_chat`
  * in `dspy/_vendor/lm15/providers/openai_chat.py`). The Responses usage has the blocks `input_tokens_details` and
  * `output_tokens_details` (upstream `OpenAILM.parse_response` and `_parse_single_stream_event` in
  * `dspy/_vendor/lm15/providers/openai.py`).
  */
private[lm] final case class OpenAiUsage(
    promptTokens           : Option[Long]               = None,
    completionTokens       : Option[Long]               = None,
    totalTokens            : Option[Long]               = None,
    inputTokens            : Option[Long]               = None,
    outputTokens           : Option[Long]               = None,
    promptTokensDetails    : Option[OpenAiTokenDetails] = None,
    completionTokensDetails: Option[OpenAiTokenDetails] = None,
    inputTokensDetails     : Option[OpenAiTokenDetails] = None,
    outputTokensDetails    : Option[OpenAiTokenDetails] = None
) derives Schema:

  /** When `totalTokens` is absent, the total is the prompt count plus the completion count. `extras` has the categories
    * of the prompt details and of the completion details. A category that is on the two sides, for example
    * `audio_tokens`, has the sum of the two counts. The prompt details are `prompt_tokens_details`, else
    * `input_tokens_details`. The completion details are `completion_tokens_details`, else `output_tokens_details`.
    */
  def toLmUsage: LmUsage =
    val prompt     = promptTokens.orElse(inputTokens).getOrElse(0L)
    val completion = completionTokens.orElse(outputTokens).getOrElse(0L)
    val extras     = mergeCounts(
      promptTokensDetails.orElse(inputTokensDetails).map(_.toCategoryCounts).getOrElse(Map.empty),
      completionTokensDetails.orElse(outputTokensDetails).map(_.toCategoryCounts).getOrElse(Map.empty)
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

/** One details object of an OpenAI usage: `prompt_tokens_details`, `completion_tokens_details`, `input_tokens_details`,
  * or `output_tokens_details`.
  */
private[lm] final case class OpenAiTokenDetails(
    cachedTokens            : Option[Long] = None,
    audioTokens             : Option[Long] = None,
    reasoningTokens         : Option[Long] = None,
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

  /** The usage of a usage record of a decoded reply or chunk. A record that does not decode gives an empty
    * `OpenAiUsage`, so that the reply does not fail.
    */
  def fromDynamic(usage: DynamicValue): OpenAiUsage =
    codec.decode(usage.toJson).getOrElse(OpenAiUsage())
