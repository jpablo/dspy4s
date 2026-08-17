package dspy4s.evaluate.metrics

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.{PredictionBackend, PredictionRequest}
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class AutoEvaluationSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private def backend(output: PredictionRequest => RawPrediction): PredictionBackend =
    new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] = ZIO.succeed(output(request))

  private def run(effect: ZIO[PredictionBackend, DspyError, Double], service: PredictionBackend) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.either.provideEnvironment(ZEnvironment(service))).getOrThrowFiberFailure()
    }

  private def example(question: String, response: String): Example =
    Example(rec("question" := question, "response" := response), Set("question"))

  private def prediction(response: String): RawPrediction = RawPrediction(rec("response" := response))

  test("f1Score computes a bounded harmonic mean") {
    assert(math.abs(AutoEvaluation.f1Score(0.5, 1.0) - (1.0 / 1.5)) < 1e-9)
    assertEquals(AutoEvaluation.f1Score(0.0, 1.0), 0.0)
    assertEquals(AutoEvaluation.f1Score(2.0, 2.0), 1.0)
    assertEquals(AutoEvaluation.f1Score(-1.0, 0.5), 0.0)
  }

  test("SemanticF1 uses an explicit prediction backend") {
    val metric  = SemanticF1()
    val service = backend(_ => RawPrediction(rec("recall" := 1.0, "precision" := 0.5)))
    val result  = run(
      metric.score(example("What is the capital?", "Paris"), prediction("The capital is Paris"), Vector.empty),
      service
    )

    assert(result.isRight)
    assert(math.abs(result.toOption.get - (1.0 / 1.5)) < 1e-9)
  }

  test("SemanticF1 reports malformed judge output") {
    val service = backend(_ => RawPrediction(rec("recall" := "bad", "precision" := 0.5)))
    val result  = run(
      SemanticF1().score(example("Q?", "truth"), prediction("answer"), Vector.empty),
      service
    )

    assert(result.isLeft)
  }

  test("CompleteAndGrounded combines two visible judge predictions") {
    val service = backend { request =>
      if request.layout.outputFields.exists(_.name == "completeness") then
        RawPrediction(rec("completeness" := 1.0))
      else RawPrediction(rec("groundedness" := 0.5))
    }
    val pred   = RawPrediction(rec("response" := "Paris", "context" := "Paris is the capital of France"))
    val result = run(
      CompleteAndGrounded().score(example("What is the capital?", "Paris"), pred, Vector.empty),
      service
    )

    assert(result.isRight)
    assert(math.abs(result.toOption.get - (1.0 / 1.5)) < 1e-9)
  }
