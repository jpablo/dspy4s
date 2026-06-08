package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

/** G-12 P-a: under `RuntimeContext.captureFailureTraces`, a failed `Module.apply` records a failure trace entry
  * (carrying the raw model response from a parse error); otherwise a failure leaves the trace untouched. */
class FailureTraceSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Returns text with no field markers, so a two-output ChatAdapter signature fails to parse. */
  private final class BadLm extends LanguageModel:
    override val id: String   = "bad"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "Paris is the answer"))))

  // Two outputs → no single-output text fallback, so a marker-less response is a genuine parse failure.
  private val layout: SignatureLayout = SignatureLayout.parse("question -> answer, confidence").toOption.get
  private val call: ProgramCall = ProgramCall(inputs = DynamicValues.record("question" := "capital of France?"))

  test("captureFailureTraces records a failure entry with the raw response") {
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new BadLm), adapter = Some(ChatAdapter()), captureFailureTraces = true)
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = DynamicPredict(layout = layout).apply(call)
      assert(result.isLeft, s"expected a parse failure, got: $result")

      val trace = RuntimeEnvironment.current.trace
      assertEquals(trace.size, 1)
      assertEquals(trace.head.component, "predict")
      assert(trace.head.failure.isDefined, "expected a failure marker on the trace entry")
      assertEquals(
        DynamicValues.recordGet(trace.head.outputs, "raw_response").map(DynamicValues.renderText),
        Some("Paris is the answer")
      )
    }
  }

  test("without the flag, a failed call leaves no trace entry (unchanged behavior)") {
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new BadLm), adapter = Some(ChatAdapter()))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = DynamicPredict(layout = layout).apply(call)
      assert(result.isLeft)
      assertEquals(RuntimeEnvironment.current.trace.size, 0)
    }
  }
