package dspy4s.core

import dspy4s.core.contracts.{FieldSpec, SignatureLayout, ValidationError}
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class SignatureLayoutStateCodecSuite extends FunSuite:
  test("signature dumpState and fromState roundtrip") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val state     = signature.dumpState
    val rebuilt   = SignatureLayout.fromState(state)

    assert(rebuilt.isRight)
    assert(signature.equalsByStructure(rebuilt.toOption.get))
  }

  test("signature dumpJson and fromJson roundtrip through clean JSON") {
    val signature = SignatureDsl.parse("question: str -> answer: string").toOption.get
    val json      = signature.dumpJson

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
    val fromJson  = SignatureLayout.fromJson(signature.dumpJson)

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
