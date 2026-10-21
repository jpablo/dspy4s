package dspy4s.signatures

import munit.FunSuite

/** The typed signature forwards `appendInstructions` to its layout, and keeps its shapes. */
class SignatureAppendInstructionsSuite extends FunSuite:

  test("appendInstructions on a typed signature changes only the instructions") {
    val signature = Signature.fromString("question -> answer").withInstructions("Answer in one word.")
    val appended  = signature.appendInstructions("Use English.")
    assertEquals(appended.instructions, Some("Answer in one word.\n\nUse English."))
    assertEquals(appended.layout.fields, signature.layout.fields)
    assertEquals(signature.instructions, Some("Answer in one word."))
  }
