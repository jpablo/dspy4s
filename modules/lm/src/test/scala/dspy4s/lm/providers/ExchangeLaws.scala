package dspy4s.lm.providers

import dspy4s.core.contracts.{
  ClosableIterator,
  DspyError,
  LmTimeoutError,
  LmTransportError,
  RuntimeContext,
  RuntimeError
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{
  ExchangeEnd,
  ExchangeRecord,
  ExchangeRequest,
  ExchangeSink,
  LmRequest,
  Message,
  MessageRole
}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import ExchangeLaws.*

/** The laws of the record of the HTTP exchanges (docs/code-harness/OBSERVABILITY.md, 4.1 and 6: L1 to L5).
  *
  * A scripted transport gives a plain reply, an error, an exception, or a stream with its lines and its failure. A
  * reader reads a stream to its end, or some lines and then closes it, closes it twice, or leaves it open. Each law
  * runs the same script with no sink and with a sink, and compares what the caller sees and what the sink got.
  */
class ExchangeLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── L1 and L2: a sink changes nothing that the caller sees ──

  test("Exchange L1: with no sink, a model call uses the client of its model") {
    val model            = OpenAiLanguageModel(model = "m", client = OpenAiClient(apiKey = "FAKE-KEY", transport = Never))
    given RuntimeContext = RuntimeContext()
    assert(model.clientFor eq model.client)
  }

  property("Exchange L2: a sink does not change the result of a plain exchange") {
    forAllNoShrink(plainScript, request) { (script, sent) =>
      val clock = Clock()
      val alone = runPlain(Scripted(script, NoStream, clock), sent)
      assertEquals(runPlain(recorded(Scripted(script, NoStream, clock), Tape(clock)), sent), alone)
    }
  }

  property("Exchange L2: a sink does not change the status, the headers, the lines or the failure of a stream") {
    forAllNoShrink(streamScript, reader, request) { (script, how, sent) =>
      val clock = Clock()
      val alone = Scripted(NoPlain, script, clock)
      val inner = Scripted(NoPlain, script, clock)
      assertEquals(runStream(recorded(inner, Tape(clock)), inner, how, sent), runStream(alone, alone, how, sent))
    }
  }

  test("Exchange L2: a model call gives the same response and the same chunks with a sink") {
    val scripts = Vector(
      Scripted(PlainScript.Reply(200, Map.empty, chatReply), NoStream, Clock()),
      Scripted(
        PlainScript.Reply(429, Map.empty, """{"error":{"message":"slow down","type":"rate_limit"}}"""),
        NoStream,
        Clock()
      ),
      Scripted(PlainScript.Fails(LmTransportError("refused")), NoStream, Clock())
    )
    scripts.foreach { script =>
      val model = modelOf(script)
      val alone = model.call(chat)(using RuntimeContext())
      assertEquals(model.call(chat)(using RuntimeContext(exchangeSink = Some(Tape(Clock())))), alone)
    }
    val streams = Vector(
      StreamScript.Lines(200, Map.empty, Vector(delta("Hi"), "", delta(" there", "stop"), "", "data: [DONE]"), None),
      StreamScript.Lines(200, Map.empty, Vector(delta("Hi")), Some(LmTimeoutError("no line in 1 s"))),
      StreamScript.Lines(500, Map.empty, Vector("""{"error":{"message":"down"}}"""), None),
      StreamScript.Fails(LmTransportError("refused"))
    )
    streams.foreach { script =>
      val alone = modelOf(Scripted(NoPlain, script, Clock())).stream(chat)(using RuntimeContext()).toVector
      val sink  = RuntimeContext(exchangeSink = Some(Tape(Clock())))
      assertEquals(modelOf(Scripted(NoPlain, script, Clock())).stream(chat)(using sink).toVector, alone)
    }
  }

  // ── L3: the record is exact ──

  property("Exchange L3: the record of a plain exchange has the request that went and the reply that came") {
    forAllNoShrink(plainScript, request) { (script, sent) =>
      val clock = Clock()
      val inner = Scripted(script, NoStream, clock)
      val tape  = Tape(clock)
      runPlain(recorded(inner, tape), sent): Unit
      startedFirst(tape, inner)
      assertEquals(tape.events.drop(1).filterNot(isEnd), expectedPlain(script).filterNot(isEnd))
    }
  }

  property("Exchange L3: the record of a stream has the request, the status, the headers and each line that was read") {
    forAllNoShrink(streamScript, reader, request) { (script, how, sent) =>
      val clock = Clock()
      val inner = Scripted(NoPlain, script, clock)
      val tape  = Tape(clock)
      runStream(recorded(inner, tape), inner, how, sent): Unit
      startedFirst(tape, inner)
      assertEquals(tape.events.drop(1).filterNot(isEnd), expectedStream(script, how).filterNot(isEnd))
    }
  }

  test("Exchange L3: a model call with a sink records the exact body that the transport got") {
    val inner = Scripted(PlainScript.Reply(200, Map("x-id" -> "7"), chatReply), NoStream, Clock())
    val tape  = Tape(Clock())
    modelOf(inner).call(chat)(using RuntimeContext(exchangeSink = Some(tape))): Unit
    val (got, _) = inner.got.headOption.getOrElse(fail("the transport got no request"))
    tape.events match
      case Event.Start(request, _, _) +: rest =>
        assertEquals(request, got)
        assert(request.url.endsWith("/chat/completions"), request.url)
        assertEquals(request.headers.get("Authorization"), Some("Bearer FAKE-KEY"))
        assertEquals(
          rest,
          Vector(Event.Replied(200, Map("x-id" -> "7")), Event.Body(chatReply), Event.End(ExchangeEnd.Done))
        )
      case other => fail(s"the record has no start: $other")
  }

  // ── L4: one end ──

  property("Exchange L4: a plain exchange has one end in its record, and the end says how it ended") {
    forAllNoShrink(plainScript, request) { (script, sent) =>
      val clock = Clock()
      val tape  = Tape(clock)
      runPlain(recorded(Scripted(script, NoStream, clock), tape), sent): Unit
      assertEquals(ends(tape.events), ends(expectedPlain(script)))
    }
  }

  property("Exchange L4: a stream that ends or closes has one end in its record, and an open stream has none") {
    forAllNoShrink(streamScript, reader, request) { (script, how, sent) =>
      val clock = Clock()
      val inner = Scripted(NoPlain, script, clock)
      val tape  = Tape(clock)
      runStream(recorded(inner, tape), inner, how, sent): Unit
      assertEquals(ends(tape.events), ends(expectedStream(script, how)))
    }
  }

  test("Exchange L4: a complete stream of the client ends Closed, after its line data: [DONE]") {
    val lines = Vector(delta("Hi"), "", delta(" there", "stop"), "", "data: [DONE]", "")
    val tape  = Tape(Clock())
    val model = modelOf(Scripted(NoPlain, StreamScript.Lines(200, Map.empty, lines, None), Clock()))
    model.stream(chat)(using RuntimeContext(exchangeSink = Some(tape))).toVector: Unit
    assertEquals(tape.events.collect { case Event.Line(text) => text }, lines.take(5))
    assertEquals(ends(tape.events), Vector(ExchangeEnd.Closed))
  }

  test("Exchange L4: no line reaches the record after its end") {
    val clock = Clock()
    val inner =
      Scripted(NoPlain, StreamScript.Lines(200, Map.empty, Vector("a", "b", "c"), None), clock, obeyClose = false)
    val tape = Tape(clock)
    recorded(inner, tape).streamSse(Url, Map.empty, "{}") match
      case Right(response) =>
        val lines = response.dataLines
        assertEquals(lines.next(), "a")
        lines.close()
        // The inner lines go on after the close. The caller still gets them, and the record does not.
        assertEquals(lines.toVector, Vector("b", "c"))
      case Left(error) => fail(error.message)
    assertEquals(
      tape.events.drop(1),
      Vector(Event.Replied(200, Map.empty), Event.Line("a"), Event.End(ExchangeEnd.Closed))
    )
  }

  test("Exchange L4: a close on another thread gives one end, and no line after it") {
    (1 to 50).foreach { round =>
      val clock = Clock()
      val inner =
        Scripted(NoPlain, StreamScript.Lines(200, Map.empty, Vector.tabulate(2000)(i => s"line $i"), None), clock)
      val tape = Tape(clock)
      recorded(inner, tape).streamSse(Url, Map.empty, "{}") match
        case Right(response) =>
          val lines   = response.dataLines
          val started = CountDownLatch(1)
          val closer  = Thread.ofPlatform().start { () =>
            started.await()
            lines.close()
          }
          while lines.hasNext do
            lines.next(): Unit
            started.countDown()
          started.countDown()
          closer.join()
        case Left(error) => fail(error.message)
      val events = tape.events
      assertEquals(ends(events).size, 1, s"round $round: $events")
      assert(
        events.last match
          case Event.End(_) => true;
          case _            => false
        ,
        s"round $round: a line came after the end"
      )
    }
  }

  test("Exchange L4: a read during the close of the lines does not end the record Done") {
    val clock = Clock()
    val inner = Scripted(NoPlain, StreamScript.Lines(200, Map.empty, Vector("a", "b"), None), clock)
    val tape  = Tape(clock)
    recorded(inner, tape).streamSse(Url, Map.empty, "{}") match
      case Right(response) =>
        val lines = response.dataLines
        assertEquals(lines.next(), "a")
        // A read on another thread at the time of the close: the inner lines are closed, so it sees no more lines.
        inner.lines.foreach(_.onClose = () => assert(!lines.hasNext))
        lines.close()
      case Left(error) => fail(error.message)
    assertEquals(ends(tape.events), Vector(ExchangeEnd.Closed))
  }

  // ── L5: a sink that fails changes nothing ──

  property("Exchange L5: a sink that throws does not change a plain exchange or a stream") {
    forAllNoShrink(plainScript, streamScript, reader, fault, request) { (plain, stream, how, at, sent) =>
      val clock  = Clock()
      val broken = Broken(at)
      assertEquals(
        runPlain(recorded(Scripted(plain, NoStream, clock), broken), sent),
        runPlain(Scripted(plain, NoStream, clock), sent)
      )
      val alone = Scripted(NoPlain, stream, clock)
      val inner = Scripted(NoPlain, stream, clock)
      assertEquals(runStream(recorded(inner, broken), inner, how, sent), runStream(alone, alone, how, sent))
    }
  }

  /** The record starts with the request that the transport got, on the thread of the call, before the transport got it.
    */
  private def startedFirst(tape: Tape, inner: Scripted): Unit =
    val (got, gotAt) = inner.got.headOption.getOrElse(fail("the transport got no request"))
    tape.events.headOption match
      case Some(Event.Start(request, thread, at)) =>
        assertEquals(request, got)
        assertEquals(thread, Thread.currentThread().threadId())
        assert(at < gotAt, s"the record started at $at, after the request went at $gotAt")
      case other => fail(s"the record has no start: $other")

object ExchangeLaws:

  val Url = "http://test.invalid/v1/chat/completions"

  /** The order of the events of a run: the start of a record and the request of a transport each take a tick. */
  final class Clock:
    private val n   = AtomicInteger(0)
    def tick(): Int = n.getAndIncrement()

  /** An event that a record got. */
  enum Event derives CanEqual:
    case Start(request: ExchangeRequest, thread: Long, at: Int)
    case Replied(status: Int, headers: Map[String, String])
    case Body(text: String)
    case Line(text: String)
    case End(how: ExchangeEnd)

  def isEnd(event: Event): Boolean =
    event match
      case Event.End(_) => true
      case _            => false

  def ends(events: Vector[Event]): Vector[ExchangeEnd] = events.collect { case Event.End(how) => how }

  /** A sink that keeps each event of each record, in their order. */
  final class Tape(clock: Clock) extends ExchangeSink:
    private var seen                    = Vector.empty[Event]
    def events: Vector[Event]           = synchronized(seen)
    private def add(event: Event): Unit = synchronized { seen = seen :+ event }

    def start(request: ExchangeRequest)(using RuntimeContext): ExchangeRecord =
      add(Event.Start(request, Thread.currentThread().threadId(), clock.tick()))
      new ExchangeRecord:
        def replied(status: Int, headers: Map[String, String]): Unit = add(Event.Replied(status, headers))
        def body(text     : String): Unit                            = add(Event.Body(text))
        def line(text     : String): Unit                            = add(Event.Line(text))
        def end(how       : ExchangeEnd): Unit                       = add(Event.End(how))

  /** The call of a sink that throws. */
  enum Fault derives CanEqual:
    case AtStart, AtReplied, AtBody, AtLine, AtEnd, Always

  /** A sink that throws at `at`. */
  final class Broken(at: Fault) extends ExchangeSink:
    private def fail(here: Fault): Unit =
      if at == here || at == Fault.Always then throw IllegalStateException(s"the sink fails at $here")

    def start(request: ExchangeRequest)(using RuntimeContext): ExchangeRecord =
      fail(Fault.AtStart)
      new ExchangeRecord:
        def replied(status: Int, headers: Map[String, String]): Unit = fail(Fault.AtReplied)
        def body(text     : String): Unit                            = fail(Fault.AtBody)
        def line(text     : String): Unit                            = fail(Fault.AtLine)
        def end(how       : ExchangeEnd): Unit                       = fail(Fault.AtEnd)

  /** The script of a plain reply. */
  enum PlainScript:
    case Reply(status: Int, headers: Map[String, String], body: String)
    case Fails(error: DspyError)
    case Throws(message: String)

  /** The script of a stream. */
  enum StreamScript:
    case Lines(status: Int, headers: Map[String, String], lines: Vector[String], failure: Option[DspyError])
    case Fails(error: DspyError)
    case Throws(message: String)

  val NoPlain: PlainScript   = PlainScript.Fails(RuntimeError("test", "the plain path is not used"))
  val NoStream: StreamScript = StreamScript.Fails(RuntimeError("test", "the stream path is not used"))

  /** How a reader reads a stream: to its end, or at most `n` lines and then a close, two closes, or no close. */
  enum Reader derives CanEqual:
    case ToEnd
    case Take(n: Int)
    case CloseTwice(n: Int)
    case Abandon(n: Int)

  def limitOf(how: Reader): Int =
    how match
      case Reader.ToEnd         => Int.MaxValue
      case Reader.Take(n)       => n
      case Reader.CloseTwice(n) => n
      case Reader.Abandon(n)    => n

  /** The lines of a stream script. As in the JDK transport, the failure shows only after the end of the lines. With
    * `obeyClose = false`, the lines go on after a close.
    */
  final class ScriptLines(items: Vector[String], failure: Option[DspyError], obeyClose: Boolean)
      extends ClosableIterator[String]:
    @volatile private var index   = 0
    @volatile var closes          = 0
    @volatile var onClose         = () => ()
    def failed: Option[DspyError] = if index >= items.size then failure else None
    override def hasNext: Boolean = index < items.size && (closes == 0 || !obeyClose)
    override def next(): String   =
      val item = items(index)
      index += 1
      item
    override def close(): Unit =
      closes += 1
      onClose()

  /** A transport of two scripts. It keeps each request that it got, with its tick. */
  final class Scripted(plain: PlainScript, stream: StreamScript, clock: Clock, obeyClose: Boolean = true)
      extends HttpTransport:
    @volatile var got: Vector[(ExchangeRequest, Int)] = Vector.empty
    @volatile var lines: Option[ScriptLines]          = None

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      got = got :+ (ExchangeRequest(url, headers, body, stream = false), clock.tick())
      plain match
        case PlainScript.Reply(status, replied, text) => Right(HttpResponse(status, replied, text))
        case PlainScript.Fails(error)                 => Left(error)
        case PlainScript.Throws(message)              => throw IllegalStateException(message)

    override def streamSse(url: String, headers: Map[String, String], body: String)
        : Either[DspyError, HttpStreamResponse] =
      got = got :+ (ExchangeRequest(url, headers, body, stream = true), clock.tick())
      stream match
        case StreamScript.Lines(status, replied, items, failure) =>
          val made = ScriptLines(items, failure, obeyClose)
          lines = Some(made)
          Right(HttpStreamResponse(status, replied, made, () => made.failed))
        case StreamScript.Fails(error)    => Left(error)
        case StreamScript.Throws(message) => throw IllegalStateException(message)

  /** A transport that no call reaches. */
  object Never extends HttpTransport:
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Left(RuntimeError("test", "no call"))
    override def streamSse(url: String, headers: Map[String, String], body: String)
        : Either[DspyError, HttpStreamResponse] = Left(RuntimeError("test", "no call"))

  def recorded(inner: HttpTransport, sink: ExchangeSink): HttpTransport =
    RecordedTransport(inner, sink)(using RuntimeContext())

  /** What a caller sees of a run. */
  enum Outcome derives CanEqual:
    case PlainResult(result: Either[DspyError, (Int, Map[String, String], String)])
    case StreamResult(result: Either[DspyError, Seen])
    case Threw(kind: String, message: String)

  /** What a reader of a stream sees: the status, the headers, the lines, the failure, and the closes of the inner
    * lines.
    */
  final case class Seen(
      status : Int,
      headers: Map[String, String],
      lines  : Vector[String],
      failure: Option[DspyError],
      closes : Int
  )

  /** A request: the URL, the headers and the body. */
  final case class Sent(url: String, headers: Map[String, String], body: String)

  def runPlain(transport: HttpTransport, sent: Sent): Outcome =
    try
      Outcome.PlainResult(transport.sendJson(sent.url, sent.headers, sent.body).map(r => (r.status, r.headers, r.body)))
    catch case e: IllegalStateException => Outcome.Threw(e.getClass.getSimpleName, e.getMessage)

  def runStream(transport: HttpTransport, inner: Scripted, how: Reader, sent: Sent): Outcome =
    try
      transport.streamSse(sent.url, sent.headers, sent.body) match
        case Left(error)     => Outcome.StreamResult(Left(error))
        case Right(response) =>
          val lines = response.dataLines
          val read  = Vector.newBuilder[String]
          var count = 0
          while count < limitOf(how) && lines.hasNext do
            read += lines.next()
            count += 1
          how match
            case Reader.Abandon(_)    => ()
            case Reader.CloseTwice(_) =>
              lines.close()
              lines.close()
            case _ => lines.close()
          val closes = inner.lines.fold(0)(_.closes)
          Outcome.StreamResult(Right(Seen(
            response.status,
            response.headers,
            read.result(),
            response.failure(),
            closes
          )))
    catch case e: IllegalStateException => Outcome.Threw(e.getClass.getSimpleName, e.getMessage)

  def failureOf(error: DspyError): ExchangeEnd = ExchangeEnd.Failed(s"${error.code}: ${error.message}")
  def thrown(message : String): ExchangeEnd    = ExchangeEnd.Failed(s"IllegalStateException: $message")

  /** The events of the record of a plain exchange after its start. */
  def expectedPlain(script: PlainScript): Vector[Event] =
    script match
      case PlainScript.Reply(status, headers, body) =>
        Vector(Event.Replied(status, headers), Event.Body(body), Event.End(ExchangeEnd.Done))
      case PlainScript.Fails(error)    => Vector(Event.End(failureOf(error)))
      case PlainScript.Throws(message) => Vector(Event.End(thrown(message)))

  /** The events of the record of a stream after its start. A reader that asks for more lines than the stream has reads
    * to the end of the lines. A reader that stops before the end closes the stream, or leaves it open.
    */
  def expectedStream(script: StreamScript, how: Reader): Vector[Event] =
    script match
      case StreamScript.Fails(error)                           => Vector(Event.End(failureOf(error)))
      case StreamScript.Throws(message)                        => Vector(Event.End(thrown(message)))
      case StreamScript.Lines(status, headers, items, failure) =>
        val limit = limitOf(how)
        val end   =
          if limit > items.size then Some(failure.fold(ExchangeEnd.Done)(failureOf))
          else
            how match
              case Reader.Abandon(_) => None
              case _                 => Some(ExchangeEnd.Closed)
        (Event.Replied(status, headers) +: items.take(limit).map(Event.Line(_))) ++ end.map(Event.End(_))

  // ── Generators ──

  private val word: Gen[String] =
    Gen.oneOf("", "data: [DONE]", """data: {"choices":[]}""", "event: ping", ": comment", "é ☃ 日本", "x")
  val text: Gen[String] = Gen.frequency(3 -> word, 2 -> Gen.asciiPrintableStr.map(_.take(40)), 1 -> Gen.alphaNumStr)

  private val message: Gen[String]  = Gen.alphaStr.map(s => s"m${s.take(12)}")
  private val error: Gen[DspyError] =
    message.flatMap(m => Gen.oneOf[DspyError](LmTransportError(m), LmTimeoutError(m), RuntimeError("test", m)))

  private val headers: Gen[Map[String, String]] =
    Gen.choose(0, 3).flatMap(n => Gen.mapOfN(n, Gen.zip(Gen.alphaStr.map(s => s"h${s.take(8)}"), text)))
  private val status: Gen[Int] = Gen.oneOf(200, 201, 204, 400, 401, 404, 429, 500, 503)

  val plainScript: Gen[PlainScript] = Gen.frequency(
    6 -> (for s <- status; h <- headers; b <- text yield PlainScript.Reply(s, h, b)),
    2 -> error.map(PlainScript.Fails(_)),
    1 -> message.map(PlainScript.Throws(_))
  )

  val streamScript: Gen[StreamScript] = Gen.frequency(
    6 ->
      (for
        s     <- status
        h     <- headers
        n     <- Gen.choose(0, 8)
        items <- Gen.listOfN(n, text)
        f     <- Gen.option(error)
      yield StreamScript.Lines(s, h, items.toVector, f)),
    2 -> error.map(StreamScript.Fails(_)),
    1 -> message.map(StreamScript.Throws(_))
  )

  val reader: Gen[Reader] = Gen.oneOf(
    Gen.const(Reader.ToEnd),
    Gen.choose(0, 10).map(Reader.Take(_)),
    Gen.choose(0, 10).map(Reader.CloseTwice(_)),
    Gen.choose(0, 10).map(Reader.Abandon(_))
  )

  val fault: Gen[Fault] = Gen.oneOf(Fault.values.toIndexedSeq)

  val request: Gen[Sent] = for h <- headers; b <- text yield Sent(Url, h + ("Authorization" -> "Bearer FAKE-KEY"), b)

  // ── A model on a scripted transport ──

  val chat: LmRequest = LmRequest(model = "m", messages = Vector(Message(MessageRole.User, Some("Hi"))))

  val chatReply: String =
    """{"id":"c1","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},""" +
      """"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""

  def delta(text: String, finish: String = "null"): String =
    val reason = if finish == "null" then "null" else s"\"$finish\""
    s"""data: {"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":$reason}]}"""

  def modelOf(transport: HttpTransport): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = "m",
      client = OpenAiClient(apiKey = "FAKE-KEY", baseUrl = "http://test.invalid/v1", transport = transport)
    )
