package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, NativeTools, ParsedOutput, ToolSpec}
import dspy4s.adapters.{ChatAdapter, JSONAdapter, TwoStepAdapter, XMLAdapter}
import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, ParseError, RuntimeContext, ToolCall, :=}
import dspy4s.core.runtime.{RuntimeEnvironment, StreamProgress}
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.lm.providers.{HttpResponse, HttpStreamResponse, HttpTransport, OpenAiLanguageModel}
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class RfrInput(question: String) derives Schema
final case class RfrTags(tags: List[String]) derives Schema
final case class RfrInner(count: Int) derives Schema
final case class RfrNested(item: RfrInner) derives Schema
final case class RfrAnswer(answer: String) derives Schema
final case class RfrPair(answer: String, note: String) derives Schema
final case class RfrOnlyCalls(calls: Vector[ToolCall]) derives Schema
final case class RfrTextAndCalls(answer: String, calls: Vector[ToolCall]) derives Schema
final case class RfrOptionAndCalls(answer: Option[String], calls: Vector[ToolCall]) derives Schema
final case class RfrTagsAndCalls(tags: Option[List[String]], calls: Vector[ToolCall]) derives Schema
final case class RfrAllAndCalls(answer: String, tags: Option[List[String]], calls: Vector[ToolCall]) derives Schema

/** When the second adapter runs (upstream `ChatAdapter.__call__`). Upstream `parse_value` validates each field with its
  * annotation inside the parse step, and `_call_postprocess` raises `AdapterParseError` for an empty reply, so these
  * two failures reach the `JSONAdapter` fallback.
  */
