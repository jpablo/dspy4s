package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

class EnsembleSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  /** A program that answers the fixed question with `answer` — used to script ensemble votes. */
  private def fixedAnswer(answer: String): ScriptedPredictProgram =
    ScriptedPredictProgram(answers = Map("q" -> answer), layout = signature)

  private val call = ProgramCall(inputs = rec("question" := "q"))

  test("Ensemble majority-votes over programs: {A, A, B} -> A") {
    val programs = Vector(fixedAnswer("A"), fixedAnswer("A"), fixedAnswer("B"))
    val ensembled = Ensemble().compile(programs)
    given RuntimeContext = RuntimeEnvironment.current

    val result = ensembled.apply(call)
    assert(result.isRight, s"apply failed: ${result.left.toOption}")
    assertEquals(lookupString(result.toOption.get.values, "answer"), "A")
  }

  test("Ensemble with a single program returns that program's output") {
    val ensembled = Ensemble().compile(Vector(fixedAnswer("only")))
    given RuntimeContext = RuntimeEnvironment.current

    val result = ensembled.apply(call)
    assertEquals(lookupString(result.toOption.get.values, "answer"), "only")
  }

  test("Ensemble size-sampling is seeded and deterministic; sample respects size") {
    // 5 programs answering distinct values; size=3 selects a fixed seeded subset.
    val programs = Vector("A", "B", "C", "D", "E").map(fixedAnswer)
    val optimizer = Ensemble(size = Some(3), seed = 42L)
    given RuntimeContext = RuntimeEnvironment.current

    val first = lookupString(optimizer.compile(programs).apply(call).toOption.get.values, "answer")
    val second = lookupString(optimizer.compile(programs).apply(call).toOption.get.values, "answer")
    assertEquals(first, second, "same seed should pick the same sample deterministically")
  }

  test("Ensemble with empty program list fails rather than silently succeeding") {
    val ensembled = Ensemble().compile(Vector.empty[ScriptedPredictProgram])
    given RuntimeContext = RuntimeEnvironment.current

    assert(ensembled.apply(call).isLeft, "majority over zero outputs should be a Left")
  }
