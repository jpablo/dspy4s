package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

/** The stream path of [[StreamingLanguageModelWrapper]] gives the same tokens as its call path. At the end of the
  * stream, the wrapper flushes the stream state of the adapter, as `call` does (upstream `StreamListener.flush` in
  * `dspy/streaming/streaming_listener.py`). The flush occurs at the `hasNext` that gives `false`, or at the `next` that
  * takes the last chunk.
  */
class StreamingWrapperFlushSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A stream model that replays the given chunks. */
  private final class Scripted(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "scripted"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks.iterator

  private val layout: SignatureLayout = SignatureLayout
    .create(name = "QA", inputFields = Vector(FieldSpec("question")), outputFields = Vector(FieldSpec("answer")))
    .fold(error => fail(error.message), identity)

  private val request = LmRequest(model = "scripted")

  /** A token as `(field, chunk, isLastChunk)`. */
  private type Token = (String, String, Boolean)

  /** The tokens before the failure of a stream of `[[ ## answer ## ]]\nPar`. The text `Par` cannot become a marker, so
    * the stream emits it at once. A flush would add a last chunk of the field.
    */
  private val beforeFailure: Vector[Token] = Vector(("answer", "Par", false))

  /** A stream model whose stream gives the chunks of `chunks`, then throws a non-fatal exception at the next read. */
  private final class Breaking(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "breaking"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      chunks.iterator ++ Iterator.continually[LmChunk](throw IllegalStateException("the stream broke"))

  /** The tokens that `run` gives to the queue, in the predict context of the predictor `qa` with the chat adapter. */
  private def tokens(chunks: Vector[LmChunk])(run: StreamingLanguageModelWrapper => RuntimeContext ?=> Any)
      : Vector[Token] =
    tokensOf(Scripted(chunks))(run)

  /** [[tokens]] for the model `delegate`. */
  private def tokensOf(delegate: StreamingLanguageModel)(run: StreamingLanguageModelWrapper => RuntimeContext ?=> Any)
      : Vector[Token] =
    val queue            = StreamingQueue[StreamEvent](256)
    val wrapper          = StreamingLanguageModelWrapper(delegate, queue, adapter = Some(ChatAdapter()))
    given RuntimeContext = RuntimeEnvironment.current
    val _                = ActivePredictContext.withActive("qa", layout)(run(wrapper))
    queue.close()
    val events = queue.asIterator
    val result = Vector.newBuilder[Token]
    while events.hasNext do
      events.next() match
        case TokenEvent("qa", field, chunk, isLast, _) => result += ((field, chunk, isLast))
        case other                                     => fail(s"unexpected event: $other")
    result.result()

  private def streamed(chunks: Vector[LmChunk]): Vector[Token] =
    tokens(chunks)(wrapper => wrapper.stream(request).toVector)

  private def called(chunks: Vector[LmChunk]): Vector[Token] =
    tokens(chunks)(wrapper => wrapper.call(request))

  /** The tokens of the stream path for a caller that uses only `next`: one `next` for each chunk of the delegate. */
  private def nextOnly(chunks: Vector[LmChunk]): Vector[Token] =
    tokens(chunks) { wrapper =>
      val stream = wrapper.stream(request)
      chunks.foreach(_ => stream.next())
    }

  test("the stream path gives the held end of a field at the end of the stream, as the call path does") {
    val chunks  = Vector(LmChunk(text = "[[ ## answer ## ]]\nPar"), LmChunk(text = "is", finishReason = Some("stop")))
    val viaCall = called(chunks)
    assertEquals(viaCall.map(_._2).mkString, "Paris")
    assertEquals(viaCall.lastOption.map(_._3), Some(true))
    assertEquals(streamed(chunks), viaCall)
  }

  test("the stream path gives the value of the single-output fallback, as the call path does") {
    val chunks  = Vector(LmChunk(text = "Par"), LmChunk(text = "is.", finishReason = Some("stop")))
    val viaCall = called(chunks)
    assertEquals(viaCall, Vector(("answer", "Paris.", true)))
    assertEquals(streamed(chunks), viaCall)
  }

  test("a failure chunk ends the stream path with no flush, as the call path does") {
    val chunks = Vector(LmChunk(text = "[[ ## answer ## ]]\nPar"), LmChunk.failure(RuntimeError("test", "boom")))
    assertEquals(called(chunks), beforeFailure)
    assertEquals(streamed(chunks), beforeFailure)
  }

  test("a cache hit gives no token on the stream path, as on the call path") {
    val chunks = Vector(LmChunk.cached(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis")))))
    assertEquals(called(chunks), Vector.empty)
    assertEquals(streamed(chunks), Vector.empty)
  }

  test("a caller that uses only next gets the flush when next takes the last chunk") {
    val usage  = LmChunk(usage = Some(LmUsage(totalTokens = 3L)))
    val header = LmChunk(text = "[[ ## answer ## ]]\nPar")
    val cases  = Vector(
      Vector(header, LmChunk(text = "is", finishReason = Some("stop"))),
      Vector(header, LmChunk(text = "is", finishReason = Some("stop")), usage),
      Vector(LmChunk(text = "Par"), LmChunk(text = "is.", finishReason = Some("stop"))),
      Vector(LmChunk(text = "Par"), LmChunk(text = "is."))
    )
    cases.foreach { chunks =>
      val viaCall = called(chunks)
      assertEquals(viaCall.lastOption.map(_._3), Some(true), chunks.toString)
      assertEquals(nextOnly(chunks), viaCall, chunks.toString)
    }
  }

  test("a delegate stream that throws ends the stream path with no flush, as the call path does") {
    val chunks = Vector(LmChunk(text = "[[ ## answer ## ]]\nPar"))
    assertEquals(tokensOf(Breaking(chunks))(wrapper => wrapper.call(request)), beforeFailure)
    assertEquals(tokensOf(Breaking(chunks))(wrapper => wrapper.stream(request).toVector), beforeFailure)
  }

  test("a close of the stream before its end gives no flush") {
    val chunks = Vector(LmChunk(text = "Par"), LmChunk(text = "is.", finishReason = Some("stop")))
    val result = tokens(chunks) { wrapper =>
      wrapper.stream(request) match
        case stream: ClosableIterator[?] =>
          val _ = stream.next()
          stream.close()
        case other => fail(s"the stream of the wrapper has no close: $other")
    }
    assertEquals(result, Vector.empty)
  }
