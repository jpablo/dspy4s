package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.DynamicPredict
import dspy4s.programs.predictors.PredictorId
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

class GepaEvalCacheSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  /** Counts how many times the model is actually called, so we can prove cache hits skip the LM. */
  private final class CountingLm extends LanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val id: String   = "counting"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "always_one"
    override def feedback(
        example: Example,
        prediction: RawPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      Right(ScoreWithFeedback(1.0, "ok"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val batch: Vector[Example] = (1 to 2).toVector.map(i =>
    Example(values = DynamicValues.record("question" := s"Q$i", "answer" := "Paris"), inputKeys = Set("question"))
  )

  test("a repeated (candidate, example) evaluation is served from cache with zero actual evals and no LM calls") {
    val lm      = new CountingLm
    val adapter = new GepaAdapter[DynamicPredict](program, metric)
    val cache   = new GepaEvalCache[DynamicPredict](adapter)
    val candA   = Map(PredictorId(0) -> Some("Answer A."))
    val candB   = Map(PredictorId(0) -> Some("Answer B."))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current

      val (scores1, evals1) = cache.scores(candA, batch)
      assertEquals(scores1, Vector(1.0, 1.0))
      assertEquals(evals1, 2) // both examples actually evaluated
      assertEquals(lm.calls.get(), 2)

      // Identical (candidate, examples): fully cached -> no actual evals, no further LM calls.
      val (scores2, evals2) = cache.scores(candA, batch)
      assertEquals(scores2, Vector(1.0, 1.0))
      assertEquals(evals2, 0)
      assertEquals(lm.calls.get(), 2)

      // A different candidate is a cache miss again (keyed by candidate AND example).
      val (_, evals3) = cache.scores(candB, batch)
      assertEquals(evals3, 2)
      assertEquals(lm.calls.get(), 4)

      // Partial overlap: one already-seen example (candA, batch(0)) is free; the new one costs one eval.
      val newExample =
        Example(values = DynamicValues.record("question" := "Q3", "answer" := "Paris"), inputKeys = Set("question"))
      val (_, evals4) = cache.scores(candA, Vector(batch(0), newExample))
      assertEquals(evals4, 1)
      assertEquals(lm.calls.get(), 5)
    }
  }

  test("duplicate examples in one batch are evaluated once and returned in batch order") {
    val lm      = new CountingLm
    val adapter = new GepaAdapter[DynamicPredict](program, metric)
    val cache   = new GepaEvalCache[DynamicPredict](adapter)
    val cand    = Map(PredictorId(0) -> Some("Answer A."))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val (scores, evals)  = cache.scores(cand, Vector(batch.head, batch.head, batch.last, batch.head))

      assertEquals(scores, Vector(1.0, 1.0, 1.0, 1.0))
      assertEquals(evals, 2)
      assertEquals(lm.calls.get(), 2)
    }
  }

  test("examples with equal values but different input metadata do not alias") {
    val lm              = new CountingLm
    val adapter         = new GepaAdapter[DynamicPredict](program, metric)
    val cache           = new GepaEvalCache[DynamicPredict](adapter)
    val cand            = Map(PredictorId(0) -> Some("Answer A."))
    val original        = batch.head
    val differentInputs = original.copy(inputKeys = Set("answer"))
    val augmented       = original.copy(augmented = true)

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val (_, evals)       = cache.scores(cand, Vector(original, differentInputs, augmented))

      assertEquals(evals, 3)
      assertEquals(lm.calls.get(), 3)
    }
  }

  test("restored candidate scores warm the cache without LM calls") {
    val lm      = new CountingLm
    val adapter = new GepaAdapter[DynamicPredict](program, metric)
    val cache   = new GepaEvalCache[DynamicPredict](adapter)
    val cand    = Map(PredictorId(0) -> Some("Answer A."))

    assertEquals(cache.restore(cand, batch, Vector(0.25, 0.75)), Right(()))
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val (scores, evals)  = cache.scores(cand, batch)

      assertEquals(scores, Vector(0.25, 0.75))
      assertEquals(evals, 0)
      assertEquals(lm.calls.get(), 0)
    }
  }
