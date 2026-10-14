package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import munit.FunSuite

import java.net.InetAddress
import java.net.ServerSocket
import scala.concurrent.duration.DurationInt

/** The JDK transport gives a failure without a response its LM error class. The tests use the loopback interface only.
  */
class JdkHttpTransportErrorSuite extends FunSuite:

  private val loopback: InetAddress = InetAddress.getLoopbackAddress

  /** A port on the loopback interface that has no listener. */
  private def closedPort(): Int =
    val socket = new ServerSocket(0, 1, loopback)
    try socket.getLocalPort
    finally socket.close()

  /** Runs `use` with the port of a listener that accepts a connection and never answers. */
  private def withSilentServer[A](use: Int => A): A =
    val socket = new ServerSocket(0, 8, loopback)
    try use(socket.getLocalPort)
    finally socket.close()

  private def url(port: Int): String = s"http://${loopback.getHostAddress}:$port/v1/chat/completions"

  private def failure[A](result: Either[DspyError, A]): DspyError =
    result match
      case Left(error) => error
      case Right(_)    => fail("expected a failure")

  test("a refused connection is LmTransportError, for a plain call and for a stream") {
    val transport = HttpTransport.jdk(HttpTimeouts(connect = 2.seconds, read = 2.seconds))
    val port      = closedPort()

    val plain = failure(transport.sendJson(url(port), Map.empty, "{}"))
    assert(plain.isInstanceOf[LmTransportError], plain.toString)
    assert(LmError.isRetryable(plain))

    val streamed = failure(transport.streamSse(url(port), Map.empty, "{}"))
    assert(streamed.isInstanceOf[LmTransportError], streamed.toString)
  }

  test("no response in the time limit is LmTimeoutError, for a plain call and for a stream") {
    val transport = HttpTransport.jdk(HttpTimeouts(connect = 2.seconds, read = 300.millis))
    withSilentServer { port =>
      val plain = failure(transport.sendJson(url(port), Map.empty, "{}"))
      assert(plain.isInstanceOf[LmTimeoutError], plain.toString)
      assert(LmError.isRetryable(plain))

      val streamed = failure(transport.streamSse(url(port), Map.empty, "{}"))
      assert(streamed.isInstanceOf[LmTimeoutError], streamed.toString)
    }
  }
