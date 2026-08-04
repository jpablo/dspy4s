package dspy4s.typed

import dspy4s.core.algebra.Monad
import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.RawPrediction
import munit.FunSuite

/** Executes the canonical writer-monad laws for [[Prediction]]. */
class PredictionAlgebraLawSuite extends FunSuite:

  private def prediction[A](output: A, stage: String): Prediction[A] =
    Prediction(output, RawPrediction(DynamicValues.record("stage" := stage)))

  test("Prediction's Monad instance preserves identity and composition") {
    val M              = Monad[Prediction]
    val initial        = prediction(3, "initial")
    val identityLaw    = M.identities[Int]
    val compositionLaw = M.composition((n: Int) => n + 1, (n: Int) => n * 2)

    assertEquals(identityLaw.lhs(initial), identityLaw.rhs(initial))
    assertEquals(compositionLaw.lhs(initial), compositionLaw.rhs(initial))
  }

  test("Prediction's flatMap obeys the Monad laws and accumulates raw evidence in execution order") {
    val M                          = Monad[Prediction]
    val initial                    = prediction(3, "initial")
    def f(n: Int): Prediction[Int] = prediction(n + 1, "f")
    def g(n: Int): Prediction[Int] = prediction(n * 2, "g")

    val leftIdentity  = M.identityLeft(3, f)
    val rightIdentity = M.identityRight(initial)
    val associativity = M.associativity(initial, f, g)

    assertEquals(leftIdentity.lhs, leftIdentity.rhs)
    assertEquals(rightIdentity.lhs, rightIdentity.rhs)
    assertEquals(associativity.lhs, associativity.rhs)
    assertEquals(associativity.lhs.output, 8)
    assertEquals(associativity.lhs.raw, initial.raw.followedBy(f(3).raw).followedBy(g(4).raw))
  }
