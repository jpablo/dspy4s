package dspy4s.core

import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.signatures.SignatureParser
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class SignatureParserSuite extends FunSuite:
  test("parse simple untyped signature") {
    val parser = new SignatureParser()
    val parsed = parser.parse("question -> answer")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    assertEquals(signature.inputFields.map(_.name), Vector("question"))
    assertEquals(signature.outputFields.map(_.name), Vector("answer"))
  }

  test("parse typed signature") {
    val parser = new SignatureParser()
    val parsed = parser.parse("question: str, top_k: int -> answer: string, score: double")

    assert(parsed.isRight)
    val signature = parsed.toOption.get
    val inputTypes = signature.inputFields.map(_.typeRef.repr)
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

  test("prepend preserves input output ordering") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val updated = signature.prepend(FieldSpec(name = "context", role = FieldRole.Input))

    assertEquals(updated.inputFields.map(_.name), Vector("context", "question"))
    assertEquals(updated.outputFields.map(_.name), Vector("answer"))
  }

  test("signature dumpState and fromState roundtrip") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val state = signature.dumpState
    val rebuilt = SignatureLayout.fromState(state)

    assert(rebuilt.isRight)
    assert(signature.equalsByStructure(rebuilt.toOption.get))
  }

  test("signature dumpJson and fromJson roundtrip through clean JSON") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val json = signature.dumpJson

    // Clean, natural JSON -- a top-level object with a fields array, no ADT tags.
    assert(json.startsWith("{"), s"expected a JSON object, got: $json")
    assert(json.contains("\"fields\""), s"expected a fields array, got: $json")

    val rebuilt = SignatureLayout.fromJson(json)
    assert(rebuilt.isRight, s"expected Right, got $rebuilt")
    assert(signature.equalsByStructure(rebuilt.toOption.get))
  }

  test("signature fromState fails on invalid role") {
    val state = DynamicValue.Record(Chunk.from(Seq(
      "name"         -> DynamicValue.Primitive(PrimitiveValue.String("BadSignature")),
      "instructions" -> DynamicValue.Primitive(PrimitiveValue.String("test")),
      "fields" -> DynamicValue.Sequence(Chunk.from(Seq(
        DynamicValue.Record(Chunk.from(Seq(
          "name"    -> DynamicValue.Primitive(PrimitiveValue.String("question")),
          "role"    -> DynamicValue.Primitive(PrimitiveValue.String("invalid")),
          "typeRef" -> DynamicValue.Primitive(PrimitiveValue.String("string"))
        )))
      ))
    ))))

    val rebuilt = SignatureLayout.fromState(state)
    assert(rebuilt.isLeft)
    assert(rebuilt.left.toOption.get.isInstanceOf[ValidationError])
  }
