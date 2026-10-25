package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final case class HttpResponse(status: Int, headers: Map[String, String], body: String):

  /** Whether the status is a success status ([[HttpResponse.isSuccess]]). */
  def isSuccess: Boolean = HttpResponse.isSuccess(status)

object HttpResponse:

  /** Whether `status` is a success status: a status from 200 to 299. */
  def isSuccess(status: Int): Boolean = status >= 200 && status < 300

/** The reply of a stream request.
  *
  * @param failure
  *   the reason why the transport ended `dataLines` early, read after `dataLines` has no more lines: an
  *   [[dspy4s.core.contracts.LmTimeoutError]] when no line came in the read limit ([[HttpTimeouts.read]]), or an
  *   [[dspy4s.core.contracts.LmTransportError]] when the connection failed during the stream. `None` when the lines
  *   ended normally. A consumer reports this failure in place of its own rule for a stream that ends early. The JDK
  *   transport also gives an [[dspy4s.core.contracts.LmUnexpectedError]] when the consumer thread gets an interrupt
  *   during the wait for a line, or when its reader thread fails for a reason that is not an IO failure.
  */
final case class HttpStreamResponse(
    status   : Int,
    headers  : Map[String, String],
    dataLines: ClosableIterator[String],
    failure  : () => Option[DspyError] = () => None
):

  /** Whether the status is a success status ([[HttpResponse.isSuccess]]). */
  def isSuccess: Boolean = HttpResponse.isSuccess(status)

/** The time limits of an HTTP transport (upstream lm15 `Timeouts` in `dspy/_vendor/lm15/transports/_limits.py`). Each
  * limit must be positive: a limit of zero or less is a configuration error, not "no limit".
  *
  * @param connect
  *   the time to open the connection. The upstream default is 10 seconds.
  * @param read
  *   the time to wait for the next data of the reply: the status and the headers, then each next part of the body of a
  *   plain reply, or each line of a stream. The upstream default is 600 seconds. A plain reply that gives no new part
  *   in this time is an [[dspy4s.core.contracts.LmTimeoutError]]. A stream that gives no new line in this time ends,
  *   and its [[HttpStreamResponse.failure]] is an [[dspy4s.core.contracts.LmTimeoutError]]. The limit is for each wait,
  *   not for the whole reply.
  */
final case class HttpTimeouts(connect: FiniteDuration = 10.seconds, read: FiniteDuration = 600.seconds):
  require(connect.toNanos > 0L, s"The connect limit must be positive, not $connect")
  require(read.toNanos > 0L, s"The read limit must be positive, not $read")

trait HttpTransport:
  def sendJson(
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpResponse]

  def streamSse(
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpStreamResponse]

  /** Release the connections of this transport. The transport stays usable: a later request opens new connections. The
    * default does nothing.
    */
  def close(): Unit = ()

object HttpTransport:
  /** A transport on the JDK HTTP client with the time limits `timeouts`. */
  def jdk(timeouts: HttpTimeouts = HttpTimeouts()): HttpTransport = new JdkHttpTransport(timeouts)
