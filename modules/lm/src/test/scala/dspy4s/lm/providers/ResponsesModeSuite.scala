package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmProviderResponseError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.LmCost
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmPrice
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RetryCount
import dspy4s.lm.runtime.RetryPolicies
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** An OpenAI provider in the Responses mode calls the Responses endpoint with the Responses body, and reads the one
  * output of the response (PORT_GAPS G-26, upgrade item L7). A stream in that mode reads the events of the Responses
  * stream.
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

  /** A transport that gives one stream with `lines` to each stream request, and records the request. A plain request
    * gets the Responses body [[responsesBody]], and is recorded too.
    */
  private final class Streaming(lines: Vector[String]) extends HttpTransport:
    val sent: ArrayBuffer[(String, String)]                                                                            = ArrayBuffer.empty
    val streamed: ArrayBuffer[(String, String)]                                                                        = ArrayBuffer.empty
    override def sendJson(url: String, headers: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      sent += ((url, payload))
      Right(HttpResponse(200, Map.empty, responsesBody))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      streamed += ((url, payload))
      val source = lines.iterator
      Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new ClosableIterator[String]:
          override def hasNext: Boolean = source.hasNext
          override def next(): String   = source.next()
          override def close(): Unit    = ()
      ))

  /** The end event of a Responses stream with text only. */
  private val completed: String =
    """data: {"type":"response.completed","response":{"output":[{"type":"message"}],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}"""

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

  /** A Responses body with reasoning summary parts and two annotations, and the events of a stream of the same
    * response.
    */
  private val citedBody: String = """{"model": "gpt-test", "output": [
      |  {"type": "reasoning", "summary": [{"type": "summary_text", "text": "Think"},
      |                                    {"type": "summary_text", "text": " more."}]},
      |  {"type": "message", "content": [{"type": "output_text", "text": "Paris", "annotations": [
      |    {"type": "url_citation", "url": "https://x.test", "title": "X", "start_index": 0, "end_index": 5},
      |    {"type": "file_citation", "file_id": "file_1", "filename": "notes.txt", "index": 2}
      |  ]}]}
      |], "usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}""".stripMargin

  private val citedLines: Vector[String] = Vector(
    """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
    """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"Think"}""",
    """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":1,"delta":" more."}""",
    """data: {"type":"response.output_text.delta","output_index":1,"delta":"Paris"}""",
    """data: {"type":"response.output_text.annotation.added","output_index":1,"annotation_index":0,"annotation":{"type":"url_citation","url":"https://x.test","title":"X","start_index":0,"end_index":5}}""",
    """data: {"type":"response.output_text.annotation.added","output_index":1,"annotation_index":1,"annotation":{"type":"file_citation","file_id":"file_1","filename":"notes.txt","index":2}}""",
    completed,
    ""
  )

  /** A Responses body with reasoning content parts, and the events of a stream of the same response. */
  private val reasonedBody: String = """{"model": "gpt-test", "output": [
      |  {"type": "reasoning", "content": [{"type": "reasoning_text", "text": "Raw"},
      |                                    {"type": "reasoning_text", "text": " chain."}]},
      |  {"type": "message", "content": [{"type": "output_text", "text": "Paris"}]}
      |], "usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}""".stripMargin

  private val reasonedLines: Vector[String] = Vector(
    """data: {"type":"response.reasoning_text.delta","output_index":0,"content_index":0,"delta":"Raw"}""",
    """data: {"type":"response.reasoning_text.delta","output_index":0,"content_index":1,"delta":" chain."}""",
    """data: {"type":"response.output_text.delta","output_index":1,"delta":"Paris"}""",
    completed,
    ""
  )

  private def model(transport: HttpTransport, mode: LmMode = LmMode.Responses): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = "gpt-test",
      mode = mode,
      client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    )

  private def json(text: String): DynamicValue.Record =
    DynamicJson.decode(text) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"not a JSON object: $other")

  /** The response that a managed model assembles from a stream of `lines` in `mode`. The managed model stores the
    * assembled response in its cache, and the next call gives that response back.
    */
  private def assembled(lines: Vector[String], mode: LmMode = LmMode.Responses): LmResponse =
    given RuntimeContext = RuntimeContext(disableHistory = Some(true))
    val managed          =
      ManagedLanguageModel(delegate = model(Streaming(lines), mode), cache = Some(InMemoryLmCache(CacheCapacity(4))))
    val chunks = managed.stream(request).toVector
    assertEquals(chunks.flatMap(_.error), Vector.empty)
    managed.call(request) match
      case Right(response) if response.cacheHit => response
      case other                                => fail(s"the stream gave no cached response: $other")

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

  test("a stream in the Responses mode posts the Responses body with stream true to the Responses endpoint") {
    // Upstream lm15 `OpenAILM._payload` and `build_request` (`dspy/_vendor/lm15/providers/openai.py`): the body has
    // `"stream": true` and no `stream_options`.
    val transport        = Streaming(Vector(completed, ""))
    given RuntimeContext = RuntimeContext()
    val _                = model(transport).stream(request).toVector
    assertEquals(transport.sent.toVector, Vector.empty)
    val (url, payload) = transport.streamed.headOption.getOrElse(fail("no stream request"))
    assertEquals(url, "https://api.test/v1/responses")
    val body = json(payload)
    assert(DynamicValues.recordGet(body, "input").isDefined, payload)
    assertEquals(DynamicValues.recordGet(body, "stream"), Some(DynamicValues.fromAny(true)))
    assertEquals(DynamicValues.recordGet(body, "messages"), None)
    assertEquals(DynamicValues.recordGet(body, "stream_options"), None)
    assertEquals(DynamicValues.recordGet(body, "mode"), None)
  }

  test("a Responses stream gives the text deltas, the function-call argument deltas, and the usage of the end event") {
    // Upstream lm15 `OpenAILM.parse_stream_events` (`dspy/_vendor/lm15/providers/openai.py`).
    val lines = Vector(
      """event: response.created""",
      """data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}""",
      """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1"}}""",
      """data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","delta":"Par"}""",
      """data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","delta":"is"}""",
      """data: {"type":"response.output_text.done","output_index":0,"item_id":"msg_1","text":"Paris"}""",
      """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":""}}""",
      """data: {"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1","delta":"{\"q\":"}""",
      """data: {"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1","delta":"\"x\"}"}""",
      """data: {"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"x\"}"}}""",
      """data: {"type":"response.completed","response":{"output":[{"type":"message"},{"type":"function_call","call_id":"call_1"}],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}""",
      ""
    )
    given RuntimeContext = RuntimeContext()
    val chunks           = model(Streaming(lines)).stream(request).toVector
    assertEquals(chunks.flatMap(_.error), Vector.empty)
    assertEquals(chunks.map(_.text).mkString, "Paris")
    assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Par", "is"))
    assertEquals(
      ToolCallAssembler.assemble(chunks.flatMap(_.toolCalls)),
      Vector(ToolCall(name = "search", args = json("""{"q":"x"}"""), id = Some("call_1")))
    )
    assertEquals(chunks.flatMap(_.finishReason), Vector("tool_call"))
    assertEquals(
      chunks.flatMap(_.usage).map(u => (u.promptTokens, u.completionTokens, u.totalTokens)),
      Vector((4L, 2L, 6L))
    )
  }

  test("a Responses stream with only text ends with the finish reason stop") {
    val lines = Vector(
      """data: {"type":"response.output_text.delta","output_index":0,"delta":"Par"}""",
      """data: {"type":"response.output_text.delta","output_index":0,"delta":"is"}""",
      completed,
      ""
    )
    given RuntimeContext = RuntimeContext()
    val chunks           = model(Streaming(lines)).stream(request).toVector
    assertEquals(chunks.flatMap(_.error), Vector.empty)
    assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Par", "is"))
    assertEquals(chunks.flatMap(_.finishReason), Vector("stop"))
  }

  test("an error event of a Responses stream gives the typed error of its code, and the stream ends") {
    // Upstream `parse_stream_events`: the code and the message of an `error` object, else of the event.
    val cases: Vector[(String, DspyError => Boolean, String)] = Vector(
      (
        """{"type":"error","code":"rate_limit_exceeded","message":"slow down"}""",
        _.isInstanceOf[LmRateLimitError],
        "slow down"
      ),
      (
        """{"type":"response.error","error":{"code":"context_length_exceeded","message":"too long"}}""",
        _.isInstanceOf[ContextWindowExceededError],
        "too long"
      ),
      ("""{"type":"error","code":"something_new"}""", _.isInstanceOf[LmProviderResponseError], "something_new")
    )
    cases.foreach { (event, hasClass, message) =>
      val lines = Vector(
        """data: {"type":"response.output_text.delta","output_index":0,"delta":"Hi"}""",
        s"data: $event",
        """data: {"type":"response.output_text.delta","output_index":0,"delta":"late"}""",
        completed,
        ""
      )
      given RuntimeContext = RuntimeContext()
      val chunks           = model(Streaming(lines)).stream(request).toVector
      assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Hi"), event)
      val errors = chunks.flatMap(_.error)
      assertEquals(errors.size, 1, event)
      assert(hasClass(errors.head), s"$event gave ${errors.head}")
      assert(errors.head.message.contains(message), s"$event gave the message ${errors.head.message}")
      assertEquals(chunks.last.finishReason, Some("error"), event)
    }
  }

  test("a Responses stream with no end event ends with a stream assembly error") {
    val lines            = Vector("""data: {"type":"response.output_text.delta","output_index":0,"delta":"Hi"}""", "")
    given RuntimeContext = RuntimeContext()
    val chunks           = model(Streaming(lines)).stream(request).toVector
    assert(chunks.last.error.exists(_.isInstanceOf[LmStreamAssemblyError]), chunks.toString)
  }

  test("a Responses call and a Responses stream read the cached and the reasoning counts of the usage") {
    // Upstream lm15 `OpenAILM.parse_response` and `_parse_single_stream_event` (`dspy/_vendor/lm15/providers/openai.py`)
    // read `cached_tokens` of `input_tokens_details` and `reasoning_tokens` of `output_tokens_details`.
    val usage =
      """{"input_tokens":10,"output_tokens":8,"total_tokens":18,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":5}}"""
    val body =
      s"""{"model": "gpt-test", "output": [{"type": "message", "content": [{"type": "output_text", "text": "Paris"}]}], "usage": $usage}"""
    val lines = Vector(
      """data: {"type":"response.output_text.delta","output_index":0,"delta":"Paris"}""",
      s"""data: {"type":"response.completed","response":{"output":[{"type":"message"}],"usage":$usage}}""",
      ""
    )
    val expected = LmUsage(
      totalTokens = 18L,
      promptTokens = 10L,
      completionTokens = 8L,
      extras = Map(TokenCategory.Cached -> 4L, TokenCategory.Reasoning -> 5L)
    )
    given RuntimeContext = RuntimeContext()
    model(Recording(body)).call(request) match
      case Right(response) => assertEquals(response.usage, Some(expected))
      case Left(error)     => fail(s"the call failed: $error")
    val streamed = model(Streaming(lines)).stream(request).toVector.flatMap(_.usage)
    assertEquals(streamed, Vector(expected))
    // The cost estimate of batch 24 prices the cached tokens and the reasoning tokens at their own rates.
    val price = LmPrice(
      inputPerMillion = 1.0,
      outputPerMillion = 2.0,
      cachedInputPerMillion = Some(0.5),
      reasoningPerMillion = Some(4.0)
    )
    assertEquals(
      LmCost.of("gpt-test", Some(price), streamed.headOption),
      LmCost.Estimate((6.0 + 2.0 + 6.0 + 20.0) / 1_000_000.0, "gpt-test")
    )
  }

  test("the assembled response of a Responses stream has the reasoning and the citations of a plain call") {
    // Upstream lm15 `_parse_single_stream_event` (`dspy/_vendor/lm15/providers/openai.py`) gives a thinking delta for
    // each reasoning text event and a citation delta for each annotation event.
    Vector((citedBody, citedLines), (reasonedBody, reasonedLines)).foreach { (body, lines) =>
      given RuntimeContext = RuntimeContext()
      val plain            = model(Recording(body)).call(request) match
        case Right(response) => response
        case Left(error)     => fail(s"the call failed: $error")
      assert(plain.outputs.exists(_.metadata.fields.nonEmpty), plain.toString)
      val streamed = assembled(lines)
      assertEquals(streamed.outputs.map(_.text), plain.outputs.map(_.text))
      assertEquals(streamed.outputs.map(_.metadata), plain.outputs.map(_.metadata))
    }
  }

  test("a managed Responses stream gives a reasoning event to the caller, and does not retry after it") {
    // Upstream `_attempt` and `_retryable` (`dspy/clients/execution.py`): a thinking delta is a chunk for the stream,
    // and an attempt with a chunk for the stream gets no retry.
    val lines = Vector(
      """data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Think"}""",
      """data: {"type":"error","code":"rate_limit_exceeded","message":"slow down"}""",
      ""
    )
    val transport        = Streaming(lines)
    given RuntimeContext = RuntimeContext(disableHistory = Some(true))
    val managed          = ManagedLanguageModel(
      delegate = model(transport),
      retryPolicy = RetryPolicies.maxRetries(RetryCount(2)),
      sleep = _ => ()
    )
    val chunks = managed.stream(request).toVector
    assertEquals(transport.streamed.size, 1)
    assertEquals(chunks.size, 2, chunks.toString)
    assert(chunks.last.error.exists(_.isInstanceOf[LmRateLimitError]), chunks.toString)
  }

  test("streamed Responses arguments that are not JSON give partial_json, and the chat stream keeps its rule") {
    // Upstream lm15 `StreamAccumulator.push` and `_parse_json_best_effort` (`dspy/_vendor/lm15/result.py`): the text
    // keeps its white space, and only an empty text gives an empty record.
    def call(index: Int, name: String, arguments: String): Vector[String] =
      Vector(
        s"""data: {"type":"response.output_item.added","output_index":$index,"item":{"type":"function_call","call_id":"call_$index","name":"$name","arguments":""}}""",
        s"""data: {"type":"response.function_call_arguments.delta","output_index":$index,"delta":$arguments}"""
      )
    val lines = call(0, "search", """" {\"q\": """") ++ call(1, "blank", """"  """") ++
      call(2, "spaced", """" {\"a\": 1} """") ++ call(3, "empty", """""""") ++ Vector(
        """data: {"type":"response.completed","response":{"output":[{"type":"function_call"}]}}""",
        ""
      )
    assertEquals(
      assembled(lines).outputs.flatMap(_.toolCalls),
      Vector(
        ToolCall(name = "search", args = json("""{"partial_json": " {\"q\": "}"""), id = Some("call_0")),
        ToolCall(name = "blank", args = json("""{"partial_json": "  "}"""), id = Some("call_1")),
        ToolCall(name = "spaced", args = json("""{"a": 1}"""), id = Some("call_2")),
        ToolCall(name = "empty", args = DynamicValue.Record.empty, id = Some("call_3"))
      )
    )
    val chat = Vector(
      """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_0","function":{"name":"search","arguments":" {\"q\": "}}]},"finish_reason":"tool_calls"}]}""",
      "data: [DONE]"
    )
    assertEquals(
      assembled(chat, LmMode.Chat).outputs.flatMap(_.toolCalls),
      Vector(ToolCall(name = "search", args = json("""{"input": "{\"q\":"}"""), id = Some("call_0")))
    )
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
