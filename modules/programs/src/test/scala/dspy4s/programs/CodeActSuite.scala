package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.runtime.SubprocessPythonInterpreter
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.typed.Signature
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class CodeActSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  /** Records every code snippet the interpreter was asked to run, so tests
    * can assert on what CodeAct passed through. Returns scripted output. */
  private final class RecordingInterpreter(responses: Vector[Either[DspyError, CodeResult]]) extends CodeInterpreter:
    private val idx = new AtomicInteger(0)
    val received: ArrayBuffer[String] = ArrayBuffer.empty
    @volatile var closed: Boolean = false
    override def execute(code: String): Either[DspyError, CodeResult] =
      received += code
      val i = idx.getAndIncrement() % responses.size
      responses(i)
    override def close(): Unit = closed = true

  /** Returns canned LM responses from a queue, advancing on each `call`.
    * Used to feed CodeAct successive codeact and extractor outputs. */
  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    private val idx = new AtomicInteger(0)
    override val id: String = "scripted-codeact-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = idx.getAndIncrement()
      if i >= responses.size then
        Right(LmResponse(outputs = Vector(LmOutput(text = ""))))
      else
        Right(LmResponse(outputs = Vector(LmOutput(text = responses(i)))))

  /** Scripted Adapter that parses the LM's raw text differently based on
    * the signature's expected outputs. For the codeact signature we look
    * for `generated_code` + `finished`; for the extractor we extract from
    * the trailing JSON-style hint in the text. */
  private object ScriptedAdapter extends Adapter:
    override val name: String = "scripted-codeact-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      val outputNames = layout.outputFields.map(_.name).toSet
      val text = output.text
      if outputNames.contains("generated_code") then
        // Codeact step: split the text on the pipe `||`. Convention used
        // only in this test scaffold: <code>||<finished>
        val parts = text.split("\\|\\|", -1)
        val code = if parts.length >= 1 then parts(0) else ""
        val finished = parts.length >= 2 && parts(1).trim.equalsIgnoreCase("true")
        Right(ParsedOutput(values = rec("generated_code" := code, "finished" := finished)))
      else
        // Extractor step: every remaining output field gets the full text.
        Right(ParsedOutput(values = rec(layout.outputFields.map(_.name := text)*)))

  // ── Wiring smoke test (scripted LM + scripted interpreter) ──────────────

  test("CodeAct: single iteration with finished=true runs code once and extracts the answer") {
    val signature = Signature.fromString("question -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "42\n", stderr = "", exitCode = 0))
    ))
    val codeActOutput = "```python\nprint(40 + 2)\n```||true"
    val extractorOutput = "42"
    val lm = new ScriptedLm(Vector(codeActOutput, extractorOutput))

    val program = CodeAct(baseSignature = signature, interpreter = interpreter, maxIterations = 3)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((question = "what is 40 + 2?"))
      assert(result.isRight, s"failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "42")
      val traj = lookupString(pred.raw.values, "trajectory")
      assert(traj.contains("print(40 + 2)"), s"trajectory missing code: $traj")
      assert(traj.contains("42"), s"trajectory missing stdout: $traj")
      // Interpreter saw exactly one execute() call with the stripped code.
      assertEquals(interpreter.received.size, 1)
      assertEquals(interpreter.received.head, "print(40 + 2)")
    }
  }

  test("CodeAct: stops at maxIterations even when finished is never true") {
    val signature = Signature.fromString("q -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "step", stderr = "", exitCode = 0))
    ))
    // Every iteration returns finished=false → CodeAct should hit max.
    val lm = new ScriptedLm(Vector(
      "```python\nprint('a')\n```||false",
      "```python\nprint('b')\n```||false",
      "```python\nprint('c')\n```||false",
      "final" // extractor
    ))

    val program = CodeAct(baseSignature = signature, interpreter = interpreter, maxIterations = 3)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((q = "?"))
      assert(result.isRight)
      assertEquals(interpreter.received.size, 3, "should run exactly maxIterations times")
    }
  }

  test("CodeAct: interpreter execution failure surfaces as a trajectory observation, not a program error") {
    val signature = Signature.fromString("q -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "", stderr = "NameError: x is undefined", exitCode = 1))
    ))
    val lm = new ScriptedLm(Vector(
      "```python\nprint(x)\n```||true",
      "extracted"
    ))

    val program = CodeAct(baseSignature = signature, interpreter = interpreter, maxIterations = 2)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((q = "?"))
      assert(result.isRight, s"CodeAct should not propagate user-code errors as Left; got $result")
      val traj = lookupString(result.toOption.get.raw.values, "trajectory")
      assert(traj.contains("Failed to execute"), s"trajectory missing error label: $traj")
      assert(traj.contains("NameError"), traj)
    }
  }

  test("CodeAct does not call close() on the interpreter (caller-owned lifecycle)") {
    val signature = Signature.fromString("q -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "ok", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector("```python\nprint('ok')\n```||true", "ok"))
    val program = CodeAct(baseSignature = signature, interpreter = interpreter, maxIterations = 1)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val _ = program.apply((q = "?"))
      assert(!interpreter.closed, "CodeAct must not auto-close — that's the caller's job")
    }
  }

  // ── Integration with real SubprocessPythonInterpreter (skipped if no python3) ──

  test("CodeAct + SubprocessPythonInterpreter: code actually runs end-to-end") {
    assume(SubprocessPythonInterpreter.isAvailable(), "python3 not on PATH — skipping")
    val signature = Signature.fromString("q -> answer")
    val interpreter = new SubprocessPythonInterpreter()
    val lm = new ScriptedLm(Vector(
      "```python\nprint(sum(range(10)))\n```||true",
      "45"
    ))
    val program = CodeAct(baseSignature = signature, interpreter = interpreter, maxIterations = 1)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((q = "sum 0..9"))
      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("?"))
      val traj = lookupString(result.toOption.get.raw.values, "trajectory")
      assert(traj.contains("45"), s"expected '45' in trajectory: $traj")
    }
    interpreter.close()
  }

  // ── sandboxTools bridge (G-20: tools-inside-code) ──────────────────────

  test("CodeAct.sandboxTools bridges ToolFunctions into SandboxTools (name, python-typed params, captured context)") {
    val tool = new dspy4s.programs.contracts.ToolFunction:
      override val name: String        = "get_weather"
      override val description: String = "weather lookup"
      override def argSchema: Vector[(String, dspy4s.core.contracts.TypeRef)] =
        Vector("city" -> dspy4s.core.contracts.TypeRef.string, "days" -> dspy4s.core.contracts.TypeRef.int)
      override def invoke(args: zio.blocks.schema.DynamicValue.Record)(using RuntimeContext) =
        val city = dspy4s.core.contracts.DynamicValues.recordGet(args, "city")
          .map(dspy4s.core.contracts.DynamicValues.renderText).getOrElse("?")
        Right(dspy4s.core.contracts.DynamicValues.fromAny(s"sunny in $city"))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given RuntimeContext = RuntimeEnvironment.current
      val bridged = CodeAct.sandboxTools(Vector(tool))

      assertEquals(bridged.size, 1)
      assertEquals(bridged.head.name, "get_weather")
      // Wire types map to Python type hints: string -> str, int -> int.
      assertEquals(
        bridged.head.parameters,
        Vector(
          dspy4s.core.contracts.SandboxTool.Param("city", Some("str")),
          dspy4s.core.contracts.SandboxTool.Param("days", Some("int"))
        )
      )
      // Invocation works OUTSIDE a dspy4s call stack (the bridge captured the context at construction).
      val result = bridged.head.invoke(rec("city" := "Paris"))
      assertEquals(result.map(dspy4s.core.contracts.DynamicValues.renderText), Right("sunny in Paris"))
    }
  }
