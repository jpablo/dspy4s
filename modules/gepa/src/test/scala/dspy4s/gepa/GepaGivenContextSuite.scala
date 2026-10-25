package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CompileStartEvent
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** The GEPA entry points install the context that they get (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS G-50). In
  * these tests, the installed context has no language model and no callback.
  */
class GepaGivenContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  /** Gives the answer "Paris" for each call. */
  private final class ParisLm extends LanguageModel:
    override val id: String                                                                    = "paris"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  /** Gives `text` for each call. */
  private final class ReflectionLm(text: String) extends LanguageModel:
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  private final class Recorder extends CallbackHandler:
    private val buffer                                                     = ArrayBuffer.empty[CallbackEvent]
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      val _ = buffer.synchronized(buffer += event)
    def events: Vector[CallbackEvent] = buffer.synchronized(buffer.toVector)

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val dataset: Vector[Example] = (1 to 2).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital of France ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  private def parisContext(callbacks: Option[Vector[CallbackHandler]] = None): RuntimeContext =
    RuntimeContext(lm = Some(ParisLm()), adapter = Some(ChatAdapter()), callbacks = callbacks)

  test("Gepa.compile sends its compile scope to the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext = parisContext(Some(Vector(recorder)))
    val config           = GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount(2)),
      reflectionMinibatchSize = MinibatchSize(2),
      seed = 1L
    )

    val result = new Gepa[DynamicPredict](metric, ReflectionLm(reply("not used")), config).compile(program, dataset)

    assertEquals(result.map(_.bestScore), Right(1.0))
    assertEquals(recorder.events.collect { case start: CompileStartEvent => start.optimizerName }, Vector("Gepa"))
  }

  test("GepaAdapter.evaluate runs each example with the language model of the given context") {
    given RuntimeContext = parisContext()

    val batch = new GepaAdapter(program, metric).evaluate(dataset, Candidate.seed(program), captureTraces = false)

    assertEquals(batch.scores, Vector(1.0, 1.0))
  }

  test("GepaAdapter.batchEvaluate runs each pair with the language model of the given context") {
    given RuntimeContext = parisContext()
    val seed             = Candidate.seed(program)

    val batches =
      new GepaAdapter(program, metric).batchEvaluate(Vector(seed -> dataset, seed -> dataset), captureTraces = false)

    assertEquals(batches.map(_.scores), Vector(Vector(1.0, 1.0), Vector(1.0, 1.0)))
  }
