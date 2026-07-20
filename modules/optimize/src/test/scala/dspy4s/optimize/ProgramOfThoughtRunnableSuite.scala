package dspy4s.optimize

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{
  CodeInterpreter, CodeResult, DspyError, DynamicValues, RuntimeContext, SignatureLayout, :=
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.programs.ProgramOfThought
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

private final case class ProgramOfThoughtRunnableInput(question: String) derives Schema
private final case class ProgramOfThoughtRunnableOutput(answer: String) derives Schema

class ProgramOfThoughtRunnableSuite extends FunSuite:

  private object ScriptedAdapter extends Adapter:
    val name: String = "program-of-thought-runnable"

    def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))

    def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val entries = layout.outputFields.map { field =>
        field.name := (if field.name == "reasoning" then "scripted reasoning" else output.text)
      }
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(entries)))

  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    private val index = new AtomicInteger(0)
    val id: String    = "program-of-thought-runnable"
    val mode: LmMode  = LmMode.Chat

    def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = responses(index.getAndIncrement())))))

  private final class RecordingInterpreter extends CodeInterpreter:
    val received: ArrayBuffer[String] = ArrayBuffer.empty

    def execute(code: String): Either[DspyError, CodeResult] =
      received += code
      Right(CodeResult(stdout = "42\n", stderr = "", exitCode = 0))

    def close(): Unit = ()

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  test("Runnable decodes ProgramOfThought input, executes it, and returns the final raw prediction") {
    val interpreter = new RecordingInterpreter
    val program = ProgramOfThought(
      baseSignature = Signature.derived[ProgramOfThoughtRunnableInput, ProgramOfThoughtRunnableOutput]("PoTRunnable"),
      interpreter = interpreter
    )
    val inputs = DynamicValues.recordFromEntries(Vector("question" := "What is six times seven?"))

    RuntimeEnvironment.withSettings(
      RuntimeContext(
        lm = Some(new ScriptedLm(Vector("```python\nprint(6 * 7)\n```", "42"))),
        adapter = Some(ScriptedAdapter)
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = summon[Runnable[ProgramOfThought[ProgramOfThoughtRunnableInput, ProgramOfThoughtRunnableOutput]]]
        .run(program, inputs)

      assert(result.isRight, result.left.toOption.map(_.message).getOrElse("Runnable failed"))
      val raw = result.toOption.get.values
      assertEquals(DynamicValues.recordGet(raw, "answer").map(DynamicValues.renderText), Some("42"))
      assertEquals(
        DynamicValues.recordGet(raw, "reasoning").map(DynamicValues.renderText),
        Some("scripted reasoning")
      )
      assertEquals(interpreter.received.toVector, Vector("print(6 * 7)"))
    }
  }
