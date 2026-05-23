package dspy4s.lm.contracts

import dspy4s.core.contracts.RuntimeContext

/** A single tool-call delta extracted from one streaming LM chunk. OpenAI emits
  * `id`/`function.name` on the first delta for an index and `function.arguments`
  * fragments on subsequent deltas; consumers accumulate by `index`.
  */
final case class LmToolCallDelta(
    index: Int,
    id: Option[String] = None,
    name: Option[String] = None,
    argumentsFragment: Option[String] = None
)

final case class LmChunk(
    text: String = "",
    finishReason: Option[String] = None,
    usage: Option[LmUsage] = None,
    toolCalls: Vector[LmToolCallDelta] = Vector.empty,
    raw: Option[Any] = None
):
  def isFinal: Boolean = finishReason.isDefined

trait StreamingLanguageModel extends LanguageModel:
  def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk]
