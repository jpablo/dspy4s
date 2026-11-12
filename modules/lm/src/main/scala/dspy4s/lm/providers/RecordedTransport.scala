package dspy4s.lm.providers

import dspy4s.core.contracts.{ClosableIterator, DspyError, RuntimeContext}
import dspy4s.lm.contracts.{ExchangeEnd, ExchangeRecord, ExchangeRequest, ExchangeSink}

import scala.util.control.NonFatal

/** An [[HttpTransport]] that gives each exchange of `inner` to a record of `sink` (docs/code-harness/OBSERVABILITY.md,
  * 4.1). The context is the context of the model call, and the sink gets it at the start of each record.
  *
  *   - The record starts on the thread of the call, before the request goes to `inner`.
  *   - A plain request gives the status, the headers and the body of the reply, then `Done`. A `Left` or an exception
  *     of `inner` gives `Failed`.
  *   - A stream gives the status and the headers, then each line that the consumer reads. The end of the lines gives
  *     `Done`, or `Failed` with the failure of the reply. A close before the end gives `Closed`.
  *   - The caller gets the result of `inner` with no change: the same values, lines, failure and exceptions. A call of
  *     the sink that throws changes nothing ([[GuardedRecord]]).
  */
final class RecordedTransport(inner: HttpTransport, sink: ExchangeSink)(using RuntimeContext) extends HttpTransport:

  override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
    val record = GuardedRecord.start(sink, ExchangeRequest(url, headers, body, stream = false))
    val result = RecordedTransport.watched(record)(inner.sendJson(url, headers, body))
    result match
      case Right(response) =>
        record.replied(response.status, response.headers)
        record.body(response.body)
        record.end(ExchangeEnd.Done)
      case Left(error) => record.end(RecordedTransport.failed(error))
    result

  override def streamSse(url: String, headers: Map[String, String], body: String)
      : Either[DspyError, HttpStreamResponse] =
    val record = GuardedRecord.start(sink, ExchangeRequest(url, headers, body, stream = true))
    RecordedTransport.watched(record)(inner.streamSse(url, headers, body)) match
      case Right(response) =>
        record.replied(response.status, response.headers)
        Right(response.copy(dataLines = RecordedLines(response.dataLines, response.failure, record)))
      case Left(error) =>
        record.end(RecordedTransport.failed(error))
        Left(error)

  /** Does nothing: the inner transport belongs to the client. */
  override def close(): Unit = ()

object RecordedTransport:

  /** The end of an exchange that failed with `error`: its code and its message. */
  private[providers] def failed(error: DspyError): ExchangeEnd = ExchangeEnd.Failed(s"${error.code}: ${error.message}")

  /** The end of an exchange that failed with the exception `error`: its class and its message. */
  private[providers] def thrown(error: Throwable): ExchangeEnd =
    ExchangeEnd.Failed(s"${error.getClass.getSimpleName}: ${DspyError.messageOf(error)}")

  /** The value of `run`. An exception of `run` ends `record` with `Failed`, and then goes on to the caller. An `Error`
    * goes on with no end, so the record stays open.
    */
  private[providers] def watched[A](record: GuardedRecord)(run: => A): A =
    try run
    catch
      case e: Exception =>
        record.end(thrown(e))
        throw e

/** A record whose calls never throw, and that gets no call after its end. Each call holds the lock of this value, so a
  * close on another thread does not mix with a read. An exception of the record is dropped. An interrupt of the record
  * is dropped too, and the interrupt status of the thread is set again, so the transport still sees it.
  */
private[providers] final class GuardedRecord(record: ExchangeRecord):
  private var ended = false

  def replied(status: Int, headers: Map[String, String]): Unit = call(record.replied(status, headers))
  def body(text     : String): Unit                            = call(record.body(text))
  def line(text     : String): Unit                            = call(record.line(text))

  /** The end of the record: the first end counts, and a later end does nothing. */
  def end(how: ExchangeEnd): Unit =
    synchronized {
      if !ended then
        ended = true
        GuardedRecord.quietly(record.end(how))
    }

  private def call(give: => Unit): Unit = synchronized(if !ended then GuardedRecord.quietly(give))

private[providers] object GuardedRecord:

  /** The record of `request` in `sink`. When the start throws, the record keeps nothing. */
  def start(sink: ExchangeSink, request: ExchangeRequest)(using RuntimeContext): GuardedRecord =
    var made: ExchangeRecord = NoRecord
    quietly { made = sink.start(request) }
    GuardedRecord(made)

  /** Runs `give`, and drops its exception. */
  def quietly(give: => Unit): Unit =
    try give
    catch
      case _: InterruptedException => Thread.currentThread().interrupt()
      case NonFatal(_)             => ()

  /** A record that keeps nothing. */
  private object NoRecord extends ExchangeRecord:
    def replied(status: Int, headers: Map[String, String]): Unit = ()
    def body(text     : String): Unit                            = ()
    def line(text     : String): Unit                            = ()
    def end(how       : ExchangeEnd): Unit                       = ()

/** The lines of a stream: each line that the consumer reads goes to `record` too. The end of the lines ends the record
  * with `Done`, or with `Failed` when `failure` gives the failure of the reply. A close ends it with `Closed`, when no
  * end came before.
  */
private final class RecordedLines(
    inner  : ClosableIterator[String],
    failure: () => Option[DspyError],
    record : GuardedRecord
) extends ClosableIterator[String]:

  override def hasNext: Boolean =
    val more = RecordedTransport.watched(record)(inner.hasNext)
    if !more then
      val how =
        try failure().fold(ExchangeEnd.Done)(RecordedTransport.failed)
        catch case NonFatal(e) => RecordedTransport.thrown(e)
      record.end(how)
    more

  override def next(): String =
    val line = RecordedTransport.watched(record)(inner.next())
    record.line(line)
    line

  /** The record ends before the close of the inner lines. After that close, their `hasNext` is false, and a read on
    * another thread must not end the record with `Done`.
    */
  override def close(): Unit =
    record.end(ExchangeEnd.Closed)
    inner.close()
