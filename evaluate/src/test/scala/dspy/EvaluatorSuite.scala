package dspy

import munit.FunSuite
import dspy.evaluate._
import dspy.primitives.{Module, Prediction}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class EvaluatorSuite extends FunSuite {
  class StaticModule(responses: Map[Map[String, String], Map[String, ujson.Value]]) extends Module {
    def forward(inputs: Map[String, String])(implicit ec: ExecutionContext): Future[Prediction] =
      Future.successful(Prediction(responses.getOrElse(inputs, Map.empty)))
  }

  test("Evaluator exactMatch computes accuracy over dataset") {
    val module = new StaticModule(
      Map(
        Map("q" -> "a?") -> Map("a" -> ujson.Str("x")),
        Map("q" -> "b?") -> Map("a" -> ujson.Str("y")),
        Map("q" -> "c?") -> Map("a" -> ujson.Str("z"))
      )
    )
    val data = Seq(
      EvalExample(Map("q" -> "a?"), Map("a" -> ujson.Str("x"))),
      EvalExample(Map("q" -> "b?"), Map("a" -> ujson.Str("nope"))),
      EvalExample(Map("q" -> "c?"), Map("a" -> ujson.Str("z")))
    )
    val metric = Metrics.exactMatch(Seq("a"))

    Evaluator.run(module, data, metric).map { r =>
      assertEquals(r.total, 3)
      assertEquals(r.correct, 2)
      assertEqualsDouble(r.accuracy, 2.0 / 3.0, 1e-9)
    }
  }
}

