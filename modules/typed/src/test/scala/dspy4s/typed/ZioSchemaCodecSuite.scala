package dspy4s.typed

import dspy4s.core.contracts.{:=, DynamicValues, FieldRole, TypeRef}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

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

// A plain (parameterless-case) enum vs. an Option field, both of which are `Reflect.Variant` under the hood.
enum ZsKind:
  case alpha, beta
object ZsKind:
  given Schema[ZsKind] = Schema.derived

case class ZsMixed(kind: ZsKind, amount: Option[Double])
object ZsMixed:
  given Schema[ZsMixed] = Schema.derived

case class ZsOptionProbe(amount: Option[Double], note: String)
object ZsOptionProbe:
  given Schema[ZsOptionProbe] = Schema.derived

/** The spine is now `DynamicValue.Record` end-to-end -- there is no
  * `Map[String, Any]` intermediate -- so the historical tests that exercised
  * the `dynamicToAny` / `anyToDynamic` converters are gone (those helpers were
  * deleted). What remains exercises `fieldSpecsFromReflect`, `normalize`, and
  * the native `encode` / `decode` round-trip through `DynamicValue.Record`. */
class ZioSchemaCodecSuite extends FunSuite:

  test("fieldSpecsFromReflect produces FieldSpecs with the right names, roles, typeRefs") {
    import ZsCommentInput.given
    val specs = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsCommentInput]].reflect, FieldRole.Input)
    assertEquals(specs.map(_.name), Vector("comment", "lang"))
    assertEquals(specs.map(_.role), Vector(FieldRole.Input, FieldRole.Input))
    assertEquals(specs.map(_.typeRef), Vector(TypeRef.string, TypeRef.string))
  }

  test("enumValuesOf surfaces a plain enum's cases but not an Option's Some/None") {
    import ZsMixed.given
    val specs = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsMixed]].reflect, FieldRole.Output)
    assertEquals(specs.find(_.name == "kind").get.enumValues, Vector("alpha", "beta"))
    assertEquals(
      specs.find(_.name == "amount").get.enumValues,
      Vector.empty,
      "Option is a Reflect.Variant but not an enum; its Some/None cases must not leak as allowed values"
    )
  }

  test("normalize wraps a bare value into Some and Null into None for an Option field") {
    import ZsOptionProbe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsOptionProbe](FieldRole.Output)
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := 2399.0, "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
    // LM-shaped string coerces through the Some payload too.
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "2399.0", "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" -> DynamicValue.Null, "note" := "y"))),
      Right(ZsOptionProbe(None, "y"))
    )
  }

  test("normalize coerces currency- and comma-formatted numbers into Int/Double") {
    case class Money(amount: Double, count: Int)
    object Money { given Schema[Money] = Schema.derived }
    import Money.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[Money](FieldRole.Output)
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "$2,399.00", "count" := "1,234"))),
      Right(Money(amount = 2399.0, count = 1234)),
      "LM-shaped numbers carry currency symbols / thousands separators; they must coerce to the numeric type"
    )
  }

  test("normalize coerces a currency-formatted number inside an Option[Double]") {
    import ZsOptionProbe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsOptionProbe](FieldRole.Output)
    assertEquals(
      shape.decode(DynamicValues.recordFromEntries(Seq("amount" := "$2,399.00", "note" := "x"))),
      Right(ZsOptionProbe(Some(2399.0), "x"))
    )
  }

  test("Variant-typed fields surface as TypeRef.string at the wire boundary") {
    import ZsClassifyOutput.given
    val specs = ZioSchemaCodec.fieldSpecsFromReflect(summon[Schema[ZsClassifyOutput]].reflect, FieldRole.Output)
    val sentiment = specs.find(_.name == "sentiment").get
    assertEquals(sentiment.typeRef, TypeRef.string)
  }

  test("derivedFromZioSchema encode produces a DynamicValue.Record with each output field present") {
    import ZsClassifyOutput.given
    val shape   = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val encoded = shape.encode(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95))
    assertEquals(DynamicValues.recordKeys(encoded).toSet, Set("sentiment", "confidence"))
    // confidence is a primitive Double -- read it back through the helper.
    assertEquals(
      DynamicValues.recordGet(encoded, "confidence"),
      Some(DynamicValue.Primitive(PrimitiveValue.Double(0.95)))
    )
  }

  test("derivedFromZioSchema decode accepts a DynamicValue.Record with the enum as a flat string") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val raw   = DynamicValues.recordFromEntries(Seq("sentiment" := "joy", "confidence" := 0.95))
    val out   = shape.decode(raw)
    assertEquals(out, Right(ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.95)))
  }

  test("normalize coerces 'true' / '42' / '0.9' string primitives into the declared type") {
    case class Probe(flag: Boolean, count: Int, score: Double)
    object Probe { given Schema[Probe] = Schema.derived }
    import Probe.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[Probe](FieldRole.Output)
    val raw   = DynamicValues.recordFromEntries(Seq("flag" := "true", "count" := "42", "score" := "0.9"))
    val out   = shape.decode(raw)
    assertEquals(out, Right(Probe(flag = true, count = 42, score = 0.9)))
  }

  test("encode / decode round-trip via DynamicValue.Record (no Map intermediate)") {
    import ZsClassifyOutput.given
    val shape = ZioSchemaCodec.derivedFromZioSchema[ZsClassifyOutput](FieldRole.Output)
    val value = ZsClassifyOutput(sentiment = ZsSentiment.joy, confidence = 0.42)

    val encoded = shape.encode(value)
    // Native: the record holds a Variant for the enum (not a flat string).
    val sentiment = DynamicValues.recordGet(encoded, "sentiment")
    assert(sentiment.exists(_.isInstanceOf[DynamicValue.Variant]),
      s"expected Variant for sentiment, got: $sentiment")

    val decoded = shape.decode(encoded)
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
