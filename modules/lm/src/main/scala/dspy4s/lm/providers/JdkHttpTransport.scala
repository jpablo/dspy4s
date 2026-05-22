package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeError

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JHttpResponse
import java.time.Duration
import scala.jdk.CollectionConverters.*

final class JdkHttpTransport(timeoutMillis: Long) extends HttpTransport:

  private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(timeoutMillis))
    .build()

  override def sendJson(
      url: String,
      headers: Map[String, String],
      body: String
  ): Either[DspyError, HttpResponse] =
    runWithErrorMap {
      val request = buildRequest(url, headers, body)
      val response = client.send(request, JHttpResponse.BodyHandlers.ofString())
      HttpResponse(
        status = response.statusCode(),
        headers = flattenHeaders(response),
        body = response.body()
      )
    }

  override def streamSse(
      url: String,
      headers: Map[String, String],
      body: String
  ): Either[DspyError, HttpStreamResponse] =
    runWithErrorMap {
      val request = buildRequest(url, headers, body)
      val response = client.send(request, JHttpResponse.BodyHandlers.ofLines())
      val stream = response.body()
      val iter: java.util.Iterator[String] = stream.iterator()
      val closable: ClosableIterator[String] = new ClosableIterator[String]:
        private var closed = false
        override def hasNext: Boolean =
          if closed then false else iter.hasNext
        override def next(): String = iter.next()
        override def close(): Unit =
          if !closed then
            closed = true
            stream.close()
      HttpStreamResponse(
        status = response.statusCode(),
        headers = flattenHeaders(response),
        dataLines = closable
      )
    }

  private def buildRequest(url: String, headers: Map[String, String], body: String): HttpRequest =
    val builder = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeoutMillis))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach { (k, v) => builder.header(k, v) }
    builder.build()

  private def flattenHeaders(response: JHttpResponse[?]): Map[String, String] =
    response.headers().map().entrySet().iterator().asScala.map { entry =>
      entry.getKey.toLowerCase -> entry.getValue.asScala.mkString(", ")
    }.toMap

  private def runWithErrorMap[A](thunk: => A): Either[DspyError, A] =
    try Right(thunk)
    catch
      case error: java.net.http.HttpTimeoutException =>
        Left(RuntimeError("openai_http", s"HTTP timeout: ${error.getMessage}"))
      case error: java.net.ConnectException =>
        Left(RuntimeError("openai_http", s"Connection failed: ${error.getMessage}"))
      case error: java.io.IOException =>
        Left(RuntimeError("openai_http", s"IO error: ${error.getMessage}"))
      case error: RuntimeException =>
        Left(RuntimeError("openai_http", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
