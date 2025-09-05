package dspy.examples

import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.utils.Settings

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  private def makeLm(settings: Settings): LM =
    ExamplesUtil.openAiOrStub(settings, stubJson = "{\"answer\": \"Paris\"}")

  def main(args: Array[String]): Unit = {
    val sig = Signature.parse("question -> answer")
    val lm  = makeLm(Settings.default)
    val predict = new Predict(sig, lm)

    val fut = predict(Map("question" -> "What is the capital of France?"))
    fut.onComplete { r =>
      println(s"Prediction: $r")
    }

    Thread.sleep(2000)
  }
}
