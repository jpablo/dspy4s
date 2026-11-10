package dspy4s.harness.sandbox

import munit.FunSuite

import java.net.{InetAddress, Socket}
import java.nio.charset.StandardCharsets.ISO_8859_1

/** The laws of the proxy (5.4). The tests talk to the proxy over a socket, with no sandbox. */
class ProxyLaws extends FunSuite:

  private val listed = HostList(Set("localhost:*"))

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

  test("Proxy: the Host header comes from the URL, not from the client") {
    // With a client Host header, a shared front such as a CDN can send the request to a host that is not on the list.
    withProxy { (proxy, server) =>
      ask(proxy, s"GET http://localhost:${server.port}/ HTTP/1.1\r\nHost: unlisted.example\r\n\r\n"): Unit
      val head = server.lastHead
      assert(head.linesIterator.contains(s"Host: localhost:${server.port}"), head)
      assert(!head.contains("unlisted.example"), head)
    }
  }

  test("Proxy: a host on the list at a port that the list does not allow gets 403, and no connection") {
    val server = TestServer()
    val proxy  = HostProxy.start(HostList(Set("localhost")))
    try
      val plain   = ask(proxy, s"GET http://localhost:${server.port}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      val connect = ask(proxy, s"CONNECT localhost:${server.port} HTTP/1.1\r\n\r\n")
      assert(plain.startsWith("HTTP/1.1 403"), plain)
      assert(connect.startsWith("HTTP/1.1 403"), connect)
      assertEquals(server.count, 0)
    finally
      proxy.close()
      server.close()
  }

  test("Proxy: a connection above the limit gets 503 at once, and the proxy goes on after the others close") {
    val server = TestServer()
    val proxy  = HostProxy.start(listed, limit = 2)
    val idle   = Vector.fill(2)(Socket(InetAddress.getLoopbackAddress, proxy.port))
    try
      Thread.sleep(200) // the two idle connections hold the two places
      val over = ask(proxy, s"GET http://localhost:${server.port}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(over.startsWith("HTTP/1.1 503"), over)
      idle.foreach(_.close())
      Thread.sleep(200)
      val after = ask(proxy, s"GET http://localhost:${server.port}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(after.startsWith("HTTP/1.1 200"), after)
    finally
      idle.foreach(_.close())
      proxy.close()
      server.close()
  }

  test("Proxy: a silent host on the list ends at the idle time, and its place comes free") {
    // A host that takes the connection and never answers must not hold a place of the proxy.
    val silent = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val server = TestServer()
    val proxy  = HostProxy.start(listed, limit = 1, idle = scala.concurrent.duration.Duration(1, "second"))
    try
      val start = System.nanoTime()
      ask(proxy, s"GET http://localhost:${silent.getLocalPort}/ HTTP/1.1\r\nHost: localhost\r\n\r\n"): Unit
      assert(System.nanoTime() - start < 6_000_000_000L, "the proxy waited for the silent host too long")
      Thread.sleep(300)
      val after = ask(proxy, s"GET http://localhost:${server.port}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(after.startsWith("HTTP/1.1 200"), after)
    finally
      proxy.close()
      server.close()
      silent.close()
  }

  test("Proxy: a download goes on while the client is silent, also longer than the idle time") {
    // The idle time ends a connection only when no byte moves in either direction.
    val slow   = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val sender = Thread(() =>
      try
        val socket = slow.accept()
        try
          val in   = socket.getInputStream
          var tail = 0
          while tail != 0x0d0a0d0a do tail = (tail << 8) | in.read()
          val out = socket.getOutputStream
          out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes(ISO_8859_1))
          (1 to 8).foreach { _ =>
            Thread.sleep(300)
            out.write('x')
          }
        finally socket.close()
      catch case _: java.io.IOException => ()
    )
    sender.setDaemon(true)
    sender.start()
    val proxy = HostProxy.start(listed, idle = scala.concurrent.duration.Duration(1, "second"))
    try
      val answer = ask(proxy, s"GET http://localhost:${slow.getLocalPort}/ HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(answer.endsWith("xxxxxxxx"), answer)
    finally
      proxy.close()
      slow.close()
  }

  test("Proxy: a request with no absolute http URL gets 400") {
    withProxy { (proxy, server) =>
      val answer = ask(proxy, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
      assert(answer.startsWith("HTTP/1.1 400"), answer)
      assertEquals(server.count, 0)
    }
  }
