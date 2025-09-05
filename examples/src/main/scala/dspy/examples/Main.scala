package dspy.examples

import dspy.signatures._
import dspy.predict.Predict
import dspy.clients.openai.OpenAI
import dspy.utils.Settings

import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  def main(args: Array[String]): Unit = {
    val sig = Signature(
      inputs = List(Field("question", "A user question")),
      outputs = List(Field("answer", "A concise answer")),
      instructions = Some("Answer clearly and briefly. Return JSON only.")
    )

    val lm = new OpenAI(model = "gpt-4o-mini", settings = Settings.default)
    val predict = new Predict(sig, lm)

    val fut = predict(Map("question" -> "What is the capital of France?"))
    fut.onComplete { r =>
      println(s"Prediction: $r")
    }

    // Simple wait for demo only
    Thread.sleep(2000)
  }
}
