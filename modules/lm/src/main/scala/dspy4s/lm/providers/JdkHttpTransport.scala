package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DspyError.messageOf
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError

import java.io.IOException
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.net.http.HttpResponse as JHttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** An [[HttpTransport]] on the JDK HTTP client. The client starts at the first request. [[close]] stops it with no wait
  * (JDK `HttpClient.shutdownNow`), and the next request starts a new client. The connect limit of `timeouts` bounds the
  * connection. The read limit bounds the wait for the status and the headers, then each wait for the next part of the
  * body of [[sendJson]], and each wait for the next line of a stream (see [[HttpTimeouts]] and
  * [[HttpStreamResponse.failure]]).
  *
  * An interrupt of the thread during a wait does not throw. Before the headers, [[sendJson]] and [[streamSse]] give a
  * `Left` with an [[dspy4s.core.contracts.LmUnexpectedError]]. During the body of [[sendJson]], the result is the same
  * `Left`. During a stream, the lines end, and [[HttpStreamResponse.failure]] is that error. In each case the interrupt
  * status of the thread stays set.
  */
final class JdkHttpTransport(timeouts: HttpTimeouts) extends HttpTransport:
  import JdkHttpTransport.failure

  /** The read limit of each request: the limit of the wait for the status and the headers. */
  private val readTimeout: Duration = Duration.ofMillis(timeouts.read.toMillis)

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

  /** Remove the client from this transport, then stop it with no wait (upstream `LM.close`). The JDK `shutdownNow`
    * closes the connections in use, as upstream `StdlibTransport.close` does (`_pool.close_all()` in
    * `dspy/_vendor/lm15/transports/_sync.py`). Thus a request that runs fails with an
    * [[dspy4s.core.contracts.LmTransportError]], and a stream that is open ends with that failure, also when nobody
    * reads it. A request that starts during this call can get the old client, and then fails in the same way. A request
    * after this call starts a new client. When the transport has no client, `close` does nothing.
    */
  override def close(): Unit =
    val removed = this.synchronized {
      val previous = current
      current = None
      previous
    }
    removed.foreach(_.shutdownNow())

  /** Send a request, and read the reply. The JDK request timeout (the read limit) bounds the wait for the status and
    * the headers. Then a private `TimedBody` reads the body, and the read limit bounds each wait for the next part
    * (upstream lm15: the read limit applies to each read, also in the body). No part in the read limit is an
    * [[dspy4s.core.contracts.LmTimeoutError]], and the transport closes the connection.
    */
  override def sendJson(
      url    : String,
      headers: Map[String, String],
      body   : String
  ): Either[DspyError, HttpResponse] =
    runWithErrorMap {
      val request  = buildRequest(url, headers, body)
      val response = client.send(request, JdkHttpTransport.TimedBody.handler)
      (response.statusCode(), flattenHeaders(response), response.body())
    }.flatMap { (status, flat, timed) =>
      timed.await(timeouts.read).map(text => HttpResponse(status = status, headers = flat, body = text))
    }

  /** Open a stream. The JDK request timeout (the read limit) bounds the wait for the status and the headers. A virtual
    * reader thread then reads the lines, and a private `LimitedLines` bounds each wait for the next line.
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
      .timeout(readTimeout)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    // A header of the caller replaces the default of the same name, so a request has one `Content-Type` header.
    headers.foreach { (k, v) => builder.setHeader(k, v) }
    builder.build()

  private def flattenHeaders(response: JHttpResponse[?]): Map[String, String] =
    response.headers().map().entrySet().iterator().asScala.map { entry =>
      entry.getKey.toLowerCase(Locale.ROOT) -> entry.getValue.asScala.mkString(", ")
    }.toMap

  /** `thunk`, with a non-fatal exception as the `Left` of [[JdkHttpTransport.failure]]. An interrupt of the thread
    * during the wait for the status and the headers is an [[dspy4s.core.contracts.LmUnexpectedError]], and the
    * interrupt status of the thread stays set.
    */
  private def runWithErrorMap[A](thunk: => A): Either[DspyError, A] =
    try Right(thunk)
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(LmUnexpectedError("The thread got an interrupt during the wait for the status and the headers"))
      case NonFatal(error) => Left(failure(error))

