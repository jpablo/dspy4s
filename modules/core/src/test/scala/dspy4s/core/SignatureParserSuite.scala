package dspy4s.core

import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.signatures.SignatureParser
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

class SignatureParserSuite extends FunSuite:
  test("parse a simple signature without field annotations") {
    val parser = SignatureParser
    val parsed = parser.parse("question -> answer")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    assertEquals(signature.inputFields.map(_.name), Vector("question"))
    assertEquals(signature.outputFields.map(_.name), Vector("answer"))
  }

  test("parse signature") {
    val parser = SignatureParser
    val parsed = parser.parse("question: str, top_k: int -> answer: string, score: double")

    assert(parsed.isRight)
    val signature   = parsed.toOption.get
    val inputTypes  = signature.inputFields.map(_.typeRef.repr)
    val outputTypes = signature.outputFields.map(_.typeRef.repr)

    assertEquals(inputTypes, Vector("string", "int"))
    assertEquals(outputTypes, Vector("string", "double"))
  }

  test("invalid signature with multiple arrows fails") {
    val parser = SignatureParser
    val parsed = parser.parse("a -> b -> c")

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("input augmentation preserves cohort ordering") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val updated   = signature.appendInput(FieldSpec(name = "context"))

    assertEquals(updated.inputFields.map(_.name), Vector("question", "context"))
    assertEquals(updated.outputFields.map(_.name), Vector("answer"))
  }

  test("structural equality distinguishes input and output cohorts") {
    val inputThenOutput = SignatureLayout.create(
      name = "First",
      inputFields = Vector(FieldSpec("a")),
      outputFields = Vector(FieldSpec("b"))
    ).toOption.get
    val bothInputs = SignatureLayout.create(
      name = "Second",
      inputFields = Vector(FieldSpec("a"), FieldSpec("b")),
      outputFields = Vector.empty
    ).toOption.get

    assertEquals(inputThenOutput.fields, bothInputs.fields)
    assert(!inputThenOutput.equalsByStructure(bothInputs))
  }

  test("an arrow inside a quoted Literal member is part of the type") {
    val layout = SignatureParser.parse("step: Literal['a->b', 'c'] -> result")
    assertEquals(layout.map(_.inputFields.flatMap(_.enumValues)), Right(Vector("a->b", "c")))
  }

  test("a bracket or a comma inside a quoted Literal member is part of the member") {
    val layout = SignatureParser.parse("step: Literal['[', 'x, y'], note -> result")
    assertEquals(layout.map(_.inputFields.map(_.name)), Right(Vector("step", "note")))
    assertEquals(layout.map(_.inputFields.flatMap(_.enumValues)), Right(Vector("[", "x, y")))
  }

  test("a text with no arrow or with two arrows outside quotes and brackets gives the arrow error") {
    for text <- Vector("question, answer", "a -> b -> c", "step: Literal['a->b'] -> b -> c") do
      SignatureParser.parse(text) match
        case Left(error)  => assert(error.message.contains("Must contain exactly one '->'"), error.message)
        case Right(other) => fail(s"expected a Left for '$text', got $other")
  }
