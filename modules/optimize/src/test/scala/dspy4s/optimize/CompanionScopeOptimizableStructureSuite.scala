package dspy4s.optimize

import dspy4s.programs.optimization.{OptimizableParameters, OptimizableStructure}

import dspy4s.programs.strategies.ChainOfThought
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite

import CompanionScopeOptimizableStructureSuite.Agent

/** Regression for the HIGH-severity scope bug: the leaf `OptimizableLeaf[Predict]` / `OptimizableLeaf[ChainOfThought]`
  * instances (and the hand-written `OptimizableStructure` instances for the composite programs) USED to live in a
  * non-companion `object ProgramPredictors`, so they were only in implicit scope after an explicit
  * `import ProgramPredictors.given`.
  *
  * This suite DELIBERATELY does NOT import them: it only exercises companion-scope resolution. On the old code a user
  * composite `case class Agent(...)` with `given OptimizableStructure[Agent] = OptimizableStructure.derived` and no
  * such import would resolve each program field to ZERO predictors. With the instances moved to the typeclass
  * companions it is 2; the strict derivation boundary now also makes a future omission fail compilation instead of
  * silently falling back to `OptimizableStructure.empty`.
  */
class CompanionScopeOptimizableStructureSuite extends FunSuite:

  private val qaSignature = Signature.fromString("question -> answer")

  private def predictorsOf[P](@annotation.unused program: P)(using
      ps: OptimizableStructure[P]
  ): OptimizableStructure[P] = ps

  test("composite of programs resolves field predictors WITHOUT any import (was 0, now 2)") {
    val agent = Agent(
      planner = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps    = summon[OptimizableStructure[Agent]]
    val views = ps.inspect(agent)
    // Each program leaf is found in companion scope; strict derivation would reject a missing instance.
    assertEquals(views.size, 2)
    assertEquals(views(0).moduleName, "plan")
    assertEquals(views(1).moduleName, "reason")
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
      planner = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps = summon[OptimizableStructure[Agent]]
    assertEquals(ps.replace(agent, ps.read(agent)), agent)
  }

  test("DerivedOptimizableStructure.replace rejects an over-long update vector (LOW #4)") {
    val agent = Agent(
      planner = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps      = summon[OptimizableStructure[Agent]]
    val correct = ps.read(agent)          // arity 2
    val tooMany = correct :+ correct.head // arity 3
    val ex      = intercept[IllegalArgumentException](ps.replace(agent, tooMany))
    assert(ex.getMessage.contains("expected 2 updates, got 3"), ex.getMessage)
  }

  test("DerivedOptimizableStructure.replace rejects a too-short update vector (LOW #4)") {
    val agent = Agent(
      planner = Predict(qaSignature, name = Some("plan")),
      reasoner = ChainOfThought(qaSignature, name = Some("reason"))
    )
    val ps     = summon[OptimizableStructure[Agent]]
    val tooFew = Vector.empty[OptimizableParameters]
    intercept[IllegalArgumentException](ps.replace(agent, tooFew))
  }

object CompanionScopeOptimizableStructureSuite:

  // A USER composite of two programs. Crucially: `OptimizableStructure.derived` is the only given, and NO
  // `import ProgramPredictors.given` exists (that object no longer exists). The leaf instances must be found in
  // companion scope.
  final case class Agent(
      planner : Predict[(question: String), (answer: String)],
      reasoner: ChainOfThought[(question: String), (answer: String)]
  )

  object Agent:
    given OptimizableStructure[Agent] = OptimizableStructure.derived
