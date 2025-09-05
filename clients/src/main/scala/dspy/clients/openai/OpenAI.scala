package dspy.clients.openai

import dspy.clients.{Completion, LM, Prompt}
import dspy.utils.DspyError
import dspy.utils.Settings

import scala.concurrent.{ExecutionContext, Future}
import sttp.client3._

final class OpenAI(
    model: String,
    settings: Settings,
    backend: SttpBackend[Future, Any]
) extends LM {

  private val uriBase = uri"${settings.openaiBaseUrl}"

  override def complete(prompt: Prompt, params: Map[String, String])(implicit
      ec: ExecutionContext
  ): Future[Completion] = {
    val key = settings.openaiApiKey.getOrElse(
      throw DspyError.ConfigError("OPENAI_API_KEY is not set; cannot call OpenAI API.")
    )

    val temperature = params.get("temperature").flatMap(_.toDoubleOption).getOrElse(0.2)
    val maxTokens   = params.get("max_tokens").flatMap(_.toIntOption)

    val body = ujson.Obj(
      "model"       -> model,
      "messages"    -> ujson.Arr(
        ujson.Obj(
          "role"    -> "user",
          "content" -> prompt.content
        )
      ),
      "temperature" -> temperature
    )

    maxTokens.foreach(n => body.update("max_tokens", n))

    basicRequest
      .post(uriBase.addPath("chat", "completions"))
      .header("Authorization", s"Bearer $key")
      .contentType("application/json")
      .readTimeout(scala.concurrent.duration.Duration(settings.requestTimeoutMillis, "millis"))
      .body(body.render())
      .send(backend)
      .flatMap { resp =>
        resp.body match {
          case Left(err)      =>
            Future.failed(DspyError.HttpError(resp.code.code, err))
          case Right(jsonStr) =>
            try {
              val js   = ujson.read(jsonStr)
              val text = js("choices")(0)("message")("content").str
              Future.successful(Completion(text, js))
            } catch {
              case t: Throwable =>
                Future.failed(
                  DspyError.ParseError("Failed to parse OpenAI response", jsonStr, Some(t))
                )
            }
        }
      }
  }
}
