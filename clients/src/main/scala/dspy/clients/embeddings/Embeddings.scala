package dspy.clients.embeddings

import scala.concurrent.{ExecutionContext, Future}

trait Embeddings {
  def embed(texts: Seq[String])(implicit ec: ExecutionContext): Future[Seq[Array[Float]]]
}

