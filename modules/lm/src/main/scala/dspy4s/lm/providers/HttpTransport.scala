package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError

final case class HttpResponse(status: Int, headers: Map[String, String], body: String)

final case class HttpStreamResponse(
    status: Int,
    headers: Map[String, String],
    dataLines: ClosableIterator[String]
)

trait HttpTransport:
  def sendJson(
      url: String,
      headers: Map[String, String],
      body: String
  ): Either[DspyError, HttpResponse]

  def streamSse(
      url: String,
      headers: Map[String, String],
      body: String
  ): Either[DspyError, HttpStreamResponse]

object HttpTransport:
  def jdk(timeoutMillis: Long = 60_000L): HttpTransport = new JdkHttpTransport(timeoutMillis)
