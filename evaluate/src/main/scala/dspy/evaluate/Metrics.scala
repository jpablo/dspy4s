package dspy.evaluate

import dspy.primitives.Prediction

trait Metric {
  def name: String
  def score(pred: Prediction, expected: Map[String, ujson.Value]): Double // 0.0 to 1.0
}

object Metrics {
  def exactMatch(keys: Seq[String]): Metric = new Metric {
    val name = s"exactMatch(${keys.mkString(",")})"
    def score(pred: Prediction, expected: Map[String, ujson.Value]): Double = {
      val ok = keys.forall { k =>
        val p = pred.fields.get(k).flatMap(asString)
        val e = expected.get(k).flatMap(asString)
        p.isDefined && e.isDefined && p.get == e.get
      }
      if (ok) 1.0 else 0.0
    }
  }

  private def asString(v: ujson.Value): Option[String] = v match {
    case ujson.Str(s) => Some(s)
    case ujson.Num(n) => Some(n.toString)
    case ujson.Bool(b) => Some(b.toString)
    case _ => None
  }
}

