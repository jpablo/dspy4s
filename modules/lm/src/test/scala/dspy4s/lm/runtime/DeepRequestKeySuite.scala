package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.util.concurrent.atomic.AtomicInteger

/** A request whose cache key the cache cannot build: a value in the request is nested too deep for the stack of the
  * thread. Upstream `Cache.get` and `Cache.put` log the failure of `cache_key` and go on with no cache
  * (`dspy/clients/cache.py`), so the model gets the call.
  */
class DeepRequestKeySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val response = LmResponse(outputs = Vector(LmOutput(text = "Paris")))

  private val plain = LmRequest(model = "gpt-test", messages = Vector(Message(MessageRole.User, text = Some("Hi"))))

  /** A record nested `depth` levels deep. The function builds it with a loop, so it does not use the stack. */
  private def nested(depth: Int): DynamicValue.Record = (1 until depth).foldLeft(DynamicValue.Record(Chunk("leaf" ->
    DynamicValue.Primitive(PrimitiveValue.Int(1))))) {
    (inner, _) => DynamicValue.Record(Chunk("a" -> inner))
  }

  /** A request with an assistant message whose tool call has arguments nested 100,000 levels deep. */
  private lazy val deep: LmRequest = plain.copy(messages =
    plain.messages :+ Message(MessageRole.Assistant, toolCalls = Vector(ToolCall("deep", nested(100000))))
  )

  /** The value of `body`, or the simple name of the `StackOverflowError` that it throws. `intercept` catches only
    * non-fatal errors, so the function catches the error itself.
    */
  private def noOverflow[A](body: => A): Either[String, A] =
    try Right(body)
    catch case error: StackOverflowError => Left(error.getClass.getSimpleName)

  /** Answers each call with `response`, and counts the calls. */
  private final class Counting extends LanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val id: String   = "counting"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(response)

  test("the key of a request with no key is a ValidationError, and the next key works") {
    RequestHash.forRequest(deep) match
      case Left(error: ValidationError) => assert(error.message.contains("nested too deep"), error.message)
      case Left(other)                  => fail(s"expected a ValidationError, got $other")
      case Right(key)                   => fail(s"expected a ValidationError, got the key $key")
    assert(RequestHash.forRequest(plain).isRight)
  }

  test("the managed model calls the model for each call of a request with no key, with no cache read or write") {
    given RuntimeContext = RuntimeEnvironment.current
    val model            = Counting()
    val memory           = new InMemoryLmCache(CacheCapacity(8))
    val managed          = ManagedLanguageModel(model, cache = Some(memory))
    val first            = noOverflow(managed.call(deep).map(_.outputs.map(_.text)))
    val second           = noOverflow(managed.call(deep).map(_.outputs.map(_.text)))
    assertEquals(first, Right(Right(Vector("Paris"))))
    assertEquals(second, Right(Right(Vector("Paris"))))
    assertEquals(model.calls.get(), 2, "each call must reach the model, because no entry can serve it")
    assertEquals(memory.size, 0)

    // A request with a key still uses the cache.
    assert(managed.call(plain).isRight)
    assertEquals(managed.call(plain).map(_.cacheHit), Right(true))
    assertEquals(model.calls.get(), 3)
  }

  test("the managed stream of a request with no key calls the model, with no cache write") {
    given RuntimeContext = RuntimeEnvironment.current
    val model            = Counting()
    val memory           = new InMemoryLmCache(CacheCapacity(8))
    val managed          = ManagedLanguageModel(model, cache = Some(memory))
    val texts            = noOverflow(managed.stream(deep).map(_.text).mkString)
    assertEquals(texts, Right("Paris"))
    assertEquals(model.calls.get(), 1)
    assertEquals(memory.size, 0)
  }
