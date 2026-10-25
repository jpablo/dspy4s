package dspy4s.lm.providers

import dspy4s.core.contracts.{DynamicValues, LmUsage, TokenCategory}
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

/** The token usage of an OpenAI reply. The parser of the stream chunks and the parser of a plain reply use it. The
  * typed codec of a stream chunk ([[OpenAiStreamChunk]]) decodes it with the derived `Schema`, and
  * [[OpenAiUsage.fromDynamic]] reads it from a decoded record. The record has the names of the chat usage
  * (`prompt_tokens`, `completion_tokens`) and of the Responses usage (`input_tokens`, `output_tokens`). The
  * `*_tokens_details` blocks hold the extra counts of the provider. [[toLmUsage]] gives the `LmUsage` of all these
  * fields.
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

  /** The usage of a usage record of a decoded reply or chunk. The function reads each count field by itself, so a field
    * of the wrong type gives no count for that field only, and the other counts stay. A count is a JSON number. A
    * string, also the text of a number, is not a count, because the wire sends a count as a number. A value that is not
    * an object gives an empty `OpenAiUsage`, and the reply does not fail.
    */
  def fromDynamic(usage: DynamicValue): OpenAiUsage =
    def details(key: String): Option[OpenAiTokenDetails] =
      DynamicValues.recordGet(usage, key).flatMap(DynamicValues.asRecord).map(OpenAiTokenDetails.fromDynamic)
    OpenAiUsage(
      promptTokens = count(usage, "prompt_tokens"),
      completionTokens = count(usage, "completion_tokens"),
      totalTokens = count(usage, "total_tokens"),
      inputTokens = count(usage, "input_tokens"),
      outputTokens = count(usage, "output_tokens"),
      promptTokensDetails = details("prompt_tokens_details"),
      completionTokensDetails = details("completion_tokens_details"),
      inputTokensDetails = details("input_tokens_details"),
      outputTokensDetails = details("output_tokens_details")
    )

  /** The count at `key` of `record`: a JSON number, as a `Long`. An absent key, a null, a string, and each other value
    * that is not a number give `None`.
    */
  private[providers] def count(record: DynamicValue, key: String): Option[Long] =
    DynamicValues.recordGet(record, key).filter(DynamicValues.asString(_).isEmpty).flatMap(DynamicValues.asLong)

private[lm] object OpenAiTokenDetails:

  /** The details of a details record, with the count rule of [[OpenAiUsage.fromDynamic]]. */
  def fromDynamic(details: DynamicValue): OpenAiTokenDetails =
    OpenAiTokenDetails(
      cachedTokens = OpenAiUsage.count(details, "cached_tokens"),
      audioTokens = OpenAiUsage.count(details, "audio_tokens"),
      reasoningTokens = OpenAiUsage.count(details, "reasoning_tokens"),
      acceptedPredictionTokens = OpenAiUsage.count(details, "accepted_prediction_tokens"),
      rejectedPredictionTokens = OpenAiUsage.count(details, "rejected_prediction_tokens")
    )
