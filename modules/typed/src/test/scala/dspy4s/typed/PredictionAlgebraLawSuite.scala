package dspy4s.typed

import dspy4s.core.algebra.ScalaMonad
import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.RawPrediction
import munit.FunSuite

/** Executes the canonical writer-monad laws for [[Prediction]]. */
class PredictionAlgebraLawSuite extends FunSuite:

  private def prediction[A](output: A, stage: String): Prediction[A] =
    Prediction(output, RawPrediction(DynamicValues.record("stage" := stage)))

  test("Prediction's Monad instance preserves identity and composition") {
    val M              = ScalaMonad[Prediction]
    val initial        = prediction(3, "initial")
    val identityLaw    = M.endofunctor.identities[Int]
    val compositionLaw = M.endofunctor.composition((n: Int) => n + 1, (n: Int) => n * 2)

    assertEquals(identityLaw.lhs(initial), identityLaw.rhs(initial))
    assertEquals(compositionLaw.lhs(initial), compositionLaw.rhs(initial))
  }

  test("Prediction's flatMap obeys the Monad laws and accumulates raw evidence in execution order") {
    val M                          = ScalaMonad[Prediction]
    val initial                    = prediction(3, "initial")
    def f(n: Int): Prediction[Int] = prediction(n + 1, "f")
    def g(n: Int): Prediction[Int] = prediction(n * 2, "g")

    val leftIdentity  = M.bindIdentityLeft(3, f)
    val rightIdentity = M.bindIdentityRight(initial)
    val associativity = M.bindAssociativity(initial, f, g)

    assertEquals(leftIdentity.lhs, leftIdentity.rhs)
    assertEquals(rightIdentity.lhs, rightIdentity.rhs)
    assertEquals(associativity.lhs, associativity.rhs)
    assertEquals(associativity.lhs.output, 8)
    assertEquals(associativity.lhs.raw, initial.raw.followedBy(f(3).raw).followedBy(g(4).raw))
  }

  test("Prediction's unit and multiplication are natural and obey the categorical Monad laws") {
    val M       = ScalaMonad[Prediction]
    val initial = prediction(3, "initial")
    val nested  = prediction(prediction(prediction(3, "inner"), "middle"), "outer")

    val unitNaturality           = M.unit.naturality((n: Int) => n + 1)
    val multiplicationNaturality = M.multiplication.naturality((n: Int) => n + 1)
    val unitAtF                  = M.unitAtF
    val fMapUnit                 = M.fMapUnit
    val associativity            = M.associativity

    assertEquals(unitNaturality.lhs(3), unitNaturality.rhs(3))
    assertEquals(multiplicationNaturality.lhs(nested.output), multiplicationNaturality.rhs(nested.output))
    assertEquals(unitAtF.lhs.component[Int](initial), unitAtF.rhs.component[Int](initial))
    assertEquals(fMapUnit.lhs.component[Int](initial), fMapUnit.rhs.component[Int](initial))
    assertEquals(associativity.lhs.component[Int](nested), associativity.rhs.component[Int](nested))
  }
