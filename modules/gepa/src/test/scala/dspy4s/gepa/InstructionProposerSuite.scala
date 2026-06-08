package dspy4s.gepa

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite

class InstructionProposerSuite extends FunSuite:

  /** Records the prompt it saw and returns a fenced new instruction (with surrounding chatter to be stripped). */
  private final class ReflectionLm(response: String) extends LanguageModel:
    @volatile var lastPrompt: Option[String] = None
    override val id: String   = "reflect"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      lastPrompt = Some(request.messages.flatMap(_.text).mkString("\n"))
      Right(LmResponse(outputs = Vector(LmOutput(text = response))))

  private val records = Vector(
    ReflectiveRecord(inputs = "question: Capital of France?", generatedOutputs = "Lyon", feedback = "expected 'Paris', got 'Lyon'")
  )

  test("propose prompts with the current instruction + dataset and extracts the fenced rewrite") {
    given RuntimeContext = RuntimeContext()
    val lm = new ReflectionLm("Sure! Here's a better one:\n```\nAnswer with only the city name.\n```\nHope that helps.")

    val result = InstructionProposer.propose("Answer the question.", records, lm)
    assertEquals(result, Right("Answer with only the city name."))

    val prompt = lm.lastPrompt.getOrElse(fail("expected a prompt"))
    assert(prompt.contains("Answer the question."), "prompt must include the current instruction")
    assert(prompt.contains("expected 'Paris', got 'Lyon'"), "prompt must include the feedback")
    assert(prompt.contains("Lyon"), "prompt must include the generated output")
  }

  test("extractInstruction falls back to the whole response when there are no fences") {
    assertEquals(InstructionProposer.extractInstruction("Just write better answers."), "Just write better answers.")
  }

  test("extractInstruction tolerates a missing closing fence") {
    assertEquals(InstructionProposer.extractInstruction("```\nNew instruction without a close"), "New instruction without a close")
  }
