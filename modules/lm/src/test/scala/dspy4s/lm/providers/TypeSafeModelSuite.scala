package dspy4s.lm.providers

import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  LmAuthError,
  LmError,
  LmInvalidRequestError,
  LmNotConfiguredError,
  LmProviderResponseError,
  LmRateLimitError,
  LmServerError,
  LmUnsupportedModelError,
  LmUsage,
  RuntimeContext
}
import dspy4s.lm.contracts.{DecisionRequests, LmCapabilities, LmMode, LmRequest, Message, MessageRole}
import dspy4s.lm.runtime.{ManagedLanguageModel, StreamAccumulator}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

/** The client of the TypeSafe System One API (experimental; upstream `dspy.experimental.TypeSafe`, the wire rules of
  * `dspy/_vendor/lm15/providers/typesafe.py`, and the cases of `tests/clients/test_typesafe.py`).
  */
class TypeSafeModelSuite extends FunSuite:

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def text(value: DynamicValue): String =
    new String(Schema.dynamic.jsonCodec.encode(value), StandardCharsets.UTF_8)

  private def record(text: String): DynamicValue.Record =
    json(text) match
      case value: DynamicValue.Record => value
      case other                      => fail(s"not an object: $other")

  /** A transport that keeps each request and gives `reply`. */
  private final class Stub(reply: HttpResponse) extends HttpTransport:
    @volatile var requests: Vector[(String, Map[String, String], String)]                                           = Vector.empty
    @volatile var closed: Boolean                                                                                   = false
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      requests = requests :+ ((url, headers, body))
      Right(reply)
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      fail("no stream")
    override def close(): Unit = closed = true

  private val state = json(
    """{"instructions": "Assess the document.", "input_fields": "1. `text` (str):", "inputs": {"text": "x"}}"""
  )

  private val questions = record(
    """{"flag": {"instructions": "Is it relevant?", "type": "noul"},
      | "rating": {"instructions": "Rate usefulness.", "type": "score", "criteria": ["bad", "fair", "great"]},
      | "label": {"instructions": "Classify the document.", "type": "choice", "criteria": {"2": null, "other": null}}}""".stripMargin
  )

  private val answers =
    """{"flag": {"type": "noul", "noul": 0.8},
      | "rating": {"type": "score", "score": 1.5, "confidence": 0.61, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}, "legend": {"0": "bad", "1": "fair", "2": "great"}},
      | "label": {"type": "choice", "choice": "2", "confidence": 0.73, "probabilities": {"2": 0.8, "other": 0.2}}}""".stripMargin

  private def ok(answersText: String = answers): HttpResponse =
    HttpResponse(
      200,
      Map("x-typesafe-request-id" -> "req-1"),
      s"""{"model": "jev-test", "answers": $answersText, "usage": {"input_tokens": 10, "output_tokens": 2}}"""
    )

  private lazy val request = DecisionRequests.request("jev-test", state, questions)

  private given RuntimeContext = RuntimeContext()

  test("a decision request goes to /v1/systemone with the Bearer key and the state and the questions") {
    val stub                 = Stub(ok())
    val model                = TypeSafeModel("jev-test", apiKey = "test-only", baseUrl = "https://example.test/", transport = stub)
    val result               = model.call(request).fold(error => fail(error.message), identity)
    val (url, headers, body) = stub.requests.headOption.getOrElse(fail("no request"))
    assertEquals(url, "https://example.test/v1/systemone")
    assertEquals(headers.get("Authorization"), Some("Bearer test-only"))
    assertEquals(headers.get("Content-Type"), Some("application/json"))
    assertEquals(
      json(body),
      json(s"""{"model": "jev-test", "state": ${text(state)}, "questions": ${text(questions)}}""")
    )
    assertEquals(DecisionRequests.answers(result), Right(record(answers)))
    assertEquals(result.usage, Some(LmUsage(totalTokens = 12, promptTokens = 10, completionTokens = 2)))
    assertEquals(result.modelName, Some("jev-test"))
  }

  test("the model declares decision requests and no other capability, and closes its transport") {
    val stub  = Stub(ok())
    val model = TypeSafeModel(apiKey = "k", transport = stub)
    assertEquals(model.supportsDecisionRequests, true)
    assertEquals(
      (model.supportsFunctionCalling, model.supportsResponseSchema, model.supportsReasoning),
      (false, false, false)
    )
    assertEquals(model.id, TypeSafeModel.DefaultModel)
    model.close()
    assert(stub.closed)
    assert(!model.toString.contains("k)"), model.toString)
    assertEquals(LmCapabilities.none, LmCapabilities())
  }

  test("a request that is not a decision request fails before the wire") {
    val stub  = Stub(ok())
    val model = TypeSafeModel(apiKey = "k", transport = stub)
    val chat  = LmRequest(model = "jev-test", messages = Vector(Message(MessageRole.User, text = Some("Hi"))))
    assert(model.call(chat).left.exists(_.isInstanceOf[LmInvalidRequestError]), "a chat request")
    assert(model.call(LmRequest(model = "jev-test")).left.exists(_.isInstanceOf[LmInvalidRequestError]), "no payload")
    val tuned = request.copy(options = DynamicValues.record("temperature" -> json("0.5")))
    assert(model.call(tuned).left.exists(_.isInstanceOf[LmInvalidRequestError]), "a generation setting")
    assertEquals(stub.requests, Vector.empty)
  }

  test("a reply that breaks a wire rule is a provider response error") {
    val bad = Vector(
      """{"flag": {"type": "noul", "noul": 0.8}}""",
      answers.replace(""""noul": 0.8""", """"noul": 1.2"""),
      answers.replace(""""type": "noul"""", """"type": "score""""),
      answers.replace(""""2": 0.6}, "legend"""", """"2": 0.6, "3": 0.0}, "legend""""),
      answers.replace(""""choice": "2"""", """"choice": "unknown""""),
      answers.replace(""""noul": 0.8""", """"noul": true""")
    )
    val accepted = bad.filter { text =>
      val outcome = TypeSafeModel(apiKey = "k", transport = Stub(ok(text))).call(request)
      !outcome.left.exists(_.isInstanceOf[LmProviderResponseError])
    }
    assertEquals(accepted, Vector.empty)
  }

  test("each error status maps to the upstream error class, with the request id") {
    def errorOf(status: Int, body: String): DspyError =
      TypeSafeModel(apiKey = "k", transport = Stub(HttpResponse(status, Map("x-typesafe-request-id" -> "req-9"), body)))
        .call(request).left.getOrElse(fail(s"no error for $status"))
    assert(errorOf(
      401,
      """{"detail": {"error_type": "authentication_error", "message": "bad key"}}"""
    ).isInstanceOf[LmAuthError])
    assert(errorOf(429, "slow down").isInstanceOf[LmRateLimitError])
    assert(errorOf(400, """{"detail": {"message": "Unknown model jev-x"}}""").isInstanceOf[LmUnsupportedModelError])
    assert(errorOf(
      422,
      """{"detail": [{"loc": ["body", "questions", "flag"], "msg": "field required"}]}"""
    ).isInstanceOf[LmInvalidRequestError])
    assert(errorOf(503, "down").isInstanceOf[LmServerError])
    assertEquals(errorOf(401, """{"detail": {"message": "bad key"}}""").message, "bad key")
    assertEquals(
      errorOf(422, """{"detail": [{"loc": ["body", "questions", "flag"], "msg": "field required"}]}""").message,
      "questions.flag: field required"
    )
    errorOf(503, "down") match
      case error: LmError => assertEquals(error.details.requestId, Some("req-9"))
      case other          => fail(s"not an LM error: $other")
  }

  test("the key comes from the environment, with the model and the endpoint") {
    val model = TypeSafeModel.fromEnv(Map(
      "TYPESAFE_API_KEY"       -> "env-key",
      "TYPESAFE_DEFAULT_MODEL" -> "jev-preview",
      "TYPESAFE_BASE_URL"      -> "https://b.test"
    ))
    assertEquals(model.map(m => (m.model, m.apiKey, m.baseUrl)), Right(("jev-preview", "env-key", "https://b.test")))
    assertEquals(
      TypeSafeModel.fromEnv(Map("TYPESAFE_API_KEY" -> "k")).map(m => (m.model, m.baseUrl)),
      Right((TypeSafeModel.DefaultModel, TypeSafeModel.DefaultBaseUrl))
    )
    assert(TypeSafeModel.fromEnv(Map.empty).left.exists(_.isInstanceOf[LmNotConfiguredError]))
    assert(TypeSafeModel.fromEnv(Map("TYPESAFE_API_KEY" -> "")).left.exists(_.isInstanceOf[LmNotConfiguredError]))
  }

  test("a managed model caches a decision request, keeps no key in the history, and forwards the capability") {
    val stub    = Stub(ok())
    val managed = ManagedLanguageModel(
      TypeSafeModel(apiKey = "secret-key", transport = stub),
      cache = Some(new dspy4s.lm.runtime.InMemoryLmCache(dspy4s.lm.runtime.CacheCapacity(4)))
    )
    assert(managed.supportsDecisionRequests)
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
    assert(managed.call(request).isRight)
    val hit = managed.call(request).fold(error => fail(error.message), identity)
    assert(hit.cacheHit)
    assertEquals(stub.requests.size, 1)
    val history = dspy4s.core.runtime.RuntimeEnvironment.current.history.map(_.payload.toString).mkString
    assert(!history.contains("secret-key"), history)
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
  }

  test("two models with one Jev model and different base URLs do not share a cache entry") {
    // Upstream `_request` puts `base_url` in the key, and strips each slash at its end (`tests/clients/test_typesafe.py`
    // `test_cache_identity_and_controls`).
    val cache                                                      = Some(new dspy4s.lm.runtime.InMemoryLmCache(dspy4s.lm.runtime.CacheCapacity(8)))
    def managed(baseUrl: String, stub: Stub): ManagedLanguageModel =
      ManagedLanguageModel(TypeSafeModel("jev-test", apiKey = "k", baseUrl = baseUrl, transport = stub), cache = cache)
    val (first, second, third) = (Stub(ok()), Stub(ok()), Stub(ok()))
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
    assertEquals(managed("https://a.test", first).call(request).map(_.cacheHit), Right(false))
    assertEquals(managed("https://b.test", second).call(request).map(_.cacheHit), Right(false))
    assertEquals(second.requests.size, 1, "the model with the other base URL must reach its endpoint")
    assertEquals(managed("https://a.test/", third).call(request).map(_.cacheHit), Right(true))
    assertEquals(third.requests.size, 0)
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
  }

  test("the stream of a managed model keeps the typed answers in its chunk and in the assembled output") {
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
    val managed = ManagedLanguageModel(TypeSafeModel("jev-test", apiKey = "k", transport = Stub(ok())))
    val chunks  = managed.stream(request).toVector
    assertEquals(chunks.flatMap(_.answers), Vector(record(answers)))
    val parts = StreamAccumulator(LmMode.Chat)
    chunks.foreach(parts.push)
    assertEquals(parts.output.answers, Some(record(answers)))
    dspy4s.core.runtime.RuntimeEnvironment.resetForTests()
  }
