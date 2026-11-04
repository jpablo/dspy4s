package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{Modifier, Schema}

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class AbIn(@Modifier.rename("the_question") question: String)
object AbIn:
  given Schema[AbIn] = Schema.derived

final case class AbOut(@Modifier.rename("full_name") name: String)
object AbOut:
  given Schema[AbOut] = Schema.derived

/** An optimizer reads the trace of a typed program with renamed fields: a demo has the wire names. */
class AnnotatedBootstrapSuite extends FunSuite:

  private object Model extends LanguageModel:
    override val id: String                                                                    = "scripted"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## full_name ## ]]\na\n\n[[ ## completed ## ]]"))))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  test("a metric reads a prediction by the wire name") {
    given RuntimeContext = RuntimeContext()
    val prediction       = RawPrediction(DynamicValues.record("ans" := "x"))
    val byCode           = new ExactMatch().score(Example(DynamicValues.record("answer" := "x")), prediction, Vector.empty)
    assert(byCode.left.exists(_.message.contains("answer")), byCode.toString)
    val byWire = new ExactMatch("ans").score(Example(DynamicValues.record("ans" := "x")), prediction, Vector.empty)
    assertEquals(byWire, Right(1.0))
  }

  test("BootstrapFewShot makes a demo with the wire names, from an example with each name of a field") {
    val student = Predict(Signature.derived[AbIn, AbOut]("Named", "Give the name."), name = Some("ask"))
    Vector("the_question", "question").foreach { key =>
      val trainset  = Vector(Example(DynamicValues.record(key := "q", "full_name" := "a"), inputKeys = Set(key)))
      val optimizer = new BootstrapFewShot[Predict[AbIn, AbOut]](
        BootstrapFewShotConfig(
          maxBootstrappedDemos = DemoCount(1),
          maxLabeledDemos = DemoCount(0),
          maxRounds = RoundCount(1)
        )
      )
      val report = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(Model), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        optimizer.compile(student, trainset)
      }
      val demos = report.fold(error => fail(error.message), _.bestProgram.demos)
      assertEquals(demos.size, 1)
      assertEquals(DynamicValues.recordKeys(demos.head.values).toSet, Set("the_question", "full_name"))
    }
  }
