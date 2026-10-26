package dspy4s.programs

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** The adapter reads the capabilities of the model that the predictor calls, also when `Predict.withLm` pins a model
  * that is not the model of the context. The test failed before the fix.
  */
class PinnedModelAdapterSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model that records each request and gives a JSON reply with the answer `Paris`. */
  private final class Recording(override val id: String, schema: Boolean) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsResponseSchema: Boolean                                               = schema
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(LmOutput(text = """{"answer": "Paris"}"""))))

  test("a pinned model with response schema support gets the response format") {
    val pinned  = Recording("pinned", schema = true)
    val ambient = Recording("ambient", schema = false)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(ambient), adapter = Some(JSONAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = Predict(Signature.fromString("question -> answer")).withLm(pinned)((question = "capital?"))
      assert(result.isRight, result.toString)
    }
    assertEquals(ambient.requests.size, 0)
    val options = pinned.requests.headOption.map(_.options).getOrElse(fail("the pinned model got no request"))
    assert(DynamicValues.recordGet(options, "response_format").isDefined, options.toString)
  }
