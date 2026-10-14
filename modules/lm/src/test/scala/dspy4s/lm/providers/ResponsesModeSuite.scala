package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** An OpenAI provider in the Responses mode calls the Responses endpoint with the Responses body, and reads the one
  * output of the response (PORT_GAPS G-26, upgrade item L7).
  */
class ResponsesModeSuite extends FunSuite:

  /** A transport that records each plain request and answers with `body`. A stream request is an error. */
  private final class Recording(body: String) extends HttpTransport:
    val sent: ArrayBuffer[(String, String)]                                                                            = ArrayBuffer.empty
    override def sendJson(url: String, headers: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      sent += ((url, payload))
      Right(HttpResponse(200, Map.empty, body))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      Left(RuntimeError("test", "the stream endpoint is not used"))

  private val responsesBody: String = """{"model": "gpt-test", "output": [
      |  {"type": "reasoning", "summary": [{"text": "Think."}]},
      |  {"type": "message", "content": [{"type": "output_text", "text": "Paris"}]}
      |], "usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}""".stripMargin

  private val request = LmRequest(
    model = "gpt-test",
    mode = LmMode.Responses,
    messages = Vector(
      Message(role = MessageRole.System, text = Some("Be terse.")),
      Message(role = MessageRole.User, text = Some("Capital of France?"))
    ),
    options = DynamicValues.record()
  )

  private def model(transport: HttpTransport): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = "gpt-test",
      mode = LmMode.Responses,
      client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    )

  private def json(text: String): DynamicValue.Record =
    DynamicJson.decode(text) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"not a JSON object: $other")

  test("a call in the Responses mode goes to the Responses endpoint with the Responses body") {
    val transport        = Recording(responsesBody)
    given RuntimeContext = RuntimeContext()
    val response         = model(transport).call(request)
    assert(response.isRight, response.toString)
    val (url, payload) = transport.sent.headOption.getOrElse(fail("no request"))
    assertEquals(url, "https://api.test/v1/responses")
    val body = json(payload)
    assert(DynamicValues.recordGet(body, "input").isDefined, payload)
    assertEquals(DynamicValues.recordGet(body, "messages"), None)
    assertEquals(DynamicValues.recordGet(body, "mode"), None)
  }

  test("a call in the Responses mode reads one output with its text, its reasoning, and the usage") {
    given RuntimeContext = RuntimeContext()
    model(Recording(responsesBody)).call(request) match
      case Right(response) =>
        assertEquals(response.outputs.map(_.text), Vector("Paris"))
        assertEquals(
          DynamicValues.recordGet(response.outputs.head.metadata, "reasoning_content"),
          Some(DynamicValues.fromAny("Think."))
        )
        assertEquals(response.usage.map(_.totalTokens), Some(6L))
      case Left(error) => fail(s"the call failed: $error")
  }

  test("a stream in the Responses mode gives one final chunk from a plain call") {
    val transport        = Recording(responsesBody)
    given RuntimeContext = RuntimeContext()
    val chunks           = model(transport).stream(request).toVector
    assertEquals(chunks.map(_.text), Vector("Paris"))
    assertEquals(chunks.map(_.finishReason), Vector(Some("stop")))
    assertEquals(chunks.flatMap(_.usage).map(_.totalTokens), Vector(6L))
    assertEquals(transport.sent.map(_._1).toVector, Vector("https://api.test/v1/responses"))
  }

  test("a call in the chat mode still goes to the chat endpoint with messages") {
    val chatBody         = """{"choices": [{"message": {"role": "assistant", "content": "Paris"}}]}"""
    val transport        = Recording(chatBody)
    given RuntimeContext = RuntimeContext()
    val chat             = OpenAiLanguageModel(
      model = "gpt-test",
      client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    )
    assert(chat.call(request.copy(mode = LmMode.Chat)).isRight)
    val (url, payload) = transport.sent.headOption.getOrElse(fail("no request"))
    assertEquals(url, "https://api.test/v1/chat/completions")
    assert(DynamicValues.recordGet(json(payload), "messages").isDefined, payload)
  }
