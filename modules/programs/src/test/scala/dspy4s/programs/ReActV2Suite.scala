package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.core.contracts.{
  :=,
  ContextWindowExceededError,
  DspyError,
  DynamicValues,
  History,
  ParseError,
  RuntimeContext,
  RuntimeError,
  ToolCall
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, MessageRole}
import dspy4s.programs.contracts.{Prediction, ProgramCall, ToolFunction}
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.ReActV2
import dspy4s.programs.strategies.ReActV2.TerminationReason
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.util.concurrent.atomic.AtomicReference

/** ReActV2 (experimental; upstream `dspy.ReActV2` and the cases of `tests/predict/test_react_v2.py`). The upstream
  * tests that check only the async path are not ported (point 6 of P10). The prompt text of a run is in
  * `ProgramPromptParitySuite`.
  */
class ReActV2Suite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model that gives back `outputs` in order and keeps each request. Past the end it gives an empty text. */
  private final class Script(outputs: Vector[Either[DspyError, LmOutput]], functionCalling: Boolean = false)
      extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = synchronized {
        requests = requests :+ request
        requests.size - 1
      }
      outputs.lift(index).getOrElse(Right(LmOutput(""))).map(output => LmResponse(outputs = Vector(output)))

  /** The text of a ChatAdapter reply with `next_thought` and `tool_calls`. */
  private def chat(thought: String, toolCalls: String): Either[DspyError, LmOutput] =
    Right(
      LmOutput(s"[[ ## next_thought ## ]]\n$thought\n\n[[ ## tool_calls ## ]]\n$toolCalls\n\n[[ ## completed ## ]]")
    )

  /** The JSON text of tool calls: each entry is a name and the JSON text of its arguments. */
  private def calls(entries: (String, String)*): String =
    entries.map((name, args) => s"""{"name": "$name", "args": $args}""").mkString("""{"tool_calls": [""", ", ", "]}")

  private def native(text: String, toolCalls: ToolCall*): Either[DspyError, LmOutput] =
    Right(LmOutput(text, toolCalls = toolCalls.toVector))

  private def run[A](lm: LanguageModel, adapter: Adapter = ChatAdapter())(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def text(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(DynamicValues.renderText(value))

  private def field(record: DynamicValue.Record, name: String): Option[String] =
    DynamicValues.recordGet(record, name).map(text)

  private def toolCallsOf(message: DynamicValue.Record): Vector[ToolCall] =
    DynamicValues.recordGet(message, "tool_calls").flatMap(ToolCall.fromDynamic).getOrElse(Vector.empty)

  private def messagesOf(prediction: Prediction[?]): Vector[DynamicValue.Record] =
    ReActV2.history(prediction).getOrElse(fail("no history")).messages

  private def options(request: LmRequest): Vector[String] = DynamicValues.recordKeys(request.options)

  private def lookup: ToolFunction =
    ToolFunction.of("lookup", "Look up a query.")(args => s"found ${field(args, "query").getOrElse("")}")

  private def dynamic(dsl: String): Signature[DynamicValue.Record, DynamicValue.Record] =
    Signature.fromStringDynamic(dsl).fold(error => fail(error.message), identity)

  private def historyValue(messages: DynamicValue.Record*): DynamicValue =
    summon[Schema[History]].toDynamicValue(History(messages.toVector))

  private val qa = Signature.fromString("question -> answer")

  private def names[P](program: P)(using structure: OptimizableStructure[P]): Vector[String] =
    structure.inspectNamed(program).map(_._1)

  private def withInstructions[P](program: P, instructions: String)(using structure: OptimizableStructure[P]): P =
    structure.replace(program, structure.read(program).map(_.copy(instructions = Some(instructions))))

  private def runRecord[P](program: P, input: DynamicValue.Record)(using
      runner : ProgramRunner[P],
      context: RuntimeContext
  ): Either[DspyError, dspy4s.core.data.RawPrediction] =
    runner.run(program, ProgramCall(input))

  test("submit gives back the outputs of the signature, and the step layout has the upstream fields") {
    val agent  = ReActV2(qa, Vector.empty)
    val submit = agent.allTools.find(_.name == ReActV2.SubmitToolName).getOrElse(fail("no submit tool"))
    run(Script(Vector.empty)) {
      assertEquals(submit.invoke(rec("answer" := "Paris")), Right(rec("answer" := "Paris")))
      assert(
        submit.invoke(DynamicValue.Record.empty).left.exists(
          _.message.contains("Missing required final output field(s): answer")
        )
      )
    }
    assertEquals(agent.allTools.map(_.name), Vector("submit"))
    assertEquals(agent.reactLayout.inputFields.map(_.name), Vector("question", "history", "tools"))
    assertEquals(agent.reactLayout.outputFields.map(_.name), Vector("next_thought", "tool_calls"))
    assert(agent.reactLayout.inputFields.head.optional)
    assert(!agent.reactLayout.inputFields.exists(_.name == History.ToolCallResultsKey))
  }

  test("a text loop records the inputs once, and each call gets an id") {
    val lm = Script(Vector(
      chat("I should look this up.", calls("lookup" -> """{"query": "cats"}""")),
      chat("I can answer now.", calls("submit" -> """{"answer": "found cats"}"""))
    ))
    val result = run(lm)(ReActV2(qa, Vector(lookup))((question = "cats"))).fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "found cats")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.Submit))
    val messages = messagesOf(result)
    assertEquals(messages.count(message => DynamicValues.recordGet(message, "question").isDefined), 1)
    assertEquals(toolCallsOf(messages(0)).map(_.id), Vector(Some("call_0_0")))
    assertEquals(History.toolCallResults(messages(0)).map(_.callId), Vector(Some("call_0_0")))
    assertEquals(field(result.raw.values, ReActV2.TerminationReasonKey), Some("submit"))
  }

  test("a later turn shows no input that the call does not give again") {
    val lm = Script(Vector(
      chat("I should look this up.", calls("lookup" -> """{"query": "cats"}""")),
      chat("I can answer now.", calls("submit" -> """{"answer": "found cats"}"""))
    ))
    val result = run(lm)(ReActV2(qa, Vector(lookup))((question = "cats")))
    assertEquals(result.map(_.output.answer), Right("found cats"))
    val second  = lm.requests.lift(1).getOrElse(fail("no second request")).messages
    val current = second.lastOption.flatMap(_.text).getOrElse("")
    assert(!current.contains("[[ ## question ## ]]"), current)
    assert(second.exists(_.text.exists(_.contains("[[ ## question ## ]]\ncats"))), second.toString)
  }

  test("text mode reads one call with top-level arguments") {
    val lm = Script(Vector(
      chat("I should look this up.", """{"name": "lookup", "arguments": {"query": "cats"}}"""),
      chat("I can answer now.", calls("submit" -> """{"answer": "found cats"}"""))
    ))
    val result =
      run(lm, ChatAdapter(useNativeFunctionCalling = false))(ReActV2(qa, Vector(lookup))((question = "cats")))
        .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "found cats")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.Submit))
    assertEquals(toolCallsOf(messagesOf(result)(0)).map(_.args), Vector(rec("query" := "cats")))
  }

  test("text mode reads wrapped submit arguments") {
    val lm = Script(Vector(
      chat("I can answer now.", """{"tool_calls": [{"name": "submit", "arguments": {"answer": "done"}}]}""")
    ))
    val result = run(lm, ChatAdapter(useNativeFunctionCalling = false))(ReActV2(qa, Vector.empty)((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "done")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.Submit))
  }

  test("an unknown tool gives an error result, and the run continues") {
    val lm = Script(Vector(
      chat("Try a missing tool.", calls("missing_tool" -> """{"query": "cats"}""")),
      chat("Recover with a final answer.", calls("submit" -> """{"answer": "done"}"""))
    ))
    val result = run(lm)(ReActV2(qa, Vector.empty)((question = "cats"))).fold(error => fail(error.message), identity)
    val first  = History.toolCallResults(messagesOf(result)(0)).headOption.getOrElse(fail("no result"))
    assert(first.isError)
    assertEquals(first.callId, Some("call_0_0"))
    assert(text(first.value).contains("Unknown tool"), first.toString)
    assertEquals(result.output.answer, "done")
  }

  test("an input field of the History type gives the start history") {
    val signature = dynamic("question, history: history -> answer")
    val agent     = ReActV2(signature, Vector.empty)
    assertEquals(agent.reactLayout.inputFields.map(_.name), Vector("question", "history", "tools"))
    val lm     = Script(Vector(chat("I can answer.", calls("submit" -> """{"answer": "done"}"""))))
    val result = run(lm)(agent(rec("history" -> historyValue(rec("question" := "old")))))
      .fold(error => fail(error.message), identity)
    assertEquals(field(result.output, "answer"), Some("done"))
    val messages = messagesOf(result)
    assertEquals(messages(0), rec("question" := "old"))
    assert(messages.forall(_.fields.nonEmpty), messages.toString)
  }

  test("no tool calls start the forced submit, and a model with no function calling gets no tool_choice") {
    val lm = Script(Vector(
      chat("No action.", """{"tool_calls": []}"""),
      chat("Forced final.", calls("submit" -> """{"answer": "forced"}"""))
    ))
    val result = run(lm)(ReActV2(qa, Vector.empty)((question = "cats"))).fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "forced")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.ForcedSubmit))
    assert(!options(lm.requests(1)).contains("tool_choice"), options(lm.requests(1)).toString)
  }

  test("the forced submit with the native flag and a model with no function calling sends no tools") {
    val lm = Script(Vector(
      Right(LmOutput("""{"next_thought": "No action.", "tool_calls": {"tool_calls": []}}""")),
      Right(LmOutput(
        """{"next_thought": "Forced final.", "tool_calls": {"tool_calls": [{"name": "submit", "args": {"answer": "forced"}}]}}"""
      ))
    ))
    val result = run(lm, JSONAdapter(useNativeFunctionCalling = true))(ReActV2(qa, Vector.empty)((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "forced")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.ForcedSubmit))
    assert(!options(lm.requests(1)).contains("tool_choice"), options(lm.requests(1)).toString)
    assert(!options(lm.requests(1)).contains("tools"), options(lm.requests(1)).toString)
  }

  test("a native loop keeps the provider id and replays the result as a tool message") {
    val lm = Script(
      Vector(
        native("", ToolCall("lookup", rec("query" := "cats"), Some("call_provider_1"))),
        native("", ToolCall("submit", rec("answer" := "found cats"), Some("call_submit")))
      ),
      functionCalling = true
    )
    val result = run(lm, ChatAdapter(useNativeFunctionCalling = true))(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "found cats")
    val messages = messagesOf(result)
    assertEquals(toolCallsOf(messages(0)).map(_.id), Vector(Some("call_provider_1")))
    assertEquals(History.toolCallResults(messages(0)).map(_.callId), Vector(Some("call_provider_1")))
    assert(
      lm.requests(1).messages.exists(message =>
        message.role == MessageRole.Tool && message.toolCallId.contains("call_provider_1")
      ),
      lm.requests(1).messages.toString
    )
    assert(options(lm.requests(0)).contains("tools"), options(lm.requests(0)).toString)
    val system = lm.requests(0).messages.headOption.flatMap(_.text).getOrElse("")
    assert(!system.contains("`tools`") && !system.contains("`tool_calls`"), system)
  }

  test("native parallel calls are requested and replayed in order") {
    val lm = Script(
      Vector(
        native(
          "",
          ToolCall("lookup", rec("query" := "cats"), Some("call_provider_1")),
          ToolCall("lookup", rec("query" := "dogs"), Some("call_provider_2"))
        ),
        native("", ToolCall("submit", rec("answer" := "found cats and found dogs"), Some("call_submit")))
      ),
      functionCalling = true
    )
    val adapter = ChatAdapter(useNativeFunctionCalling = true, parallelToolCalls = Some(true))
    val result  = run(lm, adapter)(ReActV2(qa, Vector(lookup))((question = "cats and dogs")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "found cats and found dogs")
    assertEquals(
      DynamicValues.recordGet(lm.requests(0).options, "parallel_tool_calls"),
      Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
    )
    val first = messagesOf(result)(0)
    assertEquals(toolCallsOf(first).map(_.id), Vector(Some("call_provider_1"), Some("call_provider_2")))
    assertEquals(History.toolCallResults(first).map(_.callId), Vector(Some("call_provider_1"), Some("call_provider_2")))
    assertEquals(
      lm.requests(1).messages.filter(_.role == MessageRole.Tool).map(_.toolCallId),
      Vector(Some("call_provider_1"), Some("call_provider_2"))
    )
  }

  test("an output named history or termination_reason, and a tool named submit, fail at construction") {
    Vector("history", "termination_reason").foreach { reserved =>
      val error =
        intercept[IllegalArgumentException](ReActV2(dynamic(s"question -> answer, $reserved: str"), Vector.empty))
      assert(error.getMessage.contains(reserved), error.getMessage)
    }
    val submit = ToolFunction.of("submit")(_ => "no")
    val error  = intercept[IllegalArgumentException](ReActV2(qa, Vector(submit)))
    assert(error.getMessage.contains("`submit` is reserved by ReActV2"), error.getMessage)
  }

  test("a failed forced submit is an error, for each reason of the break") {
    val signature                               = Signature.fromString("question -> answer: str, count: int")
    val incomplete                              = chat("Partial.", calls("submit" -> """{"answer": "partial"}"""))
    val empty                                   = chat("Nothing.", """{"tool_calls": []}""")
    val unreadable: Either[DspyError, LmOutput] = Right(LmOutput("no fields here"))
    val tooLong: Either[DspyError, LmOutput]    = Left(ContextWindowExceededError("too long"))
    val breaks                                  = Vector(
      "max_iters"               -> incomplete,
      "empty_tool_calls"        -> empty,
      "parse_error"             -> unreadable,
      "context_window_exceeded" -> tooLong
    )
    val forced = Vector(
      "no_submit"               -> empty,
      "missing_field"           -> incomplete,
      "parse_error"             -> unreadable,
      "context_window_exceeded" -> tooLong
    )
    for (breakReason, first) <- breaks; (forcedResult, second) <- forced do
      val lm     = Script(Vector(first, second))
      val agent  = ReActV2(signature, Vector.empty, maxIterations = IterationLimit(1))
      val label  = s"$breakReason then $forcedResult"
      val result = run(lm, ChatAdapter(useJsonAdapterFallback = false))(agent((question = "cats")))
      val error  = result.left.getOrElse(fail(s"no error for $label"))
      forcedResult match
        case "parse_error"             => assert(error.isInstanceOf[ParseError], s"$label: $error")
        case "context_window_exceeded" => assert(error.isInstanceOf[ContextWindowExceededError], s"$label: $error")
        case _                         =>
          assert(error.message.contains(s"failed to produce final outputs after $breakReason"), s"$label: $error")
      if forcedResult == "no_submit" then assert(error.message.contains("no submit call"), s"$label: $error")
      if forcedResult == "missing_field" then
        assert(error.message.contains("Missing required final output field(s): count"), s"$label: $error")
  }

  test("the tools of a turn run in order, an error result does not stop the turn, and the history keeps it") {
    val executed = AtomicReference(Vector.empty[String])
    val search   = ToolFunction.of("lookup") { args =>
      val query = field(args, "query").getOrElse("")
      val _     = executed.updateAndGet(_ :+ query)
      s"found $query"
    }
    val broken = ToolFunction("fail") { _ =>
      val _ = executed.updateAndGet(_ :+ "fail")
      Left(RuntimeError("fail", "lookup unavailable"))
    }
    val lm = Script(Vector(
      chat(
        "Look up both.",
        calls(
          "lookup"  -> """{"query": "cats"}""",
          "missing" -> "{}",
          "fail"    -> "{}",
          "lookup"  -> """{"query": "dogs"}"""
        )
      ),
      chat("Done.", calls("submit" -> """{"answer": "cats and dogs", "count": 2}"""))
    ))
    val agent  = ReActV2(dynamic("question, history: history -> answer: str, count: int"), Vector(search, broken))
    val input  = rec("question" := "pets", "history" -> historyValue(rec("question" := "old")))
    val result = run(lm)(agent(input)).fold(error => fail(error.message), identity)
    assertEquals(field(result.output, "answer"), Some("cats and dogs"))
    assertEquals(field(result.output, "count"), Some("2"))
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.Submit))
    assertEquals(executed.get(), Vector("cats", "fail", "dogs"))
    val messages = messagesOf(result)
    assertEquals(messages(0), rec("question" := "old"))
    assertEquals(field(messages(1), "question"), Some("pets"))
    assert(DynamicValues.recordGet(messages(2), "question").isEmpty, messages(2).toString)
    val results = History.toolCallResults(messages(1))
    assertEquals(results.map(_.callId), Vector("call_0_0", "call_0_1", "call_0_2", "call_0_3").map(Some(_)))
    assertEquals(results.map(_.isError), Vector(false, true, true, false))
    assertEquals(text(results(0).value), "found cats")
    assertEquals(text(results(1).value), "Unknown tool: missing")
    assert(text(results(2).value).contains("lookup unavailable"), results(2).toString)
    assertEquals(text(results(3).value), "found dogs")
  }

  test("the forced submit runs only the submit calls") {
    val forbidden = ToolFunction("forbidden")(_ => fail("the forced submit must not run other tools"))
    val lm        = Script(Vector(
      chat("Nothing.", """{"tool_calls": []}"""),
      chat("Final.", calls("forbidden" -> "{}", "submit" -> """{"answer": "forced"}"""))
    ))
    val agent  = ReActV2(qa, Vector(forbidden), maxIterations = IterationLimit(1))
    val result = run(lm)(agent((question = "cats"))).fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "forced")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.ForcedSubmit))
    val first = messagesOf(result)(0)
    assertEquals(field(first, "question"), Some("cats"))
    assertEquals(toolCallsOf(first).map(call => (call.name, call.id)), Vector(("submit", Some("call_1_1"))))
  }

  test("the forced submit asks a model with function calling for the submit tool") {
    val lm = Script(
      Vector(
        native("[[ ## next_thought ## ]]\nNo action.\n\n[[ ## completed ## ]]"),
        native("", ToolCall("submit", rec("answer" := "forced"), Some("call_submit")))
      ),
      functionCalling = true
    )
    val result = run(lm, ChatAdapter(useNativeFunctionCalling = true))(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "forced")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.ForcedSubmit))
    assert(!options(lm.requests(0)).contains("tool_choice"), options(lm.requests(0)).toString)
    assertEquals(
      DynamicValues.recordGet(lm.requests(1).options, "tool_choice"),
      Some(rec("type" := "function", "function" -> rec("name" := "submit")))
    )
  }

  test("the step predictor is the one optimizable part, and the program runs from a record") {
    val agent = ReActV2(qa, Vector(lookup))
    assertEquals(names(agent), Vector("react"))
    val updated = withInstructions(agent, "Be brief.")
    assertEquals(updated.reactPredict.layout.instructions, Some("Be brief."))
    val lm     = Script(Vector(chat("Done.", calls("submit" -> """{"answer": "yes"}"""))))
    val result = run(lm)(runRecord(agent, rec("question" := "cats")))
    assertEquals(result.map(raw => field(raw.values, "answer")), Right(Some("yes")))
  }

  test("a tool that throws gives an error result, and the run goes on") {
    val throwing = ToolFunction("broken")(_ => throw IllegalStateException("boom"))
    val lm       = Script(Vector(
      chat("Try it.", calls("broken" -> "{}")),
      chat("Recover.", calls("submit" -> """{"answer": "done"}"""))
    ))
    val result =
      run(lm)(ReActV2(qa, Vector(throwing))((question = "cats"))).fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "done")
    val first = History.toolCallResults(messagesOf(result)(0)).headOption.getOrElse(fail("no result"))
    assert(first.isError)
    assertEquals(text(first.value), "Execution error in broken: IllegalStateException: boom")
  }

  test("a fatal error of a tool ends the run (upstream: a cancelled tool call goes up to the caller)") {
    val fatal = ToolFunction("fatal")(_ => throw InterruptedException("stop"))
    val lm    = Script(Vector(chat("Try it.", calls("fatal" -> "{}"))))
    // `intercept` catches only non-fatal errors, so the test catches the fatal error itself.
    val thrown =
      try
        val _ = run(lm)(ReActV2(qa, Vector(fatal))((question = "cats")))
        None
      catch case error: InterruptedException => Some(error.getMessage)
    assertEquals(thrown, Some("stop"))
    assertEquals(lm.requests.size, 1)
  }
