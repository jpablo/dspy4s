package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.typed.Signature
import dspy4s.programs.contracts.ToolFunction
import zio.blocks.schema.DynamicValue
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class ReActSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  /** Returns canned LM response texts from a queue, advancing per `call`. Feeds successive react steps then the
    * final extractor step. */
  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    val calls: AtomicInteger = AtomicInteger(0)
    override val id: String = "scripted-react-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = calls.getAndIncrement()
      val text = if i < responses.size then responses(i) else ""
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Test adapter. For a react step (its outputs include `next_tool_name`) it parses the convention
    * `thought || tool_name || key=value` into the three react fields (`key=value` -> `{key: value}` args, blank ->
    * `{}`). For the extractor step it assigns the full text to every output field. */
  private object ScriptedAdapter extends Adapter:
    override val name: String = "scripted-react-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      val outputNames = layout.outputFields.map(_.name).toSet
      val text = output.text
      if outputNames.contains("next_tool_name") then
        val parts = text.split("\\|\\|", -1)
        val thought = if parts.length >= 1 then parts(0).trim else ""
        val toolName = if parts.length >= 2 then parts(1).trim else ""
        val args =
          if parts.length >= 3 && parts(2).trim.nonEmpty then
            parts(2).trim.split("=", 2) match
              case Array(k, v) => rec(k.trim := v.trim)
              case _           => DynamicValue.Record.empty
          else DynamicValue.Record.empty
        Right(ParsedOutput(values = rec("next_thought" := thought, "next_tool_name" := toolName, "next_tool_args" -> args)))
      else
        Right(ParsedOutput(values = rec(layout.outputFields.map(_.name := text)*)))

  private final class SearchTool extends ToolFunction:
    val calls: AtomicInteger = AtomicInteger(0)
    override val name: String = "search"
    override val description: String = "Look up a fact about the world."
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      calls.incrementAndGet()
      Right(ToolFunction.result("Brussels"))

  private def withReact[A](lm: ScriptedLm)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ScriptedAdapter))) {
      body(using RuntimeEnvironment.current)
    }

  private val qaSignature = Signature.fromString("question -> answer")

  test("react runs a tool, finishes, and extracts the answer from the trajectory") {
    val search = new SearchTool
    val lm = new ScriptedLm(Vector(
      "I should look it up||search||query=capital of Belgium", // step 1 -> search
      "I have what I need||finish||", // step 2 -> finish
      "Brussels" // extractor -> answer
    ))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withReact(lm) {
      val result = react.apply((question = "What is the capital of Belgium?"))
      assert(result.isRight, s"failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "Brussels")
      assertEquals(search.calls.get(), 1)
      val traj = lookupString(pred.raw.values, "trajectory")
      assert(traj.contains("tool_name: search"), s"trajectory missing tool call: $traj")
      assert(traj.contains("observation: Brussels"), s"trajectory missing observation: $traj")
      assert(traj.contains("tool_name: finish"), s"trajectory missing finish: $traj")
    }
  }

  test("react can finish on the first step without calling any tool") {
    val search = new SearchTool
    val lm = new ScriptedLm(Vector("I already know||finish||", "42"))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withReact(lm) {
      val result = react.apply((question = "2+2 doubled?"))
      assert(result.isRight)
      assertEquals(result.toOption.get.output.answer, "42")
      assertEquals(search.calls.get(), 0)
    }
  }

  test("react stops at maxIterations when the model never finishes, then extracts") {
    val search = new SearchTool
    val lm = new ScriptedLm(Vector(
      "keep going||search||query=a",
      "keep going||search||query=b",
      "extracted-after-cap" // extractor, reached after the 2 capped react steps
    ))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 2)

    withReact(lm) {
      val result = react.apply((question = "x"))
      assert(result.isRight)
      assertEquals(result.toOption.get.output.answer, "extracted-after-cap")
      assertEquals(search.calls.get(), 2) // tool ran once per capped iteration
      assertEquals(lm.calls.get(), 3) // 2 react steps + 1 extractor
    }
  }

  test("react records an error observation for an unknown tool and keeps going") {
    val search = new SearchTool
    val lm = new ScriptedLm(Vector(
      "try this||nonexistent||", // unknown tool -> error observation, continue
      "ok now finish||finish||",
      "done"
    ))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withReact(lm) {
      val result = react.apply((question = "x"))
      assert(result.isRight)
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "done")
      assertEquals(search.calls.get(), 0)
      assert(lookupString(pred.raw.values, "trajectory").contains("does not exist"))
    }
  }

  test("react emits tool callback events parented to the react module call") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

    val search = new SearchTool
    val lm = new ScriptedLm(Vector("look||search||query=x", "done||finish||", "Brussels"))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ScriptedAdapter), callbacks = Vector(callback))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      assert(react.apply((question = "x")).isRight)
    }

    val toolStart = events.collectFirst { case e: ToolStartEvent => e }
    val toolEnd = events.collectFirst { case e: ToolEndEvent => e }
    assert(toolStart.exists(_.toolName == "search"), "expected a ToolStartEvent for search")
    assert(toolEnd.exists(_.toolName == "search"), "expected a ToolEndEvent for search")
    // The react module's own start event exists; the tool call is parented under some active module call.
    assert(events.exists { case _: ModuleStartEvent => true; case _ => false })
  }

  // ── Trajectory truncation on context-window overflow (upstream `_call_with_potential_trajectory_truncation`) ──

  /** Scripted LM whose responses can be failures — used to inject ContextWindowExceededError mid-run. */
  private final class EitherLm(responses: Vector[Either[DspyError, String]]) extends LanguageModel:
    val calls: AtomicInteger = AtomicInteger(0)
    override val id: String = "either-react-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = calls.getAndIncrement()
      if i >= responses.size then Right(LmResponse(outputs = Vector(LmOutput(text = ""))))
      else responses(i).map(text => LmResponse(outputs = Vector(LmOutput(text = text))))

  /** ScriptedAdapter variant that records the rendered trajectory of every EXTRACTOR call. */
  private final class ExtractProbeAdapter extends Adapter:
    val extractorTrajectories: ArrayBuffer[String] = ArrayBuffer.empty
    override val name: String = "extract-probe"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      if !invocation.layout.outputFields.exists(_.name == "next_tool_name") then
        dspy4s.core.contracts.DynamicValues.recordGet(invocation.inputs.values, "trajectory")
          .map(dspy4s.core.contracts.DynamicValues.renderText).foreach(extractorTrajectories += _)
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      ScriptedAdapter.parse(layout, output)

  private def cwError: DspyError = dspy4s.core.contracts.ContextWindowExceededError(model = Some("either-react-lm"))

  private def withLmAndAdapter[A](lm: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  test("extract step truncates the oldest trajectory step and retries on context-window overflow") {
    val search = new SearchTool
    // 2 react steps (search, finish), then the extractor overflows once before succeeding.
    val lm = new EitherLm(Vector(
      Right("look it up||search||query=x"),
      Right("done||finish||"),
      Left(cwError),
      Right("Brussels")
    ))
    val probe = new ExtractProbeAdapter
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withLmAndAdapter(lm, probe) {
      val result = react.apply((question = "capital?"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "Brussels")
      // Two extractor attempts: full trajectory, then with the oldest step dropped.
      assertEquals(probe.extractorTrajectories.size, 2)
      assert(probe.extractorTrajectories(0).contains("Step 1") && probe.extractorTrajectories(0).contains("Step 2"))
      assert(!probe.extractorTrajectories(1).contains("Step 1") && probe.extractorTrajectories(1).contains("Step 2"))
      // The RETURNED trajectory stays complete (documented delta from upstream's in-place pops).
      val traj = lookupString(result.toOption.get.raw.values, "trajectory")
      assert(traj.contains("Step 1"), traj)
    }
  }

  test("react step truncation is durable: later iterations and the result build on the truncated trajectory") {
    val search = new SearchTool
    // Iteration 1: search (trajectory gains step 1). Iteration 2's react call overflows -> oldest step dropped ->
    // retry succeeds with finish. The finish entry builds on the TRUNCATED view.
    val lm = new EitherLm(Vector(
      Right("look it up||search||query=x"),
      Left(cwError),
      Right("done||finish||"),
      Right("42")
    ))
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withLmAndAdapter(lm, new ExtractProbeAdapter) {
      val result = react.apply((question = "q?"))
      assert(result.isRight, result.toString)
      val traj = lookupString(result.toOption.get.raw.values, "trajectory")
      // Step 1 (the search) was truncated away; only the finish step (iteration 2 -> "Step 2") remains.
      assert(!traj.contains("Step 1"), traj)
      assert(traj.contains("Step 2") && traj.contains("tool_name: finish"), traj)
    }
  }

  test("a persistent react-step overflow breaks the loop (no failure) and the extractor still runs") {
    val search = new SearchTool
    // The very first react call overflows with an EMPTY trajectory: nothing to truncate -> upstream's
    // ValueError path -> break. The extractor then runs over the empty trajectory.
    val lm = new EitherLm(Vector(Left(cwError), Right("best guess")))
    val probe = new ExtractProbeAdapter
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search), maxIterations = 5)

    withLmAndAdapter(lm, probe) {
      val result = react.apply((question = "q?"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "best guess")
      assertEquals(probe.extractorTrajectories.toList, List("(empty)"))
    }
  }
