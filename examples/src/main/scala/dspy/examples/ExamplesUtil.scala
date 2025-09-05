package dspy.examples

import dspy.clients._
import dspy.clients.openai.OpenAI
import dspy.utils.Settings
import sttp.client3.SttpBackend

import scala.concurrent.{ExecutionContext, Future}

object ExamplesUtil {
  def tryHttpClientBackend(): Option[SttpBackend[Future, Any]] = {
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

  def openAiOrStub(settings: Settings, stubJson: String, model: String = "gpt-4o-mini"): LM = {
    (settings.openaiApiKey, tryHttpClientBackend()) match {
      case (Some(_), Some(backend)) => new OpenAI(model, settings, backend)
      case _ =>
        new LM {
          def complete(prompt: Prompt, params: Map[String, String])(implicit ec: ExecutionContext) =
            Future.successful(Completion(stubJson, ujson.Obj()))
        }
    }
  }
}

