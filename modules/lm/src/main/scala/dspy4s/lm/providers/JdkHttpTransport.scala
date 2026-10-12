package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError

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
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpResponse] =
    runWithErrorMap {
      val request  = buildRequest(url, headers, body)
      val response = client.send(request, JHttpResponse.BodyHandlers.ofString())
      HttpResponse(
        status = response.statusCode(),
        headers = flattenHeaders(response),
        body = response.body()
      )
    }

  override def streamSse(
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpStreamResponse] =
    runWithErrorMap {
      val request                            = buildRequest(url, headers, body)
      val response                           = client.send(request, JHttpResponse.BodyHandlers.ofLines())
      val stream                             = response.body()
      val iter: java.util.Iterator[String]   = stream.iterator()
      val closable: ClosableIterator[String] = new ClosableIterator[String]:
        private var closed            = false
        override def hasNext: Boolean =
          if closed then false else iter.hasNext
        override def next(): String = iter.next()
        override def close(): Unit  =
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

  /** Gives a failure without a response its LM error class. A timeout (this includes the connect timeout) is
    * `LmTimeoutError`. A refused connection and each other IO failure is `LmTransportError`. Each other failure is
    * `LmUnexpectedError`. The transport is generic, so the details are empty: the caller adds the model and the
    * provider.
    */
  private def runWithErrorMap[A](thunk: => A): Either[DspyError, A] =
    try Right(thunk)
    catch
      case error: java.net.http.HttpTimeoutException => Left(LmTimeoutError(s"HTTP timeout: ${describe(error)}"))
      case error: java.net.ConnectException          => Left(LmTransportError(s"Connection failed: ${describe(error)}"))
      case error: java.io.IOException                => Left(LmTransportError(s"IO error: ${describe(error)}"))
      case error: RuntimeException                   => Left(LmUnexpectedError(describe(error)))

  /** The message of the exception. The JDK client gives some exceptions no message, so the class name is the default.
    */
  private def describe(error: Exception): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
