package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.UsageScope
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.providers.HttpResponse
import dspy4s.lm.providers.HttpStreamResponse
import dspy4s.lm.providers.HttpTransport
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RetryCount
import dspy4s.lm.runtime.RetryPolicies
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite

/** The usage of a call that fails (upstream `_account_failed_call` in `dspy/clients/execution.py`). An LM error carries
  * the usage that the provider reported for the failed call (`LmErrorDetails.usage`), and the record point records it.
  * The usage of a call is the total of each attempt that reported a usage, for a success and for a failure (upstream
  * `combine(results)` and `_account_failed_call(results)`). Each test failed before the change (most of them do not
  * compile against the old API).
  */
class FailedCallUsageSuite extends FunSuite:

  private def usage(n: Long): LmUsage = LmUsage(totalTokens = 11L * n, promptTokens = n, completionTokens = 10L * n)

  private val request: LmRequest = LmRequest(model = "test-model")

  /** A model whose calls give the next outcome of `outcomes`. */
  private final class Script(outcomes: Vector[Either[DspyError, LmResponse]]) extends LanguageModel:
    private var remaining     = outcomes
    var calls                 = 0
    override val id: String   = "script"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      val next = remaining.head
      remaining = remaining.tail
      next

  /** A stream model that gives `chunks` for each stream request. */
  private final class Streams(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "streams"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks.iterator

  private def failure(n: Long): Either[DspyError, LmResponse] =
    Left(LmServerError("the server is busy", LmErrorDetails(usage = Some(usage(n)))))

  private def success(n: Long): Either[DspyError, LmResponse] =
    Right(LmResponse(outputs = Vector(LmOutput(text = "Hello")), usage = Some(usage(n))))

  /** The result of `body`, the total of the user tracker, and the total of the usage scope around it. */
  private def measured[A](body: => A): (A, Map[String, LmUsage], Option[LmUsage]) =
    val ((value, tracked), scoped) = UsageScope.measure {
      UsageTracking.withNewTracker(tracker => (body, tracker.totalUsage))
    }
    (value, tracked, scoped)

  test("the record point records the usage of a failed call into each sink") {
    given RuntimeContext          = RuntimeContext()
    val (result, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(Script(Vector(failure(1))), request))
    assert(result.isLeft, result.toString)
    assertEquals(tracked, Map("test-model" -> usage(1)))
    assertEquals(scoped, Some(usage(1)))
  }

  test("a failed call with no usage records nothing") {
    given RuntimeContext     = RuntimeContext()
    val plain                = Left(LmServerError("the server is down"))
    val (_, tracked, scoped) = measured(LanguageModel.callAndRecordUsage(Script(Vector(plain)), request))
    assertEquals(tracked, Map.empty[String, LmUsage])
    assertEquals(scoped, None)
  }

  test("a managed stream that fails after its usage chunk gives the usage on the error of the failure chunk") {
    given RuntimeContext = RuntimeContext()
    val chunks           = Vector(
      LmChunk(text = "Hel"),
      LmChunk(usage = Some(usage(2))),
      LmChunk.failure(LmTransportError("the connection closed"))
    )
    val last = ManagedLanguageModel(Streams(chunks)).stream(request).toVector.lastOption
    assertEquals(last.flatMap(_.reportedFailure).flatMap(LmError.usage), Some(usage(2)))
  }

  test("the usage of a call is the total of each attempt: a failed attempt and the retry that succeeds") {
    given RuntimeContext = RuntimeContext()
    val delegate         = Script(Vector(failure(1), success(2)))
    val managed          = ManagedLanguageModel(delegate, retryPolicy = RetryPolicies.maxRetries(RetryCount(1)))
    assertEquals(managed.call(request).map(_.usage), Right(Some(usage(1).combine(usage(2)))))
    assertEquals(delegate.calls, 2)
  }

  test("the usage of a call is the total of each attempt: two failed attempts") {
    given RuntimeContext = RuntimeContext()
    val delegate         = Script(Vector(failure(1), failure(2)))
    val managed          = ManagedLanguageModel(delegate, retryPolicy = RetryPolicies.maxRetries(RetryCount(1)))
    val error            = managed.call(request).left.getOrElse(fail("the call must fail"))
    assertEquals(LmError.usage(error), Some(usage(1).combine(usage(2))))
  }

  test("an OpenAI reply with a usage and no output gives an LM error with the usage") {
    given RuntimeContext = RuntimeContext()
    val reply            =
      """{"model": "gpt-test", "choices": [], "usage": {"prompt_tokens": 4, "completion_tokens": 5, "total_tokens": 9}}"""
    val transport = new HttpTransport:
      override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
        Right(HttpResponse(200, Map.empty, reply))
      override def streamSse(
          url    : String,
          headers: Map[String, String],
          body   : String
      ): Either[DspyError, HttpStreamResponse] = Left(RuntimeError("test", "no stream"))
    val model = OpenAiLanguageModel("gpt-test", apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    val error = model.call(LmRequest(model = "gpt-test")).left.getOrElse(fail("the call must fail"))
    assert(error.message.contains("does not contain any outputs"), error.message)
    assertEquals(LmError.usage(error), Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)))
  }
