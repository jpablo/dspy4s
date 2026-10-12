package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

/** PORT_GAPS G-46 (upstream c7ee4462a): `Evaluate` rejects an empty devset. An optimizer that scores its candidates
  * with `Evaluate` rejects an empty evaluation set before it calls the LM. It does not report a score of 0.0.
  */
class OptimizerEmptyEvalsetSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class CountingLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = new AtomicInteger(0)
    override val id: String                                                                    = "counting"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\na1\n\n[[ ## completed ## ]]"))))

  private val student: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val metric = new ExactMatch(answerField = "answer")

  private val examples: Vector[Example] = Vector("q1", "q2").map(q =>
    Example(DynamicValues.record("question" := q, "answer" := "a1"), inputKeys = Set("question"))
  )

  private def assertRejected(
      optimizer: Teleprompter[DynamicPredict],
      trainset : Vector[Example],
      valset   : Option[Vector[Example]]
  ): Unit =
    val lm = new CountingLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      optimizer.compile(student, trainset, valset = valset) match
        case Left(error: ValidationError) => assert(error.message.contains("empty"), error.message)
        case other                        => fail(s"expected a ValidationError, got $other")
    }
    assertEquals(lm.calls.get(), 0)

  private def copro     = new COPRO[DynamicPredict](COPROConfig(metric = metric, breadth = CoproBreadth(2)))
  private def miproV2   = new MIPROv2[DynamicPredict](MIPROv2Config(metric = metric, numTrials = TrialCount(2)))
  private def infer     = new InferRules[DynamicPredict](InferRulesConfig(metric = metric))
  private def randomSrc =
    new BootstrapFewShotWithRandomSearch[DynamicPredict](RandomSearchConfig(metric = metric))

  test("COPRO rejects an empty trainset when there is no valset") {
    assertRejected(copro, trainset = Vector.empty, valset = None)
  }

  test("COPRO rejects an empty valset") {
    assertRejected(copro, trainset = examples, valset = Some(Vector.empty))
  }

  test("MIPROv2 rejects an empty trainset when there is no valset") {
    assertRejected(miproV2, trainset = Vector.empty, valset = None)
  }

  test("MIPROv2 rejects an empty valset") {
    assertRejected(miproV2, trainset = examples, valset = Some(Vector.empty))
  }

  test("InferRules rejects an empty trainset when there is no valset") {
    assertRejected(infer, trainset = Vector.empty, valset = None)
  }

  test("InferRules rejects an empty valset") {
    assertRejected(infer, trainset = examples, valset = Some(Vector.empty))
  }

  test("BootstrapFewShotWithRandomSearch rejects an empty valset") {
    assertRejected(randomSrc, trainset = examples, valset = Some(Vector.empty))
  }
