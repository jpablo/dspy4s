package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, LmUsage, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.UsageTracking
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite

/** One sink for the LM usage, seen from a program: the user tracker and the result of the call get the same total, also
  * for a model with no managed wrapper. The test failed before the change.
  */
class ProgramUsageSinkSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  /** A model with no managed wrapper that gives a ChatAdapter reply with the answer `Paris`. */
  private final class Plain extends LanguageModel:
    override val id: String   = "plain"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"
      Right(LmResponse(outputs = Vector(LmOutput(text)), usage = Some(usage)))

  test("Predict with a model with no managed wrapper: the user tracker has the total of the result") {
    val model = Plain()
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
      given RuntimeContext      = RuntimeEnvironment.current
      val (prediction, tracked) = UsageTracking.withNewTracker { tracker =>
        (Predict(Signature.fromString("question -> answer"))((question = "capital?")), tracker.totalUsage)
      }
      val result = prediction.fold(error => fail(error.message), identity)
      assertEquals(result.raw.lmUsage, Some(usage))
      assertEquals(tracked.values.toVector, Vector(usage), tracked.toString)
    }
  }
