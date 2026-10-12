package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, LmUsage, RuntimeContext}

/** A single tool-call delta extracted from one streaming LM chunk. OpenAI emits `id`/`function.name` on the first delta
  * for an index and `function.arguments` fragments on subsequent deltas; consumers accumulate by `index`.
  */
final case class LmToolCallDelta(
    index            : Int,
    id               : Option[String] = None,
    name             : Option[String] = None,
    argumentsFragment: Option[String] = None
)

/** One chunk of a streamed LM call.
  *
  * A producer reports a failure as a terminal chunk, not as an exception. That chunk has
  * `finishReason = Some("error")`, the message under `raw` (`Map("error" -> message)`), and the typed failure in
  * `error`. Build it with [[LmChunk.failure]].
  */
final case class LmChunk(
    text        : String                  = "",
    finishReason: Option[String]          = None,
    usage       : Option[LmUsage]         = None,
    toolCalls   : Vector[LmToolCallDelta] = Vector.empty,
    raw         : Option[Any]             = None,
    error       : Option[DspyError]       = None
):
  def isFinal: Boolean = finishReason.isDefined

object LmChunk:

  /** The terminal chunk that reports `error`. It keeps the type of the failure, so a consumer can return the same
    * `DspyError` that the provider produced.
    */
  def failure(error: DspyError): LmChunk =
    LmChunk(finishReason = Some("error"), raw = Some(Map("error" -> error.message)), error = Some(error))

trait StreamingLanguageModel extends LanguageModel:
  def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk]
