package dspy4s.core

import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

/** `appendInstructions` keeps the instructions and adds text after a blank line (upstream
  * `Signature.append_instructions` in `dspy/signatures/signature.py`).
  */
class AppendInstructionsSuite extends FunSuite:

  test("appendInstructions keeps the instructions, adds a blank line and the text, and keeps the fields") {
    val layout   = SignatureDsl.parse("input_text -> output_text").toOption.get.withInstructions("Translate to French.")
    val appended = layout.appendInstructions("Pass additional context.")
    assertEquals(appended.instructions, Some("Translate to French.\n\nPass additional context."))
    assertEquals(appended.fields, layout.fields)
    assertEquals(layout.instructions, Some("Translate to French."))
  }

  test("with no instructions, appendInstructions adds the text after the default instructions") {
    val layout = SignatureDsl.parse("question, context -> answer").toOption.get.withInstructions(None)
    assertEquals(
      layout.appendInstructions("Be brief.").instructions,
      Some("Given the fields `question`, `context`, produce the fields `answer`.\n\nBe brief.")
    )
  }
