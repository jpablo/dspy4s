package dspy4s.lm.providers

import dspy4s.core.contracts.{ClosableIterator, DspyError, LmRateLimitError, PythonText, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LmChunk, LmMode, LmOutput, LmRequest, Message, MessageRole}
import dspy4s.lm.runtime.{ManagedLanguageModel, RetryCount, RetryPolicies, StreamAccumulator}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The reasoning of a chat reply reaches `LmOutput.reasoning`. Upstream keeps `reasoning_content` of a choice
  * (`dspy/clients/base_lm.py:352`), and its stream reads `reasoning_content` or `reasoning` of a delta
  * (`dspy/_vendor/lm15/providers/openai_chat.py:2085`). An OpenAI-compatible server can send either key.
  */
class ChatReasoningSuite extends FunSuite:

  private def record(json: String): DynamicValue =
    PythonText.parseJson(json).fold(error => fail(error.toString), identity)

  private def chat(json: String): Vector[LmOutput] =
    ChatWire.outputs(record(json)).fold(error => fail(error.toString), identity)

  private def question: LmRequest =
    LmRequest(model = "m", messages = Vector(Message(MessageRole.User, Some("Q?"))))

  private final class StreamOf(lines: Vector[String]) extends HttpTransport:
    var opened = 0

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Right(HttpResponse(200, Map.empty, "{}"))

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      opened += 1
      val source = lines.iterator
      Right(HttpStreamResponse(
        200,
        Map.empty,
        new ClosableIterator[String]:
          override def hasNext: Boolean = source.hasNext
          override def next(): String   = source.next()
          override def close(): Unit    = ()
      ))

  private def streamed(lines: String*) =
    val model = OpenAiLanguageModel(
      model = "m",
      client = OpenAiClient(apiKey = "x", transport = new StreamOf(lines.toVector :+ "data: [DONE]"))
    )
    given RuntimeContext = RuntimeEnvironment.current
    model.stream(question).toVector

  /** The chunks and the number of opened streams of a stream of `lines` through a [[ManagedLanguageModel]] with one
    * retry.
    */
  private def managedStream(mode: LmMode, lines: String*): (Vector[LmChunk], Int) =
    val transport = new StreamOf(lines.toVector)
    val model     = ManagedLanguageModel(
      delegate =
        OpenAiLanguageModel(model = "m", mode = mode, client = OpenAiClient(apiKey = "x", transport = transport)),
      retryPolicy = RetryPolicies.maxRetries(RetryCount(1)),
      sleep = _ => ()
    )
    given RuntimeContext = RuntimeEnvironment.current
    (model.stream(question).toVector, transport.opened)

  private def chatBusy: String = """data: {"error":{"code":"rate_limit_error","message":"busy"}}"""

  private def responsesBusy: String = """data: {"type":"error","code":"rate_limit_error","message":"busy"}"""

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── A plain reply ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a chat reply keeps reasoning_content") {
    val outputs = chat("""{"choices": [{"message": {"content": "a", "reasoning_content": "why"}}]}""")
    assertEquals(outputs.map(output => (output.text, output.reasoning)), Vector(("a", "why")))
  }

  test("a chat reply keeps reasoning, the key of other servers") {
    val outputs = chat("""{"choices": [{"message": {"content": "a", "reasoning": "why"}}]}""")
    assertEquals(outputs.map(output => (output.text, output.reasoning)), Vector(("a", "why")))
  }

  test("reasoning_content wins when the reply has the two keys") {
    val outputs =
      chat("""{"choices": [{"message": {"content": "a", "reasoning_content": "one", "reasoning": "two"}}]}""")
    assertEquals(outputs.map(_.reasoning), Vector("one"))
  }

  test("an empty or a null reasoning_content does not hide reasoning") {
    assertEquals(
      chat("""{"choices": [{"message": {"content": "a", "reasoning_content": null, "reasoning": "two"}}]}""")
        .map(_.reasoning),
      Vector("two")
    )
    assertEquals(
      chat("""{"choices": [{"message": {"content": "a", "reasoning_content": "", "reasoning": "two"}}]}""")
        .map(_.reasoning),
      Vector("two")
    )
  }

  test("a reply with no reasoning has the empty text, also with null keys") {
    assertEquals(chat("""{"choices": [{"message": {"content": "a"}}]}""").map(_.reasoning), Vector(""))
    assertEquals(
      chat("""{"choices": [{"message": {"content": "a", "reasoning_content": null, "reasoning": null}}]}""")
        .map(_.reasoning),
      Vector("")
    )
  }

  test("a value of reasoning that is not a text is no reasoning") {
    assertEquals(
      chat("""{"choices": [{"message": {"content": "a", "reasoning": {"effort": "low"}}}]}""").map(_.reasoning),
      Vector("")
    )
  }

  test("each choice has its own reasoning") {
    val outputs = chat(
      """{"choices": [{"message": {"content": "a", "reasoning": "one"}},
        |{"message": {"content": "b"}},
        |{"message": {"content": "c", "reasoning_content": "three"}}]}""".stripMargin
    )
    assertEquals(outputs.map(_.reasoning), Vector("one", "", "three"))
  }

  test("a reply with only reasoning is an output with an empty text and the reasoning") {
    val outputs =
      chat("""{"choices": [{"message": {"content": null, "reasoning": "why"}, "finish_reason": "length"}]}""")
    assertEquals(outputs.map(output => (output.text, output.reasoning)), Vector(("", "why")))
  }

  test("the reasoning keeps its white space") {
    val outputs = chat("""{"choices": [{"message": {"content": "a", "reasoning": "  why\n"}}]}""")
    assertEquals(outputs.map(_.reasoning), Vector("  why\n"))
  }

  test("a text reply has its reasoning too") {
    val outputs = ChatWire.textOutputs(record("""{"choices": [{"message": {"content": "a", "reasoning": "why"}}]}"""))
      .fold(error => fail(error.toString), identity)
    assertEquals(outputs.map(_.reasoning), Vector("why"))
  }

  // ── A stream ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("a stream gives reasoning_content and reasoning of each delta as the reasoning of its chunk") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"reasoning_content":"one "}}]}""",
      """data: {"choices":[{"delta":{"reasoning":"two"}}]}""",
      """data: {"choices":[{"delta":{"content":"a"}}]}""",
      """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
    )
    assertEquals(chunks.map(_.reasoning).filter(_.nonEmpty), Vector("one ", "two"))
    assertEquals(chunks.map(_.text).mkString, "a")
  }

  test("reasoning_content wins in a delta with the two keys, and an empty one does not hide reasoning") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"reasoning_content":"one","reasoning":"two"}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":"","reasoning":"three"}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":null,"reasoning":"four"}}]}""",
      """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
    )
    assertEquals(chunks.map(_.reasoning).filter(_.nonEmpty), Vector("one", "three", "four"))
  }

  test("an assembled stream has the reasoning, as a plain reply has it") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"reasoning":"why "}}]}""",
      """data: {"choices":[{"delta":{"reasoning":"so"}}]}""",
      """data: {"choices":[{"delta":{"content":"a"},"finish_reason":"stop"}]}"""
    )
    val accumulator = new StreamAccumulator(LmMode.Chat)
    chunks.foreach(accumulator.push)
    assertEquals(accumulator.output.reasoning, "why so")
    assertEquals(accumulator.output.text, "a")
  }

  test("a stream with only reasoning assembles to an empty text and the reasoning") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"reasoning":"why"}}]}""",
      """data: {"choices":[{"delta":{},"finish_reason":"length"}]}"""
    )
    val accumulator = new StreamAccumulator(LmMode.Chat)
    chunks.foreach(accumulator.push)
    assertEquals((accumulator.output.text, accumulator.output.reasoning), ("", "why"))
  }

  test("a stream keeps the white space of the reasoning, also of a delta with only white space") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"reasoning":"  why\n"}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":" "}}]}""",
      """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
    )
    assertEquals(chunks.map(_.reasoning).filter(_.nonEmpty), Vector("  why\n", " "))
  }

  test("a value of reasoning that is not a text is no reasoning in a delta, and the chunk keeps its other fields") {
    val chunks = streamed(
      """data: {"choices":[{"delta":{"content":"a","reasoning":{"effort":"low"}}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":"why","reasoning":7}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":["x"],"reasoning":"so"}}]}""",
      """data: {"choices":[{"delta":{"reasoning_content":true,"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
    )
    assertEquals(chunks.map(_.text).mkString, "a")
    assertEquals(chunks.map(_.reasoning).filter(_.nonEmpty), Vector("why", "so"))
    assertEquals(chunks.flatMap(_.toolCalls).map(_.name), Vector(Some("f")))
    assertEquals(chunks.flatMap(_.finishReason), Vector("tool_calls"))
    assertEquals(chunks.flatMap(_.usage).map(_.totalTokens), Vector(3L))
    assertEquals(chunks.flatMap(_.error), Vector.empty)
  }

  // ── A failure in a stream ─────────────────────────────────────────────────────────────────────────────────────

  test("a failure after only reasoning gets no retry, in a chat stream as in a Responses stream") {
    val outcomes = Vector(
      managedStream(LmMode.Chat, """data: {"choices":[{"delta":{"reasoning":"why"}}]}""", chatBusy),
      managedStream(LmMode.Responses, """data: {"type":"response.reasoning_text.delta","delta":"why"}""", responsesBusy)
    )
    outcomes.foreach { (chunks, opened) =>
      assertEquals(opened, 1, chunks.toString)
      assertEquals(chunks.map(_.reasoning).mkString, "why")
      assert(chunks.lastOption.flatMap(_.error).exists(_.isInstanceOf[LmRateLimitError]), chunks.toString)
    }
  }

  test("the same failure with no reasoning before it gets the retry, in a chat stream as in a Responses stream") {
    assertEquals(managedStream(LmMode.Chat, chatBusy)._2, 2)
    assertEquals(managedStream(LmMode.Responses, responsesBusy)._2, 2)
  }
