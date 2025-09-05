package dspy.predict.predictors

import dspy.primitives.{Module, Prediction}

import scala.concurrent.{ExecutionContext, Future}

final class BestOfN(
    makeModule: () => Module,
    n: Int,
    select: Seq[Prediction] => Prediction
) extends Module {
  override def forward(inputs: Map[String, String])(implicit ec: ExecutionContext): Future[Prediction] = {
    val futures = Vector.fill(n)(makeModule().forward(inputs))
    Future.sequence(futures).map(select)
  }
}

object BestOfN {
  /** Select prediction with highest integer field `confidence`, fallback to head. */
  def byConfidence(field: String = "confidence"): Seq[Prediction] => Prediction = { preds =>
    def score(p: Prediction): Int = p.fields.get(field).flatMap(_.numOpt).map(_.toInt)
      .orElse(p.fields.get(field).flatMap(_.strOpt).filter(_.forall(_.isDigit)).map(_.toInt))
      .getOrElse(Int.MinValue)
    preds.maxBy(score)
  }
}

