package dspy.clients.openai

import dspy.clients.embeddings.Embeddings
import dspy.utils.{DspyError, Settings}
import sttp.client4._

import scala.concurrent.{ExecutionContext, Future}

final class OpenAIEmbeddings(
    model: String = "text-embedding-3-small",
    settings: Settings,
    backend: Backend[Future]
) extends Embeddings {

  private val base = uri"${settings.openaiBaseUrl}"

  def embed(texts: Seq[String])(implicit ec: ExecutionContext): Future[Seq[Array[Float]]] = {
    val key = settings.openaiApiKey.getOrElse(
      throw DspyError.ConfigError("OPENAI_API_KEY is not set; cannot call OpenAI Embeddings API.")
    )

    val body = ujson.Obj(
      "model" -> model,
      "input" -> ujson.Arr(texts.map(ujson.Str.apply)*)
    )

    basicRequest
      .post(base.addPath("embeddings"))
      .header("Authorization", s"Bearer $key")
      .contentType("application/json")
      .readTimeout(scala.concurrent.duration.Duration(settings.requestTimeoutMillis, "millis"))
      .body(body.render())
      .response(asStringAlways)
      .send(backend)
      .flatMap { resp =>
        if (!resp.code.isSuccess) Future.failed(DspyError.HttpError(resp.code.code, resp.body))
        else
          try {
            val js = ujson.read(resp.body)
            val arr = js("data").arr
            val out = arr.map { item =>
              val emb = item("embedding").arr
              val xs  = new Array[Float](emb.length)
              var i   = 0
              while (i < emb.length) {
                xs(i) = emb(i).num.toFloat
                i += 1
              }
              xs
            }
            Future.successful(out.toSeq)
          } catch {
            case t: Throwable => Future.failed(DspyError.ParseError("Failed to parse embeddings", resp.body, Some(t)))
          }
      }
  }
}
