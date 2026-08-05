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
    val parser = new SignatureParser()
    val parsed = parser.parse("question -> answer")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    assertEquals(signature.inputFields.map(_.name), Vector("question"))
    assertEquals(signature.outputFields.map(_.name), Vector("answer"))
  }

  test("parse signature") {
    val parser = new SignatureParser()
    val parsed = parser.parse("question: str, top_k: int -> answer: string, score: double")

    assert(parsed.isRight)
    val signature   = parsed.toOption.get
    val inputTypes  = signature.inputFields.map(_.typeRef.repr)
    val outputTypes = signature.outputFields.map(_.typeRef.repr)

    assertEquals(inputTypes, Vector("string", "int"))
    assertEquals(outputTypes, Vector("string", "double"))
  }

  test("invalid signature with multiple arrows fails") {
    val parser = new SignatureParser()
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
