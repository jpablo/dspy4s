package dspy4s.core

import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ValidationError
import dspy4s.core.signatures.DefaultSignatureParser
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

class SignatureParserSuite extends FunSuite:
  test("parse simple untyped signature") {
    val parser = new DefaultSignatureParser()
    val parsed = parser.parse("question -> answer")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    assertEquals(signature.inputFields.map(_.name), Vector("question"))
    assertEquals(signature.outputFields.map(_.name), Vector("answer"))
  }

  test("parse typed signature") {
    val parser = new DefaultSignatureParser()
    val parsed = parser.parse("question: str, top_k: int -> answer: string, score: double")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    val inputTypes = signature.inputFields.map(_.typeRef.repr)
    val outputTypes = signature.outputFields.map(_.typeRef.repr)

    assertEquals(inputTypes, Vector("string", "int"))
    assertEquals(outputTypes, Vector("string", "double"))
  }

  test("invalid signature with multiple arrows fails") {
    val parser = new DefaultSignatureParser()
    val parsed = parser.parse("a -> b -> c")

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.isInstanceOf[ValidationError])
  }

  test("insert preserves input output ordering") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val inserted = signature.insert(
      index = 0,
      field = FieldSpec(name = "context", role = FieldRole.Input)
    )

    assert(inserted.isRight)
    val updated = inserted.toOption.get
    assertEquals(updated.inputFields.map(_.name), Vector("context", "question"))
    assertEquals(updated.outputFields.map(_.name), Vector("answer"))
  }
