package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
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
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.optimization.OptimizableMetadata
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger

/** Two leaves that run one after the other. Top-level so `OptimizableStructure.derived` sees its Mirror field labels.
  */
final case class TwinPipeline(first: DynamicPredict, second: DynamicPredict)

object TwinPipeline:
  given ProgramRunner[TwinPipeline] with
    def run(program: TwinPipeline, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.first(call).flatMap(_ => program.second(call)).map(_.raw)

/** A leaf type whose module records no predictor key. It does not call the LM, and its answer is always wrong. */
final case class KeylessLeaf(layout: SignatureLayout) extends DynamicModule:
  override val moduleName: String = "keyless"
  override protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    Right(RawPrediction(DynamicValues.record("answer" := "WRONG")))

object KeylessLeaf:
  given OptimizableLeaf[KeylessLeaf] with
    def get(program: KeylessLeaf): OptimizableParameters    = OptimizableParameters(program.layout.instructions)
    def metadata(program: KeylessLeaf): OptimizableMetadata =
      OptimizableMetadata.from(program.layout, program.moduleName)
    def set(program: KeylessLeaf, updated: OptimizableParameters): KeylessLeaf =
      program.copy(layout = program.layout.withInstructions(updated.instructions))

/** The two checks of decision D2 (UPGRADE-3.2.1-to-3.4.0) at the GEPA boundary: two leaves with the same predictor key
  * are an error before any run, and a leaf type that records no key is an error, not a leaf to skip.
  */
class GepaPredictorKeySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Counts its calls. The answer is always wrong, so each minibatch gives the reflection a reason to run. */
  private final class CountingLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = new AtomicInteger(0)
    override val id: String                                                                    = "counting"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nWRONG\n\n[[ ## completed ## ]]"))))

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

  private val qa: SignatureLayout = SignatureLayout.parse("question -> answer").toOption.get

  private def predict(instruction: String, name: Option[String] = None): DynamicPredict =
    DynamicPredict(layout = qa.withInstructions(Some(instruction)), name = name)

  /** The instructions differ, but the module name and the signature structure are the same. */
  private val twins = TwinPipeline(first = predict("Answer."), second = predict("Answer again."))

  private val keyless = KeylessLeaf(qa.withInstructions(Some("Answer.")))

  private val dataset: Vector[Example] = (1 to 2).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital of France ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  private val config = GepaConfig(
    budget = GepaBudget.MetricCalls(MetricCallCount(20)),
    reflectionMinibatchSize = MinibatchSize(2),
    seed = 1L
  )

  private def withTaskLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def assertTwinError(result: Either[DspyError, ?]): Unit =
    result match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'first'") && error.message.contains("'second'"), error.message)
      case other => fail(s"expected a ValidationError for the twin leaves, got $other")

  private def assertMissingKeyError(result: Either[DspyError, ?]): Unit =
    result match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'keyless'") && error.message.contains("predictor key"), error.message)
      case other => fail(s"expected a ValidationError for the leaf with no key, got $other")

  test("Gepa.compile rejects two leaves with the same key before any LM call") {
    val taskLm       = new CountingLm
    val reflectionLm = new CountingLm
    val result       = withTaskLm(taskLm) {
      new Gepa[TwinPipeline](metric, reflectionLm, config).compile(twins, trainset = dataset, valset = dataset)
    }
    assertTwinError(result)
    assertEquals(taskLm.calls.get(), 0)
    assertEquals(reflectionLm.calls.get(), 0)
  }

  test("GepaEngine.optimize rejects two leaves with the same key before any LM call") {
    val taskLm       = new CountingLm
    val reflectionLm = new CountingLm
    val result       = withTaskLm(taskLm) {
      new GepaEngine(new GepaAdapter(twins, metric), reflectionLm, config)
        .optimize(Candidate.seed(twins), trainset = dataset, valset = dataset)
    }
    assertTwinError(result)
    assertEquals(taskLm.calls.get(), 0)
    assertEquals(reflectionLm.calls.get(), 0)
  }

  test("makeReflectiveDataset rejects two leaves with the same key") {
    withTaskLm(new CountingLm) {
      val adapter = new GepaAdapter(twins, metric)
      val seed    = Candidate.seed(twins)
      val batch   = adapter.evaluate(dataset, seed, captureTraces = true)
      assertTwinError(adapter.makeReflectiveDataset(seed, batch, Vector(OptimizableId(0), OptimizableId(1))))
    }
  }

  test("a name for each leaf makes the same program valid") {
    val named = TwinPipeline(
      first = predict("Answer.", name = Some("first_pass")),
      second = predict("Answer again.", name = Some("second_pass"))
    )
    val result = withTaskLm(new CountingLm) {
      new Gepa[TwinPipeline](metric, new CountingLm, config).compile(named, trainset = dataset, valset = dataset)
    }
    assert(result.isRight, result.left.toOption.map(_.message).getOrElse(""))
  }

  test("makeReflectiveDataset rejects a leaf type that records no key") {
    withTaskLm(new CountingLm) {
      val adapter = new GepaAdapter(keyless, metric)
      val seed    = Candidate.seed(keyless)
      val batch   = adapter.evaluate(dataset, seed, captureTraces = true)
      assertMissingKeyError(adapter.makeReflectiveDataset(seed, batch, Vector(OptimizableId(0))))
    }
  }

  test("Gepa.compile returns the error of a leaf type that records no key, and does not ask the reflection LM") {
    val reflectionLm = new CountingLm
    val result       = withTaskLm(new CountingLm) {
      new Gepa[KeylessLeaf](metric, reflectionLm, config).compile(keyless, trainset = dataset, valset = dataset)
    }
    assertMissingKeyError(result)
    assertEquals(reflectionLm.calls.get(), 0)
  }
