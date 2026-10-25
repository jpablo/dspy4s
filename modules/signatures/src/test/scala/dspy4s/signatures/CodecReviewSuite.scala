package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, FieldSpec}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

final case class CrDetail(note: String, weight: Int) derives Schema
final case class CrAnswer(answer: Option[Int], detail: CrDetail) derives Schema
final case class CrWithDefault(text: String, count: Int = 3) derives Schema
final case class CrNumbers(price: BigDecimal, rank: Short, level: Byte, big: BigInt, whole: Int, total: Long)
    derives Schema

/** The bugs of the code review of the codec group: each test failed before its fix. */
class CodecReviewSuite extends FunSuite:

  private def str(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  test("the augmented output encodes the base fields with the base schema") {
    val base    = Shape.canonicalDerived[CrAnswer]
    val shape   = OutputAugmentation.prependedStringShape(base, FieldSpec("reasoning"), "reasoning", "test", "sig")
    val value   = CrAnswer(Some(3), CrDetail("x", 1))
    val raw     = DynamicValues.mergeRecords(DynamicValues.record("reasoning" := "because"), base.encode(value))
    val decoded = shape.decode(raw).fold(error => fail(error.message), identity)
    val encoded = shape.encode(decoded)
    assertEquals(DynamicValues.recordGet(encoded, "reasoning"), Some(str("because")))
    assertEquals(DynamicValues.recordFilterKeys(encoded, _ != "reasoning"), base.encode(value))
  }

  test("a field with a case-class default can be absent from the record") {
    val shape = Shape.canonicalDerived[CrWithDefault]
    assertEquals(shape.decode(DynamicValues.record("text" := "x")), Right(CrWithDefault("x", 3)))
    assert(shape.fieldSpecs.find(_.name == "count").exists(_.defaultValue.isDefined))
  }

  test("the decode coerces the texts of the model to each numeric target") {
    val raw = DynamicValues.record(
      "price" -> str("19.99"),
      "rank"  -> str("2"),
      "level" -> str("1"),
      "big"   -> str("12345678901234567890"),
      "whole" -> DynamicValue.Primitive(PrimitiveValue.Double(3.0)),
      "total" -> DynamicValue.Primitive(PrimitiveValue.BigInt(BigInt(7)))
    )
    assertEquals(
      Shape.canonicalDerived[CrNumbers].decode(raw),
      Right(CrNumbers(BigDecimal("19.99"), 2, 1, BigInt("12345678901234567890"), 3, 7L))
    )
  }

  test("the decode widens and narrows the numbers of the model to each numeric target") {
    def int(n: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(n))
    val raw                       = DynamicValues.record(
      "price" -> int(5),
      "rank"  -> int(2),
      "level" -> int(1),
      "big"   -> DynamicValue.Primitive(PrimitiveValue.Long(9L)),
      "whole" -> DynamicValue.Primitive(PrimitiveValue.BigDecimal(BigDecimal(3))),
      "total" -> int(4)
    )
    assertEquals(Shape.canonicalDerived[CrNumbers].decode(raw), Right(CrNumbers(BigDecimal(5), 2, 1, BigInt(9), 3, 4L)))
  }

  test("an appended input field cannot have the name of a base field") {
    val base = Shape.canonicalDerived[CrDetail]
    val _    = intercept[IllegalArgumentException](InputAugmentation.appendedStringInput(base, FieldSpec("note"), "test"))
  }
