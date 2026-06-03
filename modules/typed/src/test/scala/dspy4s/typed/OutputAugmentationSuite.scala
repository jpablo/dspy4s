package dspy4s.typed

import dspy4s.typed.OutputAugmentation.{PrependField, WithField}
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
