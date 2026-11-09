package dspy4s.signatures

import dspy4s.core.contracts.PythonText
import munit.FunSuite
import zio.blocks.maybe.Maybe
import zio.blocks.schema.{DynamicValue, Schema}

final case class MaMaybes(text: Maybe[String], number: Maybe[Int], flag: Maybe[Boolean])
object MaMaybes:
  given Schema[MaMaybes] = Schema.derived

/** An absent `Maybe` decodes to `Maybe.absent`. Since zio-blocks 0.0.54, `Maybe.absent` is the `Absent` object and not
  * a null. But the binding of the `Absent` case of a `Maybe` schema still builds a null, and a null is a present
  * `Maybe`. These tests show that defect through the decode of dspy4s.
  */
class MaybeAbsentSuite extends FunSuite:

  private val absent = MaMaybes(Maybe.absent, Maybe.absent, Maybe.absent)

  private def record(json: String): DynamicValue.Record =
    PythonText.parseJson(json) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"expected a JSON object, got $other")

  test("an absent field and a null field decode to Maybe.absent, for a reference and for a primitive") {
    assertEquals(ZioSchemaCodec.decodeValue[MaMaybes](record("{}")), Right(absent))
    assertEquals(
      ZioSchemaCodec.decodeValue[MaMaybes](record("""{"text": null, "number": null, "flag": null}""")),
      Right(absent)
    )
  }

  test("the decode of the encode of Maybe.absent is Maybe.absent") {
    val shape = ZioSchemaCodec.derivedFromZioSchema[MaMaybes]
    assertEquals(shape.decode(shape.encode(absent)), Right(absent))
  }

  test("a decoded absent Maybe is empty, so its value is not read") {
    val decoded = ZioSchemaCodec.decodeValue[MaMaybes](record("{}")).toOption.getOrElse(fail("no decode"))
    assert(decoded.text.isEmpty, s"text is not empty: ${decoded.text}")
    assert(decoded.number.isEmpty, s"number is not empty: ${decoded.number}")
    assert(decoded.flag.isEmpty, s"flag is not empty: ${decoded.flag}")
  }

  test("a present value stays present") {
    val present = MaMaybes(Maybe.present("x"), Maybe.present(0), Maybe.present(false))
    assertEquals(
      ZioSchemaCodec.decodeValue[MaMaybes](record("""{"text": "x", "number": 0, "flag": false}""")),
      Right(present)
    )
  }
