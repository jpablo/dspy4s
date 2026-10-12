package dspy4s.lm

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.lm.providers.DynamicJson
import dspy4s.lm.runtime.{CacheCapacity, InMemoryLmCache, ManagedLanguageModel, ProviderLanguageModel}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The cache key must cover the request that the provider sends. A provider merges its default options into each
  * request, so two models that share an id but have different defaults must not share cache entries (PORT_GAPS G-24).
  */
class LmCacheKeySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val request = LmRequest(
    model = "shared-model",
    mode = LmMode.Chat,
    messages = Vector(Message(role = MessageRole.User, text = Some("What is the capital of Belgium?")))
  )

  private def chatPayload(text: String): DynamicValue =
    DynamicJson
      .decode(s"""{"choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}""")
      .toOption
      .get

  /** A provider that records each payload that reaches the wire. */
  private def provider(
      sent          : ArrayBuffer[DynamicValue],
      reply         : String,
      defaultOptions: DynamicValue.Record
  ): ProviderLanguageModel =
    ProviderLanguageModel(
      id = "shared-model",
      mode = LmMode.Chat,
      invoke = payload =>
        sent += payload
        Right(chatPayload(reply))
      ,
      defaultOptions = defaultOptions
    )

  /** A model that declares default options and records each request that it receives. */
  private final class RecordingLm(override val defaultOptions: DynamicValue.Record) extends LanguageModel:
    val calls: ArrayBuffer[LmRequest] = ArrayBuffer.empty
    override val id: String           = "shared-model"
    override val mode: LmMode         = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += request
      Right(LmResponse(outputs = Vector(LmOutput(text = "ok"))))

  test("two models with different default options do not share a cache entry") {
    given RuntimeContext = RuntimeEnvironment.current
    val cache            = new InMemoryLmCache(CacheCapacity(16))
    val sent             = ArrayBuffer.empty[DynamicValue]

    val cold = ManagedLanguageModel(
      delegate = provider(sent, reply = "cold", DynamicValues.record("temperature" := 0.0)),
      cache = Some(cache)
    )
    val hot = ManagedLanguageModel(
      delegate = provider(sent, reply = "hot", DynamicValues.record("temperature" := 1.0)),
      cache = Some(cache)
    )

    assertEquals(cold.call(request).map(_.outputs.map(_.text)), Right(Vector("cold")))

    val second = hot.call(request)
    assertEquals(second.map(_.cacheHit), Right(false), "a different temperature must not be a cache hit")
    assertEquals(second.map(_.outputs.map(_.text)), Right(Vector("hot")))
    assertEquals(sent.size, 2, "the second model must reach the provider")
  }

  test("the same default options still give a cache hit") {
    given RuntimeContext = RuntimeEnvironment.current
    val cache            = new InMemoryLmCache(CacheCapacity(16))
    val sent             = ArrayBuffer.empty[DynamicValue]
    val defaults         = DynamicValues.record("temperature" := 0.0)

    val first  = ManagedLanguageModel(delegate = provider(sent, "one", defaults), cache = Some(cache))
    val second = ManagedLanguageModel(delegate = provider(sent, "two", defaults), cache = Some(cache))

    assertEquals(first.call(request).map(_.cacheHit), Right(false))
    assertEquals(second.call(request).map(_.cacheHit), Right(true))
    assertEquals(sent.size, 1)
  }

  test("a request option that repeats the default gives the same entry as the default alone") {
    given RuntimeContext = RuntimeEnvironment.current
    val cache            = new InMemoryLmCache(CacheCapacity(16))
    val sent             = ArrayBuffer.empty[DynamicValue]
    val model            = ManagedLanguageModel(
      delegate = provider(sent, "one", DynamicValues.record("temperature" := 0.0)),
      cache = Some(cache)
    )

    assertEquals(model.call(request).map(_.cacheHit), Right(false))
    val explicit = request.copy(options = DynamicValues.record("temperature" := 0.0))
    assertEquals(model.call(explicit).map(_.cacheHit), Right(true), "the merged request is the same")
    assertEquals(sent.size, 1)
  }

  test("a request option that overrides the default gives the entry of the merged request") {
    given RuntimeContext = RuntimeEnvironment.current
    val cache            = new InMemoryLmCache(CacheCapacity(16))
    val sent             = ArrayBuffer.empty[DynamicValue]

    val hot = ManagedLanguageModel(
      delegate = provider(sent, reply = "hot", DynamicValues.record("temperature" := 1.0)),
      cache = Some(cache)
    )
    val cold = ManagedLanguageModel(
      delegate = provider(sent, reply = "cold", DynamicValues.record("temperature" := 0.0)),
      cache = Some(cache)
    )

    assertEquals(hot.call(request).map(_.cacheHit), Right(false))
    // The option of the request wins, so `cold` sends the same payload as `hot` did.
    val overridden = request.copy(options = DynamicValues.record("temperature" := 1.0))
    assertEquals(cold.call(overridden).map(_.cacheHit), Right(true))
    // Without the override, `cold` sends its own default.
    assertEquals(cold.call(request).map(_.cacheHit), Right(false))
    assertEquals(sent.size, 2)
  }

  test("the key keeps rolloutId, and the provider payload does not get it") {
    given RuntimeContext = RuntimeEnvironment.current
    val sent             = ArrayBuffer.empty[DynamicValue]
    val model            = ManagedLanguageModel(
      delegate = provider(sent, "one", DynamicValues.record("temperature" := 1.0)),
      cache = Some(new InMemoryLmCache(CacheCapacity(16)))
    )

    assertEquals(model.call(request.copy(rolloutId = Some(1))).map(_.cacheHit), Right(false))
    assertEquals(model.call(request.copy(rolloutId = Some(1))).map(_.cacheHit), Right(true))
    assertEquals(model.call(request.copy(rolloutId = Some(2))).map(_.cacheHit), Right(false))

    assertEquals(sent.size, 2)
    sent.foreach { payload =>
      assertEquals(DynamicJson.field(payload, "temperature"), Some(DynamicValues.fromAny(1.0)))
      assertEquals(DynamicJson.field(payload, "rollout_id"), None)
      assertEquals(DynamicJson.field(payload, "rolloutId"), None)
    }
  }

  test("the delegate receives the original request, not the key request") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = new RecordingLm(DynamicValues.record("temperature" := 0.0))
    val model            = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(CacheCapacity(16))))

    assertEquals(model.call(request).map(_.cacheHit), Right(false))
    assertEquals(delegate.calls.toVector, Vector(request))
  }

  test("a managed model forwards the default options, also through a second wrapper") {
    given RuntimeContext = RuntimeEnvironment.current
    val cache            = new InMemoryLmCache(CacheCapacity(16))
    val sent             = ArrayBuffer.empty[DynamicValue]
    val defaults         = DynamicValues.record("temperature" := 0.0)

    // The inner wrappers have no cache. The outer wrappers must still see the defaults of the provider.
    val cold = ManagedLanguageModel(
      delegate = ManagedLanguageModel(delegate = provider(sent, "cold", defaults)),
      cache = Some(cache)
    )
    val hot = ManagedLanguageModel(
      delegate = ManagedLanguageModel(delegate = provider(sent, "hot", DynamicValues.record("temperature" := 1.0))),
      cache = Some(cache)
    )

    assertEquals(cold.defaultOptions, defaults)
    assertEquals(cold.call(request).map(_.cacheHit), Right(false))
    assertEquals(hot.call(request).map(_.cacheHit), Right(false))
    assertEquals(sent.size, 2)
  }
