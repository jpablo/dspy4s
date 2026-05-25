package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.runtime.SubprocessPythonInterpreter
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class ProgramOfThoughtSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private final class RecordingInterpreter(responses: Vector[Either[DspyError, CodeResult]]) extends CodeInterpreter:
    private val idx = new AtomicInteger(0)
    val received: ArrayBuffer[String] = ArrayBuffer.empty
    @volatile var closed: Boolean = false
    override def execute(code: String): Either[DspyError, CodeResult] =
      received += code
      val i = idx.getAndIncrement() % responses.size
      responses(i)
    override def close(): Unit = closed = true

  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    private val idx = new AtomicInteger(0)
    override val id: String = "scripted-pot-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = idx.getAndIncrement()
      val text = if i >= responses.size then "" else responses(i)
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Adapter that picks output values from the LM's text by simple
    * convention used only for these scaffold tests:
    *
    *   - signature has `generated_code` output → text is the code, we wrap
    *     in fences to mirror what a real LM would do
    *   - any other signature → every output field gets the raw text as its
    *     value
    */
  private object ScriptedAdapter extends Adapter:
    override val name: String = "scripted-pot-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(signature: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val names = signature.outputFields.map(_.name)
      if names.contains("generated_code") then
        Right(ParsedOutput(values = Map("generated_code" -> output.text) ++
          // DynamicChainOfThought also wants `reasoning`; supply a placeholder.
          (if names.contains("reasoning") then Map("reasoning" -> "scripted reasoning") else Map.empty)))
      else
        // Answer signature — every output field gets the LM's text.
        Right(ParsedOutput(values = names.map(_ -> output.text).toMap))

  // ── Single-shot success ────────────────────────────────────────────────

  test("ProgramOfThought: successful first attempt — code generates, runs, answer extracted") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = """{"answer": "42"}""" + "\n", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector(
      "```python\nimport json\nprint(json.dumps({'answer': '42'}))\n```",
      "42"
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> lm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.run(ProgramCall(inputs = Map("question" -> "what is 6 * 7?")))
      assert(result.isRight, s"failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      assertEquals(pred.values("answer"), "42")
      assertEquals(interpreter.received.size, 1)
      assert(interpreter.received.head.contains("import json"))
    }
  }

  // ── Retry on execution error ───────────────────────────────────────────

  test("ProgramOfThought: regenerate path runs on execution error, then succeeds") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "", stderr = "NameError: x is undefined", exitCode = 1)),
      Right(CodeResult(stdout = "ok\n", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector(
      "```python\nprint(x)\n```",     // initial bad code
      "```python\nprint('ok')\n```",  // regenerated good code
      "ok"                            // answer extraction
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter, maxIterations = 3)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> lm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.run(ProgramCall(inputs = Map("question" -> "?")))
      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("?"))
      assertEquals(interpreter.received.size, 2, "should have retried after error")
      assertEquals(result.toOption.get.values("answer"), "ok")
    }
  }

  // ── Give up after maxIterations ────────────────────────────────────────

  test("ProgramOfThought: fails with RuntimeError after maxIterations consecutive errors") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "", stderr = "Boom", exitCode = 1))
    ))
    val lm = new ScriptedLm(Vector(
      "```python\nprint(x)\n```",
      "```python\nprint(x)\n```",
      "```python\nprint(x)\n```"
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter, maxIterations = 2)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> lm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.run(ProgramCall(inputs = Map("question" -> "?")))
      assert(result.isLeft, s"expected Left after maxIterations, got $result")
      val err = result.left.toOption.get.asInstanceOf[RuntimeError]
      assertEquals(err.component, "program_of_thought")
      assert(err.message.contains("Max attempts"), err.message)
    }
  }

  test("ProgramOfThought: does NOT close the interpreter (caller-owned lifecycle)") {
    val signature = SignatureDsl.parse("q -> answer").toOption.get
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "ok", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector("```python\nprint('ok')\n```", "ok"))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> lm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      program.run(ProgramCall(inputs = Map("q" -> "?")))
      assert(!interpreter.closed)
    }
  }

  // ── Integration test (real python3 if available) ───────────────────────

  test("ProgramOfThought + SubprocessPythonInterpreter: runs end-to-end") {
    assume(SubprocessPythonInterpreter.isAvailable(), "python3 not on PATH — skipping")
    val signature = SignatureDsl.parse("q -> answer").toOption.get
    val interpreter = new SubprocessPythonInterpreter()
    val lm = new ScriptedLm(Vector(
      "```python\nimport json\nresult = sum(range(11))\nprint(json.dumps({'answer': result}))\n```",
      "55"
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> lm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.run(ProgramCall(inputs = Map("q" -> "sum 0..10")))
      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("?"))
      assertEquals(result.toOption.get.values("answer"), "55")
    }
    interpreter.close()
  }
