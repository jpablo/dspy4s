package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, FieldSpec, NotFoundError}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

final case class RoundTripShapeLawValue(label: String, count: Int) derives Schema

/** Executes the law stated on [[RoundTripShape]]. Concrete shape suites retain responsibility for their format-specific
  * coercion and validation behavior; this suite checks the common domain/wire boundary contract.
  */
final class RoundTripShapeLawSuite extends FunSuite:

  private def assertDecodeEncode[A](shape: RoundTripShape[A], value: A): Unit =
    val law = shape.decodeEncode(value)
    assertEquals(law.lhs, law.rhs)

  test("a schema-backed product shape satisfies decode-after-encode") {
    assertDecodeEncode(Shape.derived[RoundTripShapeLawValue], RoundTripShapeLawValue("sample", 3))
  }

  test("MapShape is a validating Shape, not a total RoundTripShape") {
    val shape                        = Shape.MapShape(Vector(FieldSpec("required")))
    val invalid: DynamicValue.Record = DynamicValues.record("extra" := 7)

    shape.decode(invalid) match
      case Left(_: NotFoundError) => ()
      case other                  => fail(s"expected a missing-field error, got $other")
  }
