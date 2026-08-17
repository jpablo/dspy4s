package dspy4s.gepa.contracts

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.{ParameterId, ProgramEvent}
import munit.FunSuite
import zio.{Runtime, Unsafe, ZIO}

final class FeedbackMetricSuite extends FunSuite:

  private val metric = new FeedbackMetric:
    val name: String = "toy_exact"

    def feedback(
        example                           : Example,
        prediction                        : RawPrediction,
        @annotation.unused events         : Vector[ProgramEvent],
        component                         : Option[ParameterId],
        @annotation.unused componentEvents: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, ScoreWithFeedback] =
      val expected = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val actual   = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      ZIO.succeed(ScoreWithFeedback(if expected == actual then 1.0 else 0.0, s"$component: $expected / $actual"))

  private def run[A](effect: ZIO[Any, DspyError, A]): A =
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("score delegates to program-level feedback") {
    val example    = Example(DynamicValues.record("answer" := "Paris"))
    val prediction = RawPrediction(DynamicValues.record("answer" := "Paris"))

    assertEquals(run(metric.score(example, prediction, Vector.empty)), 1.0)
    assert(run(metric.feedback(example, prediction, Vector.empty, Some(ParameterId("qa")), Vector.empty)).feedback
      .contains("qa"))
  }

  test("defaultFeedback retains the score") {
    assert(FeedbackMetric.defaultFeedback(0.5).contains("0.5"))
  }
