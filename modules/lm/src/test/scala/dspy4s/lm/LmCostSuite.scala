package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmCost
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmPrice
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.LmCacheRegistry
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The advisory cost estimate of an LM call from the prices that the user declares (decision L10, PORT_GAPS G-27;
  * upstream `estimate_cost` in `dspy/clients/costs.py` and the `cost` keys of the history entry in
  * `dspy/clients/execution.py`).
  */
class LmCostSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  private val price = LmPrice(inputPerMillion = 0.15, outputPerMillion = 0.60)

  private def usage(prompt: Long, completion: Long, extras: (TokenCategory, Long)*): Option[LmUsage] =
    Some(LmUsage(
      totalTokens = prompt + completion,
      promptTokens = prompt,
      completionTokens = completion,
      extras = extras.toMap
    ))

  private def usd(cost: LmCost): Double =
    cost match
      case LmCost.Estimate(value, _) => value
      case LmCost.Unknown(reason)    => fail(s"expected an estimate, got the reason: $reason")

  private def assertUsd(cost: LmCost, expected: Double): Unit =
    assertEqualsDouble(usd(cost), expected, 1e-15)

  // ── Fixtures of the history tests ─────────────────────────────────────────────────────────────────────────────────────────

  private val response = LmResponse(outputs = Vector(LmOutput(text = "ok")), usage = usage(1000, 500))
  private val request  = LmRequest(model = "gpt-test")

  private final class Fixed(result: Either[DspyError, LmResponse]) extends LanguageModel:
    override val id: String                                                                    = "fixed"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] = result

  private final class Streams(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String                                                                      = "fixed"
    override val mode: LmMode                                                                    = LmMode.Chat
    override def call(request  : LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] = Right(response)
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk]             = chunks.iterator

  private def lmEntries: Vector[HistoryEntry] = RuntimeEnvironment.current.history.filter(_.component == "lm:fixed")

  private def keys(entry: HistoryEntry): Vector[String] = entry.payload.fields.toVector.map(_._1)

  private def costOf(entry: HistoryEntry): Option[Double] =
    DynamicValues.recordGet(entry.payload, "cost") match
      case Some(DynamicValue.Primitive(PrimitiveValue.Double(value))) => Some(value)
      case Some(_: DynamicValue.Null.type)                            => None
      case other                                                      => fail(s"the cost is not a number or null: $other")

  private def detailsOf(entry: HistoryEntry): Map[String, String] =
    DynamicValues.recordGet(entry.payload, "cost_details") match
      case Some(record: DynamicValue.Record) => DynamicValues.recordEntries(record).map {
          case (key, DynamicValue.Primitive(PrimitiveValue.String(text))) => key -> text
          case (key, other)                                               => fail(s"$key: $other")
        }.toMap
      case other => fail(s"the cost details are not an object: $other")

  private val expectedUsd = 1000 * 0.15e-6 + 500 * 0.60e-6

  test("the cost of the input and the output tokens comes from the declared price") {
    assertUsd(LmCost.of("m", Some(price), usage(1000, 500)), 1000 * 0.15e-6 + 500 * 0.60e-6)
    LmCost.of("m", Some(price), usage(1000, 500)) match
      case LmCost.Estimate(_, model) => assertEquals(model, "m")
      case other                     => fail(s"expected an estimate, got $other")
  }

  test("a cached token has the cached price, else the input price") {
    val cached = usage(1000, 500, TokenCategory.Cached -> 400L)
    assertUsd(
      LmCost.of("m", Some(price.copy(cachedInputPerMillion = Some(0.075))), cached),
      600 * 0.15e-6 + 400 * 0.075e-6 + 500 * 0.60e-6
    )
    assertUsd(LmCost.of("m", Some(price), cached), 1000 * 0.15e-6 + 500 * 0.60e-6)
  }

  test("a reasoning token has the reasoning price, else the output price") {
    val reasoning = usage(1000, 500, TokenCategory.Reasoning -> 200L)
    assertUsd(
      LmCost.of("m", Some(price.copy(reasoningPerMillion = Some(1.0))), reasoning),
      1000 * 0.15e-6 + 300 * 0.60e-6 + 200 * 1.0e-6
    )
    assertUsd(LmCost.of("m", Some(price), reasoning), 1000 * 0.15e-6 + 500 * 0.60e-6)
  }

  test("a call with no tokens costs nothing") {
    assertUsd(LmCost.of("m", Some(price), usage(0, 0)), 0.0)
  }

  test("an unknown price or usage gives no estimate, not zero") {
    assertEquals(LmCost.of("m", None, usage(10, 10)), LmCost.Unknown(LmCost.NoPrice))
    assertEquals(LmCost.of("m", Some(price), None), LmCost.Unknown(LmCost.NoUsage))
    assertEquals(
      LmCost.of("m", Some(price), usage(10, 10, TokenCategory.Cached -> 11L)),
      LmCost.Unknown(LmCost.Inseparable)
    )
    assertEquals(
      LmCost.of("m", Some(price), usage(10, 10, TokenCategory.Reasoning -> 11L)),
      LmCost.Unknown(LmCost.Inseparable)
    )
  }

  test("a price must be a finite number that is not negative") {
    val _ = intercept[IllegalArgumentException](LmPrice(inputPerMillion = -1.0, outputPerMillion = 1.0))
    val _ = intercept[IllegalArgumentException](LmPrice(inputPerMillion = 1.0, outputPerMillion = Double.NaN))
    val _ = intercept[IllegalArgumentException](
      LmPrice(inputPerMillion = 1.0, outputPerMillion = 1.0, cachedInputPerMillion = Some(Double.PositiveInfinity))
    )
  }

  test("the history entry of a call has the cost and its details after the usage") {
    given RuntimeContext = RuntimeEnvironment.current
    assert(ManagedLanguageModel(delegate = Fixed(Right(response)), price = Some(price)).call(request).isRight)
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEquals(
      keys(entry),
      Vector(
        "model",
        "model_type",
        "cache_hit",
        "messages",
        "kwargs",
        "outputs",
        "usage",
        "cost",
        "cost_details",
        "response_model",
        "uuid"
      )
    )
    assertEqualsDouble(costOf(entry).getOrElse(fail("no cost")), expectedUsd, 1e-15)
    assertEquals(
      detailsOf(entry),
      Map("kind" -> "estimate", "currency" -> "USD", "model" -> "gpt-test", "source" -> "declared")
    )
  }

  test("the history entry of a model with no price has a null cost and the reason") {
    given RuntimeContext = RuntimeEnvironment.current
    assert(ManagedLanguageModel(delegate = Fixed(Right(response))).call(request).isRight)
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEquals(costOf(entry), None)
    assertEquals(detailsOf(entry), Map("kind" -> "unknown", "reason" -> LmCost.NoPrice))
  }

  test("a cache hit keeps the cost of the cached response") {
    given RuntimeContext = RuntimeEnvironment.current
    val model            = ManagedLanguageModel(
      delegate = Fixed(Right(response)),
      cache = Some(new InMemoryLmCache(CacheCapacity(4))),
      price = Some(price)
    )
    assert(model.call(request).isRight)
    assert(model.call(request).isRight)
    val costs = lmEntries.map(costOf)
    assertEquals(costs.size, 2)
    costs.foreach(cost => assertEqualsDouble(cost.getOrElse(fail("no cost")), expectedUsd, 1e-15))
  }

  test("the history entry of a streamed call has the cost of the usage of the chunks") {
    given RuntimeContext = RuntimeEnvironment.current
    val chunks           = Vector(
      LmChunk(text = "ok", finishReason = Some("stop")),
      LmChunk(usage = usage(1000, 500))
    )
    val _     = ManagedLanguageModel(delegate = Streams(chunks), price = Some(price)).stream(request).toVector
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEqualsDouble(costOf(entry).getOrElse(fail("no cost")), expectedUsd, 1e-15)
  }

  test("the history entry of a failure has no cost") {
    given RuntimeContext = RuntimeEnvironment.current
    assert(ManagedLanguageModel(
      delegate = Fixed(Left(LmAuthError("bad key"))),
      price = Some(price)
    ).call(request).isLeft)
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assert(!keys(entry).contains("cost"), keys(entry).toString)
  }

  // ── Nested usage trackers (upstream bb8ba59c0) ──────────────────────────────────────────────────────────────

  test("the usage of a call in a nested tracker goes to the outer tracker too, and a later call only to the outer") {
    given RuntimeContext = RuntimeEnvironment.current
    val model            = ManagedLanguageModel(delegate = Fixed(Right(response)))
    UsageTracking.withNewTracker { outer =>
      val inner = UsageTracking.withNewTracker { inner =>
        assert(model.call(request).isRight)
        inner
      }
      assert(model.call(request).isRight)
      assertEquals(inner.usageData.get("gpt-test").map(_.size), Some(1))
      assertEquals(outer.usageData.get("gpt-test").map(_.size), Some(2))
      assertEquals(outer.totalUsage.get("gpt-test").map(_.promptTokens), Some(2000L))
    }
  }
