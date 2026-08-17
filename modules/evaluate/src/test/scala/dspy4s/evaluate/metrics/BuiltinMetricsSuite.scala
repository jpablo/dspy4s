package dspy4s.evaluate.metrics

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.{PredictionBackend, PredictionRequest}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class BuiltinMetricsSuite extends FunSuite:

  private val unusedBackend = new PredictionBackend:
    def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.dieMessage("Pure metrics must not call the prediction backend")

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private def run(effect: ZIO[PredictionBackend, DspyError, Double]): Either[DspyError, Double] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.either.provideEnvironment(ZEnvironment(unusedBackend))).getOrThrowFiberFailure()
    }

  private def score(metric: dspy4s.evaluate.Metric, example: Example, prediction: RawPrediction) =
    run(metric.score(example, prediction, Vector.empty))

  test("ExactMatch normalizes matches and rejects non-matches") {
    val metric = new ExactMatch()
    assertEquals(
      score(metric, Example(rec("answer" := "the Paris")), RawPrediction(rec("answer" := "Paris"))),
      Right(1.0)
    )
    assertEquals(
      score(metric, Example(rec("answer" := "Paris")), RawPrediction(rec("answer" := "Brussels"))),
      Right(0.0)
    )
    assertEquals(
      score(metric, Example(rec("answer" := Vector("Paris", "paris france"))), RawPrediction(rec("answer" := "paris"))),
      Right(1.0)
    )
  }

  test("ContainsMatch finds a normalized reference") {
    val metric = new ContainsMatch()
    assertEquals(
      score(metric, Example(rec("answer" := "paris")), RawPrediction(rec("answer" := "The capital CITY is Paris!"))),
      Right(1.0)
    )
    assertEquals(
      score(metric, Example(rec("answer" := "paris")), RawPrediction(rec("answer" := "Brussels"))),
      Right(0.0)
    )
  }

  test("F1Score returns full, partial, and zero scores") {
    val metric  = new F1Score()
    val full    = score(metric, Example(rec("answer" := "the cat sat")), RawPrediction(rec("answer" := "the cat sat")))
    val partial = score(
      metric,
      Example(rec("answer" := "the engine broke down yesterday")),
      RawPrediction(rec("answer" := "the engine broke"))
    ).toOption.get
    val zero = score(metric, Example(rec("answer" := "the cat")), RawPrediction(rec("answer" := "a dog")))

    assertEquals(full, Right(1.0))
    assert(partial > 0.6 && partial < 1.0)
    assertEquals(zero, Right(0.0))
  }

  test("AnswerMatch supports exact and threshold modes") {
    val example    = Example(rec("answer" := "the red fast car"))
    val prediction = RawPrediction(rec("answer" := "red car"))

    assertEquals(score(new AnswerMatch(1.0), example, prediction), Right(0.0))
    assertEquals(score(new AnswerMatch(0.5), example, prediction), Right(1.0))
  }

  test("PassageMatch searches retrieved passages") {
    val metric  = new PassageMatch()
    val example = Example(rec("answer" := "Paris"))

    assertEquals(
      score(metric, example, RawPrediction(rec("context" := Vector("The capital of France is Paris.")))),
      Right(1.0)
    )
    assertEquals(score(metric, example, RawPrediction(rec("context" := Vector("Brussels")))), Right(0.0))
  }

  test("FunctionMetric wraps a predicate") {
    val metric = FunctionMetric.bool("length_gt_3") { (_, prediction) =>
      prediction.get("answer").exists {
        case DynamicValue.Primitive(PrimitiveValue.String(value)) => value.length > 3
        case _                                                    => false
      }
    }

    assertEquals(score(metric, Example.empty, RawPrediction(rec("answer" := "long answer"))), Right(1.0))
    assertEquals(score(metric, Example.empty, RawPrediction(rec("answer" := "hi"))), Right(0.0))
  }

  test("metrics report a missing answer") {
    val result = score(new ExactMatch(), Example(rec("answer" := "Paris")), RawPrediction.empty)

    assert(result.isLeft)
  }
