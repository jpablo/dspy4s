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
import zio.blocks.schema.DynamicValue

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
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val names = layout.outputFields.map(_.name)
      if names.contains("generated_code") then
        val entries: Seq[(String, DynamicValue)] =
          Seq("generated_code" := output.text) ++
            (if names.contains("reasoning") then Seq("reasoning" := "scripted reasoning") else Seq.empty)
        Right(ParsedOutput(values = rec(entries*)))
      else
        // Answer signature — every output field gets the LM's text.
        Right(ParsedOutput(values = rec(names.map(_ := output.text)*)))

  // ── Single-shot success ────────────────────────────────────────────────

  test("ProgramOfThought: successful first attempt — code generates, runs, answer extracted") {
    val signature = Signature.fromString("question -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = """{"answer": "42"}""" + "\n", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector(
      "```python\nimport json\nprint(json.dumps({'answer': '42'}))\n```",
      "42"
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((question = "what is 6 * 7?"))
      assert(result.isRight, s"failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "42")
      assertEquals(interpreter.received.size, 1)
      assert(interpreter.received.head.contains("import json"))
    }
  }

  // ── Retry on execution error ───────────────────────────────────────────

  test("ProgramOfThought: regenerate path runs on execution error, then succeeds") {
    val signature = Signature.fromString("question -> answer")
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
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((question = "?"))
      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("?"))
      assertEquals(interpreter.received.size, 2, "should have retried after error")
      assertEquals(result.toOption.get.output.answer, "ok")
    }
  }

  // ── Give up after maxIterations ────────────────────────────────────────

  test("ProgramOfThought: fails with RuntimeError after maxIterations consecutive errors") {
    val signature = Signature.fromString("question -> answer")
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
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((question = "?"))
      assert(result.isLeft, s"expected Left after maxIterations, got $result")
      val err = result.left.toOption.get.asInstanceOf[RuntimeError]
      assertEquals(err.component, "program_of_thought")
      assert(err.message.contains("Max attempts"), err.message)
    }
  }

  test("ProgramOfThought: does NOT close the interpreter (caller-owned lifecycle)") {
    val signature = Signature.fromString("q -> answer")
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "ok", stderr = "", exitCode = 0))
    ))
    val lm = new ScriptedLm(Vector("```python\nprint('ok')\n```", "ok"))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val _ = program.apply((q = "?"))
      assert(!interpreter.closed)
    }
  }

  // ── Integration test (real python3 if available) ───────────────────────

  test("ProgramOfThought + SubprocessPythonInterpreter: runs end-to-end") {
    assume(SubprocessPythonInterpreter.isAvailable(), "python3 not on PATH — skipping")
    val signature = Signature.fromString("q -> answer")
    val interpreter = new SubprocessPythonInterpreter()
    val lm = new ScriptedLm(Vector(
      "```python\nimport json\nresult = sum(range(11))\nprint(json.dumps({'answer': result}))\n```",
      "55"
    ))
    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(lm),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((q = "sum 0..10"))
      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("?"))
      assertEquals(result.toOption.get.output.answer, "55")
    }
    interpreter.close()
  }

  // ── Field-marker derivation (dspy 3.2.1 alignment, item P3) ──────────────
  // Upstream dropped the hardcoded `prefix=` kwargs from the PoT fields and
  // relies on adapter defaults. dspy4s mirrors that by running
  // `FieldSpec.normalize` over the auxiliary fields in the augment path, so the
  // prefix is DERIVED (title-case) from the field name rather than hardcoded.
  // Note the old code hardcoded "Code:" on BOTH generated_code and
  // final_generated_code (a collision); derivation disambiguates them.
  test("ProgramOfThought: auxiliary field prefixes are derived (title-case) from field names") {
    val signature   = Signature.fromString("question -> answer")
    val interpreter = new RecordingInterpreter(Vector(Right(CodeResult(stdout = "ok", stderr = "", exitCode = 0))))
    val program     = ProgramOfThought(baseSignature = signature, interpreter = interpreter)

    def prefixOf(layout: SignatureLayout, field: String): Option[String] =
      layout.fields.find(_.name == field).flatMap(_.prefix)

    assertEquals(prefixOf(program.generateSignature, "generated_code"),       Some("Generated Code:"))
    assertEquals(prefixOf(program.regenerateSignature, "previous_code"),      Some("Previous Code:"))
    assertEquals(prefixOf(program.regenerateSignature, "error"),             Some("Error:"))
    assertEquals(prefixOf(program.answerSignature, "final_generated_code"),  Some("Final Generated Code:"))
    assertEquals(prefixOf(program.answerSignature, "code_output"),           Some("Code Output:"))
    interpreter.close()
  }

  // ── SUBMIT-capable interpreter (G-20) ─────────────────────────────────

  test("ProgramOfThought: prefers a SUBMIT finalOutput over printed stdout when the interpreter provides one") {
    val signature = Signature.fromString("question -> answer")
    // SUBMIT-capable result: stdout is noise; the structured early-exit carries the real output.
    val interpreter = new RecordingInterpreter(Vector(
      Right(CodeResult(stdout = "debug noise\n", stderr = "", exitCode = 0, finalOutput = Some("""{"answer": "42"}""")))
    ))
    val lm = new ScriptedLm(Vector("```python\nSUBMIT(answer='42')\n```", "42"))

    // Adapter variant that also records the answer step's `code_output` input, so the test can assert WHAT
    // reached the answer signature (the finalOutput, not the printed noise).
    val codeOutputs = ArrayBuffer.empty[String]
    object RecordingAnswerAdapter extends Adapter:
      override val name: String = "recording-answer"
      override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
        if invocation.layout.outputFields.exists(_.name == "answer") then
          dspy4s.core.contracts.DynamicValues.recordGet(invocation.inputs.values, "code_output")
            .map(dspy4s.core.contracts.DynamicValues.renderText).foreach(codeOutputs += _)
        Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
      override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
        val names = layout.outputFields.map(_.name)
        if names.contains("generated_code") then
          val entries: Seq[(String, DynamicValue)] = Seq("generated_code" := output.text)
          Right(ParsedOutput(values = rec(entries*)))
        else Right(ParsedOutput(values = rec(names.map(_ := output.text)*)))

    val program = ProgramOfThought(baseSignature = signature, interpreter = interpreter)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(RecordingAnswerAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((question = "what is 6 * 7?"))
      assert(result.isRight, s"failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      // The answer step saw the SUBMIT payload, not the printed stdout noise.
      assertEquals(codeOutputs.toList, List("""{"answer": "42"}"""))
    }
  }