class ReplyFallbackRulesSuite extends FunSuite:

  /** A model that gives the outputs of the next entry of `replies` for each call. */
  private class Script(replies: Vector[LmOutput]*) extends LanguageModel:
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(
        outputs = replies(requests.size - 1),
        usage = Some(LmUsage(totalTokens = 3, promptTokens = 1, completionTokens = 2))
      ))

  private def texts(replies: String*): Vector[Vector[LmOutput]] =
    replies.toVector.map(text => Vector(LmOutput(text = text)))

  private def withModel[A](model: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def tagsProgram: Predict[RfrInput, RfrTags] =
    Predict(Signature.derived[RfrInput, RfrTags](name = "Tags", instructions = "Give the tags."))

  private def nestedProgram: Predict[RfrInput, RfrNested] =
    Predict(Signature.derived[RfrInput, RfrNested](name = "Nested", instructions = "Give the item."))

  private def answerProgram: Predict[RfrInput, RfrAnswer] =
    Predict(Signature.derived[RfrInput, RfrAnswer](name = "Answer", instructions = "Give the answer."))

  private def badItems: String  = "[[ ## tags ## ]]\n[{\"a\": 1}]\n\n[[ ## completed ## ]]"
  private def goodItems: String = "[[ ## tags ## ]]\n[\"x\"]\n\n[[ ## completed ## ]]"
  private def goodJson: String  = """{"tags": ["x"]}"""

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── A reply that the typed decode rejects ─────────────────────────────────────────────────────────────────────

  test("a list item of the wrong type gets a second call with a JSONAdapter") {
    val model  = Script(texts(badItems, goodJson)*)
    val result = withModel(model, ChatAdapter())(tagsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrTags(List("x"))))
    assertEquals(model.requests.size, 2)
  }

  test("a value of a nested record with the wrong type gets a second call with a JSONAdapter") {
    val model = Script(texts(
      "[[ ## item ## ]]\n{\"count\": \"many\"}\n\n[[ ## completed ## ]]",
      """{"item": {"count": 2}}"""
    )*)
    val result = withModel(model, ChatAdapter())(nestedProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrNested(RfrInner(2))))
    assertEquals(model.requests.size, 2)
  }

  test("a reply that the typed decode rejects two times is a parse error with the path of the value and the reply") {
    val second = """{"tags": [{"a": 1}]}"""
    val model  = Script(texts(badItems, second)*)
    withModel(model, ChatAdapter())(tagsProgram(RfrInput("q"))) match
      case Left(error: ParseError) =>
        assert(error.message.contains(".tags"), error.message)
        assert(!error.message.contains("zio.blocks"), error.message)
        assertEquals(error.raw, Some(second))
      case other => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 2)
  }

  test("with no fallback, a reply that the typed decode rejects is a parse error after one call") {
    val model  = Script(texts(badItems, goodJson)*)
    val result = withModel(model, ChatAdapter(useJsonAdapterFallback = false))(tagsProgram(RfrInput("q")))
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(model.requests.size, 1)
  }

  test("a JSONAdapter gives a parse error after one call for a null in a list field") {
    val model  = Script(texts("""{"tags": null}""", goodJson)*)
    val result = withModel(model, JSONAdapter())(tagsProgram(RfrInput("q")))
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(model.requests.size, 1)
  }

  test(
    "a completion after the first that the typed decode rejects also gets the second call, as upstream parses each"
  ) {
    val model = Script(
      Vector(LmOutput(text = goodItems), LmOutput(text = badItems)),
      Vector(LmOutput(text = goodJson))
    )
    val result = withModel(model, ChatAdapter())(tagsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrTags(List("x"))))
    assertEquals(model.requests.size, 2)
  }

  test("a list[int] item of a string signature that is not a number gets a second call with a JSONAdapter") {
    val layout = SignatureDsl.parse("question -> xs: list[int]").fold(error => fail(error.toString), identity)
    val model  = Script(texts("[[ ## xs ## ]]\n[\"a\"]\n\n[[ ## completed ## ]]", """{"xs": [1]}""")*)
    val result = withModel(model, ChatAdapter()) {
      strategies.DynamicPredict(layout)(ProgramCall(input = DynamicValues.record("question" := "q")))
    }
    assert(result.isRight, result.toString)
    assertEquals(model.requests.size, 2)
  }

  /** The three adapter cells of one reply that fails, with the reply in the chat and in the JSON format. */
  private def failureMatrix[O](
      name    : String,
      program : => Predict[RfrInput, O],
      badChat : String,
      badJson : String,
      goodJson: String,
      expected: O
  ): Unit =
    test(s"$name: a ChatAdapter gets a second call with a JSONAdapter") {
      val model  = Script(texts(badChat, goodJson)*)
      val result = withModel(model, ChatAdapter())(program(RfrInput("q")))
      assertEquals(result.map(_.output), Right(expected))
      assertEquals(model.requests.size, 2)
    }

    test(s"$name: a ChatAdapter with no fallback gives a parse error with the reply after one call") {
      val model = Script(texts(badChat, goodJson)*)
      withModel(model, ChatAdapter(useJsonAdapterFallback = false))(program(RfrInput("q"))) match
        case Left(error: ParseError) => assertEquals(error.raw, Some(badChat))
        case other                   => fail(s"expected a parse error, got $other")
      assertEquals(model.requests.size, 1)
    }

    test(s"$name: a JSONAdapter gives a parse error with the reply after one call") {
      val model = Script(texts(badJson, goodJson)*)
      withModel(model, JSONAdapter())(program(RfrInput("q"))) match
        case Left(error: ParseError) => assertEquals(error.raw, Some(badJson))
        case other                   => fail(s"expected a parse error, got $other")
      assertEquals(model.requests.size, 1)
    }

  failureMatrix(
    "a list item of the wrong type",
    tagsProgram,
    badItems,
    """{"tags": [{"a": 1}]}""",
    goodJson,
    RfrTags(List("x"))
  )

  failureMatrix(
    "a value of a nested record with the wrong type",
    nestedProgram,
    "[[ ## item ## ]]\n{\"count\": \"many\"}\n\n[[ ## completed ## ]]",
    """{"item": {"count": "many"}}""",
    """{"item": {"count": 2}}""",
    RfrNested(RfrInner(2))
  )

  failureMatrix(
    "a null in a list that is not optional",
    tagsProgram,
    "[[ ## tags ## ]]\n[null]\n\n[[ ## completed ## ]]",
    """{"tags": [null]}""",
    goodJson,
    RfrTags(List("x"))
  )

  failureMatrix(
    "a missing field",
    Predict(Signature.derived[RfrInput, RfrPair](name = "Pair", instructions = "Give the pair.")),
    "[[ ## answer ## ]]\na\n\n[[ ## completed ## ]]",
    """{"answer": "a"}""",
    """{"answer": "a", "note": "b"}""",
    RfrPair("a", "b")
  )

  test("a call that streamed output gets no second call when the typed decode rejects the reply") {
    // Upstream `ChatAdapter.__call__`: `if progress.emitted ... raise`.
    val script = Script(texts(badItems, goodJson)*)
    val model  = new LanguageModel:
      override val id: String                                                                    = script.id
      override val mode: LmMode                                                                  = script.mode
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        StreamProgress.markEmitted()
        script.call(request)
    withModel(model, ChatAdapter())(tagsProgram(RfrInput("q"))) match
      case Left(error: ParseError) => assertEquals(error.raw, Some(badItems))
      case other                   => fail(s"expected a parse error, got $other")
    assertEquals(script.requests.size, 1)
  }

  test("a TwoStepAdapter gives a parse error with the completion of the main model for a rejected extraction") {
    val completion = "The tags are not clear."
    val extraction = Script(texts(badItems)*)
    val model      = Script(texts(completion)*)
    withModel(model, TwoStepAdapter(extraction))(tagsProgram(RfrInput("q"))) match
      case Left(error: ParseError) =>
        assert(error.message.contains(".tags"), error.message)
        assertEquals(error.raw, Some(completion))
      case other => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 1)
    assertEquals(extraction.requests.size, 1)
  }

  // ── Provider tool calls and the typed decode ──────────────────────────────────────────────────────────────────

  private def toolCalls: Vector[ToolCall] = Vector(ToolCall("search", DynamicValue.Record.empty))

  private def toolTurn: Vector[LmOutput] = Vector(LmOutput(text = "", toolCalls = toolCalls))

  /** A model with native function calling, with a reply of `replies` for each call. */
  private def nativeModel(replies: Vector[LmOutput]*): Script =
    new Script(replies*):
      override def supportsFunctionCalling: Boolean = true

  private def withTools[O](signature: Signature[RfrInput, O]): Predict[RfrInput, O] =
    Predict(signature = signature, tools = Vector(ToolSpec("search", Some("Search."), DynamicValue.Record.empty)))

  private def onlyCallsProgram: Predict[RfrInput, RfrOnlyCalls] =
    withTools(Signature.derived[RfrInput, RfrOnlyCalls](name = "OnlyCalls", instructions = "Use the tools."))

  private def textAndCallsProgram: Predict[RfrInput, RfrTextAndCalls] =
    withTools(Signature.derived[RfrInput, RfrTextAndCalls](name = "TextAndCalls", instructions = "Use the tools."))

  private def optionAndCallsProgram: Predict[RfrInput, RfrOptionAndCalls] =
    withTools(Signature.derived[RfrInput, RfrOptionAndCalls](name = "OptionAndCalls", instructions = "Use the tools."))

  private def nativeChat: ChatAdapter = ChatAdapter(native = NativeTools(enabled = true))

  test("a tool turn passes the typed decode when the only output is the list of calls") {
    val model  = nativeModel(toolTurn)
    val result = withModel(model, nativeChat)(onlyCallsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrOnlyCalls(toolCalls)))
    assertEquals(model.requests.size, 1)
  }

  test("a tool turn gives a null to an Option output, and it passes the typed decode") {
    val model  = nativeModel(toolTurn)
    val result = withModel(model, nativeChat)(optionAndCallsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrOptionAndCalls(None, toolCalls)))
    assertEquals(model.requests.size, 1)
  }

  test("a tool turn with no text fails the typed decode of a String output, and it gets a second call") {
    // Upstream `_call_postprocess` gives such an output the value `None` and no error. A Scala `String` output has no
    // null, so the port gives a parse error. `PORT_DIFFERENCES.md` has this difference.
    val model  = nativeModel(toolTurn, toolTurn)
    val result = withModel(model, nativeChat)(textAndCallsProgram(RfrInput("q")))
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(model.requests.size, 2)
  }

  test("a tool turn whose text the typed decode rejects keeps the calls after one call, as upstream") {
    // Upstream `_call_postprocess`: `except AdapterParseError: value = {}`.
    val reply   = Vector(LmOutput(text = "[[ ## tags ## ]]\n[{\"a\": 1}]", toolCalls = toolCalls))
    val model   = nativeModel(reply, reply)
    val program =
      withTools(Signature.derived[RfrInput, RfrTagsAndCalls](name = "TagsAndCalls", instructions = "Use the tools."))
    val result = withModel(model, nativeChat)(program(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrTagsAndCalls(None, toolCalls)))
    assertEquals(model.requests.size, 1)
  }

  test("a tool turn whose text the typed decode rejects is a parse error when a String output then has no value") {
    val reply =
      Vector(LmOutput(text = "[[ ## answer ## ]]\nOk.\n\n[[ ## tags ## ]]\n[{\"a\": 1}]", toolCalls = toolCalls))
    val model   = nativeModel(reply, reply)
    val program =
      withTools(Signature.derived[RfrInput, RfrAllAndCalls](name = "AllAndCalls", instructions = "Use the tools."))
    val result = withModel(model, nativeChat)(program(RfrInput("q")))
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(model.requests.size, 2)
  }

  test("a text reply with native function calling gives an empty list of calls, and it passes the typed decode") {
    val model  = nativeModel(Vector(LmOutput(text = "I need no tool.")))
    val result = withModel(model, nativeChat)(onlyCallsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrOnlyCalls(Vector.empty)))
    assertEquals(model.requests.size, 1)
  }

  test("a text reply with native function calling gives the text output and an empty list of calls") {
    val model  = nativeModel(Vector(LmOutput(text = "I need no tool.")))
    val result = withModel(model, nativeChat)(textAndCallsProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrTextAndCalls("I need no tool.", Vector.empty)))
    assertEquals(model.requests.size, 1)
  }

  // ── An empty reply ────────────────────────────────────────────────────────────────────────────────────────────

  private def emptyReply: String = "The LM returned an empty or null response."

  test("an empty reply gets a second call with a JSONAdapter, and the usage of each call counts") {
    val model  = Script(texts("", """{"answer": "yes"}""")*)
    val result = withModel(model, ChatAdapter())(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
    assertEquals(model.requests.size, 2)
    assertEquals(result.toOption.flatMap(_.raw.lmUsage).map(_.totalTokens), Some(6L))
  }

  test("a JSONAdapter gives the upstream parse error for an empty reply after one call") {
    val model = Script(texts("")*)
    withModel(model, JSONAdapter())(answerProgram(RfrInput("q"))) match
      case Left(error: ParseError) => assertEquals(error.message, emptyReply)
      case other                   => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 1)
  }

  test("an empty completion after the first also gets the second call") {
    val model = Script(
      Vector(LmOutput(text = "[[ ## answer ## ]]\nno\n\n[[ ## completed ## ]]"), LmOutput(text = "")),
      Vector(LmOutput(text = """{"answer": "yes"}"""))
    )
    val result = withModel(model, ChatAdapter())(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
    assertEquals(model.requests.size, 2)
  }

  test("provider tool calls with no text and no tool calls field are an empty reply, as upstream") {
    val calls = Vector(ToolCall("search", DynamicValue.Record.empty))
    val model = Script(
      Vector(LmOutput(text = "", toolCalls = calls)),
      Vector(LmOutput(text = """{"answer": "yes"}"""))
    )
    val result = withModel(model, ChatAdapter())(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
    assertEquals(model.requests.size, 2)
  }

  test("llm_query of the RLM sandbox gives an error for an empty reply") {
    val tools = withModel(Script(texts("")*), ChatAdapter()) {
      strategies.RLMSandboxTools.build(
        maxLlmCalls = LlmCallLimit.applyUnsafe(2),
        subLm = None,
        ctx = summon[RuntimeContext]
      )
    }
    val query = tools.find(_.name == "llm_query").getOrElse(fail("no llm_query"))
    assert(query.invoke(DynamicValues.record("prompt" := "p")).isLeft)
  }

  test("provider tool calls with no text and a tool calls field are a normal reply") {
    val layout = SignatureDsl
      .parse("question -> tool_calls: ToolCalls")
      .fold(error => fail(error.toString), identity)
    val calls  = Vector(ToolCall("search", DynamicValue.Record.empty))
    val model  = Script(Vector(LmOutput(text = "", toolCalls = calls)))
    val result = withModel(model, ChatAdapter()) {
      strategies.DynamicPredict(layout)(ProgramCall(input = DynamicValues.record("question" := "q")))
    }
    assert(result.isRight, result.toString)
    assertEquals(model.requests.size, 1)
  }

  test("a blank reply gets the second call, and a second blank reply is the upstream parse error with the text") {
    val model = Script(texts("  \n ", " ")*)
    withModel(model, ChatAdapter())(answerProgram(RfrInput("q"))) match
      case Left(error: ParseError) =>
        assertEquals(error.message, emptyReply)
        assertEquals(error.raw, Some(" "))
      case other => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 2)
  }

  test("a ChatAdapter with no fallback gives the upstream parse error for an empty reply after one call") {
    val model = Script(texts("", """{"answer": "yes"}""")*)
    withModel(model, ChatAdapter(useJsonAdapterFallback = false))(answerProgram(RfrInput("q"))) match
      case Left(error: ParseError) => assertEquals(error.message, emptyReply)
      case other                   => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 1)
  }

  test("an XMLAdapter gives the upstream parse error for an empty reply") {
    val model = Script(texts("", "")*)
    withModel(model, XMLAdapter())(answerProgram(RfrInput("q"))) match
      case Left(error: ParseError) => assertEquals(error.message, emptyReply)
      case other                   => fail(s"expected a parse error, got $other")
  }

  test(
    "a TwoStepAdapter gives the upstream parse error for an empty main reply, and the extraction model gets no call"
  ) {
    val extraction = Script(texts("[[ ## answer ## ]]\nyes\n\n[[ ## completed ## ]]")*)
    val model      = Script(texts("")*)
    withModel(model, TwoStepAdapter(extraction))(answerProgram(RfrInput("q"))) match
      case Left(error: ParseError) => assertEquals(error.message, emptyReply)
      case other                   => fail(s"expected a parse error, got $other")
    assertEquals(model.requests.size, 1)
    assertEquals(extraction.requests.size, 0)
  }

  test("an empty reply of the extraction model of a TwoStepAdapter gets the JSON fallback of the extraction step") {
    val extraction = Script(texts("", """{"answer": "yes"}""")*)
    val model      = Script(texts("The answer is yes.")*)
    val result     = withModel(model, TwoStepAdapter(extraction))(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
    assertEquals(extraction.requests.size, 2)
  }

  test("an output with typed answers and no text gets the parse of the adapter") {
    val answers = DynamicValues.record("answer" := "yes")
    val adapter = new Adapter:
      override val name: String                                                                                    = "typed-answers"
      override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
        Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("x")))))
      override def parse(invocation: AdapterInvocation, output: LmOutput)(using
          RuntimeContext
      ): Either[DspyError, ParsedOutput] =
        output.answers.map(ParsedOutput(_)).toRight(ParseError(name, "no answers"))
    val model  = Script(Vector(LmOutput(text = "", answers = Some(answers))))
    val result = withModel(model, adapter)(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
  }

  /** A transport that gives the next body of `replies` for each plain request. */
  private class ScriptedBodies(replies: String*) extends HttpTransport:
    private val remaining                                                                                           = replies.iterator
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Right(HttpResponse(200, Map.empty, remaining.next()))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      Left(ParseError("test", "the stream path is not used"))

  test("a provider reply with an empty content and all text in reasoning_content gets the JSON fallback") {
    val usage = """"usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}"""
    val model = OpenAiLanguageModel(
      model = "openai/test",
      apiKey = "x",
      baseUrl = "https://api.test/v1",
      transport = ScriptedBodies(
        s"""{"choices": [{"message": {"content": "", "reasoning_content": "why"}, "finish_reason": "stop"}], $usage}""",
        s"""{"choices": [{"message": {"content": "{\\"answer\\": \\"yes\\"}"}, "finish_reason": "stop"}], $usage}"""
      )
    )
    val result = withModel(model, ChatAdapter())(answerProgram(RfrInput("q")))
    assertEquals(result.map(_.output), Right(RfrAnswer("yes")))
    assertEquals(result.toOption.flatMap(_.raw.lmUsage).map(_.totalTokens), Some(6L))
  }
