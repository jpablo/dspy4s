package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, RuntimeContext, SignatureLayout, TypeRef}
import dspy4s.core.decisions.{Choice, DecisionSettings, Noul, Score}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.lm.providers.{HttpResponse, HttpStreamResponse, HttpTransport, TypeSafeModel}
import dspy4s.lm.runtime.{CacheCapacity, InMemoryLmCache, ManagedLanguageModel}
import dspy4s.programs.strategies.{DynamicPredict, Predict}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

final case class SoTriage(urgent: Noul, severity: Score[PdSeverity], category: Choice[PdTeam]) derives Schema

/** A typed `Predict` with a System One model, and `Predict` with the TypeSafe client on a stub transport, end to end
  * (experimental; upstream `DecisionAdapter` with `system_one=True`, `dspy/clients/typesafe.py`, and the cases of
  * `tests/clients/test_typesafe.py`).
  */
class PredictTypeSafeSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def record(text: String): DynamicValue.Record =
    json(text) match
      case value: DynamicValue.Record => value
      case other                      => fail(s"not an object: $other")

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  /** A System One model that gives `answers` to each decision request and keeps the requests. */
  private final class FakeJev(answers: DynamicValue.Record) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "jev-test"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsDecisionRequests: Boolean                                             = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      DecisionRequests.parts(request).map(_ => DecisionRequests.response(answers, None, Some(id)))

  /** A transport that keeps each request and gives `reply`. */
  private final class Stub(reply: HttpResponse) extends HttpTransport:
    @volatile var requests: Vector[(String, Map[String, String], String)]                                           = Vector.empty
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      requests = requests :+ ((url, headers, body))
      Right(reply)
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      fail("no stream")

  private def run[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private val signature = Signature.derived[PdTicket, SoTriage]("Triage", "Triage the ticket.")

  private val settings: Vector[(String, DecisionSettings)] = Vector(
    "urgent"   -> DecisionSettings(threshold = Some(0.8), instructions = Some(text("Is immediate action needed?"))),
    "severity" -> DecisionSettings(cuts = Some(Vector(0.4, 1.7)), instructions = Some(text("How severe is it?"))),
    "category" -> DecisionSettings(weights = Some(Map("billing" -> 0.5)), instructions = Some(text("Which team?")))
  )

  private val triageAnswers = record(
    """{"urgent": {"type": "noul", "noul": 0.9},
      | "severity": {"type": "score", "score": 1.5, "confidence": 0.61, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}},
      | "category": {"type": "choice", "choice": "billing", "confidence": 0.7, "probabilities": {"billing": 0.6, "technical": 0.4}}}""".stripMargin
  )

  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Assess",
      inputFields = Vector(FieldSpec("text")),
      outputFields = Vector(
        FieldSpec("flag", TypeRef.noul, description = Some("Is it relevant?")),
        FieldSpec("rating", TypeRef.score, description = Some("Rate usefulness."), enumValues = Vector("bad", "good")),
        FieldSpec("label", TypeRef.choice, description = Some("Classify it."), enumValues = Vector("news", "other"))
      ),
      instructions = Some("Assess the document.")
    )
    .fold(error => fail(error.message), identity)

  private val wireAnswers =
    """{"flag": {"type": "noul", "noul": 0.3},
      | "rating": {"type": "score", "score": 0.75, "confidence": 0.5, "probabilities": {"0": 0.25, "1": 0.75}, "legend": {"0": "bad", "1": "good"}},
      | "label": {"type": "choice", "choice": "news", "confidence": 0.9, "probabilities": {"news": 0.9, "other": 0.1}}}""".stripMargin

  test("a typed Predict with a System One model gives Noul, Score, and Choice values with its settings") {
    val program = settings.foldLeft(Predict(signature)) { case (predict, (name, value)) =>
      predict.withDecision(name, value).fold(error => fail(s"$name: ${error.message}"), identity)
    }
    val lm     = FakeJev(triageAnswers)
    val output = run(lm)(program(PdTicket("Checkout fails."))).fold(error => fail(error.message), _.output)
    assertEquals(output.urgent.value, true)
    assertEqualsDouble(output.urgent.confidence, 0.125, 1e-12)
    assertEquals(output.urgent.probability, Some(0.9))
    assertEqualsDouble(output.severity.value, 1.5, 1e-12)
    assertEquals(output.severity.levelIn(PdSeverity.values.toIndexedSeq), Some(PdSeverity.Disruptive))
    assertEquals(output.severity.probabilities, Some(Map(0 -> 0.1, 1 -> 0.3, 2 -> 0.6)))
    assertEquals(output.category.value, PdTeam.technical, "the weights apply, not the pick of the model")
    assertEquals(output.category.probabilities, Some(Map("billing" -> 0.6, "technical" -> 0.4)))
    val (state, questions) = DecisionRequests.parts(lm.requests.headOption.getOrElse(fail("no request")))
      .fold(error => fail(error.message), identity)
    assertEquals(
      state,
      json(
        """{"instructions": "Triage the ticket.", "input_fields": "1. `ticket` (str):", "inputs": {"ticket": "Checkout fails."}}"""
      )
    )
    assertEquals(
      questions,
      json(
        """{"urgent": {"instructions": "Is immediate action needed?", "type": "noul"},
          | "severity": {"instructions": "How severe is it?", "type": "score", "criteria": ["Minor", "Disruptive", "Critical"]},
          | "category": {"instructions": "Which team?", "type": "choice", "criteria": {"billing": null, "technical": null}}}""".stripMargin
      )
    )
  }

  test("Predict with the TypeSafe client sends the wire body, decodes the reply, and caches the answer") {
    val stub = Stub(HttpResponse(
      200,
      Map("x-typesafe-request-id" -> "req-1"),
      s"""{"model": "jev-test", "answers": $wireAnswers, "usage": {"input_tokens": 10, "output_tokens": 3}}"""
    ))
    val lm = ManagedLanguageModel(
      TypeSafeModel("jev-test", apiKey = "test-only", baseUrl = "https://example.test", transport = stub),
      cache = Some(new InMemoryLmCache(CacheCapacity(4)))
    )
    val first = run(lm)(DynamicPredict(layout)(DynamicValues.record("text" := "x", "extra" := "no")))
      .fold(error => fail(error.message), identity)
    val (url, headers, body) = stub.requests.headOption.getOrElse(fail("no request"))
    assertEquals(url, "https://example.test/v1/systemone")
    assertEquals(headers.get("Authorization"), Some("Bearer test-only"))
    assertEquals(
      json(body),
      json(
        """{"model": "jev-test",
          | "state": {"instructions": "Assess the document.", "input_fields": "1. `text` (str):", "inputs": {"text": "x"}},
          | "questions": {"flag": {"instructions": "Is it relevant?", "type": "noul"},
          |   "rating": {"instructions": "Rate usefulness.", "type": "score", "criteria": ["bad", "good"]},
          |   "label": {"instructions": "Classify it.", "type": "choice", "criteria": {"news": null, "other": null}}}}""".stripMargin
      )
    )
    val values = first.raw.values
    assertEquals(
      DynamicValues.recordGet(values, "flag").collect { case flag: DynamicValue.Record => flag }
        .flatMap(DynamicValues.recordGet(_, "value")),
      Some(DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    )
    assertEquals(
      DynamicValues.recordGet(values, "label").collect { case label: DynamicValue.Record => label }
        .flatMap(DynamicValues.recordGet(_, "value")),
      Some(text("news"))
    )
    assertEquals(
      DynamicValues.recordGet(values, "rating").collect { case rating: DynamicValue.Record => rating }
        .flatMap(DynamicValues.recordGet(_, "level")),
      Some(DynamicValue.Primitive(PrimitiveValue.Int(1)))
    )
    assertEquals(first.raw.lmUsage.map(usage => (usage.promptTokens, usage.completionTokens)), Some((10L, 3L)))
    val second = run(lm)(DynamicPredict(layout)(DynamicValues.record("text" := "x")))
      .fold(error => fail(error.message), identity)
    assertEquals(second.raw.values, values)
    assertEquals(stub.requests.size, 1, "the second call is a cache hit")
  }

  test("an error status of the TypeSafe API fails the call with its class") {
    val stub   = Stub(HttpResponse(401, Map.empty, """{"detail": {"message": "bad key"}}"""))
    val lm     = TypeSafeModel("jev-test", apiKey = "test-only", transport = stub)
    val result = run(lm)(DynamicPredict(layout)(DynamicValues.record("text" := "x")))
    assertEquals(result.left.map(error => (error.code, error.message)), Left(("auth", "bad key")))
  }
