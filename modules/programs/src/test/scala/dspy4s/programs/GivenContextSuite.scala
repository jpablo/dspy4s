package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.InterpreterStartEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.runtime.LocalPythonInterpreter
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/** The given context wins (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS G-50). A caller can give a context that is
  * not installed on the thread. Each entry point then installs that context for the duration of the call, so the
  * callbacks, the parallel workers, and the other parts that read the installed context see the same settings as the
  * program. In these tests, the installed context has no language model and no callback.
  */
class GivenContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the same reply for each call. */
  private final class FixedLm(reply: String, override val id: String = "fixed") extends LanguageModel:
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  /** Records each event. The workers of a parallel run call it from more than one thread. */
  private final class Recorder extends CallbackHandler:
    private val buffer                                                     = ArrayBuffer.empty[CallbackEvent]
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      val _ = buffer.synchronized(buffer += event)
    def events: Vector[CallbackEvent] = buffer.synchronized(buffer.toVector)
    def moduleStarts: Vector[String]  = events.collect { case start: ModuleStartEvent => start.moduleName }

  private val answerReply = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"

  private val signature = SignatureDsl.parse("question -> answer").toOption.get

  private def question: DynamicValue.Record = rec("question" := "Capital of France?")

  /** A context with the language model `lm`, the chat adapter, and the callback `recorder`. */
  private def givenContext(lm: LanguageModel, recorder: Recorder): RuntimeContext =
    RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()), callbacks = Vector(recorder))

  private lazy val pythonAvailable: Boolean =
    try
      val process = new ProcessBuilder("python3", "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)")
        .start()
      process.waitFor() == 0
    catch case NonFatal(_) => false

  // ── A program call ──────────────────────────────────────────────────────────────────────────────────────────

  test("a program call sends its events to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = givenContext(FixedLm(answerReply), recorder)

    val result = DynamicPredict(signature)(ProgramCall(question))

    assertEquals(result.map(prediction => lookupString(prediction.output, "answer")), Right("Paris"))
    assertEquals(recorder.moduleStarts, Vector("predict"))
    assertEquals(recorder.events.collect { case start: LmStartEvent => start.modelId }, Vector("fixed"))
  }

  test("a program that reads the installed context runs with the language model of the given context") {
    // BestOfN runs each attempt in a copy of the installed context.
    val bestOfN = BestOfN[DynamicPredict, DynamicValue.Record, DynamicValue.Record](
      DynamicPredict(signature),
      n = AttemptCount(2),
      rewardFn = (_, _) => 1.0,
      threshold = 1.0
    )
    given RuntimeContext = givenContext(FixedLm(answerReply), Recorder())

    val result = bestOfN(question)

    assertEquals(result.map(prediction => lookupString(prediction.output, "answer")), Right("Paris"))
  }

  test("the trace and the history of a program call reach the installed context of the caller") {
    given RuntimeContext = givenContext(FixedLm(answerReply), Recorder())

    val result = DynamicPredict(signature)(ProgramCall(question))

    assert(result.isRight, result.toString)
    assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector("predict"))
    assertEquals(RuntimeEnvironment.current.history.map(_.component), Vector("predict"))
    // The call does not change the settings of the installed context.
    assertEquals(RuntimeEnvironment.current.lm, None)
    assertEquals(RuntimeEnvironment.current.callbacks, Vector.empty)
  }

  test("an async program call sends its events to the callbacks of the given context") {
    val recorder           = Recorder()
    given RuntimeContext   = givenContext(FixedLm(answerReply), recorder)
    given ExecutionContext = ExecutionContext.global

    val result = Await.result(DynamicPredict(signature).applyAsync(ProgramCall(question)), 10.seconds)

    assertEquals(result.map(prediction => lookupString(prediction.output, "answer")), Right("Paris"))
    assertEquals(recorder.moduleStarts, Vector("predict"))
  }

  test("ProgramRunner.run sends the events of the program to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = givenContext(FixedLm(answerReply), recorder)

    val result = ProgramRunner[DynamicPredict].run(DynamicPredict(signature), question)

    assertEquals(result.map(raw => lookupString(raw.values, "answer")), Right("Paris"))
    assertEquals(recorder.moduleStarts, Vector("predict"))
  }

  // ── Parallel work ───────────────────────────────────────────────────────────────────────────────────────────

  test("Parallel.run sends the events of each worker to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = givenContext(FixedLm(answerReply), recorder)
    val program          = DynamicPredict(signature)

    val result = Parallel(numThreads = Some(ThreadCount(2))).run(Vector.fill(3)(program -> ProgramCall(question)))

    assertEquals(
      result.map(_.results.flatten.map(raw => lookupString(raw.values, "answer"))),
      Right(Vector.fill(3)("Paris"))
    )
    assertEquals(recorder.moduleStarts, Vector.fill(3)("predict"))
  }

  test("a worker of the ParallelExecutor reads the given context, not the installed one") {
    val installedLm = FixedLm(answerReply, id = "installed")
    val givenLm     = FixedLm(answerReply, id = "given")
    val context     = RuntimeContext(lm = Some(givenLm))

    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(installedLm))) {
      ParallelExecutor(numThreads = ThreadCount(2)).execute(
        task = (_: Int) => RuntimeEnvironment.current.lm.exists(_ eq givenLm),
        data = Vector(1, 2, 3)
      )(using context)
    }

    assertEquals(result.map(_.results.flatten), Right(Vector(true, true, true)))
  }

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("ToolExecutor.run sends the tool scope to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = RuntimeContext(callbacks = Vector(recorder))
    val tool             = ToolFunction("lookup")(_ => Right(DynamicValues.fromAny("found")))

    val result = ToolExecutor.run(tool, DynamicValue.Record.empty)

    assertEquals(result, Right(DynamicValues.fromAny("found")))
    assertEquals(
      recorder.events.collect {
        case start: ToolStartEvent => s"start ${start.toolName}"
        case end: ToolEndEvent     => s"end ${end.toolName}"
      },
      Vector("start lookup", "end lookup")
    )
  }

  test("the interpreter of RLM sends its scopes and its tool calls to the callbacks of the given context") {
    assume(pythonAvailable, "python3 3.10+ not installed")
    val recorder = Recorder()
    val action   = "[[ ## reasoning ## ]]\nCall the tool.\n\n[[ ## code ## ]]\n```python\nSUBMIT(answer=lookup())\n```"
    val rlm      = RLM(
      baseSignature = Signature.fromString("context, query -> answer"),
      maxIterations = IterationLimit(3),
      tools = Vector(ToolFunction("lookup")(_ => Right(DynamicValues.fromAny("found")))),
      interpreterFactory = Some(LocalPythonInterpreter.factory())
    )
    given RuntimeContext = givenContext(FixedLm(action), recorder)

    val result = rlm((context = "hello", query = "What does the tool give?"))

    assertEquals(result.map(_.output.answer), Right("found"))
    val events = recorder.events
    assertEquals(events.collect { case start: ModuleStartEvent => start.moduleName }.headOption, Some("rlm"))
    assert(events.exists { case _: InterpreterStartEvent => true; case _ => false }, events.toString)
    assertEquals(events.collect { case start: ToolStartEvent => start.toolName }, Vector("lookup"))
  }
