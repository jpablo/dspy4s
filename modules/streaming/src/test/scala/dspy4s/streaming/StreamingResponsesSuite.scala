package dspy4s.streaming

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.providers.HttpResponse
import dspy4s.lm.providers.HttpStreamResponse
import dspy4s.lm.providers.HttpTransport
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.ProviderLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

import java.nio.charset.StandardCharsets

/** The `streamify` wrapper assembles the response of a model in the Responses mode with the reasoning and the citations
  * of a plain call (upstream lm15 `StreamAccumulator` in `dspy/_vendor/lm15/result.py`, read through `legacy_output` in
  * `dspy/clients/call_result.py`).
  */
class StreamingResponsesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A transport that answers a plain request with `body`, and a stream request with `lines`. */
  private final class Scripted(body: String, lines: Vector[String]) extends HttpTransport:
    override def sendJson(url: String, headers: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      Right(HttpResponse(200, Map.empty, body))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      val source = lines.iterator
      Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new ClosableIterator[String]:
          override def hasNext: Boolean = source.hasNext
          override def next(): String   = source.next()
          override def close(): Unit    = ()
      ))

  private val body: String = """{"model": "gpt-test", "output": [
      |  {"type": "reasoning", "summary": [{"type": "summary_text", "text": "Think"},
      |                                    {"type": "summary_text", "text": " more."}]},
      |  {"type": "message", "content": [{"type": "output_text", "text": "Paris", "annotations": [
      |    {"type": "url_citation", "url": "https://x.test", "title": "X", "start_index": 0, "end_index": 5}
      |  ]}]},
      |  {"type": "function_call", "call_id": "call_1", "name": "search", "arguments": "{\"q\": \"x\"}"}
      |], "usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}""".stripMargin

  private val lines: Vector[String] = Vector(
    """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Think"}""",
    """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":" more."}""",
    """data: {"type":"response.output_text.delta","output_index":1,"delta":"Paris"}""",
    """data: {"type":"response.output_text.annotation.added","output_index":1,"annotation":{"type":"url_citation","url":"https://x.test","title":"X","start_index":0,"end_index":5}}""",
    """data: {"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"call_1","name":"search","arguments":""}}""",
    """data: {"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"q\": "}""",
    """data: {"type":"response.completed","response":{"output":[{"type":"function_call"}]}}""",
    ""
  )

  private val request = LmRequest(
    model = "gpt-test",
    mode = LmMode.Responses,
    messages = Vector(Message(role = MessageRole.User, text = Some("Capital of France?")))
  )

  private def openAi(transport: HttpTransport): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = "gpt-test",
      mode = LmMode.Responses,
      client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    )

  private def wrapped(model: StreamingLanguageModel)(using RuntimeContext): LmResponse =
    val queue = StreamingQueue[StreamEvent]()
    try StreamingLanguageModelWrapper(model, queue).call(request) match
        case Right(response) => response
        case Left(error)     => fail(s"the wrapper call failed: $error")
    finally queue.close()

  private def plainCall(model: LanguageModel)(using RuntimeContext): LmResponse =
    model.call(request) match
      case Right(response) => response
      case Left(error)     => fail(s"the plain call failed: $error")

  private def decode(text: String): Either[DspyError, DynamicValue] =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).left.map(err =>
      ParseError("json", err.toString)
    )

  test("the wrapper gives the reasoning, the citations, and the partial arguments of a Responses stream") {
    given RuntimeContext = RuntimeEnvironment.current
    val plain            = plainCall(openAi(Scripted(body, Vector.empty)))
    val streamed         = wrapped(openAi(Scripted(body, lines)))
    assertEquals(streamed.outputs.map(_.text), plain.outputs.map(_.text))
    assertEquals(streamed.outputs.map(_.metadata), plain.outputs.map(_.metadata))
    // Upstream lm15 `_parse_json_best_effort`: streamed arguments that are not JSON give `{"partial_json": raw}`.
    assertEquals(
      streamed.outputs.flatMap(_.toolCalls).map(call => DynamicValues.recordToMap(call.args)),
      Vector(Map[String, Any]("partial_json" -> "{\"q\": "))
    )
  }

  test("the wrapper gives the reasoning and the citations of a Responses model that cannot stream") {
    given RuntimeContext = RuntimeEnvironment.current
    val provider         = ProviderLanguageModel(id = "gpt-test", mode = LmMode.Responses, invoke = _ => decode(body))
    val plain            = plainCall(provider)
    assert(plain.outputs.exists(_.metadata.fields.nonEmpty), plain.toString)
    val streamed = wrapped(ManagedLanguageModel(delegate = provider))
    assertEquals(streamed.outputs.map(_.metadata), plain.outputs.map(_.metadata))
  }

  test("a stream with no reasoning and no citation keeps an empty metadata") {
    given RuntimeContext = RuntimeEnvironment.current
    val textOnly         = Vector(
      """data: {"type":"response.output_text.delta","output_index":0,"delta":"Paris"}""",
      """data: {"type":"response.completed","response":{"output":[{"type":"message"}]}}""",
      ""
    )
    val streamed = wrapped(openAi(Scripted(body, textOnly)))
    assertEquals(streamed.outputs.map(_.text), Vector("Paris"))
    assertEquals(streamed.outputs.map(_.metadata), Vector(DynamicValue.Record.empty))
  }
