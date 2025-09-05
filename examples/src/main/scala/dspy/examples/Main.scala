package dspy.examples

import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.clients.openai.OpenAI
import dspy.utils.Settings
import sttp.client3.SttpBackend

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  private def tryHttpClientBackend(): Option[SttpBackend[Future, Any]] = {
    try {
      val cls     = Class.forName("sttp.client3.httpclient.future.HttpClientFutureBackend$")
      val module  = cls.getField("MODULE$").get(null)
      val apply   = cls.getMethod("apply")
      val backend = apply.invoke(module).asInstanceOf[SttpBackend[Future, Any]]
      Some(backend)
    } catch {
      case _: Throwable => None
    }
  }

  private def makeLm(settings: Settings): LM = {
    (settings.openaiApiKey, tryHttpClientBackend()) match {
      case (Some(_), Some(backend)) => new OpenAI("gpt-4o-mini", settings, backend)
      case _ =>
        // Offline stub LM for example compilation without network
        new LM {
          def complete(prompt: Prompt, params: Map[String, String])(implicit
              ec: ExecutionContext
          ) = Future.successful(Completion("{\"answer\": \"Paris\"}", ujson.Obj()))
        }
    }
  }

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
