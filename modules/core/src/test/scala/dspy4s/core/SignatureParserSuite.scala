package dspy4s.core

import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureOps.*
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

  test("input augmentation preserves cohort ordering") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val updated = signature.appendInput(FieldSpec(name = "context"))

    assertEquals(updated.inputFields.map(_.name), Vector("question", "context"))
    assertEquals(updated.outputFields.map(_.name), Vector("answer"))
  }

  test("signature dumpState and fromState roundtrip") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val state = signature.dumpState
    val rebuilt = SignatureLayout.fromState(state)

    assert(rebuilt.isRight)
    assert(signature.equalsByStructure(rebuilt.toOption.get))
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

  test("signature dumpJson and fromJson roundtrip through clean JSON") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val json = signature.dumpJson

    // Clean, natural JSON -- a top-level object with separate cohorts and no redundant role tags.
    assert(json.startsWith("{"), s"expected a JSON object, got: $json")
    assert(json.contains("\"inputFields\""), s"expected an inputFields array, got: $json")
    assert(json.contains("\"outputFields\""), s"expected an outputFields array, got: $json")
    assert(!json.contains("\"role\""), s"did not expect role tags, got: $json")

    val rebuilt = SignatureLayout.fromJson(json)
    assert(rebuilt.isRight, s"expected Right, got $rebuilt")
    assert(signature.equalsByStructure(rebuilt.toOption.get))
  }

  test("signature state and JSON preserve enum values") {
    val signature = SignatureLayout.create(
      name = "Sentiment",
      inputFields = Vector(FieldSpec(name = "text")),
      outputFields = Vector(
        FieldSpec(
          name = "label",
          enumValues = Vector("negative", "neutral", "positive")
        )
      )
    ).toOption.get

    val fromState = SignatureLayout.fromState(signature.dumpState)
    val fromJson = SignatureLayout.fromJson(signature.dumpJson)

    assertEquals(fromState.map(_.outputFields.head.enumValues), Right(Vector("negative", "neutral", "positive")))
    assertEquals(fromJson.map(_.outputFields.head.enumValues), Right(Vector("negative", "neutral", "positive")))
    assert(fromState.exists(signature.equalsByStructure))
    assert(fromJson.exists(signature.equalsByStructure))
  }

  test("signature fromState requires both field cohorts") {
    val state = DynamicValue.Record(Chunk.from(Seq(
      "name"         -> DynamicValue.Primitive(PrimitiveValue.String("BadSignature")),
      "instructions" -> DynamicValue.Primitive(PrimitiveValue.String("test")),
      "inputFields"  -> DynamicValue.Sequence(Chunk.empty)
    )))

    val rebuilt = SignatureLayout.fromState(state)
    assert(rebuilt.isLeft)
    assert(rebuilt.left.toOption.get.isInstanceOf[ValidationError])
  }
