package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.ParallelExecutor
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The given context wins for each field that it sets, and each field that it leaves unset comes from the installed
  * context (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS G-50). Upstream `dspy.context(**kwargs)` also overrides only
  * the given settings and keeps the others.
  *
  * The class gives one context with a language model and an adapter. Each test captures that context before it installs
  * a callback handler or another model.
  */
class GivenContextFillSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the answer `answer` for each call. */
  private final class FixedLm(answer: String, override val id: String) extends LanguageModel:
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** Records each event. The workers of a parallel run call it from more than one thread. */
  private final class Recorder extends CallbackHandler:
    private val buffer                                                     = ArrayBuffer.empty[CallbackEvent]
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      val _ = buffer.synchronized(buffer += event)
    def events: Vector[CallbackEvent] = buffer.synchronized(buffer.toVector)
    def moduleStarts: Vector[String]  = events.collect { case start: ModuleStartEvent => start.moduleName }
    def lmStarts: Vector[String]      = events.collect { case start: LmStartEvent => start.modelId }

  private val givenLm     = FixedLm("Paris", "given")
  private val installedLm = FixedLm("Rome", "installed")

  private val classContext = RuntimeContext(lm = Some(givenLm), adapter = Some(ChatAdapter()))

  private given RuntimeContext = classContext

  private val signature = SignatureDsl.parse("question -> answer").toOption.get

  private def question: DynamicValue.Record = rec("question" := "Capital of France?")

  private def answerOf(result: Either[DspyError, Prediction[DynamicValue.Record]]): Either[DspyError, String] =
    result.map(prediction => lookupString(prediction.output, "answer"))

  // ── A field that the given context leaves unset comes from the installed context ─────────────────────────────

  test("a program call in a withCallbacks block sends its events to the handler of the block") {
    val recorder = Recorder()

    val result = RuntimeEnvironment.withCallbacks(Vector(recorder)) {
      DynamicPredict(signature)(ProgramCall(question))
    }

    assertEquals(answerOf(result), Right("Paris"))
    assertEquals(recorder.moduleStarts, Vector("predict"))
    assertEquals(recorder.lmStarts, Vector("given"))
  }

  test("a parallel worker in a withSettings block uses the LM of the given context and the handler of the block") {
    val recorder = Recorder()

    val result = RuntimeEnvironment.withSettings(RuntimeContext(callbacks = Some(Vector(recorder)))) {
      ParallelExecutor(numThreads = ThreadCount(2)).execute(
        task = (_: Int) =>
          val installed = RuntimeEnvironment.current
          installed.lm.exists(_ eq givenLm) && installed.callbacks.exists(_ eq recorder)
        ,
        data = Vector(1, 2, 3)
      )
    }

    assertEquals(result.map(_.results.flatten), Right(Vector(true, true, true)))
  }

  test("Parallel.run in a withCallbacks block sends the events of each worker to the handler of the block") {
    val recorder = Recorder()
    val program  = DynamicPredict(signature)

    val result = RuntimeEnvironment.withCallbacks(Vector(recorder)) {
      Parallel(numThreads = Some(ThreadCount(2))).run(Vector.fill(3)(program -> ProgramCall(question)))
    }

    assertEquals(
      result.map(_.results.flatten.map(raw => lookupString(raw.values, "answer"))),
      Right(Vector.fill(3)("Paris"))
    )
    assertEquals(recorder.moduleStarts, Vector.fill(3)("predict"))
    assertEquals(recorder.lmStarts, Vector.fill(3)("given"))
  }

  // ── A field that the given context sets wins over the installed context ──────────────────────────────────────

  test("the LM of the given context wins over the installed LM in a program call") {
    val recorder = Recorder()

    val result =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(installedLm), callbacks = Some(Vector(recorder)))) {
        DynamicPredict(signature)(ProgramCall(question))
      }

    assertEquals(answerOf(result), Right("Paris"))
    assertEquals(recorder.lmStarts, Vector("given"))
  }

  test("the LM of the given context wins over the installed LM in a parallel worker") {
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(installedLm))) {
      ParallelExecutor(numThreads = ThreadCount(2)).execute(
        task = (_: Int) => RuntimeEnvironment.current.lm.exists(_ eq givenLm),
        data = Vector(1, 2, 3)
      )
    }

    assertEquals(result.map(_.results.flatten), Right(Vector(true, true, true)))
  }

  test("a program that reads the installed context runs with the LM of the given context") {
    // BestOfN runs each attempt in a copy of the installed context.
    val bestOfN = BestOfN[DynamicPredict, DynamicValue.Record, DynamicValue.Record](
      DynamicPredict(signature),
      n = AttemptCount(2),
      rewardFn = (_, _) => 1.0,
      threshold = 1.0
    )

    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(installedLm))) {
      bestOfN(question)
    }

    assertEquals(answerOf(result), Right("Paris"))
  }
