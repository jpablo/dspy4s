package dspy.examples

import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._

import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  def main(args: Array[String]): Unit = {
    val sig = Signature(
      inputs = List(Field("question", "A user question")),
      outputs = List(Field("answer", "A concise answer")),
      instructions = Some("Answer clearly and briefly. Return JSON only.")
    )

    // Offline stub LM for example compilation without network
    val stubLm = new LM {
      def complete(prompt: Prompt, params: Map[String, String])(implicit ec: scala.concurrent.ExecutionContext) =
        scala.concurrent.Future.successful(Completion("{\"answer\": \"Paris\"}", ujson.Obj()))
    }
    val predict = new Predict(sig, stubLm)

    val fut = predict(Map("question" -> "What is the capital of France?"))
    fut.onComplete { r =>
      println(s"Prediction: $r")
    }

    // Simple wait for demo only
    Thread.sleep(2000)
  }
}
