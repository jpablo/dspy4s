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
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

class GepaEvalCacheSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Counts how many times the model is actually called, so we can prove cache hits skip the LM. */
  private final class CountingLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "counting"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "always_one"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
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
    val candA   = Map(OptimizableId(0) -> Some("Answer A."))
    val candB   = Map(OptimizableId(0) -> Some("Answer B."))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current

      val first = cache.scored(candA, batch)
      assertEquals(first.scores, Vector(1.0, 1.0))
      assertEquals(first.evaluated, 2) // both examples actually evaluated
      assertEquals(lm.calls.get(), 2)

      // Identical (candidate, examples): fully cached -> no actual evals, no further LM calls.
      val second = cache.scored(candA, batch)
      assertEquals(second.scores, Vector(1.0, 1.0))
      assertEquals(second.evaluated, 0)
      assertEquals(lm.calls.get(), 2)

      // A different candidate is a cache miss again (keyed by candidate AND example).
      assertEquals(cache.scored(candB, batch).evaluated, 2)
      assertEquals(lm.calls.get(), 4)

      // Partial overlap: one already-seen example (candA, batch(0)) is free; the new one costs one eval.
      val newExample =
        Example(values = DynamicValues.record("question" := "Q3", "answer" := "Paris"), inputKeys = Set("question"))
      assertEquals(cache.scored(candA, Vector(batch(0), newExample)).evaluated, 1)
      assertEquals(lm.calls.get(), 5)
    }
  }

  test("duplicate examples in one batch are evaluated once and returned in batch order") {
    val lm      = new CountingLm
    val adapter = new GepaAdapter[DynamicPredict](program, metric)
    val cache   = new GepaEvalCache[DynamicPredict](adapter)
    val cand    = Map(OptimizableId(0) -> Some("Answer A."))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = cache.scored(cand, Vector(batch.head, batch.head, batch.last, batch.head))

      assertEquals(result.scores, Vector(1.0, 1.0, 1.0, 1.0))
      assertEquals(result.evaluated, 2)
      assertEquals(lm.calls.get(), 2)
    }
  }

  test("examples with equal values but different input metadata do not alias") {
    val lm              = new CountingLm
    val adapter         = new GepaAdapter[DynamicPredict](program, metric)
    val cache           = new GepaEvalCache[DynamicPredict](adapter)
    val cand            = Map(OptimizableId(0) -> Some("Answer A."))
    val original        = batch.head
    val differentInputs = original.copy(inputKeys = Set("answer"))
    val augmented       = original.copy(augmented = true)

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current

      assertEquals(cache.scored(cand, Vector(original, differentInputs, augmented)).evaluated, 3)
      assertEquals(lm.calls.get(), 3)
    }
  }

  test("restored candidate scores warm the cache without LM calls") {
    val lm      = new CountingLm
    val adapter = new GepaAdapter[DynamicPredict](program, metric)
    val cache   = new GepaEvalCache[DynamicPredict](adapter)
    val cand    = Map(OptimizableId(0) -> Some("Answer A."))

    assertEquals(cache.restore(cand, batch, Vector(0.25, 0.75), Vector(Map.empty, Map.empty)), Right(()))
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = cache.scored(cand, batch)

      assertEquals(result.scores, Vector(0.25, 0.75))
      assertEquals(result.evaluated, 0)
      assertEquals(lm.calls.get(), 0)
    }
  }
