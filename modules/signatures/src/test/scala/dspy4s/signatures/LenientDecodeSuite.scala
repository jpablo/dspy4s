package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues}
import munit.FunSuite
import zio.blocks.schema.Schema

final case class LdValues(whole: Int, total: Long, flag: Boolean, price: Double) derives Schema

/** The typed decode has the one rule of `LenientValues` for a number text and a bool text. Each test failed before the
  * change.
  */
class LenientDecodeSuite extends FunSuite:

  private val shape = Shape.canonicalDerived[LdValues]

  test("the typed decode reads the number and bool texts of the one rule") {
    val raw = DynamicValues.record("whole" := "3.0", "total" := "1e3", "flag" := "yes", "price" := "1_000")
    assertEquals(shape.decode(raw), Right(LdValues(3, 1000L, true, 1000.0)))
  }

  test("the typed decode does not read a Java-only number text") {
    val raw = DynamicValues.record("whole" := "1", "total" := "2", "flag" := "true", "price" := "3d")
    assert(shape.decode(raw).isLeft, shape.decode(raw).toString)
  }
