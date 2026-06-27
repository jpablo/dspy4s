package dspy4s.typed

import dspy4s.typed.OutputAugmentation.{PrependField, WithField}
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldRole, FieldSpec, NotFoundError, ValidationError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import scala.NamedTuple
import munit.FunSuite

class OutputAugmentationSuite extends FunSuite:

  case class Answer(answer: String, score: Double)

  // Summon the pinned instance the way a program would (Aux[Name, T, O, WithField[O, Name, T]]).
  private inline def prepend[Name <: String & Singleton, T, O](value: T, base: O)(using
      p: PrependField.Aux[Name, T, O, WithField[O, Name, T]]
  ): Option[WithField[O, Name, T]] = p.prepend(value, base)

  test("named-tuple output without the field: prepends it") {
    type O = (answer: String, score: Double)
    val base: O = (answer = "Paris", score = 0.9)
    val result = prepend["reasoning", String, O]("because", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("because", "Paris", 0.9)))
  }

  test("named-tuple output that already declares the field: idempotent (kept unchanged)") {
    type O = (reasoning: String, answer: String)
    val base: O = (reasoning = "kept", answer = "Paris")
    val result = prepend["reasoning", String, O]("ignored", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("kept", "Paris")))
  }

  test("case-class output: normalized to a named tuple with the field prepended") {
    val result = prepend["reasoning", String, Answer]("because", Answer("Paris", 0.9))
    assertEquals(result.map(NamedTuple.toTuple), Some(("because", "Paris", 0.9)))
  }

  test("generic over field name and type (e.g. rationale)") {
    type O = (winner: Int)
    val base: O = (winner = 2)
    val result = prepend["rationale", String, O]("chain 2 wins", base)
    assertEquals(result.map(NamedTuple.toTuple), Some(("chain 2 wins", 2)))
  }

  test("fallback: a non-product / non-named-tuple output is unsupported (None)") {
    // No `Mirror.ProductOf[Int]` and not a named tuple -> only the low-priority fallback matches.
    val result = summon[PrependField["x", String, Int]].prepend("v", 5)
    assert(result.isEmpty)
  }

  // ── decodePrepended: the value-level decode shared by the composite programs ──────────────────────

  private def str(s: String): DynamicValue                            = DynamicValue.Primitive(PrimitiveValue.String(s))
  private def rec(pairs: (String, DynamicValue)*): DynamicValue.Record = DynamicValue.Record(Chunk.from(pairs))

  // A minimal named-tuple Shape that decodes `{ answer }` into `(answer: String)`.
  private def answerShape: Shape[(answer: String)] = new Shape[(answer: String)]:
    val fieldSpecs: Vector[FieldSpec] = Vector(FieldSpec("answer", FieldRole.Output))
    def encode(value: (answer: String)): DynamicValue.Record = rec("answer" -> str(value.answer))
    def decode(raw: DynamicValue.Record): Either[DspyError, (answer: String)] =
      DynamicValues.requireString(raw, "answer", "test").map(a => (answer = a))

  // A non-product Shape: its decoded output hits the PrependField fallback (None) inside decodePrepended.
  private def intShape: Shape[Int] = new Shape[Int]:
    val fieldSpecs: Vector[FieldSpec]                              = Vector.empty
    def encode(value: Int): DynamicValue.Record                   = rec()
    def decode(raw: DynamicValue.Record): Either[DspyError, Int]  = Right(0)

  test("decodePrepended: reads the field, decodes the base, prepends") {
    val r = OutputAugmentation.decodePrepended(
      rec("reasoning" -> str("because"), "answer" -> str("Paris")), answerShape, "reasoning", "test", "sig"
    )
    assertEquals(r.map(NamedTuple.toTuple), Right(("because", "Paris")): Either[DspyError, (String, String)])
  }

  test("decodePrepended: a missing field is a NotFoundError") {
    val r = OutputAugmentation.decodePrepended(rec("answer" -> str("Paris")), answerShape, "reasoning", "test", "sig")
    r match
      case Left(_: NotFoundError) => ()
      case other                  => fail(s"expected NotFoundError, got $other")
  }

  test("decodePrepended: a fieldless (non-product) output is a ValidationError") {
    val r = OutputAugmentation.decodePrepended(rec("reasoning" -> str("x")), intShape, "reasoning", "test", "sig")
    r match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected ValidationError, got $other")
  }
