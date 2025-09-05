package dspy.clients

import scala.concurrent.{ExecutionContext, Future}

trait LM {
  def complete(prompt: Prompt, params: Map[String, String] = Map.empty)(implicit
      ec: ExecutionContext
  ): Future[Completion]
}
