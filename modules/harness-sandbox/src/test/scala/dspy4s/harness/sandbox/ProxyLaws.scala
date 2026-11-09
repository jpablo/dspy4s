package dspy4s.harness.sandbox

import munit.FunSuite

import java.net.{InetAddress, Socket}
import java.nio.charset.StandardCharsets.ISO_8859_1

/** The laws of the proxy (5.4). The tests talk to the proxy over a socket, with no sandbox. */
class ProxyLaws extends FunSuite:

  private val listed = HostList(Set("localhost"))

  /** Sends `text` to the proxy, then more text after the first answer, and gives all that the proxy sent back. */
  private def ask(proxy: HostProxy, text: String, after: Option[String] = None): String =
    val socket = Socket(InetAddress.getLoopbackAddress, proxy.port)
    try
      socket.setSoTimeout(10_000)
      socket.getOutputStream.write(text.getBytes(ISO_8859_1))
      after.foreach { more =>
        val head = new Array[Byte](39) // "HTTP/1.1 200 Connection Established\r\n\r\n"
        socket.getInputStream.readNBytes(head, 0, head.length): Unit
        socket.getOutputStream.write(more.getBytes(ISO_8859_1))
      }
      String(socket.getInputStream.readAllBytes(), ISO_8859_1)
    finally socket.close()

  private def withProxy(body: (HostProxy, TestServer) => Unit): Unit =
    val server = TestServer()
    val proxy  = HostProxy.start(listed)
    try body(proxy, server)
    finally
      proxy.close()
      server.close()

  test("Proxy: a plain request for a host on the list reaches the host") {
    withProxy { (proxy, server) =>
      val answer = ask(proxy, s"GET http://localhost:${server.port}/a?b=1 HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(answer.startsWith("HTTP/1.1 200"), answer)
      assert(answer.endsWith("FAKE-SERVER"), answer)
    }
  }

  test("Proxy: CONNECT for a host on the list opens a tunnel to the host") {
    withProxy { (proxy, server) =>
      val answer = ask(
        proxy,
        s"CONNECT localhost:${server.port} HTTP/1.1\r\nHost: localhost\r\n\r\n",
        after = Some("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
      )
      assert(answer.startsWith("HTTP/1.1 200"), answer)
      assert(answer.endsWith("FAKE-SERVER"), answer)
    }
  }

  test("Proxy: a request for another host gets 403, and the proxy opens no connection") {
    withProxy { (proxy, server) =>
      val plain   = ask(proxy, s"GET http://127.0.0.1:${server.port}/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
      val connect = ask(proxy, s"CONNECT 127.0.0.1:${server.port} HTTP/1.1\r\n\r\n")
      assert(plain.startsWith("HTTP/1.1 403"), plain)
      assert(connect.startsWith("HTTP/1.1 403"), connect)
      assert(plain.contains("127.0.0.1 is not on the host list"), plain)
      assertEquals(server.count, 0)
    }
  }

  test("Proxy: a host on the list that does not answer gets 502") {
    withProxy { (proxy, _) =>
      val closed = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val port   = closed.getLocalPort
      closed.close()
      val answer = ask(proxy, s"GET http://localhost:$port/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(answer.startsWith("HTTP/1.1 502"), answer)
    }
  }

  test("Proxy: a request with no absolute http URL gets 400") {
    withProxy { (proxy, server) =>
      val answer = ask(proxy, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(answer.startsWith("HTTP/1.1 400"), answer)
      assertEquals(server.count, 0)
    }
  }
