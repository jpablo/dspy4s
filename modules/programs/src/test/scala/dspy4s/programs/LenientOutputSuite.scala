package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.{Predict, Refine}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.util.concurrent.atomic.AtomicInteger

/** The lenient output rule and the wrapping adapter of `Refine`, seen from a program. Each test failed before the
  * change.
  */
class LenientOutputSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model with one reply, which counts its calls. */
  private final class Reply(text: String) extends LanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val id: String   = "reply"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text))))

  test("a required int with a currency symbol needs no second call of the model") {
    val model = Reply("[[ ## count ## ]]\n$1,200\n\n[[ ## completed ## ]]")
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val prediction       = Predict(Signature.fromString("question -> count: int"))((question = "how many?"))
      val result           = prediction.fold(error => fail(error.message), identity)
      assertEquals(
        DynamicValues.recordGet(result.raw.values, "count"),
        Some(DynamicValue.Primitive(PrimitiveValue.Int(1200)))
      )
    }
    assertEquals(model.calls.get(), 1)
  }

  test("the hint adapter of Refine keeps the stream state of its base adapter") {
    given RuntimeContext = RuntimeContext()
    val layout           = Signature.fromString("question -> answer").layout
    val state            = Refine.HintInjectingAdapter(ChatAdapter(), Map.empty).streamingState(layout)
      .getOrElse(fail("the hint adapter has no stream state, so a stream has no field names"))
    val chunks = state.receive("[[ ## answer ## ]]\nhi\n[[ ## completed ## ]]") ++ state.finish()
    assertEquals(chunks.map(_.fieldName).distinct, Vector("answer"))
  }
