package dspy.examples

import dspy.clients._
import dspy.clients.openai.OpenAI
import dspy.utils.Settings
import sttp.client4.httpclient.HttpClientFutureBackend

import scala.concurrent.Future

object ExamplesUtil {
  // For parity demos, always use the real HTTP backend;
  // if OPENAI_API_KEY is unset, runtime will fail with ConfigError.
  def openAiOrStub(settings: Settings, stubJson: String, model: String = "gpt-4o-mini"): LM = {
    val backend = HttpClientFutureBackend()
    new OpenAI(model, settings, backend)
  }
}
