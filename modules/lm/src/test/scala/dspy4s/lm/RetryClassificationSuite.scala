package dspy4s.lm

import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmBillingError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmInvalidRequestError
import dspy4s.core.contracts.LmNotConfiguredError
import dspy4s.core.contracts.LmProviderResponseError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.LmUnsupportedModelError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.RetryPolicy
import dspy4s.lm.providers.HttpResponse
import dspy4s.lm.providers.HttpStreamResponse
import dspy4s.lm.providers.HttpTransport
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.runtime.JitterFactor
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RetryCount
import dspy4s.lm.runtime.RetryDelayMillis
import dspy4s.lm.runtime.RetryPolicies
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** A retry policy retries the transient LM error classes only, and obeys the delay that the provider asked for
  * (upstream `is_retryable_lm_error` in `dspy/utils/exceptions.py`, and `_delay` in `dspy/clients/execution.py`).
  */
class RetryClassificationSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val request  = LmRequest(model = "gpt-test", messages = Vector(Message(MessageRole.User, Some("x"))))
  private val response = LmResponse(outputs = Vector(LmOutput(text = "hello")))

  private val okBody: String =
    """{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"Brussels"},"finish_reason":"stop"}],"model":"gpt-test","usage":{"prompt_tokens":6,"completion_tokens":1,"total_tokens":7}}"""

  private val transient: Vector[DspyError] = Vector(
    LmRateLimitError("rate limited"),
    LmTimeoutError("timed out"),
    LmServerError("server failed"),
    LmTransportError("connection reset")
  )

  private val permanent: Vector[DspyError] = Vector(
    LmAuthError("bad key"),
    LmBillingError("no quota"),
    LmInvalidRequestError("bad request"),
    ContextWindowExceededError(),
    LmUnsupportedModelError("no such model"),
    LmProviderResponseError("teapot"),
    LmNotConfiguredError("no key"),
    LmUnexpectedError("bug"),
    RuntimeError("lm", "custom failure"),
    ConfigurationError("bad setup"),
    ParseError("adapter", "bad output")
  )

  private val defaultPolicies: Vector[(String, RetryPolicy)] = Vector(
    "maxRetries"         -> RetryPolicies.maxRetries(RetryCount(3)),
    "exponentialBackoff" -> RetryPolicies.exponentialBackoff(RetryCount(3))
  )

  test("the default predicate retries each transient LM error") {
    for
      (name, policy) <- defaultPolicies
      error          <- transient
    do assert(policy.shouldRetry(0, error), s"$name must retry $error")
  }

  test("the default predicate does not retry a permanent error") {
    for
      (name, policy) <- defaultPolicies
      error          <- permanent
    do assert(!policy.shouldRetry(0, error), s"$name must not retry $error")
  }

  test("a transient error is not retried after the last permitted attempt") {
    defaultPolicies.foreach { (name, policy) =>
      assert(policy.shouldRetry(2, LmServerError("boom")), name)
      assert(!policy.shouldRetry(3, LmServerError("boom")), name)
    }
  }

  test("an explicit predicate replaces the default") {
    val all = RetryPolicies.maxRetries(RetryCount(1), retryOn = _ => true)
    assert(all.shouldRetry(0, RuntimeError("lm", "custom failure")))
    assert(all.shouldRetry(0, LmAuthError("bad key")))

    val none = RetryPolicies.exponentialBackoff(RetryCount(1), retryOn = _ => false)
    assert(!none.shouldRetry(0, LmServerError("boom")))
  }

  test("maxRetriesOnCodes selects LM errors by their code") {
    val policy = RetryPolicies.maxRetriesOnCodes(RetryCount(2), retryableCodes = Set("rate_limit"))
    assert(policy.shouldRetry(0, LmRateLimitError("slow")))
    assert(!policy.shouldRetry(0, LmServerError("boom")))
  }

  private def hinted(delay: FiniteDuration): DspyError =
    LmRateLimitError("slow down", LmErrorDetails(retryAfter = Some(delay)))

  test("the backoff delay is the hint of the provider when the error carries one") {
    val policy = RetryPolicies.exponentialBackoff(
      maxRetries = RetryCount(3),
      baseDelayMillis = RetryDelayMillis(5L),
      maxDelayMillis = RetryDelayMillis(20L),
      jitterFactor = JitterFactor(0.5)
    )
    // The hint wins over the computed delay, over the maximum delay, and gets no jitter.
    assertEquals(policy.delayBeforeNextAttemptMillis(0, hinted(2.seconds)), 2000L)
    assertEquals(policy.delayBeforeNextAttemptMillis(2, hinted(1500.millis)), 1500L)
    assertEquals(policy.delayBeforeNextAttemptMillis(0, hinted(Duration.Zero)), 0L)
  }

  test("the backoff delay is the computed delay when the error carries no hint") {
    val policy = RetryPolicies.exponentialBackoff(
      maxRetries = RetryCount(3),
      baseDelayMillis = RetryDelayMillis(5L),
      maxDelayMillis = RetryDelayMillis(20L),
      jitterFactor = JitterFactor(0.0)
    )
    assertEquals(policy.delayBeforeNextAttemptMillis(0, LmRateLimitError("slow")), 5L)
    assertEquals(policy.delayBeforeNextAttemptMillis(1, LmServerError("boom")), 10L)
  }

  test("maxRetries waits for the hint of the provider, and does not wait without one") {
    val policy = RetryPolicies.maxRetries(RetryCount(3))
    assertEquals(policy.delayBeforeNextAttemptMillis(0, hinted(2.seconds)), 2000L)
    assertEquals(policy.delayBeforeNextAttemptMillis(0, LmServerError("boom")), 0L)
  }

  private final class StubLanguageModel(initial: Vector[Either[DspyError, LmResponse]]) extends LanguageModel:
    private var scripted = initial
    val calls            = ArrayBuffer.empty[LmRequest]

    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += request
      if scripted.isEmpty then Left(RuntimeError("stub_lm", "No scripted response available"))
      else
        val head = scripted.head
        scripted = scripted.tail
        head

  test("a managed model with the default predicate calls the provider one time for an authentication failure") {
    val delegate = new StubLanguageModel(Vector(Left(LmAuthError("bad key")), Right(response)))
    val managed  = ManagedLanguageModel(delegate = delegate, retryPolicy = RetryPolicies.maxRetries(RetryCount(3)))

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(managed.call(request), Left(LmAuthError("bad key")))
    assertEquals(delegate.calls.size, 1)
  }

  test("a managed model does not retry a context-window failure") {
    val delegate = new StubLanguageModel(Vector(Left(ContextWindowExceededError()), Right(response)))
    val managed  =
      ManagedLanguageModel(delegate = delegate, retryPolicy = RetryPolicies.exponentialBackoff(RetryCount(3)))

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(managed.call(request), Left(ContextWindowExceededError()))
    assertEquals(delegate.calls.size, 1)
  }

  test("a managed model sleeps for the hint of the provider before the next attempt") {
    val delegate = new StubLanguageModel(Vector(Left(hinted(2.seconds)), Left(LmServerError("boom")), Right(response)))
    val sleeps   = ArrayBuffer.empty[Long]
    val managed  = ManagedLanguageModel(
      delegate = delegate,
      retryPolicy = RetryPolicies.exponentialBackoff(
        maxRetries = RetryCount(3),
        baseDelayMillis = RetryDelayMillis(5L),
        maxDelayMillis = RetryDelayMillis(20L)
      ),
      sleep = millis => sleeps += millis
    )

    given RuntimeContext = RuntimeEnvironment.current
    assert(managed.call(request).isRight)
    assertEquals(delegate.calls.size, 3)
    assertEquals(sleeps.toVector, Vector(2000L, 10L))
  }

  /** Replays scripted HTTP responses and counts the requests. */
  private final class ScriptedTransport(responses: Vector[HttpResponse]) extends HttpTransport:
    var requests = 0

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      val index = requests
      requests += 1
      if index < responses.size then Right(responses(index))
      else Left(RuntimeError("test", "No more responses scripted"))

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      Left(RuntimeError("test", "streaming is not used here"))

  private def managedOpenAi(transport: HttpTransport, sleeps: ArrayBuffer[Long]): ManagedLanguageModel =
    ManagedLanguageModel(
      delegate = OpenAiLanguageModel(model = "gpt-test", client = OpenAiClient(apiKey = "x", transport = transport)),
      retryPolicy = RetryPolicies.exponentialBackoff(RetryCount(3)),
      sleep = millis => sleeps += millis
    )

  test("end to end: HTTP 429 with Retry-After is retried after the hint") {
    val transport = new ScriptedTransport(Vector(
      HttpResponse(
        429,
        Map("retry-after" -> "1"),
        """{"error":{"message":"slow down","code":"rate_limit_exceeded"}}"""
      ),
      HttpResponse(200, Map.empty, okBody)
    ))
    val sleeps = ArrayBuffer.empty[Long]

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managedOpenAi(transport, sleeps).call(request)
    assertEquals(result.map(_.outputs.map(_.text)), Right(Vector("Brussels")))
    assertEquals(transport.requests, 2)
    assertEquals(sleeps.toVector, Vector(1000L))
  }

  test("end to end: HTTP 401 is not retried") {
    val transport = new ScriptedTransport(Vector(
      HttpResponse(401, Map.empty, """{"error":{"message":"Incorrect API key provided","code":"invalid_api_key"}}"""),
      HttpResponse(200, Map.empty, okBody)
    ))
    val sleeps = ArrayBuffer.empty[Long]

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managedOpenAi(transport, sleeps).call(request)
    assert(result.left.exists(_.isInstanceOf[LmAuthError]), result.toString)
    assertEquals(transport.requests, 1)
    assertEquals(sleeps.toVector, Vector.empty[Long])
  }

  test("end to end: insufficient_quota on HTTP 429 is not retried") {
    val transport = new ScriptedTransport(Vector(
      HttpResponse(
        429,
        Map.empty,
        """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}"""
      ),
      HttpResponse(200, Map.empty, okBody)
    ))
    val sleeps = ArrayBuffer.empty[Long]

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managedOpenAi(transport, sleeps).call(request)
    assert(result.left.exists(_.isInstanceOf[LmBillingError]), result.toString)
    assertEquals(transport.requests, 1)
  }
