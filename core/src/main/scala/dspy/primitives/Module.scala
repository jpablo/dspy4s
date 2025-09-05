package dspy.primitives

import scala.concurrent.{ExecutionContext, Future}

trait Module {
  def forward(inputs: Map[String, String])(implicit ec: ExecutionContext): Future[Prediction]
  final def apply(inputs: Map[String, String])(implicit ec: ExecutionContext): Future[Prediction] =
    forward(inputs)
}

final case class Example(fields: Map[String, String])

final case class Prediction(fields: Map[String, ujson.Value]) {
  def getString(key: String): Option[String] = fields.get(key).flatMap(_.strOpt)
}
