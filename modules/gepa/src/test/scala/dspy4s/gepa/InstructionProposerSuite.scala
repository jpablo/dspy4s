package dspy4s.gepa

import munit.FunSuite

final class InstructionProposerSuite extends FunSuite:

  private val records = Vector(ReflectiveRecord("question: Capital of France?", "Lyon", "Expected Paris"))

  test("buildPrompt includes the current instruction and evidence") {
    val prompt = InstructionProposer.buildPrompt("Answer the question.", records)

    assert(prompt.contains("Answer the question."))
    assert(prompt.contains("Lyon"))
    assert(prompt.contains("Expected Paris"))
  }

  test("extractInstruction reads fenced and plain responses") {
    assertEquals(InstructionProposer.extractInstruction("before\n```\nBe exact.\n```\nafter"), "Be exact.")
    assertEquals(InstructionProposer.extractInstruction("Just answer."), "Just answer.")
    assertEquals(InstructionProposer.extractInstruction("```\nNo closing fence"), "No closing fence")
  }
