package dspy4s.lm.providers

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, LmInvalidRequestError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{
  CacheKey,
  DecisionPayload,
  DecisionRequests,
  LmMode,
  LmOutput,
  LmResponse,
  Message,
  MessageRole
}
import dspy4s.lm.runtime.{CacheCapacity, DiskLmCache, InMemoryLmCache, ManagedLanguageModel, RequestHash}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The decision request has a typed payload, and its reply has the typed answers. Before the change, the payload went
  * in the option bag under the keys `state` and `questions`, and the answers went only as JSON text in the output. Each
  * test failed before the change (most of them do not compile against the old API).
  */
class DecisionPayloadSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def record(text: String): DynamicValue.Record =
    json(text) match
      case value: DynamicValue.Record => value
      case other                      => fail(s"not an object: $other")

  private val state = record("""{"instructions": "Assess the document.", "inputs": {"text": "x"}}""")

  private val questions = record("""{"flag": {"instructions": "Is it relevant?", "type": "noul"}}""")

  private val answers = record("""{"flag": {"type": "noul", "noul": 0.8}}""")

  private val request = DecisionRequests.request("jev-test", state, questions)

  /** A transport that keeps each body and gives a valid reply with `answers`. */
  private final class Stub extends HttpTransport:
    @volatile var bodies: Vector[String]                                                                            = Vector.empty
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      bodies = bodies :+ body
      Right(HttpResponse(
        200,
        Map.empty,
        """{"model": "jev-test", "answers": {"flag": {"type": "noul", "noul": 0.8}}, "usage": {"input_tokens": 1, "output_tokens": 1}}"""
      ))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] = fail("no stream")

  private def model(stub: Stub): TypeSafeModel = TypeSafeModel("jev-test", apiKey = "k", transport = stub)

  test("a decision request holds its payload in a typed field, and its option bag is empty") {
    assertEquals(request.decision, Some(DecisionPayload(state, questions)))
    assertEquals(request.options, DynamicValue.Record.empty)
    assertEquals(request.messages, Vector.empty)
  }

  test("an option named state is an unsupported setting, not the payload") {
    given RuntimeContext = RuntimeContext()
    val stub             = Stub()
    val tuned            = request.copy(options = DynamicValues.record("state" := "other"))
    model(stub).call(tuned) match
      case Left(error: LmInvalidRequestError) => assert(error.message.contains("'state'"), error.message)
      case other                              => fail(s"expected an LmInvalidRequestError, got $other")
    assertEquals(stub.bodies, Vector.empty)
  }

  test("a request with messages and a decision is invalid before the wire") {
    given RuntimeContext = RuntimeContext()
    val stub             = Stub()
    val mixed            = request.copy(messages = Vector(Message(MessageRole.User, text = Some("Hi"))))
    assert(model(stub).call(mixed).left.exists(_.isInstanceOf[LmInvalidRequestError]))
    assertEquals(stub.bodies, Vector.empty)
  }

  test("the reply of a TypeSafe call has the typed answers") {
    given RuntimeContext = RuntimeContext()
    val result           = model(Stub()).call(request).fold(error => fail(error.message), identity)
    assertEquals(result.outputs.headOption.flatMap(_.answers), Some(answers))
  }

  test("the answers of a reply come from the typed field, with no parse of the text") {
    val reply = LmResponse(outputs = Vector(LmOutput(text = "not JSON", answers = Some(answers))))
    assertEquals(DecisionRequests.answers(reply), Right(answers))
  }

  test("the disk cache keeps the typed answers") {
    val cache = new DiskLmCache(Files.createTempDirectory("dspy4s-decision"))
    val key   = CacheKey.of("decision")
    cache.put(key, DecisionRequests.response(answers, usage = None, modelName = Some("jev-test")))
    assertEquals(cache.get(key).flatMap(_.outputs.headOption).flatMap(_.answers), Some(answers))
  }

  test("the history entry has the decision payload, and the kwargs have no state") {
    given RuntimeContext = RuntimeEnvironment.current
    val managed          = ManagedLanguageModel(model(Stub()), cache = Some(new InMemoryLmCache(CacheCapacity(4))))
    assert(managed.call(request).isRight)
    val payload  = RuntimeEnvironment.current.history.lastOption.map(_.payload).getOrElse(fail("no history entry"))
    val decision = DynamicValues.recordGet(payload, "decision")
    assertEquals(decision.flatMap(DynamicValues.recordGet(_, "state")), Some(state))
    assertEquals(decision.flatMap(DynamicValues.recordGet(_, "questions")), Some(questions))
    assertEquals(DynamicValues.recordGet(payload, "kwargs"), Some(DynamicValue.Record.empty))
  }

  test("guard: two decision requests with different questions get different cache keys") {
    val other =
      DecisionRequests.request("jev-test", state, record("""{"flag": {"instructions": "Other?", "type": "noul"}}"""))
    assertNotEquals(RequestHash.forRequest(request, LmMode.Chat), RequestHash.forRequest(other, LmMode.Chat))
  }
