package dspy.evaluate

import dspy.primitives.{Module, Prediction}

import scala.concurrent.{ExecutionContext, Future}

final case class EvalExample(inputs: Map[String, String], expected: Map[String, ujson.Value])
final case class EvalResult(total: Int, correct: Int, accuracy: Double, perExample: Seq[(EvalExample, Double)])

object Evaluator {
  def run(module: Module, data: Seq[EvalExample], metric: Metric)(implicit
      ec: ExecutionContext
  ): Future[EvalResult] = {
    val futs = data.map { ex =>
      module.forward(ex.inputs).map { pred =>
        val s = metric.score(pred, ex.expected)
        (ex, s)
      }.recover { case _ => (ex, 0.0) }
    }
    Future.sequence(futs).map { scored =>
      val total   = scored.size
      val correct = scored.count(_._2 >= 1.0)
      val acc     = if (total == 0) 0.0 else correct.toDouble / total
      EvalResult(total, correct, acc, scored)
    }
  }
}