object JdkHttpTransport:

  /** The LM error of a failure with no response: of the request, of the body, or of a stream. A timeout (this includes
    * the connect timeout) is an `LmTimeoutError`. A refused connection and each other IO failure is an
    * `LmTransportError`. Each other failure is an `LmUnexpectedError`. The text of an IO failure has the message of the
    * last cause ([[rootCause]]). The transport is generic, so the details are empty: the caller adds the model and the
    * provider.
    */
  private def failure(error: Throwable): DspyError =
    error match
      case timeout: HttpTimeoutException            => LmTimeoutError(s"HTTP timeout: ${messageOf(timeout)}")
      case refused: ConnectException                => LmTransportError(s"Connection failed: ${messageOf(refused)}")
      case io: (IOException | UncheckedIOException) => LmTransportError(s"IO error: ${messageOf(rootCause(io))}")
      case other                                    => LmUnexpectedError(messageOf(other))

  /** The last cause in the cause chain of `error`. The JDK client wraps the IO failure of a stream ("closed") around
    * the real reason, for example an `EOFException`.
    */
  @scala.annotation.tailrec
  private def rootCause(error: Throwable): Throwable =
    val cause = error.getCause
    if (cause eq null) || (cause eq error) then error else rootCause(cause)

  /** The body of a plain reply, with the read limit on each wait for the next part (upstream lm15: the read limit
    * applies to each read, also in the body, `dspy/_vendor/lm15/transports/_limits.py`).
    *
    * The JDK string subscriber of the reply (`BodyHandlers.ofString`, with the charset of the reply) reads the body.
    * This subscriber gives its body at once, so `HttpClient.send` returns after the headers, and [[await]] waits for
    * the text. The wait ends in these cases:
    *   - The body ends. The result is the text.
    *   - No part comes in the read limit after the headers or after the last part. The result is an
    *     [[dspy4s.core.contracts.LmTimeoutError]]. The subscription stops, and the JDK client closes the connection.
    *   - The JDK client fails during the body, for example when the connection closes in the middle of the body. The
    *     result is an [[dspy4s.core.contracts.LmTransportError]] for an IO failure, else an
    *     [[dspy4s.core.contracts.LmUnexpectedError]].
    *   - The thread gets an interrupt during the wait. The result is an [[dspy4s.core.contracts.LmUnexpectedError]].
    *     The subscription stops, and the interrupt status of the thread stays set.
    */
  private final class TimedBody private (text: JHttpResponse.BodySubscriber[String])
      extends JHttpResponse.BodySubscriber[TimedBody]:

    /** The time of the headers or of the last part, from `System.nanoTime`. */
    @volatile private var lastPart: Long = System.nanoTime()

    private val subscription = new AtomicReference[Flow.Subscription | Null](null)
    private val cancelled    = new AtomicBoolean(false)

    override def getBody: CompletionStage[TimedBody] = CompletableFuture.completedFuture(this)

    override def onSubscribe(started: Flow.Subscription): Unit =
      subscription.set(started)
      if cancelled.get() then started.cancel() else text.onSubscribe(started)

    override def onNext(item: java.util.List[ByteBuffer]): Unit =
      lastPart = System.nanoTime()
      text.onNext(item)

    override def onError(error: Throwable): Unit = text.onError(error)

    override def onComplete(): Unit = text.onComplete()

    /** Wait for the text of the body, with the read limit `readLimit` on each wait for the next part. */
    def await(readLimit: FiniteDuration): Either[DspyError, String] =
      val body                                       = text.getBody.toCompletableFuture
      val limit                                      = readLimit.toNanos
      @tailrec def loop(): Either[DspyError, String] =
        waitFor(body, math.max(lastPart + limit - System.nanoTime(), 0L)) match
          case Some(result)                                 => result
          case None if System.nanoTime() - lastPart < limit => loop()
          case None                                         =>
            cancel()
            Left(LmTimeoutError(
              s"No part of the body came in the read limit of $readLimit. This is the read limit of the client, not " +
                "a failure of the server: increase HttpTimeouts.read if the model needs more time"
            ))
      loop()

    /** The result of `body` when it comes in `nanos`, else `None`. */
    private def waitFor(body: CompletableFuture[String], nanos: Long): Option[Either[DspyError, String]] =
      try Some(Right(body.get(nanos, TimeUnit.NANOSECONDS)))
      catch
        case _: TimeoutException       => None
        case error: ExecutionException => Option(error.getCause).getOrElse(error) match
            case NonFatal(cause) => Some(Left(failure(cause)))
            case fatal           => throw fatal
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          cancel()
          Some(Left(LmUnexpectedError("The thread got an interrupt during the wait for the body")))

    /** Stop the subscription, so that the JDK client closes the connection. */
    private def cancel(): Unit =
      cancelled.set(true)
      val started = subscription.get()
      if started ne null then started.cancel()

  private object TimedBody:
    /** The body handler of [[JdkHttpTransport.sendJson]]: a [[TimedBody]] over the JDK string subscriber of the reply.
      */
    val handler: JHttpResponse.BodyHandler[TimedBody] =
      info => new TimedBody(JHttpResponse.BodyHandlers.ofString().apply(info))

  /** A message from the reader thread to the consumer of [[LimitedLines]]. */
  private enum Signal derives CanEqual:
    case Line(text: String)
    case End
    case Failed(error: DspyError)

  /** The lines of a stream, with the read limit on each wait for the next line.
    *
    * A virtual reader thread reads the lines of the JDK client into a bounded queue. [[hasNext]] waits for the next
    * line on the queue for the read limit at most. The lines end, and the stream closes (the JDK client then releases
    * the connection), in these cases:
    *   - The JDK client ends the lines. [[failure]] is `None`.
    *   - No line comes in the read limit. [[failure]] is an [[dspy4s.core.contracts.LmTimeoutError]].
    *   - The JDK client fails during the stream, for example when the connection closes in the middle of a chunked
    *     body. [[failure]] is the error of [[JdkHttpTransport.failure]]: an [[dspy4s.core.contracts.LmTransportError]]
    *     for an IO failure, else an [[dspy4s.core.contracts.LmUnexpectedError]].
    *   - The consumer thread gets an interrupt during the wait. [[failure]] is an
    *     [[dspy4s.core.contracts.LmUnexpectedError]], and the interrupt status of the thread stays set.
    *   - The consumer calls [[close]]. [[failure]] does not change.
    *
    * [[hasNext]] and [[next]] do not throw for these cases. In each case the stream closes and the reader thread stops.
    * A call to [[close]] from another thread also stops a wait in [[hasNext]].
    */
  private final class LimitedLines private (
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

  private object LimitedLines:
    /** The number of lines that the reader thread can read before the consumer takes them. */
    private val capacity: Int = 64

    /** Start a reader thread for the lines of `stream`, and give the lines with the read limit `readLimit`. The thread
      * is a virtual thread: the build targets Java 21, and the thread waits for the network most of the time.
      */
    def start(stream: java.util.stream.Stream[String], readLimit: FiniteDuration): LimitedLines =
      val signals = new ArrayBlockingQueue[Signal](capacity)
      val closed  = new AtomicBoolean(false)
      val lines   = stream.iterator()
      val reader  = Thread.ofVirtual().name("dspy4s-sse-reader").unstarted(() => read(lines, signals, closed))
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
          catch case NonFatal(error) => Signal.Failed(failure(error))
        if !closed.get() then signals.put(last)
      catch case _: InterruptedException => () // `close` stops the reader thread.
