package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The workers of `Parallel` add to the trace of the caller. In upstream they share one trace list
  * (`dspy/utils/parallelizer.py`, `dspy/predict/predict.py`).
  */
class ParallelTraceSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private object AnswerLm extends LanguageModel:
    override val id: String                                                                    = "answer"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  private def predict(name: String): DynamicPredict =
    DynamicPredict(layout = SignatureDsl.parse("question -> answer").toOption.get, name = Some(name))

  private def task(name: String): (DynamicModule, ProgramCall[DynamicValue.Record]) =
    predict(name) -> ProgramCall(input = DynamicValues.record("question" := name))

  private def questions(context: RuntimeContext): Vector[String] =
    context.trace.flatMap(entry => DynamicValues.recordGet(entry.inputs, "question")).map(DynamicValues.renderText)

  test("the trace entries of each task reach the context of the caller, in task order") {
    val settings = RuntimeContext(
      lm = Some(AnswerLm),
      adapter = Some(ChatAdapter()),
      numThreads = Some(ThreadCount(2))
    )
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = Parallel().run(Vector("one", "two", "three", "four").map(task))
      assert(result.isRight, result.toString)
      assertEquals(questions(RuntimeEnvironment.current), Vector("one", "two", "three", "four"))
    }
  }
