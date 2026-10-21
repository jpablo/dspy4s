package dspy4s.streaming

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite

/** The stream of the wrapper keeps the `close` of the stream of its delegate, so a caller that stops early releases the
  * connection (upstream closes each stream with `closing_stream`).
  */
class StreamingWrapperCloseSuite extends FunSuite:

  /** A stream of two chunks that records its close. */
  private final class Chunks extends ClosableIterator[LmChunk]:
    var closed                    = false
    private var remaining         = Vector(LmChunk(text = "Hel"), LmChunk(text = "lo", finishReason = Some("stop")))
    override def hasNext: Boolean = remaining.nonEmpty
    override def next(): LmChunk  =
      val chunk = remaining.head
      remaining = remaining.tail
      chunk
    override def close(): Unit = closed = true

  /** A stream model that gives `chunks` for each stream request. */
  private final class Streams(chunks: ClosableIterator[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "streams"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks

  test("a close of the stream of the wrapper closes the stream of the delegate") {
    given RuntimeContext = RuntimeContext()
    val source           = Chunks()
    val wrapper          = StreamingLanguageModelWrapper(Streams(source), StreamingQueue[StreamEvent]())
    wrapper.stream(LmRequest(model = "streams")) match
      case stream: ClosableIterator[?] =>
        assert(stream.hasNext)
        val _ = stream.next()
        stream.close()
        assert(source.closed, "the stream of the delegate is open after the close of the wrapper stream")
      case other => fail(s"the stream of the wrapper has no close: $other")
  }
