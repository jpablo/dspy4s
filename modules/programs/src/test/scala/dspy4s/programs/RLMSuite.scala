package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{CodeResult, DspyError, DynamicValues, FieldRole, FieldSpec, ReplCodeInterpreter, RuntimeContext, RuntimeError, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class RLMSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  /** Scripted REPL: returns canned CodeResults, recording each (code, variables) execute call. */
  private final class ScriptedRepl(responses: Vector[Either[DspyError, CodeResult]]) extends ReplCodeInterpreter:
    private val idx = new AtomicInteger(0)
    val executed: ArrayBuffer[(String, Map[String, DynamicValue])] = ArrayBuffer.empty
    @volatile var closed: Boolean = false
    override def execute(code: String): Either[DspyError, CodeResult] = execute(code, Map.empty)
    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      executed += ((code, variables))
      val i = idx.getAndIncrement()
      if i < responses.size then responses(i) else Right(CodeResult("", "", 0))
    override def close(): Unit = closed = true

  /** Scripted LM: canned responses, in order. */
  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    val calls: AtomicInteger = AtomicInteger(0)
    override val id: String = "scripted-rlm-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = calls.getAndIncrement()
      val text = if i < responses.size then responses(i) else ""
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Adapter convention: action steps (outputs include `code`) parse `reasoning||code`; the extract step assigns
    * the text to every output field. Records the `repl_history` input of every action call. */
  private final class ProbeAdapter extends Adapter:
    val actionHistories: ArrayBuffer[String] = ArrayBuffer.empty
    override val name: String = "scripted-rlm-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      if invocation.layout.outputFields.exists(_.name == "code") then
        DynamicValues.recordGet(invocation.inputs.values, "repl_history")
          .map(DynamicValues.renderText).foreach(actionHistories += _)
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val names = layout.outputFields.map(_.name)
      if names.contains("code") then
        val parts = output.text.split("\\|\\|", 2)
        val reasoning = if parts.length >= 1 then parts(0).trim else ""
        val code = if parts.length >= 2 then parts(1) else ""
        Right(ParsedOutput(values = rec("reasoning" := reasoning, "code" := code)))
      else Right(ParsedOutput(values = rec(names.map(_ := output.text)*)))

  private val qaSignature = Signature.fromString("context, query -> answer")

  private def withRlm[A](lm: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def rlm(repl: ScriptedRepl, maxIterations: Int = 5): RLM[(context: String, query: String), (answer: String)] =
    RLM(baseSignature = qaSignature, maxIterations = maxIterations, interpreterFactory = (_, _) => repl)

  test("SUBMIT terminates the loop: outputs decode, trajectory + final_reasoning ride on .raw, repl is closed") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "42"}""")))
    ))
    val lm = new ScriptedLm(Vector("solved it||```python\nSUBMIT(answer='42')\n```"))
    val program = rlm(repl)

    withRlm(lm, new ProbeAdapter) {
      val result = program.apply((context = "the answer is 42", query = "what is it?"))
      assert(result.isRight, result.toString)
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "42")
      assertEquals(pred.raw.asString("final_reasoning").toOption, Some("solved it"))
      val traj = pred.raw.asString("trajectory").toOption.getOrElse("")
      assert(traj.contains("=== Step 1 ===") && traj.contains("FINAL:"), traj)
      // The fence was stripped before execution, and the input fields arrived as REPL variables.
      assertEquals(repl.executed.size, 1)
      assertEquals(repl.executed.head._1, "SUBMIT(answer='42')")
      assertEquals(repl.executed.head._2.keySet, Set("context", "query"))
      assert(repl.closed, "RLM must close the interpreter it built")
    }
  }

  test("iterative exploration: the second action sees the first step's output in repl_history") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("the data has 3 parts\n", "", 0)),
      Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "3"}""")))
    ))
    val lm = new ScriptedLm(Vector(
      "explore||```python\nprint(context[:100])\n```",
      "done||```python\nSUBMIT(answer='3')\n```"
    ))
    val probe = new ProbeAdapter
    val program = rlm(repl)

    withRlm(lm, probe) {
      val result = program.apply((context = "a|b|c", query = "how many parts?"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "3")
      assertEquals(probe.actionHistories.size, 2)
      assertEquals(probe.actionHistories(0), "You have not interacted with the REPL environment yet.")
      assert(probe.actionHistories(1).contains("=== Step 1 ==="), probe.actionHistories(1))
      assert(probe.actionHistories(1).contains("the data has 3 parts"), probe.actionHistories(1))
      assert(probe.actionHistories(1).contains("Output ("), probe.actionHistories(1)) // upstream output header
    }
  }

  test("a SUBMIT missing output fields becomes an [Error] observation and the loop continues") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "", 0, finalOutput = Some("""{"wrong_field": "x"}"""))),
      Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "right"}""")))
    ))
    val lm = new ScriptedLm(Vector(
      "try||```python\nSUBMIT(wrong_field='x')\n```",
      "fix||```python\nSUBMIT(answer='right')\n```"
    ))
    val program = rlm(repl)

    withRlm(lm, new ProbeAdapter) {
      val result = program.apply((context = "c", query = "q"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "right")
      val traj = result.toOption.get.raw.asString("trajectory").toOption.getOrElse("")
      assert(traj.contains("[Error] Missing output fields: [answer]"), traj)
    }
  }

  test("user-code errors and non-Python fences become [Error] observations; max iterations triggers the extract fallback") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "NameError: nope", 1)) // user-code error
    ))
    val lm = new ScriptedLm(Vector(
      "bad code||```python\nnope\n```",
      "wrong lang||```javascript\nconsole.log(1)\n```",
      "fallback answer" // extract step
    ))
    val probe = new ProbeAdapter
    val program = rlm(repl, maxIterations = 2)

    withRlm(lm, probe) {
      val result = program.apply((context = "c", query = "q"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "fallback answer")
      assertEquals(result.toOption.get.raw.asString("final_reasoning").toOption, Some("Extract forced final output"))
      val traj = result.toOption.get.raw.asString("trajectory").toOption.getOrElse("")
      assert(traj.contains("[Error] NameError: nope"), traj)
      assert(traj.contains("Expected Python code but got ```javascript fence"), traj)
      // Only the first snippet reached the interpreter (the non-Python fence was rejected host-side).
      assertEquals(repl.executed.size, 1)
    }
  }

  test("stripCodeFences ports upstream: decorative fences, bare/py tags, non-Python rejection") {
    assertEquals(RLM.stripCodeFences("print(1)"), Right("print(1)"))
    assertEquals(RLM.stripCodeFences("```python\nprint(1)\n```"), Right("print(1)"))
    assertEquals(RLM.stripCodeFences("```\nprint(2)\n```"), Right("print(2)"))
    assertEquals(RLM.stripCodeFences("```py\nprint(3)\n```"), Right("print(3)"))
    // Outer decorative pair around a tagged fence.
    assertEquals(RLM.stripCodeFences("```\n```python\nprint(4)\n```\n```"), Right("print(4)"))
    // Explicit non-Python tag is rejected.
    assert(RLM.stripCodeFences("```javascript\nconsole.log(1)\n```").isLeft)
    // Unclosed fence: take the remainder.
    assertEquals(RLM.stripCodeFences("```python\nprint(5)"), Right("print(5)"))
  }

  test("llm_query tools: shared counter caps total calls; batched returns per-item [ERROR] entries") {
    val lmCalls = AtomicInteger(0)
    val subLm: LanguageModel = new LanguageModel:
      override val id: String = "sub"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val n = lmCalls.incrementAndGet()
        if n == 2 then Left(RuntimeError("sub", "boom"))
        else Right(LmResponse(outputs = Vector(LmOutput(text = s"reply-$n"))))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given ctx: RuntimeContext = RuntimeEnvironment.current
      val tools = RLM.makeLlmTools(maxLlmCalls = 3, subLm = Some(subLm), ctx)
      val query = tools.find(_.name == "llm_query").get
      val batched = tools.find(_.name == "llm_query_batched").get

      assertEquals(query.invoke(rec("prompt" := "hi")).map(DynamicValues.renderText), Right("reply-1"))
      // Batched: 2 prompts -> one fails -> [ERROR] item, like upstream; counter now 3/3.
      val out = batched.invoke(rec("prompts" -> DynamicValues.fromAny(List("a", "b")))).map(DynamicValues.renderText)
      assert(out.toOption.get.contains("[ERROR]"), out.toString)
      // Cap reached: the next call is rejected with the upstream-style message.
      val rejected = query.invoke(rec("prompt" := "again"))
      assert(rejected.left.exists(_.message.contains("LLM call limit exceeded")), rejected.toString)
      // Empty prompt is rejected.
      assert(query.invoke(rec("prompt" := "")).isLeft)
    }
  }

  test("reserved tool names are rejected at construction") {
    val bad = new dspy4s.programs.contracts.ToolFunction:
      override val name: String = "llm_query"
      override def invoke(args: DynamicValue.Record)(using RuntimeContext) = Right(DynamicValues.fromAny("x"))
    intercept[IllegalArgumentException] {
      val _ = RLM(baseSignature = qaSignature, tools = Vector(bad))
    }
  }

  test("ReplVariable.fromValue renders metadata with a head+tail preview, not the full value") {
    val long = "x" * 3000
    val v = RLM.ReplVariable.fromValue(
      "context",
      DynamicValues.fromAny(long),
      Some(FieldSpec("context", FieldRole.Input, description = Some("the corpus")))
    )
    assertEquals(v.typeName, "str")
    assertEquals(v.totalLength, 3000)
    assert(v.preview.length < 1100, s"preview must truncate: ${v.preview.length}")
    assert(v.preview.contains("..."), v.preview)
    val formatted = v.format
    assert(formatted.contains("Variable: `context` (access it in your code)"), formatted)
    assert(formatted.contains("Description: the corpus"), formatted)
    assert(formatted.contains("Total length: 3,000 characters"), formatted)
  }

  test("predict calls carry only their declared meta inputs — base fields stay in the REPL, no warnings") {
    // One non-SUBMIT action with maxIterations = 1 exercises both predict sites (the action step and the
    // extract fallback). The base inputs (context/query) must reach the sandbox as REPL variables only;
    // leaking them into the predict calls makes PredictEngine warn on undeclared fields (dspy's RLM calls
    // its programs with only variables_info/repl_history/iteration).
    val repl = new ScriptedRepl(Vector(Right(CodeResult("looking\n", "", 0))))
    val lm = new ScriptedLm(Vector(
      "explore||```python\nprint(context[:10])\n```",
      "FALLBACK"
    ))
    val program = rlm(repl, maxIterations = 1)
    val captured = new java.io.ByteArrayOutputStream
    withRlm(lm, new ProbeAdapter) {
      Console.withErr(captured) {
        val result = program.apply((context = "abc", query = "q"))
        assert(result.isRight, result.toString)
        assertEquals(result.toOption.get.output.answer, "FALLBACK")
      }
    }
    val warnings = captured.toString.linesIterator.filter(_.contains("ignoring unexpected input field")).toVector
    assertEquals(warnings, Vector.empty)
  }
