package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{
  CodeResult, DspyError, DynamicValues, FieldSpec, ReplCodeInterpreter, RuntimeContext, RuntimeError, SandboxTool,
  TypeRef
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.signatures.Signature
import dspy4s.programs.support.ScriptedLm
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class RLMSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  /** Scripted REPL: returns canned CodeResults, recording each (code, variables) execute call. */
  private final class ScriptedRepl(responses: Vector[Either[DspyError, CodeResult]]) extends ReplCodeInterpreter:
    private val idx                                                                                         = new AtomicInteger(0)
    val executed: ArrayBuffer[(String, Map[String, DynamicValue])]                                          = ArrayBuffer.empty
    @volatile var closed: Boolean                                                                           = false
    override def execute(code: String): Either[DspyError, CodeResult]                                       = execute(code, Map.empty)
    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      executed += ((code, variables))
      val i = idx.getAndIncrement()
      if i < responses.size then responses(i) else Right(CodeResult("", "", 0))
    override def close(): Unit = closed = true

  /** Adapter convention: action steps (outputs include `code`) parse `reasoning||code`; the extract step assigns the
    * text to every output field. Records the `repl_history` input of every action call.
    */
  private final class ProbeAdapter extends Adapter:
    val actionHistories: ArrayBuffer[String]                                                                     = ArrayBuffer.empty
    override val name: String                                                                                    = "scripted-rlm-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      if invocation.layout.outputFields.exists(_.name == "code") then
        DynamicValues.recordGet(invocation.inputs.values, "repl_history")
          .map(DynamicValues.renderText).foreach(actionHistories += _)
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(invocation: AdapterInvocation, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      val names = invocation.layout.outputFields.map(_.name)
      if names.contains("code") then
        val parts     = output.text.split("\\|\\|", 2)
        val reasoning = if parts.length >= 1 then parts(0).trim else ""
        val code      = if parts.length >= 2 then parts(1) else ""
        Right(ParsedOutput(values = rec("reasoning" := reasoning, "code" := code)))
      else Right(ParsedOutput(values = rec(names.map(_ := output.text)*)))

  private val qaSignature = Signature.fromString("context, query -> answer")

  private def withRlm[A](lm: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def rlm(
      repl         : ScriptedRepl,
      maxIterations: IterationLimit = IterationLimit(5)
  ): RLM[(context: String, query: String), (answer: String)] =
    RLM(baseSignature = qaSignature, maxIterations = maxIterations, interpreterFactory = Some((_, _) => repl))

  test("SUBMIT terminates the loop: outputs decode, trajectory + final_reasoning ride on .raw, repl is closed") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "42"}""")))
    ))
    val lm      = new ScriptedLm(Vector("solved it||```python\nSUBMIT(answer='42')\n```"))
    val program = rlm(repl)

    withRlm(lm, new ProbeAdapter) {
      val result = program((context = "the answer is 42", query = "what is it?"))
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
    val probe   = new ProbeAdapter
    val program = rlm(repl)

    withRlm(lm, probe) {
      val result = program((context = "a|b|c", query = "how many parts?"))
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
      val result = program((context = "c", query = "q"))
      assert(result.isRight, result.toString)
      assertEquals(result.toOption.get.output.answer, "right")
      val traj = result.toOption.get.raw.asString("trajectory").toOption.getOrElse("")
      assert(traj.contains("[Error] Missing output fields: [answer]"), traj)
    }
  }

  test(
    "user-code errors and non-Python fences become [Error] observations; max iterations triggers the extract fallback"
  ) {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "NameError: nope", 1)) // user-code error
    ))
    val lm = new ScriptedLm(Vector(
      "bad code||```python\nnope\n```",
      "wrong lang||```javascript\nconsole.log(1)\n```",
      "fallback answer" // extract step
    ))
    val probe   = new ProbeAdapter
    val program = rlm(repl, maxIterations = IterationLimit(2))

    withRlm(lm, probe) {
      val result = program((context = "c", query = "q"))
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
    val lmCalls              = AtomicInteger(0)
    val subLm: LanguageModel = new LanguageModel:
      override val id: String                                                                    = "sub"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val n = lmCalls.incrementAndGet()
        if n == 2 then Left(RuntimeError("sub", "boom"))
        else Right(LmResponse(outputs = Vector(LmOutput(text = s"reply-$n"))))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given ctx: RuntimeContext = RuntimeEnvironment.current
      val tools                 = RLM.makeLlmTools(maxLlmCalls = LlmCallLimit(3), subLm = Some(subLm), ctx)
      val query                 = tools.find(_.name == "llm_query").get
      val batched               = tools.find(_.name == "llm_query_batched").get

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
      override val name: String                                            = "llm_query"
      override def invoke(args: DynamicValue.Record)(using RuntimeContext) = Right(DynamicValues.fromAny("x"))
    intercept[IllegalArgumentException] {
      val _ = RLM(baseSignature = qaSignature, tools = Vector(bad))
    }
  }

  // The bridge from `ToolFunction` to `SandboxTool` (G-20: tools-inside-code).

  test("user tools reach the interpreter factory as SandboxTools (name, Python parameter declarations, context)") {
    val tool = new dspy4s.programs.contracts.ToolFunction:
      override val name: String                         = "get_weather"
      override val description: String                  = "weather lookup"
      override def argSchema: Vector[(String, TypeRef)] =
        Vector("city" -> TypeRef.string, "days" -> TypeRef.int)
      override def invoke(args: DynamicValue.Record)(using RuntimeContext) =
        val city = DynamicValues.recordGet(args, "city").map(DynamicValues.renderText).getOrElse("?")
        Right(DynamicValues.fromAny(s"sunny in $city"))

    val repl    = new ScriptedRepl(Vector(Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "42"}""")))))
    val bridged = ArrayBuffer.empty[SandboxTool]
    val program = RLM(
      baseSignature = qaSignature,
      tools = Vector(tool),
      interpreterFactory = Some { (sandboxTools, _) =>
        bridged ++= sandboxTools
        repl
      }
    )

    withRlm(new ScriptedLm(Vector("solved it||```python\nSUBMIT(answer='42')\n```")), new ProbeAdapter) {
      assert(program((context = "c", query = "q")).isRight)
    }

    // The built-in `llm_query` tools come first. The user tools come after them.
    assertEquals(bridged.map(_.name).toVector, Vector("llm_query", "llm_query_batched", "get_weather"))
    val weather = bridged.last
    // Wire types map to Python type hints: string -> str, int -> int.
    assertEquals(
      weather.parameters,
      Vector(SandboxTool.Param("city", Some("str")), SandboxTool.Param("days", Some("int")))
    )
    // The call works outside a dspy4s call stack, because the bridge captured the context of the run.
    assertEquals(weather.invoke(rec("city" := "Paris")).map(DynamicValues.renderText), Right("sunny in Paris"))
  }

  test("ReplVariable.fromValue renders metadata with a head+tail preview, not the full value") {
    val long = "x" * 3000
    val v    = RLM.ReplVariable.fromValue(
      "context",
      DynamicValues.fromAny(long),
      Some(FieldSpec("context", description = Some("the corpus")))
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
    val lm   = new ScriptedLm(Vector(
      "explore||```python\nprint(context[:10])\n```",
      "FALLBACK"
    ))
    val program  = rlm(repl, maxIterations = IterationLimit(1))
    val captured = new java.io.ByteArrayOutputStream
    withRlm(lm, new ProbeAdapter) {
      Console.withErr(captured) {
        val result = program((context = "abc", query = "q"))
        assert(result.isRight, result.toString)
        assertEquals(result.toOption.get.output.answer, "FALLBACK")
      }
    }
    val warnings = captured.toString.linesIterator.filter(_.contains("ignoring unexpected input field")).toVector
    assertEquals(warnings, Vector.empty)
  }

  test(
    "verbose = true streams each iteration's reasoning/code and step output to stderr — surviving a mid-run failure"
  ) {
    val repl = new ScriptedRepl(Vector(Right(CodeResult("looking around\n", "", 0))))
    // First action succeeds; the second LM call fails hard, so the run returns Left and the trajectory
    // never reaches a Prediction. The verbose stream is the surviving record of what happened.
    val lm = new LanguageModel:
      private val n                                                                              = new AtomicInteger(0)
      override val id: String                                                                    = "fails-second"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        if n.getAndIncrement() == 0 then
          Right(LmResponse(outputs = Vector(LmOutput(text = "explore||```python\nprint(context)\n```"))))
        else Left(RuntimeError("lm", "boom"))
    val program = RLM(
      baseSignature = qaSignature,
      maxIterations = IterationLimit(5),
      verbose = true,
      interpreterFactory = Some((_, _) => repl)
    )
    val captured = new java.io.ByteArrayOutputStream
    withRlm(lm, new ProbeAdapter) {
      Console.withErr(captured) {
        assert(program((context = "abc", query = "q")).isLeft)
      }
    }
    val logged = captured.toString
    assert(logged.contains("RLM iteration 1/5"), logged)
    assert(logged.contains("Reasoning: explore"), logged)
    assert(logged.contains("print(context)"), logged)
    assert(logged.contains("looking around"), logged)
  }

  test("max-iterations fallback warns on stderr unconditionally; per-step logs only appear with verbose") {
    val repl     = new ScriptedRepl(Vector(Right(CodeResult("x\n", "", 0))))
    val lm       = new ScriptedLm(Vector("look||```python\nprint(1)\n```", "FALLBACK"))
    val program  = rlm(repl, maxIterations = IterationLimit(1))
    val captured = new java.io.ByteArrayOutputStream
    withRlm(lm, new ProbeAdapter) {
      Console.withErr(captured) {
        assert(program((context = "abc", query = "q")).isRight)
      }
    }
    val logged = captured.toString
    assert(logged.contains("WARN [dspy4s] RLM: reached max iterations, using extract to get final output"), logged)
    assert(!logged.contains("RLM iteration"), logged)
  }

  // PORT_GAPS G-35 (upstream 89696e0cc): a `Left` from the interpreter means that the interpreter failed. Generated
  // code cannot repair that, so the run must stop.

  test("an interpreter failure ends the run: no observation and no more LM calls") {
    val failure = RuntimeError("code_interpreter", "Deno executable not found")
    val repl    = new ScriptedRepl(Vector(Left(failure)))
    val lm      = new ScriptedLm(Vector(
      "first try||```python\nprint(1)\n```",
      "second try||```python\nprint(2)\n```",
      "extract"
    ))
    val program = rlm(repl)

    withRlm(lm, new ProbeAdapter) {
      val result = program((context = "c", query = "q"))
      assertEquals(result.left.toOption, Some(failure))
      assertEquals(lm.calls.get(), 1, "the LM must not see an interpreter failure as an observation")
      assertEquals(repl.executed.size, 1)
      assert(repl.closed, "RLM must close the interpreter that it built")
    }
  }

  test("a user-code error is still an observation and the loop continues") {
    val repl = new ScriptedRepl(Vector(
      Right(CodeResult("", "NameError: name 'x' is not defined", 1)),
      Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "ok"}""")))
    ))
    val lm = new ScriptedLm(Vector(
      "first try||```python\nprint(x)\n```",
      "second try||```python\nSUBMIT(answer='ok')\n```"
    ))
    val probe   = new ProbeAdapter
    val program = rlm(repl)

    withRlm(lm, probe) {
      val result = program((context = "c", query = "q"))
      assertEquals(result.map(_.output.answer), Right("ok"))
      assertEquals(lm.calls.get(), 2)
      assert(probe.actionHistories.lastOption.exists(_.contains("NameError")), probe.actionHistories.toString)
    }
  }

  // The tests below are for the interpreter factory of the runtime context (PORT_GAPS G-35; upstream
  // `dspy.settings.interpreter_factory` and `resolve_interpreter_factory`).

  private def submitRepl(answer: String): ScriptedRepl =
    new ScriptedRepl(Vector(Right(CodeResult("", "", 0, finalOutput = Some(s"""{"answer": "$answer"}""")))))

  private def submitLm: ScriptedLm = new ScriptedLm(Vector("solved it||```python\nSUBMIT(answer='x')\n```"))

  test("an RLM without a factory uses the factory of the runtime context") {
    val repl    = submitRepl("from context")
    val seen    = ArrayBuffer.empty[(Vector[String], Vector[String])]
    val program = RLM(baseSignature = qaSignature)
    val context = RuntimeContext(
      lm = Some(submitLm),
      adapter = Some(new ProbeAdapter),
      interpreterFactory = Some { (tools, outputs) =>
        seen += ((tools.map(_.name), outputs.map(_.name)))
        repl
      }
    )

    RuntimeEnvironment.withSettings(context) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("from context"))
    }
    // The factory gets the sandbox tools and the output fields of SUBMIT, and RLM closes the interpreter.
    assertEquals(seen.toVector, Vector((Vector("llm_query", "llm_query_batched"), Vector("answer"))))
    assert(repl.closed, "RLM must close the interpreter that the context factory built")
  }

  test("the factory of the RLM wins over the factory of the runtime context") {
    val own     = submitRepl("from program")
    val ambient = submitRepl("from context")
    val program = RLM(baseSignature = qaSignature, interpreterFactory = Some((_, _) => own))
    val context = RuntimeContext(
      lm = Some(submitLm),
      adapter = Some(new ProbeAdapter),
      interpreterFactory = Some((_, _) => ambient)
    )

    RuntimeEnvironment.withSettings(context) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("from program"))
    }
    assertEquals(ambient.executed.size, 0)
  }

  test("the context factory builds one interpreter for each call") {
    val built   = ArrayBuffer.empty[ScriptedRepl]
    val program = RLM(baseSignature = qaSignature)
    val context = RuntimeContext(
      lm = Some(new ScriptedLm(Vector.fill(2)("solved it||```python\nSUBMIT(answer='x')\n```"))),
      adapter = Some(new ProbeAdapter),
      interpreterFactory = Some { (_, _) =>
        val repl = submitRepl(s"call ${built.size + 1}")
        built += repl
        repl
      }
    )

    RuntimeEnvironment.withSettings(context) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("call 1"))
      assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("call 2"))
    }
    assertEquals(built.size, 2)
    assert(built.forall(_.closed), "RLM must close each interpreter")
  }

  test("a factory in a nested scope replaces the factory of the outer scope for that scope only") {
    val outer                                  = ArrayBuffer.empty[String]
    val program                                = RLM(baseSignature = qaSignature)
    def context(label: String): RuntimeContext =
      RuntimeContext(
        lm = Some(submitLm),
        adapter = Some(new ProbeAdapter),
        interpreterFactory = Some { (_, _) =>
          outer += label
          submitRepl(label)
        }
      )

    RuntimeEnvironment.withSettings(context("outer")) {
      RuntimeEnvironment.withSettings(context("inner")) {
        given RuntimeContext = RuntimeEnvironment.current
        assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("inner"))
      }
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(program((context = "c", query = "q")).map(_.output.answer), Right("outer"))
    }
    assertEquals(outer.toVector, Vector("inner", "outer"))
  }

  // ── The allowed values of a SUBMIT (decision D7) ────────────────────────────────────────────────────────────
  // Upstream `_process_final_output` reads each SUBMIT value with `parse_value` (`dspy/predict/rlm.py`). A value
  // outside a `Literal[...]` gives the observation `[Type Error] <name>: expected Literal, got <type>: <error>`, and
  // the loop continues. A value that `parse_value` accepts after its steps is the allowed value itself.

  /** An RLM over `dsl`, with a REPL that gives the SUBMIT payloads `payloads` in order. */
  private def submitting(dsl: String, payloads: String*): (RLM[DynamicValue.Record, DynamicValue.Record], ScriptedLm) =
    val signature = Signature.fromStringDynamic(dsl).fold(error => fail(error.message), identity)
    val results   = payloads.toVector.map(json => Right(CodeResult("", "", 0, finalOutput = Some(json))))
    val repl      = new ScriptedRepl(results)
    val lm        = new ScriptedLm(payloads.toVector.map(_ => "submit||```python\nSUBMIT()\n```"))
    (RLM(baseSignature = signature, interpreterFactory = Some((_, _) => repl)), lm)

  test("a SUBMIT value outside the allowed values is a [Type Error] observation, and the loop continues") {
    val (program, lm) = submitting(
      "context, query -> answer: Literal['yes', 'no'], note",
      """{"answer": "maybe", "note": "n"}""",
      """{"answer": " 'yes' ", "note": "n"}"""
    )
    withRlm(lm, new ProbeAdapter) {
      val result = program(rec("context" := "c", "query" := "q")).fold(error => fail(error.message), identity)
      // Upstream `parse_value` strips the text and removes the quotes, and gives the allowed value.
      assertEquals(DynamicValues.recordGet(result.output, "answer").map(DynamicValues.renderText), Some("yes"))
      assertEquals(DynamicValues.recordGet(result.raw.values, "answer").map(DynamicValues.renderText), Some("yes"))
      val trajectory = result.raw.asString("trajectory").toOption.getOrElse("")
      assert(
        trajectory.contains(
          "[Type Error] answer: expected Literal, got str: Value 'maybe' is not one of the allowed values: yes; no"
        ),
        trajectory
      )
      assertEquals(lm.calls.get(), 2)
    }
  }

  test("each SUBMIT value outside its allowed values is in the [Type Error], also a value that is not a string") {
    val (program, lm) = submitting(
      "context, query -> tone: Literal['calm', 'angry'], topic: Literal['billing', 'login']",
      """{"tone": 3, "topic": "Billing"}""",
      """{"tone": "Literal['calm']", "topic": "login"}"""
    )
    withRlm(lm, new ProbeAdapter) {
      val result = program(rec("context" := "c", "query" := "q")).fold(error => fail(error.message), identity)
      assertEquals(
        Vector("tone", "topic").map(name => DynamicValues.recordGet(result.output, name).map(DynamicValues.renderText)),
        Vector(Some("calm"), Some("login"))
      )
      val trajectory = result.raw.asString("trajectory").toOption.getOrElse("")
      assert(
        trajectory.contains(
          "[Type Error] tone: expected Literal, got int: Value '3' is not one of the allowed values: calm; angry; " +
            "topic: expected Literal, got str: Value 'Billing' is not one of the allowed values: billing; login"
        ),
        trajectory
      )
    }
  }
