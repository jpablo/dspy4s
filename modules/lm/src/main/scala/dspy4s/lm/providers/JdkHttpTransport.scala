package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError

import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JHttpResponse
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** An [[HttpTransport]] on the JDK HTTP client. The client starts at the first request. [[close]] closes it (JDK
  * `HttpClient.close`, which waits for the requests that run), and the next request starts a new client. The connect
  * limit of `timeouts` bounds the connection. The read limit bounds the wait for the status and the headers, and, for a
  * stream, the wait for each next line (see [[HttpTimeouts]] and [[HttpStreamResponse.failure]]).
  *
  * The read limit of [[sendJson]] bounds only the wait for the status and the headers (the JDK request timeout). The
  * JDK client then reads the body with no limit.
  */
final class JdkHttpTransport(timeouts: HttpTimeouts) extends HttpTransport:
  import JdkHttpTransport.describe

  private val timeoutMillis: Long = timeouts.read.toMillis

  /** The JDK client: `None` before the first request and after [[close]]. The lock of this transport guards it. */
  private var current: Option[HttpClient] = None

  /** The JDK client. The first call after the start or after [[close]] starts a new client. */
  private def client: HttpClient =
    this.synchronized {
      current.getOrElse {
        val started = HttpClient.newBuilder()
          .connectTimeout(Duration.ofMillis(timeouts.connect.toMillis))
          .build()
        current = Some(started)
        started
      }
    }

  /** `true` when this transport has a JDK client now: after a request and before [[close]]. For tests. */
  private[providers] def isOpen: Boolean = this.synchronized(current.isDefined)

  /** Remove the client from this transport, then close it (upstream `LM.close`). The JDK close waits for the requests
    * that run, and for each stream until it ends. A request that starts during this call can get the old client, and
    * then fails with an [[dspy4s.core.contracts.LmTransportError]]. A stream that is open can end with a failure of the
    * transport. A request after this call starts a new client. When the transport has no client, `close` does nothing.
    */
  override def close(): Unit =
    val removed = this.synchronized {
      val previous = current
      current = None
      previous
    }
    removed.foreach(_.close())

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

  /** Open a stream. The JDK request timeout (the read limit) bounds the wait for the status and the headers. A reader
    * thread then reads the lines, and [[JdkHttpTransport.LimitedLines]] bounds each wait for the next line.
    */
  override def streamSse(
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpStreamResponse] =
    runWithErrorMap {
      val request  = buildRequest(url, headers, body)
      val response = client.send(request, JHttpResponse.BodyHandlers.ofLines())
      val status   = response.statusCode()
      val flat     = flattenHeaders(response)
      val lines    = JdkHttpTransport.LimitedLines.start(response.body(), timeouts.read)
      HttpStreamResponse(
        status = status,
        headers = flat,
        dataLines = lines,
        failure = () => lines.failure
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

object JdkHttpTransport:

  /** The message of the exception. The JDK client gives some exceptions no message, so the class name is the default.
    */
  private def describe(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  /** The last cause in the cause chain of `error`. The JDK client wraps the IO failure of a stream ("closed") around
    * the real reason, for example an `EOFException`.
    */
  @scala.annotation.tailrec
  private def rootCause(error: Throwable): Throwable =
    val cause = error.getCause
    if (cause eq null) || (cause eq error) then error else rootCause(cause)

  /** A message from the reader thread to the consumer of [[LimitedLines]]. */
  private enum Signal derives CanEqual:
    case Line(text: String)
    case End
    case Failed(error: DspyError)

  /** The lines of a stream, with the read limit on each wait for the next line.
    *
    * A daemon reader thread reads the lines of the JDK client into a bounded queue. [[hasNext]] waits for the next line
    * on the queue for the read limit at most. The lines end, and the stream closes (the JDK client then releases the
    * connection), in these cases:
    *   - The JDK client ends the lines. [[failure]] is `None`.
    *   - No line comes in the read limit. [[failure]] is an [[dspy4s.core.contracts.LmTimeoutError]].
    *   - The JDK client fails during the stream, for example when the connection closes in the middle of a chunked
    *     body. [[failure]] is an [[dspy4s.core.contracts.LmTransportError]]. Another failure of the reader thread is an
    *     [[dspy4s.core.contracts.LmUnexpectedError]].
    *   - The consumer thread gets an interrupt during the wait. [[failure]] is an
    *     [[dspy4s.core.contracts.LmUnexpectedError]], and the interrupt status of the thread stays set.
    *   - The consumer calls [[close]]. [[failure]] does not change.
    *
    * [[hasNext]] and [[next]] do not throw for these cases. In each case the stream closes and the reader thread stops.
    * A call to [[close]] from another thread also stops a wait in [[hasNext]].
    */
  private[providers] final class LimitedLines private (
      stream   : java.util.stream.Stream[String],
      signals  : BlockingQueue[Signal],
      closed   : AtomicBoolean,
      reader   : Thread,
      readLimit: FiniteDuration
  ) extends ClosableIterator[String]:
    @volatile private var failed: Option[DspyError] = None
    @volatile private var ended: Boolean            = false
    private var nextLine: String | Null             = null

    /** The reason why the lines ended early, or `None`. Read it after [[hasNext]] gives `false`. */
    def failure: Option[DspyError] = failed

    override def hasNext: Boolean = (nextLine ne null) || (!ended && receive())

    override def next(): String =
      if !hasNext then throw new NoSuchElementException("The stream has no more lines")
      val line = nextLine
      nextLine = null
      line

    override def close(): Unit =
      ended = true
      nextLine = null
      if closed.compareAndSet(false, true) then
        reader.interrupt()
        // The close action of the JDK line stream closes the body stream of the client. This stops a read that waits
        // in the reader thread, and the client releases the connection.
        try stream.close()
        catch case NonFatal(_) => ()
        signals.clear()
        // Stop a wait in `hasNext` on another thread.
        val _ = signals.offer(Signal.End)

    /** Wait for the next signal of the reader thread, and give `true` for a line. */
    private def receive(): Boolean =
      val signal =
        try Option(signals.poll(readLimit.toNanos, TimeUnit.NANOSECONDS))
        catch
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            Some(
              Signal.Failed(LmUnexpectedError("The thread got an interrupt during the wait for the next stream line"))
            )
      if closed.get() then
        ended = true
        false
      else
        signal match
          case Some(Signal.Line(text)) =>
            nextLine = text
            true
          case Some(Signal.End)           => stop(None)
          case Some(Signal.Failed(error)) => stop(Some(error))
          case None                       => stop(Some(LmTimeoutError(
              s"No stream line came in the read limit of $readLimit. This is the read limit of the client, not a " +
                "failure of the server: increase HttpTimeouts.read if the model needs more time"
            )))

    private def stop(reason: Option[DspyError]): Boolean =
      failed = reason
      close()
      false

  private[providers] object LimitedLines:
    /** The number of lines that the reader thread can read before the consumer takes them. */
    private val capacity: Int = 64

    /** Start a reader thread for the lines of `stream`, and give the lines with the read limit `readLimit`. */
    def start(stream: java.util.stream.Stream[String], readLimit: FiniteDuration): LimitedLines =
      val signals = new ArrayBlockingQueue[Signal](capacity)
      val closed  = new AtomicBoolean(false)
      val lines   = stream.iterator()
      val reader  = new Thread(() => read(lines, signals, closed), "dspy4s-sse-reader")
      reader.setDaemon(true)
      val limited = new LimitedLines(stream, signals, closed, reader, readLimit)
      reader.start()
      limited

    /** The loop of the reader thread. It puts each line on `signals`, then one [[Signal.End]] or [[Signal.Failed]].
      * After `close`, it stops and puts nothing more.
      */
    private def read(lines: java.util.Iterator[String], signals: BlockingQueue[Signal], closed: AtomicBoolean): Unit =
      try
        val last =
          try
            while !closed.get() && lines.hasNext do signals.put(Signal.Line(lines.next()))
            Signal.End
          catch
            case error: UncheckedIOException => Signal.Failed(streamFailure(error))
            case error: IOException          => Signal.Failed(streamFailure(error))
            case NonFatal(error)             => Signal.Failed(LmUnexpectedError(s"The stream failed: ${describe(error)}"))
        if !closed.get() then signals.put(last)
      catch case _: InterruptedException => () // `close` stops the reader thread.

    private def streamFailure(error: Exception): LmTransportError =
      LmTransportError(s"The stream failed: ${describe(rootCause(error))}")
