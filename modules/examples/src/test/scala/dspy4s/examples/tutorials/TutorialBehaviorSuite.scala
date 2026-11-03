package dspy4s.examples.tutorials

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  :=,
  CallbackEvent,
  CallbackHandler,
  ClosableIterator,
  DspyError,
  DynamicValues,
  LmUsage,
  ModuleStartEvent,
  RuntimeContext,
  ToolCall
}
import dspy4s.core.runtime.{CallbackDispatcher, RuntimeEnvironment}
import dspy4s.examples.learn.programming.Mcp as LearnMcp
import dspy4s.examples.tutorials.async.Async
import dspy4s.examples.tutorials.cache.Cache
import dspy4s.examples.tutorials.email_extraction.EmailExtraction
import dspy4s.examples.tutorials.mcp.Mcp as AirlineMcp
import dspy4s.examples.tutorials.output_refinement.BestOfNAndRefine
import dspy4s.examples.tutorials.react_vs_rlm.{CallRecorder, DrugSafetyTools}
import dspy4s.examples.tutorials.streaming.Streaming
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.{InMemoryLmCache, ManagedLanguageModel, UsageTracking}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

/** Behavior of the tutorial examples, with a scripted model. The MCP tests start the servers of the examples in a child
  * JVM with the class path of the tests.
  */
class TutorialBehaviorSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(120, "s")

  private final class Script(
      reply          : LmRequest => LmOutput,
      override val id: String          = "script",
      usage          : Option[LmUsage] = None
  ) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      Right(LmResponse(outputs = Vector(reply(request)), usage = usage))

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def contextOf(lm: LanguageModel): RuntimeContext =
    RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))

  /** A reply in the format of `ChatAdapter`, with one section for each field. */
  private def chatReply(fields: (String, String)*): LmOutput =
    LmOutput(fields.map((name, value) => s"[[ ## $name ## ]]\n$value\n\n").mkString + "[[ ## completed ## ]]")

  private def someUsage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  // ── streaming ──

  test("the streaming example with a tool shows the status messages of the tool") {
    val lm = Script(_ =>
      LmOutput("[[ ## reasoning ## ]]\nDouble it.\n\n[[ ## answer ## ]]\n42\n\n[[ ## completed ## ]]")
    )
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out) {
      RuntimeEnvironment.withSettings(contextOf(lm)) {
        val _ = Streaming.streamReasoningWithTool("What is 21 doubled?")(using RuntimeEnvironment.current)
      }
    }
    val text = out.toString
    assert(text.contains("Calling Tool double_the_number"), text)
    assert(text.contains("Tool finished with output: 42!"), text)
  }

  test("each streaming example gives its final prediction") {
    val lm = Script(_ =>
      chatReply(
        "next_thought"      -> "Done.",
        "next_tool_name"    -> "finish",
        "next_tool_args"    -> "{}",
        "reasoning"         -> "Known.",
        "answer"            -> "42",
        "simplified_answer" -> "42",
        "score"             -> "1"
      )
    )
    val results = Console.withOut(new java.io.ByteArrayOutputStream) {
      RuntimeEnvironment.withSettings(contextOf(lm)) {
        given RuntimeContext = RuntimeEnvironment.current
        Vector(
          Streaming.streamAnswer("What is 21 doubled?"),
          Streaming.streamSimplify("What is 21 doubled?"),
          Streaming.streamReactThoughts("What is 21 doubled?"),
          Streaming.streamScoring("What is 21 doubled?"),
          Streaming.streamReasoningWithTool("What is 21 doubled?")
        )
      }
    }
    assert(results.forall(_.isDefined), results.toString)
  }

  test("consume closes the stream when the loop throws") {
    var closed = false
    val stream = new ClosableIterator[StreamEvent]:
      override def hasNext: Boolean    = true
      override def next(): StreamEvent = throw RuntimeException("no event")
      override def close(): Unit       = closed = true
    val _ = intercept[RuntimeException](Streaming.consume(stream))
    assert(closed)
  }

  // ── async ──

  test("a plain Future finds the model but loses the usage tracker, and ContextPropagation.future keeps it") {
    val lm                 = Script(_ => chatReply("answer" -> "Paris"), usage = Some(someUsage))
    given RuntimeContext   = contextOf(lm)
    given ExecutionContext = ExecutionContext.global
    val question           = "What is the capital of France?"

    val (plainResult, plainTotal) = UsageTracking.withNewTracker { tracker =>
      val predict = Predict(Signature.fromString("question -> answer"))
      (Await.result(Future(predict((question = question))), 30.seconds), tracker.totalUsage)
    }
    assert(plainResult.isRight, plainResult.toString)
    assertEquals(plainTotal, Map.empty[String, LmUsage])

    val (result, total) = UsageTracking.withNewTracker { tracker =>
      (Await.result(Async.askAsync(question), 30.seconds), tracker.totalUsage)
    }
    assertEquals(result.toOption, Some("Paris"))
    assertEquals(total, Map("script" -> someUsage))
  }

  test("a plain Future loses the parent call id, and ContextPropagation.future keeps it") {
    val parents = new java.util.concurrent.ConcurrentLinkedQueue[Option[String]]
    val handler = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        event match
          case start: ModuleStartEvent if start.moduleName == "predict" => val _ = parents.add(start.parentCallId)
          case _                                                        => ()
    val lm                 = Script(_ => chatReply("answer" -> "Paris"))
    val context            = contextOf(lm).copy(callbacks = Some(Vector(handler)))
    given RuntimeContext   = context
    given ExecutionContext = ExecutionContext.global
    val question           = "What is the capital of France?"
    val predict            = Predict(Signature.fromString("question -> answer"))

    val plain = CallbackDispatcher.withModule("outer", DynamicValue.Record.empty) {
      Await.result(Future(predict((question = question))), 30.seconds)
    }
    val propagated = CallbackDispatcher.withModule("outer", DynamicValue.Record.empty) {
      Await.result(Async.askAsync(question), 30.seconds)
    }
    assert(plain.isRight, plain.toString)
    assert(propagated.isRight, propagated.toString)
    val seen = Vector(parents.poll(), parents.poll())
    assertEquals(seen.head, None)
    assert(seen(1).isDefined, seen.toString)
  }

  // ── the cache ──

  private def chatContext: RuntimeContext = RuntimeContext(adapter = Some(ChatAdapter()))

  test("a second call with one shared cache makes no second model call") {
    val lm               = Script(_ => chatReply("answer" -> "Paris"), usage = Some(someUsage))
    given RuntimeContext = chatContext
    val model            = Cache.customCached(lm, new InMemoryLmCache())
    def ask              =
      Cache.ask(model, "What is the capital of France?").fold(error => fail(error.message), identity)
    val first  = ask
    val second = ask
    assertEquals(lm.requests.size, 1)
    assertEquals(first.usage, Some(someUsage))
    assertEquals(second.usage, None)
    assertEquals(second.answer, first.answer)
  }

  test("the usage example shows the hit: the second call has no usage, and the tracker has the first call") {
    val lm               = Script(_ => chatReply("answer" -> "Paris"), usage = Some(someUsage))
    given RuntimeContext = chatContext
    val usage            = Cache.usageAcrossCachedCalls(lm, "What is the capital of France?")
      .fold(error => fail(error.message), identity)
    assertEquals(lm.requests.size, 1)
    assertEquals(usage.first, Some(someUsage))
    assertEquals(usage.second, None)
    assertEquals(usage.total, Map("script" -> someUsage))
  }

  test("two models with one shared cache and the messages key make one model call") {
    val one              = Script(_ => chatReply("answer" -> "Paris"), id = "model-one")
    val two              = Script(_ => chatReply("answer" -> "Lyon"), id = "model-two")
    given RuntimeContext = chatContext
    val shared           = new InMemoryLmCache()
    val first            = Cache.ask(Cache.customCached(one, shared), "What is the capital of France?")
    val second           = Cache.ask(Cache.customCached(two, shared), "What is the capital of France?")
    assertEquals(one.requests.size, 1)
    assertEquals(two.requests.size, 0)
    assertEquals(first.toOption.map(_.answer), Some("Paris"))
    assertEquals(second.toOption.map(_.answer), Some("Paris"))
  }

  test("two models with one shared cache and the default key make two model calls") {
    val one              = Script(_ => chatReply("answer" -> "Paris"), id = "model-one")
    val two              = Script(_ => chatReply("answer" -> "Lyon"), id = "model-two")
    given RuntimeContext = chatContext
    val shared           = new InMemoryLmCache()
    val _                = Cache.ask(ManagedLanguageModel(one, cache = Some(shared)), "What is the capital of France?")
    val second           = Cache.ask(ManagedLanguageModel(two, cache = Some(shared)), "What is the capital of France?")
    assertEquals(one.requests.size, 1)
    assertEquals(two.requests.size, 1)
    assertEquals(second.toOption.map(_.answer), Some("Lyon"))
  }

  // ── the task text of the signatures ──

  test("the email processor sends the upstream docstring of each signature") {
    val lm = Script(_ =>
      chatReply(
        "reasoning"        -> "Known.",
        "email_type"       -> "invoice",
        "urgency"          -> "low",
        "key_entities"     -> "[]",
        "financial_amount" -> "null",
        "important_dates"  -> "[]",
        "contact_info"     -> "[]",
        "summary"          -> "An invoice.",
        "action_required"  -> "false",
        "action_items"     -> "[]",
        "deadline"         -> "null",
        "priority_score"   -> "1"
      )
    )
    val result = new EmailExtraction.EmailProcessor().forward("A subject", "A body")(using contextOf(lm))
    assert(result.isRight, result.toString)
    val prompts = lm.requests.map(promptOf)
    Vector(
      "Classify the type and urgency of an email based on its content.",
      "Extract key entities and information from email content.",
      "Create a concise summary of the email content.",
      "Determine what actions are needed based on the email content and extracted information."
    ).zip(prompts).foreach((docstring, prompt) => assert(prompt.contains(docstring), prompt))
  }

  // ── output refinement ──

  test("a failed call of the judge is not a reward of 0, so Refine gives the error") {
    val lm = Script(request =>
      if promptOf(request).contains("is_factual") then LmOutput("this reply has no fields")
      else if promptOf(request).contains("advice") then
        LmOutput("[[ ## discussion ## ]]\nNone.\n\n[[ ## advice ## ]]\n{}\n\n[[ ## completed ## ]]")
      else LmOutput("[[ ## reasoning ## ]]\nKnown.\n\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]")
    )
    val result = BestOfNAndRefine.FactualityRefine.call("What is the capital of France?")(using contextOf(lm))
    assert(result.isLeft, result.toString)
  }

  test("the judge sends the upstream docstring of its signature") {
    val lm = Script(request =>
      if promptOf(request).contains("is_factual") then chatReply("reasoning" -> "Known.", "is_factual" -> "true")
      else chatReply("reasoning"                                             -> "Known.", "answer"     -> "Paris")
    )
    val result = BestOfNAndRefine.FactualityRefine.call("What is the capital of France?")(using contextOf(lm))
    assertEquals(result.toOption, Some("Paris"))
    val judgePrompts = lm.requests.map(promptOf).filter(_.contains("is_factual"))
    assert(judgePrompts.nonEmpty)
    judgePrompts.foreach(prompt => assert(prompt.contains("Determine if a statement is factually accurate."), prompt))
  }

  // ── the ReAct and RLM demo ──

  test("a drug tool with a missing argument is an error") {
    val tools            = DrugSafetyTools.build(new CallRecorder)
    given RuntimeContext = RuntimeContext()
    tools.foreach { tool =>
      val result: Either[DspyError, DynamicValue] = tool.invoke(DynamicValue.Record.empty)
      assert(result.isLeft, s"${tool.name}: $result")
    }
  }

  // ── the MCP examples ──

  test("the airline server has its seven tools") {
    val described = AirlineMcp.describeTools().fold(error => fail(error.message), identity)
    assert(described.startsWith("7\n"), described)
  }

  test("pick_flight requires its flights argument") {
    val checked = AirlineMcp.withAirlineTools { tools =>
      val tool     = tools.find(_.name == "pick_flight").getOrElse(fail("no pick_flight tool"))
      val required = tool.inputSchema
        .map(DynamicValues.sequenceAt(_, "required").flatMap(DynamicValues.asString))
        .getOrElse(Vector.empty)
      assert(required.contains("flights"), required.toString)
      given RuntimeContext = RuntimeContext()
      Right(tool.invoke(DynamicValue.Record.empty))
    }.fold(error => fail(error.message), identity)
    checked match
      case Left(error) => assert(!error.message.contains("There is no flight to pick"), error.message)
      case Right(text) => fail(s"the server accepted the call: $text")
  }

  test("the airline tools run a booking with a real server") {
    val answer = AirlineMcp.withAirlineTools { tools =>
      given RuntimeContext = RuntimeContext()
      val fetch            = tools.find(_.name == "fetch_flight_info").getOrElse(fail("no fetch_flight_info tool"))
      fetch.invoke(DynamicValues.record(
        "date"        -> DynamicValues.record("year" := 2025, "month" := 9, "day" := 1, "hour" := 0),
        "origin"      := "SFO",
        "destination" := "JFK"
      ))
    }.fold(error => fail(error.message), identity)
    assert(DynamicValues.renderText(answer).contains("DA123"), DynamicValues.renderText(answer))
  }

  test("the agent of the MCP guide calls the tools of the server on the native path") {
    var calls = 0
    val lm    = Script { _ =>
      calls += 1
      val call =
        if calls == 1 then ToolCall("add", DynamicValues.record("a" := 25, "b" := 17), Some("call_add"))
        else ToolCall("submit", DynamicValues.record("answer" := 42), Some("final"))
      LmOutput("", toolCalls = Vector(call))
    }
    val answer = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      LearnMcp.addWithAgent(using RuntimeEnvironment.current)
    }
    assertEquals(answer.toOption, Some(42))
    assert(DynamicValues.recordGet(lm.requests.head.options, "tools").isDefined, "the request has no provider tools")
  }
