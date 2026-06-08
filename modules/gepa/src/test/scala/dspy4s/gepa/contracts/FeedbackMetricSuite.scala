package dspy4s.gepa.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import munit.FunSuite

class FeedbackMetricSuite extends FunSuite:

  private def rec(entries: (String, zio.blocks.schema.DynamicValue)*): zio.blocks.schema.DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** A toy exact-match feedback metric: 1.0 when the prediction's `answer` matches the gold, else 0.0, with
    * feedback that names the expected answer (and, at predictor level, the component). */
  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "toy_exact"
    override def feedback(
        example: Example,
        prediction: DynamicPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      val s    = if got == gold then 1.0 else 0.0
      val fb = component match
        case None       => s"Program: expected '$gold', got '$got'."
        case Some(name) => s"Component '$name': expected '$gold', got '$got'."
      Right(ScoreWithFeedback(s, fb))

  test("program-level feedback scores and explains, and Metric.score delegates to it") {
    given RuntimeContext = RuntimeContext()
    val ex   = Example(values = rec("answer" := "Paris"), inputKeys = Set.empty)
    val pred = DynamicPrediction(rec("answer" := "Paris"))

    val fb = metric.feedback(ex, pred, Vector.empty, component = None, componentTrace = Vector.empty).toOption.get
    assertEquals(fb.score, 1.0)
    assert(fb.feedback.contains("Paris"), fb.feedback)

    // The inherited Metric.score is exactly the program-level score (drop-in for Evaluate).
    assertEquals(metric.score(ex, pred).toOption.get, 1.0)
  }

  test("predictor-level feedback targets the named component and reflects a wrong answer") {
    given RuntimeContext = RuntimeContext()
    val ex   = Example(values = rec("answer" := "Paris"), inputKeys = Set.empty)
    val pred = DynamicPrediction(rec("answer" := "Lyon"))

    val fb = metric.feedback(ex, pred, Vector.empty, component = Some("qa"), componentTrace = Vector.empty).toOption.get
    assertEquals(fb.score, 0.0)
    assert(fb.feedback.contains("qa"), fb.feedback)
    assert(fb.feedback.contains("Paris") && fb.feedback.contains("Lyon"), fb.feedback)
  }

  test("defaultFeedback mirrors the upstream score-only fallback") {
    assert(FeedbackMetric.defaultFeedback(0.5).contains("0.5"))
  }
