package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CompileStartEvent
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** The `compile` of an optimizer installs the context that it gets (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS
  * G-50). The given context wins for each field that it sets, and each other field comes from the installed context. In
  * the first two tests, the installed context has no language model and no callback.
  */
class GivenContextCompileSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the answer `answer` for each call. */
  private final class FixedLm(answer: String = "a1", override val id: String = "fixed") extends LanguageModel:
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  private final class Recorder extends CallbackHandler:
    private val buffer                                                     = ArrayBuffer.empty[CallbackEvent]
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      val _ = buffer.synchronized(buffer += event)
    def events: Vector[CallbackEvent] = buffer.synchronized(buffer.toVector)

  /** Gives the text "a1" for each call, for [[PassthroughAdapter]]. */
  private object PlainLm extends LanguageModel:
    override val id: String                                                                    = "plain"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "a1"))))

  /** Gives the text of the model as the value of each output field. Records the output fields of each `format` call. */
  private final class PassthroughAdapter extends Adapter:
    override val name: String                                                                                    = "passthrough"
    private val formatted                                                                                        = ArrayBuffer.empty[Vector[String]]
    def outputs: Vector[Vector[String]]                                                                          = formatted.synchronized(formatted.toVector)
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val _ = formatted.synchronized(formatted += invocation.layout.outputFields.map(_.name))
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("question")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec(layout.outputFields.map(_.name := output.text)*)))

  /** An optimizer that records the adapter of the context of its `compile` body, and runs the student one time. */
  private final class ProbeOptimizer extends Teleprompter[DynamicPredict]:
    override val name: String           = "probe"
    var bodyAdapter: Option[AdapterRef] = None
    override def compile(
        student : DynamicPredict,
        trainset: Vector[Example],
        teacher : Option[DynamicPredict]  = None,
        valset  : Option[Vector[Example]] = None
    )(using RuntimeContext): Either[DspyError, OptimizationReport[DynamicPredict]] =
      Teleprompter.withCompileCallbacks(this, trainset, valset)(probe(student))

    private def probe(student: DynamicPredict)(using
        context: RuntimeContext
    ): Either[DspyError, OptimizationReport[DynamicPredict]] =
      bodyAdapter = context.adapter
      student(ProgramCall(rec("question" := "q1"))).map(_ => OptimizationReport(student, Vector.empty))

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  private val trainset = (1 to 3).toVector.map(i =>
    Example(rec("question" := s"q$i", "answer" := "a1"), inputKeys = Set("question"))
  )

  test("compile sends its compile scope to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = RuntimeContext(callbacks = Some(Vector(recorder)))
    val optimizer        = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = DemoCount(2), seed = 1L))

    val result = optimizer.compile(DynamicPredict(layout = signature), trainset)

    assert(result.isRight, result.toString)
    assertEquals(
      recorder.events.collect { case start: CompileStartEvent => start.optimizerName },
      Vector("LabeledFewShot")
    )
  }

  /** A BootstrapFewShot that keeps a demo only when the answer is "a1". */
  private def bootstrap: BootstrapFewShot[DynamicPredict] =
    new BootstrapFewShot[DynamicPredict](
      BootstrapFewShotConfig(
        metric = Some(new ExactMatch(answerField = "answer")),
        maxBootstrappedDemos = DemoCount(2),
        maxLabeledDemos = DemoCount(0),
        maxRounds = RoundCount(1)
      )
    )

  test("BootstrapFewShot runs the teacher with the language model of the given context") {
    given RuntimeContext = RuntimeContext(lm = Some(FixedLm()), adapter = Some(ChatAdapter()))

    val result = bootstrap.compile(DynamicPredict(layout = signature), trainset)

    val bootstrapped = result.map(_.bestProgram.demos.count(_.augmented))
    assertEquals(bootstrapped, Right(2))
  }

  test("in compile, the LM of the given context wins, and the unset fields come from the installed context") {
    val recorder = Recorder()
    // The given context sets only the language model.
    given RuntimeContext = RuntimeContext(lm = Some(FixedLm()))
    val installed        = RuntimeContext(
      lm = Some(FixedLm(answer = "wrong", id = "installed")),
      adapter = Some(ChatAdapter()),
      callbacks = Some(Vector(recorder))
    )

    val result = RuntimeEnvironment.withSettings(installed) {
      bootstrap.compile(DynamicPredict(layout = signature), trainset)
    }

    assertEquals(result.map(_.bestProgram.demos.count(_.augmented)), Right(2))
    assertEquals(
      recorder.events.collect { case start: CompileStartEvent => start.optimizerName }.headOption,
      Some("BootstrapFewShot")
    )
  }

  // ── The compile body gets the effective context ─────────────────────────────────────────────────────────────

  test("the compile body with a given model only gets the adapter of the installed context") {
    val installed        = PassthroughAdapter()
    val optimizer        = ProbeOptimizer()
    given RuntimeContext = RuntimeContext(lm = Some(PlainLm))

    val result = RuntimeEnvironment.withSettings(RuntimeContext(adapter = Some(installed))) {
      optimizer.compile(DynamicPredict(layout = signature), trainset)
    }

    assert(result.isRight, result.toString)
    assert(optimizer.bodyAdapter.exists(_ eq installed), optimizer.bodyAdapter.toString)
    assertEquals(installed.outputs, Vector(Vector("answer")))
  }

  test("COPRO with a given model only proposes instructions with the adapter of the installed context") {
    val installed        = PassthroughAdapter()
    given RuntimeContext = RuntimeContext(lm = Some(PlainLm))
    val optimizer        = new COPRO[DynamicPredict](
      COPROConfig(metric = new ExactMatch(answerField = "answer"), breadth = CoproBreadth(2), depth = RoundCount(1))
    )

    val result = RuntimeEnvironment.withSettings(RuntimeContext(adapter = Some(installed))) {
      optimizer.compile(DynamicPredict(layout = signature), trainset)
    }

    assert(result.isRight, result.toString)
    // The instruction generator ran with the installed adapter, so no call failed with "Missing 'adapter'".
    assert(installed.outputs.contains(Vector("proposed_instruction")), installed.outputs.toString)
  }

  test("the adapter of the given context wins over the installed adapter in the compile body") {
    val givenAdapter     = PassthroughAdapter()
    val installedAdapter = PassthroughAdapter()
    val optimizer        = ProbeOptimizer()
    given RuntimeContext = RuntimeContext(lm = Some(PlainLm), adapter = Some(givenAdapter))

    val result = RuntimeEnvironment.withSettings(RuntimeContext(adapter = Some(installedAdapter))) {
      optimizer.compile(DynamicPredict(layout = signature), trainset)
    }

    assert(result.isRight, result.toString)
    assert(optimizer.bodyAdapter.exists(_ eq givenAdapter), optimizer.bodyAdapter.toString)
    assertEquals(givenAdapter.outputs, Vector(Vector("answer")))
    assertEquals(installedAdapter.outputs, Vector.empty)
  }
