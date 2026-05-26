package dspy4s.typed

import dspy4s.core.contracts.{FieldMetadata, FieldRole, TypeRef}
import munit.FunSuite
import zio.blocks.schema.Schema

// Top-level fixtures (Mirror derivation needs them outside the suite).
enum ZsSentiment:
  case sadness, joy, love
object ZsSentiment:
  given Schema[ZsSentiment] = Schema.derived

case class ZsClassifyOutput(sentiment: ZsSentiment, confidence: Double)
object ZsClassifyOutput:
  given Schema[ZsClassifyOutput] = Schema.derived

case class ZsCommentInput(comment: String, lang: String)
object ZsCommentInput:
  given Schema[ZsCommentInput] = Schema.derived

class ZioSchemaCodecSuite extends FunSuite:

  test("derivedFromZioSchema produces FieldSpecs with the right names, roles, typeRefs") {
    import ZsCommentInput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsCommentInput](FieldRole.Input)
    val specs = shape.fieldSpecs
    assertEquals(specs.map(_.name), Vector("comment", "lang"))
    assertEquals(specs.map(_.role), Vector(FieldRole.Input, FieldRole.Input))
    assertEquals(specs.map(_.typeRef), Vector(TypeRef.string, TypeRef.string))
  }

  test("Variant-typed fields carry EnumCases / EnumName metadata") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val sentiment = shape.fieldSpecs.find(_.name == "sentiment").get
    assertEquals(sentiment.typeRef, TypeRef.string)
    assertEquals(sentiment.metadata.get(FieldMetadata.EnumCases), Some("sadness,joy,love"))
    // The display name from TypeId; the exact string depends on zio-blocks naming.
    assert(sentiment.metadata.contains(FieldMetadata.EnumName))
  }

  test("encode produces a Map[String, Any] with enum flattened to its case name") {
    import ZsClassifyOutput.given
    val shape   = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val encoded = shape.encode(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95))
    assertEquals(encoded.keySet, Set("sentiment", "confidence"))
    assertEquals(encoded("sentiment"), "joy")  // Variant flattened to case-name string
    assertEquals(encoded("confidence"), 0.95)
  }

  test("decode accepts a raw Map[String, Any] with enum as a flat string") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val raw   = Map[String, Any]("sentiment" -> "joy", "confidence" -> 0.95)
    val out   = shape.decode(raw)
    assertEquals(out, Right(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95)))
  }

  test("decode coerces 'true' / '42' / '0.9' strings into the expected primitives") {
    case class Probe(flag: Boolean, count: Int, score: Double)
    object Probe { given Schema[Probe] = Schema.derived }
    import Probe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[Probe](FieldRole.Output)
    val raw   = Map[String, Any]("flag" -> "true", "count" -> "42", "score" -> "0.9")
    val out   = shape.decode(raw)
    assertEquals(out, Right(Probe(flag = true, count = 42, score = 0.9)))
  }

  test("encodeToDynamic / decodeFromDynamic round-trip skips the Map conversion") {
    import zio.blocks.schema.DynamicValue
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val value = ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.42)

    val dyn = shape.encodeToDynamic(value)
    // Native: the record holds a Variant for the enum (not a flat string).
    dyn match
      case rec: DynamicValue.Record =>
        val sentiment = rec.fields.toMap.get("sentiment")
        assert(sentiment.exists(_.isInstanceOf[DynamicValue.Variant]),
          s"expected Variant for sentiment, got: $sentiment")
      case other =>
        fail(s"expected Record, got: ${other.getClass.getSimpleName}")

    val decoded = shape.decodeFromDynamic(dyn)
    assertEquals(decoded, Right(value))
  }

  test("encode/decode round-trip for a case-class input") {
    import ZsCommentInput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsCommentInput](FieldRole.Input)
    val value = ZsCommentInput(comment = "Best movie ever!", lang = "en")
    val encoded = shape.encode(value)
    val decoded = shape.decode(encoded)
    assertEquals(decoded, Right(value))
  }
