package dspy4s.harness.sandbox

import java.io.ByteArrayOutputStream
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets.ISO_8859_1

/** The laws of the servers of the tests (Support.scala). The escape probes need a server that answers each real client,
  * or the control of the probes fails by chance.
  */
class TestServerLaws extends munit.FunSuite:

  test("Test server: the UNIX server answers a client that sends its request after a pause") {
    val unix = UnixServer()
    try
      val channel = SocketChannel.open(UnixDomainSocketAddress.of(unix.path))
      try
        // `curl` connects and then sends. On a slow host, the server can run first, so the test waits on purpose.
        Thread.sleep(300)
        channel.write(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(ISO_8859_1))): Unit
        val answer = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(1024)
        while channel.read(buffer) >= 0 do
          answer.write(buffer.array(), 0, buffer.position())
          buffer.clear(): Unit
        assert(answer.toString(ISO_8859_1).contains("FAKE-UNIX"), answer.toString(ISO_8859_1))
      finally channel.close()
    finally unix.close()
  }
