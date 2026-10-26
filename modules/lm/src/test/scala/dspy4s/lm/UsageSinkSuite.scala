package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.UsageScope
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite

/** One sink for the LM usage: the user tracker (`UsageTracking`) and the usage scope of a program call (`UsageScope`)
  * get each record from one record point, `LanguageModel.callAndRecordUsage`. The tests marked "guard" passed before
  * the change too. Each other test failed before it.
  */
class UsageSinkSuite extends FunSuite:

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  private val request: LmRequest = LmRequest(model = "test-model")

  /** A model with no managed wrapper. It answers each call with `usage`, and counts the calls. */
  private final class Plain extends LanguageModel:
    var calls                 = 0
    override val id: String   = "plain"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      Right(LmResponse(outputs = Vector(LmOutput(text = "Hello")), usage = Some(usage)))

  private def memory = Some(new InMemoryLmCache(CacheCapacity(16)))

  /** The result of `body`, the total of the user tracker, and the total of the usage scope around it. */
  private def measured[A](body: => A): (A, Map[String, LmUsage], Option[LmUsage]) =
    val ((value, tracked), scoped) = UsageScope.measure {
      UsageTracking.withNewTracker(tracker => (body, tracker.totalUsage))
    }
    (value, tracked, scoped)

  test("a model with no managed wrapper counts in the user tracker") {
    given RuntimeContext          = RuntimeContext()
    val (result, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(Plain(), request))
    assert(result.isRight, result.toString)
    assertEquals(tracked, Map("test-model" -> usage))
    assertEquals(scoped, Some(usage))
  }

  test("a direct call of a managed model counts in no sink") {
    given RuntimeContext     = RuntimeContext()
    val (_, tracked, scoped) = measured(ManagedLanguageModel(Plain()).call(request))
    assertEquals(tracked, Map.empty[String, LmUsage])
    assertEquals(scoped, None)
  }

  test("guard: a managed model counts one time in each sink") {
    given RuntimeContext     = RuntimeContext()
    val (_, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(ManagedLanguageModel(Plain()), request))
    assertEquals(tracked, Map("test-model" -> usage))
    assertEquals(scoped, Some(usage))
  }

  test("guard: a managed model over a managed model counts one time in each sink") {
    given RuntimeContext     = RuntimeContext()
    val outer                = ManagedLanguageModel(ManagedLanguageModel(Plain(), cache = memory), cache = memory)
    val (_, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(outer, request))
    assertEquals(tracked, Map("test-model" -> usage))
    assertEquals(scoped, Some(usage))
  }

  test("guard: a cache hit counts in no sink") {
    given RuntimeContext          = RuntimeContext()
    val managed                   = ManagedLanguageModel(Plain(), cache = memory)
    val _                         = LanguageModel.callAndRecordUsage(managed, request)
    val (result, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(managed, request))
    assert(result.exists(_.cacheHit), result.toString)
    assertEquals(tracked, Map.empty[String, LmUsage])
    assertEquals(scoped, None)
  }

  test("guard: with trackUsage = false the user tracker gets nothing, and the scope keeps the total") {
    given RuntimeContext     = RuntimeContext(trackUsage = Some(false))
    val (_, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(Plain(), request))
    assertEquals(tracked, Map.empty[String, LmUsage])
    assertEquals(scoped, Some(usage))
  }

  test("each open tracker gets the record, the outer one and the inner one") {
    given RuntimeContext         = RuntimeContext()
    val (outerTotal, innerTotal) = UsageTracking.withNewTracker { outer =>
      val inner = UsageTracking.withNewTracker { tracker =>
        val _ = LanguageModel.callAndRecordUsage(Plain(), request)
        tracker.totalUsage
      }
      (outer.totalUsage, inner)
    }
    assertEquals(outerTotal, Map("test-model" -> usage))
    assertEquals(innerTotal, Map("test-model" -> usage))
  }
