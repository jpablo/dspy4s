package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** A streamed call goes through the cache of [[ManagedLanguageModel]], and a cache hit sends no token to the listeners
  * (upstream `dspy/clients/execution.py` `execute`: `_cached` returns before the stream).
  */
class StreamingCacheSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A stream model that gives `chunks` for each stream request, and counts the requests. */
  private final class CountingStreams(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    val streams               = AtomicInteger(0)
    override val id: String   = "counting"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val _ = streams.incrementAndGet()
      chunks.iterator

  private val request = LmRequest(
    model = "gpt-test",
    messages = Vector(Message(role = MessageRole.User, text = Some("hello")))
  )

  private val hello: Vector[LmChunk] = Vector(LmChunk(text = "Hel"), LmChunk(text = "lo", finishReason = Some("stop")))

  private def cachedModel(delegate: StreamingLanguageModel): ManagedLanguageModel =
    ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(CacheCapacity(16))))

  /** The response of one wrapper call, and the token events that the call gave. */
  private def callOnce(model: ManagedLanguageModel)(using
      RuntimeContext
  ): (Either[DspyError, LmResponse], Vector[TokenEvent]) =
    val queue   = StreamingQueue[StreamEvent]()
    val wrapper = StreamingLanguageModelWrapper(model, queue)
    val result  = wrapper.call(request)
    queue.close()
    (result, queue.asIterator.toVector.collect { case token: TokenEvent => token })

  test("a cache hit under the stream wrapper gives the cached response, and the listeners get no token") {
    val delegate         = CountingStreams(hello)
    val model            = cachedModel(delegate)
    given RuntimeContext = RuntimeEnvironment.current

    val (first, firstTokens) = callOnce(model)
    assertEquals(first.map(_.outputs.map(_.text)), Right(Vector("Hello")))
    assert(firstTokens.nonEmpty)

    val (second, secondTokens) = callOnce(model)
    assertEquals(second.map(_.outputs.map(_.text)), Right(Vector("Hello")))
    assertEquals(second.map(_.cacheHit), Right(true))
    assertEquals(secondTokens, Vector.empty)
    assertEquals(delegate.streams.get(), 1)
  }

  test("a stream with no end gives the stream assembly error to the caller of the wrapper") {
    val model            = ManagedLanguageModel(delegate = CountingStreams(Vector(LmChunk(text = "Hel"))))
    given RuntimeContext = RuntimeEnvironment.current

    val (result, _) = callOnce(model)
    assert(result.left.exists(_.isInstanceOf[LmStreamAssemblyError]), result.toString)
  }

  test("under streamify, a run that the cache answers gives the prediction and no token") {
    val reply = Vector(
      LmChunk(text = "[[ ## answer ## ]]\nParis\n\n"),
      LmChunk(text = "[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val delegate = CountingStreams(reply)
    val model    = cachedModel(delegate)
    val program  = DynamicPredict(layout = SignatureDsl.parse("question -> answer").toOption.get)

    def run(): Vector[StreamEvent] =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        val stream           = Streamify.streamify(program)(DynamicValues.record("question" := "Capital of France?"))
        val events           = ArrayBuffer.empty[StreamEvent]
        while stream.hasNext do events += stream.next()
        events.toVector
      }

    val first = run()
    assert(first.exists(_.isInstanceOf[TokenEvent]), first.toString)
    val second = run()
    assert(!second.exists(_.isInstanceOf[TokenEvent]), second.toString)
    assert(second.exists(_.isInstanceOf[PredictionEvent]), second.toString)
    assertEquals(delegate.streams.get(), 1)
  }
