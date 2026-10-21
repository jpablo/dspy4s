package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import scala.collection.mutable.ArrayBuffer

case class FallbackQuestion(question: String) derives Schema
case class FallbackAnswer(answer: String, note: Option[String]) derives Schema

/** The fallback from the chat format to the JSON format after a parse error, and an optional output of a typed
  * signature (PORT_GAPS G-29, items A1 and A2; upstream `dspy/adapters/chat_adapter.py` `__call__`).
  */
class AdapterFallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the replies in order and repeats the last one. Records the text of each request. */
  private final class ScriptedLm(replies: Either[DspyError, String]*) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "scripted"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = requests.synchronized {
        requests += request.messages.flatMap(_.text).mkString("\n")
        requests.size - 1
      }
      replies(math.min(index, replies.size - 1)).map(text => LmResponse(outputs = Vector(LmOutput(text = text))))

  private def scripted(replies: String*): ScriptedLm = new ScriptedLm(replies.map(Right(_))*)

  /** `question -> answer, reason`: two outputs, so the chat parser has no plain-text rule for one output. */
  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer, reason").toOption.get)

  private val unparseable = "I think the answer is Paris."
  private val json        = """{"answer": "Paris", "reason": "It is the capital."}"""

  private def run(
      lm     : LanguageModel,
      adapter: dspy4s.adapters.contracts.Adapter
  ): Either[DspyError, DynamicValue.Record] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      program(ProgramCall(DynamicValues.record("question" := "Capital of France?"))).map(_.output)
    }

  private def answer(result: Either[DspyError, DynamicValue.Record]): Option[String] =
    result.toOption.flatMap(DynamicValues.recordGet(_, "answer")).flatMap(DynamicValues.asString)

  test("a parse error of the chat format gives one more call in the JSON format") {
    val lm     = scripted(unparseable, json)
    val result = run(lm, ChatAdapter())
    assertEquals(answer(result), Some("Paris"))
    assertEquals(lm.requests.size, 2)
    // The second request has the form of the JSON adapter.
    assert(!lm.requests(0).contains("JSON object"), lm.requests(0))
    assert(lm.requests(1).contains("JSON object"), lm.requests(1))
  }

  test("a coercion error of the chat format also gives the second call") {
    val counting = DynamicPredict(layout = SignatureLayout.parse("question -> count: int, reason").toOption.get)
    val lm       = scripted(
      "[[ ## count ## ]]\nmany\n\n[[ ## reason ## ]]\nx\n\n[[ ## completed ## ]]",
      """{"count": 3, "reason": "x"}"""
    )
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      counting(ProgramCall(DynamicValues.record("question" := "How many?"))).map(_.output)
    }
    assertEquals(result.toOption.flatMap(DynamicValues.recordGet(_, "count")), Some(DynamicValues.fromAny(3)))
    assertEquals(lm.requests.size, 2)
  }

  test("with the option off, a parse error of the chat format is the result, after one call") {
    val lm     = scripted(unparseable, json)
    val result = run(lm, ChatAdapter(useJsonAdapterFallback = false))
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("a parse error of the JSON fallback is the result, after two calls") {
    val lm     = scripted(unparseable)
    val result = run(lm, ChatAdapter())
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(lm.requests.size, 2)
  }

  test("a value outside the allowed values of the chat format gives one more call in the JSON format") {
    // Decision D7: upstream `parse_value` rejects a value outside a `Literal`, so `ChatAdapter.parse` raises
    // `AdapterParseError`, and `ChatAdapter.__call__` tries `JSONAdapter`.
    val labelled =
      DynamicPredict(layout = SignatureLayout.parse("question -> label: Literal['pos', 'neg'], reason").toOption.get)
    val lm = scripted(
      "[[ ## label ## ]]\nmaybe\n\n[[ ## reason ## ]]\nx\n\n[[ ## completed ## ]]",
      """{"label": "neg", "reason": "x"}"""
    )
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      labelled(ProgramCall(DynamicValues.record("question" := "Is it good?"))).map(_.output)
    }
    assertEquals(result.toOption.flatMap(DynamicValues.recordGet(_, "label")), Some(DynamicValues.fromAny("neg")))
    assertEquals(lm.requests.size, 2)
  }

  test("a value outside the allowed values in the chat format and in the JSON fallback fails the call") {
    val labelled =
      DynamicPredict(layout = SignatureLayout.parse("question -> label: Literal['pos', 'neg'], reason").toOption.get)
    val lm = scripted(
      "[[ ## label ## ]]\nmaybe\n\n[[ ## reason ## ]]\nx\n\n[[ ## completed ## ]]",
      """{"label": "maybe", "reason": "x"}"""
    )
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      labelled(ProgramCall(DynamicValues.record("question" := "Is it good?"))).map(_.output)
    }
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(lm.requests.size, 2)
  }

  test("an error of the model gives no second call") {
    val lm     = new ScriptedLm(Left(LmServerError("the server failed")), Right(json))
    val result = run(lm, ChatAdapter())
    assert(result.isLeft, result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("a JSON adapter has no fallback") {
    val lm     = scripted(unparseable, json)
    val result = run(lm, JSONAdapter())
    assert(result.isLeft, result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("the predictor is the one call path: the adapter contract has no call method without the fallback") {
    // Upstream `Adapter.__call__` is the one call path of an adapter, and `ChatAdapter.__call__` has the fallback
    // (`dspy/adapters/chat_adapter.py`). In the port, `PredictEngine` has the fallback.
    val errors = compileErrors(
      "(??? : dspy4s.adapters.contracts.Adapter).execute(???, ???)(using RuntimeContext())"
    )
    assert(errors.contains("execute is not a member"), errors)
  }

  test("a typed output with an Option field that the response does not have is None") {
    val signature = Signature.derived[FallbackQuestion, FallbackAnswer]("QA")
    val lm        = scripted("[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]")
    val result    = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      Predict(signature)(FallbackQuestion("Capital of France?")).map(_.output)
    }
    assertEquals(result, Right(FallbackAnswer("Paris", None)))
    assertEquals(lm.requests.size, 1)
  }

  test("a typed Option field with an explicit JSON null is None") {
    val signature = Signature.derived[FallbackQuestion, FallbackAnswer]("QA")
    val lm        = scripted("""{"answer": "Paris", "note": null}""")
    val result    = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      Predict(signature)(FallbackQuestion("Capital of France?")).map(_.output)
    }
    assertEquals(result, Right(FallbackAnswer("Paris", None)))
    assertEquals(lm.requests.size, 1)
  }
