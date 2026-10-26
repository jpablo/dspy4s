package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite

/** The usage of a streamed call that fails (upstream `_account_failed_call`): the call of the wrapper gives the error
  * of the stream with the usage of the stream. The test failed before the change.
  */
class StreamingFailedUsageSuite extends FunSuite:

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  /** A stream model that gives `chunks` for each stream request. */
  private final class Streams(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "streams"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks.iterator

  test("the call of the wrapper gives the usage of a stream that fails after its usage chunk") {
    given RuntimeContext = RuntimeContext()
    val chunks           = Vector(
      LmChunk(text = "Hel"),
      LmChunk(usage = Some(usage)),
      LmChunk.failure(LmTransportError("the connection closed"))
    )
    val wrapper = StreamingLanguageModelWrapper(Streams(chunks), StreamingQueue[StreamEvent]())
    val error   = wrapper.call(LmRequest(model = "streams")).left.getOrElse(fail("the call must fail"))
    assertEquals(LmError.usage(error), Some(usage))
  }
