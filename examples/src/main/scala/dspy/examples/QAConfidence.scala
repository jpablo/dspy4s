package dspy.examples

import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.utils.Settings

import scala.concurrent.ExecutionContext.Implicits.global

object QAConfidence {
  def main(args: Array[String]): Unit = {
    val sig = Signature(
      inputs = List(Field("question", "A user question", kind = "str")),
      outputs = List(
        Field("answer", "A concise answer", kind = "str"),
        Field("confidence", "Confidence 0-100", kind = "int")
      ),
      instructions = Some("Answer clearly and briefly. Also provide confidence 0-100 as an integer. Return JSON only.")
    )

    val lm = ExamplesUtil.openAi(Settings.default)
    val predict = new Predict(sig, lm)

    val fut = predict(Map("question" -> "What is the capital of France?"))
    fut.onComplete { r =>
      println(s"Prediction: $r")
    }

    Thread.sleep(2000)
  }
}

