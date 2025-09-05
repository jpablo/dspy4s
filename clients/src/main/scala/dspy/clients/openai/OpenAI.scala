package dspy.clients.openai

import dspy.clients.{Completion, LM, Prompt}
import dspy.utils.{ConsoleLogger, DspyError, Redaction, Settings}

import scala.concurrent.{ExecutionContext, Future}
import sttp.client4._

final class OpenAI(
    model: String,
    settings: Settings,
    backend: Backend[Future]
) extends LM {

  private val uriBase = uri"${settings.openaiBaseUrl}"
  private val logger  = ConsoleLogger

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

    def sendOnce(): Future[Completion] =
      basicRequest
        .post(uriBase.addPath("chat", "completions"))
        .header("Authorization", s"Bearer $key")
        .contentType("application/json")
        .readTimeout(scala.concurrent.duration.Duration(settings.requestTimeoutMillis, "millis"))
        .body(body.render())
        .response(asStringAlways)
        .send(backend)
        .flatMap { resp =>
          val status  = resp.code.code
          val jsonStr = resp.body
          if (!resp.code.isSuccess)
            Future.failed(DspyError.HttpError(status, jsonStr))
          else
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

    def retryable(status: Int): Boolean =
      status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504

    val maxRetries    = params.get("max_retries").flatMap(_.toIntOption).getOrElse(2)
    val baseBackoffMs = params.get("backoff_ms").flatMap(_.toLongOption).getOrElse(250L)

    def after(ms: Long): Future[Unit] = Future { Thread.sleep(ms) }

    def loop(attempt: Int): Future[Completion] = {
      val start = System.nanoTime()
      if (settings.debug) {
        val promptPreview =
          if (settings.logPrompts)
            Redaction.truncate(Redaction.redact(prompt.content, Seq(key)), 200)
          else "<hidden>"
        logger.debug(s"openai.request model=$model attempt=$attempt prompt=${promptPreview}")
      }

      sendOnce().map { c =>
        if (settings.debug) {
          val tookMs = (System.nanoTime() - start) / 1000000
          val respPreview = if (settings.logResponses) Redaction.truncate(c.text, 200) else "<hidden>"
          logger.debug(s"openai.response model=$model status=200 took_ms=$tookMs body=${respPreview}")
        }
        c
      }.recoverWith {
        case e: DspyError.HttpError if retryable(e.status) && attempt < maxRetries =>
          val delay = baseBackoffMs * math.pow(2.0, attempt.toDouble).toLong
          if (settings.debug) logger.warn(s"openai.retry status=${e.status} after=${delay}ms")
          after(delay).flatMap(_ => loop(attempt + 1))
      }
    }

    loop(0)
  }
}
