package dspy4s.lm.contracts

import dspy4s.core.contracts.RuntimeContext

final case class LmChunk(
    text: String = "",
    finishReason: Option[String] = None,
    usage: Option[LmUsage] = None,
    raw: Option[Any] = None
):
  def isFinal: Boolean = finishReason.isDefined

trait StreamingLanguageModel extends LanguageModel:
  def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk]
