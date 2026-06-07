package dspy4s.optimize

import dspy4s.programs.ChainOfThought
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predict
import dspy4s.typed.Signature
import munit.FunSuite

import CompanionScopePredictorsSuite.Agent

/** Regression for the HIGH-severity scope bug: the leaf `Predictor[Predict]` / `Predictor[ChainOfThought]`
  * instances (and the hand-written `Predictors` instances for the composite typed programs) USED to live in a
  * non-companion `object ProgramPredictors`, so they were only in implicit scope after an explicit
  * `import ProgramPredictors.given`.
  *
  * This suite DELIBERATELY does NOT import them: it only exercises companion-scope resolution. On the old code a
  * user composite `case class Agent(...)` with `given Predictors[Agent] = Predictors.derived` and no such import
  * would resolve each typed-program field to ZERO predictors (`summonFieldInstance` falling through to
  * `Predictors.empty`), so `read(agent).size` would be 0. With the instances moved to the typeclass companions it
  * is 2. */
class CompanionScopePredictorsSuite extends FunSuite:

  private val qaSignature = Signature.fromString("question -> answer")

  private def predictorsOf[P](@annotation.unused program: P)(using ps: Predictors[P]): Predictors[P] = ps

  test("composite of typed programs resolves field predictors WITHOUT any import (was 0, now 2)") {
    val agent = Agent(
      planner  = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps   = summon[Predictors[Agent]]
    val read = ps.read(agent)
    // Pre-fix: each typed-program field fell back to Predictors.empty -> size 0. Post-fix: each leaf is in
    // companion scope -> size 2.
    assertEquals(read.size, 2)
    assertEquals(read(0).name, Some("plan"))
    assertEquals(read(1).name, Some("reason"))
  }

  test("standalone Predict resolves to the 1-element leaf WITHOUT any import") {
    val predict = Predict(qaSignature, name = Some("plan"))
    val ps      = predictorsOf(predict)
    assertEquals(ps.read(predict).size, 1)
  }

  test("standalone ChainOfThought resolves to the 1-element leaf WITHOUT any import") {
    val cot = ChainOfThought(qaSignature, name = Some("reason"))
    val ps  = predictorsOf(cot)
    assertEquals(ps.read(cot).size, 1)
  }

  test("composite round-trips: replace(p, read(p)) == p WITHOUT any import") {
    val agent = Agent(
      planner  = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps = summon[Predictors[Agent]]
    assertEquals(ps.replace(agent, ps.read(agent)), agent)
  }

  test("DerivedPredictors.replace rejects an over-long update vector (LOW #4)") {
    val agent = Agent(
      planner  = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps      = summon[Predictors[Agent]]
    val correct = ps.read(agent)              // arity 2
    val tooMany = correct :+ correct.head     // arity 3
    val ex = intercept[IllegalArgumentException](ps.replace(agent, tooMany))
    assert(ex.getMessage.contains("expected 2 updates, got 3"), ex.getMessage)
  }

  test("DerivedPredictors.replace rejects a too-short update vector (LOW #4)") {
    val agent = Agent(
      planner  = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps      = summon[Predictors[Agent]]
    val tooFew  = Vector.empty[DynamicPredict]
    intercept[IllegalArgumentException](ps.replace(agent, tooFew))
  }

object CompanionScopePredictorsSuite:

  // A USER composite of two typed programs. Crucially: `Predictors.derived` is the only given, and NO
  // `import ProgramPredictors.given` exists (that object no longer exists). The leaf instances must be found in
  // companion scope.
  final case class Agent(
      planner: Predict[(question: String), (answer: String)],
      reasoner: ChainOfThought[(question: String), (answer: String)]
  )

  object Agent:
    given Predictors[Agent] = Predictors.derived
