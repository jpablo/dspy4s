package dspy4s.lm.providers

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmMode
import munit.FunSuite

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The time limits of the JDK transport (upstream lm15 `Timeouts`: connect 10 s and read 600 s by default; the read
  * limit bounds each wait for data, also between the lines of a stream). The tests use the loopback interface only.
  */
class HttpTimeoutsSuite extends FunSuite:

  private val loopback: InetAddress = InetAddress.getLoopbackAddress

  private val shortLimits: HttpTimeouts = HttpTimeouts(connect = 2.seconds, read = 300.millis)

  /** Read the head of an HTTP request, then its body by the `Content-Length` header. */
  private def readRequest(in: InputStream): Unit =
    val head = new StringBuilder
    while !head.endsWith("\r\n\r\n") do
      val next = in.read()
      if next < 0 then return
      val _ = head.append(next.toChar)
    val length = head.toString.linesIterator
      .collectFirst { case line if line.toLowerCase.startsWith("content-length:") => line.drop(15).trim.toInt }
      .getOrElse(0)
    val _ = in.readNBytes(length)

  /** One chunk of a chunked HTTP body. */
  private def chunk(text: String): String =
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    s"${bytes.length.toHexString}\r\n$text\r\n"

  private val streamHead: String =
    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"

  private def write(out: OutputStream, text: String): Unit =
    out.write(text.getBytes(StandardCharsets.UTF_8))
    out.flush()

  /** Run `use` with the port of a server that answers one request with `respond`. */
  private def withServer[A](respond: OutputStream => Unit)(use: Int => A): A =
    val server = new ServerSocket(0, 8, loopback)
    val thread = new Thread(() =>
      try
        val socket = server.accept()
        try
          readRequest(socket.getInputStream)
          respond(socket.getOutputStream)
        finally socket.close()
      catch case NonFatal(_) => ()
    )
    thread.setDaemon(true)
    thread.start()
    try use(server.getLocalPort)
    finally server.close()

  private def url(port: Int): String = s"http://${loopback.getHostAddress}:$port/v1/chat/completions"

  private def content(text: String): String =
    s"""data: {"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":null}]}\n\n"""

  /** The lines of a stream response and the failure of the transport, and the time that the read took. */
  private def readAll(response: HttpStreamResponse): (Vector[String], Option[dspy4s.core.contracts.DspyError], Long) =
    val started = System.nanoTime()
    val lines   = ArrayBuffer.empty[String]
    while response.dataLines.hasNext do lines += response.dataLines.next()
    (lines.toVector, response.failure(), (System.nanoTime() - started) / 1_000_000L)

  // ── The limits ──────────────────────────────────────────────────────────────────────────────────────────────

  test("the default limits are the upstream defaults") {
    assertEquals(HttpTimeouts(), HttpTimeouts(connect = 10.seconds, read = 600.seconds))
  }

  test("a limit that is not positive is rejected") {
    val _ = intercept[IllegalArgumentException](HttpTimeouts(connect = 0.seconds))
    val _ = intercept[IllegalArgumentException](HttpTimeouts(read = -1.seconds))
  }

  // ── Streams ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("a stream with no new line in the read limit ends, and its failure is an LmTimeoutError") {
    withServer { out =>
      write(out, streamHead + chunk(content("Hi")))
      Thread.sleep(3000)
    } { port =>
      HttpTransport.jdk(shortLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          val (lines, failure, millis) = readAll(response)
          assert(lines.exists(_.contains("Hi")), lines.toString)
          assert(failure.exists(_.isInstanceOf[LmTimeoutError]), failure.toString)
          assert(millis < 2500L, s"the transport must end the stream after the read limit, not after $millis ms")
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  test("a stream that sends lines inside the read limit does not end, also when it is longer than the limit") {
    withServer { out =>
      write(out, streamHead)
      (1 to 8).foreach { i =>
        Thread.sleep(100)
        write(out, chunk(content(s"part$i")))
      }
      write(out, chunk("data: [DONE]\n\n") + "0\r\n\r\n")
    } { port =>
      HttpTransport.jdk(shortLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          val (lines, failure, _) = readAll(response)
          assertEquals(lines.count(_.contains("part")), 8)
          assertEquals(failure, None)
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  test("a stream whose connection closes in the middle of the body ends, and its failure is an LmTransportError") {
    withServer { out =>
      write(out, streamHead + chunk(content("Hi")))
      // No last chunk: the connection closes in the middle of the body.
    } { port =>
      HttpTransport.jdk(shortLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          val (_, failure, _) = readAll(response)
          assert(failure.exists(_.isInstanceOf[LmTransportError]), failure.toString)
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  test("the OpenAI client reports the timeout of the transport, not a stream assembly error") {
    withServer { out =>
      write(out, streamHead + chunk(content("Hi")))
      Thread.sleep(3000)
    } { port =>
      val client = OpenAiClient(
        apiKey = "x",
        baseUrl = s"http://${loopback.getHostAddress}:$port/v1",
        transport = HttpTransport.jdk(shortLimits)
      )
      client.stream(LmMode.Chat, DynamicValues.record("model" := "m")) match
        case Right(stream) =>
          val chunks = stream.toVector
          val error  = chunks.lastOption.flatMap(_.error)
          assert(error.exists(_.isInstanceOf[LmTimeoutError]), chunks.toString)
          assert(!error.exists(_.isInstanceOf[LmStreamAssemblyError]), chunks.toString)
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  // ── Plain calls ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The head of a plain reply with a body of `length` bytes. */
  private def plainHead(length: Int): String =
    s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $length\r\n\r\n"

  test("a plain call whose body stops after the headers ends after the read limit with an LmTimeoutError") {
    withServer { out =>
      write(out, plainHead(100) + """{"choices":""")
      Thread.sleep(3000)
    } { port =>
      val started = System.nanoTime()
      val result  = HttpTransport.jdk(shortLimits).sendJson(url(port), Map.empty, "{}")
      val millis  = (System.nanoTime() - started) / 1_000_000L
      assert(result.left.exists(_.isInstanceOf[LmTimeoutError]), result.toString)
      assert(millis < 2500L, s"the transport must end the call after the read limit, not after $millis ms")
    }
  }

  test("a plain call whose body comes in parts inside the read limit gets the whole body") {
    val parts = Vector("""{"text": """, "\"caf", "é", " au", " lait\"", ", ", """"n": 1""", "}")
    withServer { out =>
      write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n")
      parts.foreach { part =>
        Thread.sleep(100)
        write(out, chunk(part))
      }
      write(out, "0\r\n\r\n")
    } { port =>
      val result = HttpTransport.jdk(shortLimits).sendJson(url(port), Map.empty, "{}")
      assertEquals(result.map(response => (response.status, response.body)), Right((200, parts.mkString)))
    }
  }

  test("a plain call whose connection closes in the middle of the body is an LmTransportError") {
    withServer { out =>
      write(out, plainHead(100) + """{"choices":""")
    } { port =>
      val result = HttpTransport.jdk(shortLimits).sendJson(url(port), Map.empty, "{}")
      assert(result.left.exists(_.isInstanceOf[LmTransportError]), result.toString)
    }
  }

  // ── The release of the resources ───────────────────────────────────────────────────────────────────────────

  /** Run `use` with the port of a server that sends `head` and then no more data, and with a latch that the server
    * counts down when the client closes the connection.
    */
  private def withStalledServer[A](head: String)(use: (Int, CountDownLatch) => A): A =
    val released = new CountDownLatch(1)
    val server   = new ServerSocket(0, 8, loopback)
    val thread   = new Thread(() =>
      try
        val socket = server.accept()
        try
          readRequest(socket.getInputStream)
          write(socket.getOutputStream, head)
          socket.setSoTimeout(5000)
          val closedByClient =
            try socket.getInputStream.read() < 0
            catch case _: SocketException => true
          if closedByClient then released.countDown()
        finally socket.close()
      catch case NonFatal(_) => ()
    )
    thread.setDaemon(true)
    thread.start()
    try use(server.getLocalPort, released)
    finally server.close()

  /** Wait 2 seconds at most until no reader thread of the JDK transport is alive, and give `true` if none is alive. */
  private def readerThreadsEnd(): Boolean =
    def alive: Boolean =
      Thread.getAllStackTraces.keySet.asScala.exists(thread => thread.getName == "dspy4s-sse-reader" && thread.isAlive)
    val deadline = System.nanoTime() + 2_000_000_000L
    while alive && System.nanoTime() < deadline do Thread.sleep(20)
    !alive

  test("after a timeout and after close, the transport closes the connection and its reader thread ends") {
    withStalledServer(streamHead + chunk(content("Hi"))) { (port, released) =>
      HttpTransport.jdk(shortLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          val (_, failure, _) = readAll(response)
          assert(failure.exists(_.isInstanceOf[LmTimeoutError]), failure.toString)
          assert(released.await(2, TimeUnit.SECONDS), "the connection stayed open after the timeout")
          assert(readerThreadsEnd(), "the reader thread is alive after the timeout")
        case Left(error) => fail(s"the stream did not open: $error")
    }
    withStalledServer(streamHead + chunk(content("Hi"))) { (port, released) =>
      val longLimits = HttpTimeouts(connect = 2.seconds, read = 10.seconds)
      HttpTransport.jdk(longLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          assert(response.dataLines.hasNext)
          assert(response.dataLines.next().contains("Hi"))
          response.dataLines.close()
          assert(!response.dataLines.hasNext)
          assertEquals(response.failure(), None)
          assert(released.await(2, TimeUnit.SECONDS), "the connection stayed open after close")
          assert(readerThreadsEnd(), "the reader thread is alive after close")
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  test("after the timeout of a plain call, the transport closes the connection") {
    withStalledServer(plainHead(100) + """{"choices":""") { (port, released) =>
      val result = HttpTransport.jdk(shortLimits).sendJson(url(port), Map.empty, "{}")
      assert(result.left.exists(_.isInstanceOf[LmTimeoutError]), result.toString)
      assert(released.await(2, TimeUnit.SECONDS), "the connection stayed open after the timeout")
    }
  }

  test("close does not wait for an open stream that nobody reads, and the stream then fails") {
    // Upstream `StdlibTransport.close` closes the connections in use (`dspy/_vendor/lm15/transports/_sync.py`).
    withStalledServer(streamHead + chunk(content("Hi"))) { (port, released) =>
      val transport = HttpTransport.jdk(HttpTimeouts(connect = 2.seconds, read = 10.seconds))
      transport.streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          try
            val closer = new Thread(() => transport.close())
            closer.setDaemon(true)
            closer.start()
            closer.join(2000)
            assert(!closer.isAlive, "close waits for the open stream")
            val (_, failure, millis) = readAll(response)
            assert(failure.exists(_.isInstanceOf[LmTransportError]), failure.toString)
            assert(millis < 2000L, s"the stream must fail after close, not after $millis ms")
            assert(released.await(2, TimeUnit.SECONDS), "the connection stayed open after close")
          finally response.dataLines.close() // A failed check must not leave the reader thread for the next test.
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }

  test("after the end of the lines, the reader thread ends") {
    withServer { out =>
      write(out, streamHead + chunk(content("Hi")) + chunk("data: [DONE]\n\n") + "0\r\n\r\n")
    } { port =>
      HttpTransport.jdk(shortLimits).streamSse(url(port), Map.empty, "{}") match
        case Right(response) =>
          val (lines, failure, _) = readAll(response)
          assert(lines.exists(_.contains("[DONE]")), lines.toString)
          assertEquals(failure, None)
          assert(readerThreadsEnd(), "the reader thread is alive after the end of the lines")
        case Left(error) => fail(s"the stream did not open: $error")
    }
  }
