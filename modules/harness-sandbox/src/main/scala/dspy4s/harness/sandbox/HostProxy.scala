package dspy4s.harness.sandbox

import java.io.{BufferedInputStream, ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketTimeoutException, URI}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors, RejectedExecutionException, Semaphore}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

/** An HTTP proxy on the loopback address (CODE_HARNESS_DESIGN.md, 5.4). It takes a plain request with an absolute
  * `http` URL, and `CONNECT` for TLS. It lets a request through only when the list allows its host at its port, and it
  * answers 403 for another host or port with no connection to that host. The proxy resolves the names, so a child needs
  * no DNS: a Seatbelt profile with the network lets a child reach only the port of this proxy.
  *
  * The proxy serves at most `limit` connections at a time, each on its own thread. A connection above the limit gets
  * 503 at once, and the proxy closes it. A place comes free when a connection ends. A connection ends when no byte
  * moves in either direction for the time `idle`, or when one side of the relay ends with another error. So a silent
  * host on the list does not hold a place, and its place comes free in about two idle times. A side that is silent
  * while the other side sends does not end the connection, for example the client during a long download.
  */
final class HostProxy private (
    hosts : HostList,
    server: ServerSocket,
    pool  : ExecutorService,
    places: Semaphore,
    idle  : FiniteDuration
) extends AutoCloseable:

  /** The port on the loopback address. */
  def port: Int = server.getLocalPort

  def close(): Unit =
    server.close()
    pool.shutdownNow(): Unit

  private def acceptAll(): Unit =
    try
      while !server.isClosed do
        val client = server.accept()
        if places.tryAcquire() then
          try
            pool.execute(() =>
              try serve(client)
              finally places.release()
            )
          catch
            case _: RejectedExecutionException => // the proxy closed
              places.release()
              client.close()
        else busy(client)
    catch case _: IOException => () // the server closed

  /** Answers 503 to a connection above the limit, on the thread that accepts. Before the close, the proxy reads what
    * the client sent, for a short time: a close with unread bytes resets the connection, and the client can then lose
    * the answer.
    */
  private def busy(client: Socket): Unit =
    try
      HostProxy.reply(client.getOutputStream, 503, "Service Unavailable", "The proxy has no free connection.")
      client.shutdownOutput()
      val deadline = System.nanoTime() + HostProxy.BusyNanos
      val buffer   = new Array[Byte](4096)
      var open     = true
      while open && System.nanoTime() < deadline do
        client.setSoTimeout(((deadline - System.nanoTime()) / 1_000_000).max(1L).toInt)
        open = client.getInputStream.read(buffer) >= 0
    catch case _: IOException => ()
    finally client.close()

  private def serve(client: Socket): Unit =
    try
      client.setSoTimeout(idle.toMillis.toInt)
      val in = BufferedInputStream(client.getInputStream)
      HostProxy.readHead(in).map(_.split("\r\n").toVector) match
        case Some(first +: headers) => first.split(" ").toVector match
            case Vector(method, target, _) if method.equalsIgnoreCase("CONNECT") => tunnel(client, in, target)
            case Vector(method, target, version)                                 => forward(client, in, method, target, version, headers)
            case _                                                               => HostProxy.reply(client.getOutputStream, 400, "Bad Request", "The proxy cannot read this request.")
        case _ => ()
    catch case _: IOException => ()
    finally client.close()

  /** CONNECT host:port: a tunnel of bytes, for TLS. */
  private def tunnel(client: Socket, in: InputStream, target: String): Unit =
    HostProxy.hostAndPort(s"http://$target", 443) match
      case None                                            => HostProxy.reply(client.getOutputStream, 400, "Bad Request", s"No host in $target.")
      case Some((host, port)) if !hosts.allows(host, port) => refuse(client, host, port)
      case Some((host, port))                              => HostProxy.connect(host, port, idle) match
          case None           => unreachable(client, host)
          case Some(upstream) =>
            client.getOutputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(ISO_8859_1))
            relay(client, in, upstream)

  /** A plain request: the proxy sends it to the host in origin form, with `Connection: close`. The proxy drops each
    * `Host` header of the client and sends the host and the port of the URL that it checked. With the `Host` header of
    * the client, a shared front such as a CDN can send the request to a host that is not on the list. The proxy refuses
    * a folded header, because a folded line after a dropped header joins the header before it.
    */
  private def forward(
      client : Socket,
      in     : InputStream,
      method : String,
      target : String,
      version: String,
      headers: Vector[String]
  ): Unit =
    val uri = Try(URI(target)).toOption.filter(u => "http".equalsIgnoreCase(u.getScheme))
    uri.flatMap(u => HostProxy.hostAndPort(target, 80).map((u, _))) match
      case None =>
        HostProxy.reply(client.getOutputStream, 400, "Bad Request", "The proxy takes an absolute http URL, or CONNECT.")
      case Some(_) if headers.exists(h => h.startsWith(" ") || h.startsWith("\t")) =>
        HostProxy.reply(client.getOutputStream, 400, "Bad Request", "The proxy does not take a folded header.")
      case Some((_, (host, port))) if !hosts.allows(host, port) => refuse(client, host, port)
      case Some((u, (host, port)))                              =>
        val path    = Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/") + Option(u.getRawQuery).fold("")("?" + _)
        val kept    = headers.filterNot(HostProxy.dropped)
        val request = (s"$method $path $version" +: HostProxy.hostHeader(host, port) +: kept :+ "Connection: close")
          .mkString("", "\r\n", "\r\n\r\n")
        HostProxy.connect(host, port, idle) match
          case None           => unreachable(client, host)
          case Some(upstream) =>
            upstream.getOutputStream.write(request.getBytes(ISO_8859_1))
            relay(client, in, upstream)

  /** Copies the bytes both ways. The end of the answer closes both sockets. The end of the request closes only the
    * request direction, so the answer can still come. An error on one side, also the idle time, closes both sockets.
    */
  private def relay(client: Socket, in: InputStream, upstream: Socket): Unit =
    val moved = AtomicLong(System.nanoTime()) // the time of the last byte in either direction
    val back  = CompletableFuture.runAsync(
      () =>
        try HostProxy.copy(upstream.getInputStream, client.getOutputStream, moved, idle)
        finally
          upstream.close()
          client.close()
      ,
      pool
    )
    try
      HostProxy.copy(in, upstream.getOutputStream, moved, idle)
      upstream.shutdownOutput()
    catch
      case _: IOException =>
        upstream.close()
        client.close()
    Try(back.join()): Unit

  private def unreachable(client: Socket, host: String): Unit =
    HostProxy.reply(client.getOutputStream, 502, "Bad Gateway", s"The proxy cannot reach $host.")

  private def refuse(client: Socket, host: String, port: Int): Unit =
    HostProxy.reply(
      client.getOutputStream,
      403,
      "Forbidden",
      s"$host is not on the host list of this session at the port $port."
    )

object HostProxy:

  /** Starts a proxy for `hosts` on a free port of the loopback address, with at most `limit` connections at a time. Its
    * threads do not keep the JVM alive.
    */
  def start(hosts: HostList, limit: Int = 64, idle: FiniteDuration = 60.seconds): HostProxy =
    require(limit > 0, "the proxy needs a place for one connection")
    require(idle.toMillis > 0 && idle.toMillis <= Int.MaxValue, "the idle time must be a positive time in milliseconds")
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val pool   = Executors.newCachedThreadPool { (task: Runnable) =>
      val thread = Thread(task, "dspy4s-host-proxy")
      thread.setDaemon(true)
      thread
    }
    val proxy = HostProxy(hosts, server, pool, Semaphore(limit), idle)
    pool.execute(() => proxy.acceptAll())
    proxy

  private val ConnectMillis = 10_000
  private val HeadLimit     = 64 * 1024
  private val BusyNanos     = 500_000_000L // the time to read a client above the limit
  private val End           = 0x0d0a0d0a   // the four bytes of "\r\n\r\n"

  /** True for a header that the proxy does not send: a header for this hop only, and `Host`, which comes from the URL.
    * The name ends at the colon, with no case.
    */
  private def dropped(header: String): Boolean =
    val name = header.takeWhile(_ != ':').trim.toLowerCase(Locale.ROOT)
    name == "host" || name == "connection" || name == "keep-alive" || name.startsWith("proxy-")

  /** The `Host` header of a URL: no port for the port 80, and an IPv6 address in brackets. */
  private def hostHeader(host: String, port: Int): String =
    val name = if host.contains(':') then s"[$host]" else host
    if port == 80 then s"Host: $name" else s"Host: $name:$port"

  /** The head of a request, up to the empty line, or none when the stream ends first or the head is too long. */
  private def readHead(in: InputStream): Option[String] =
    val bytes  = ByteArrayOutputStream()
    var window = 0 // the last four bytes
    var open   = true
    while open && window != End && bytes.size < HeadLimit do
      val byte = in.read()
      if byte < 0 then open = false
      else
        bytes.write(byte)
        window = (window << 8) | byte
    Option.when(window == End)(bytes.toString(ISO_8859_1).stripSuffix("\r\n\r\n"))

  /** The host, with no brackets for IPv6, and the port of a URL. */
  private def hostAndPort(url: String, defaultPort: Int): Option[(String, Int)] =
    Try(URI(url)).toOption.flatMap { uri =>
      Option(uri.getHost).map(h =>
        (h.stripPrefix("[").stripSuffix("]"), if uri.getPort < 0 then defaultPort else uri.getPort)
      )
    }

  /** A connection to the host, with the read timeout `idle`, or none when the name does not resolve or the host does
    * not answer.
    */
  private def connect(host: String, port: Int, idle: FiniteDuration): Option[Socket] =
    val socket = Socket()
    Try {
      socket.connect(InetSocketAddress(host, port), ConnectMillis)
      socket.setSoTimeout(idle.toMillis.toInt)
    }.toOption.map(_ => socket).orElse {
      socket.close()
      None
    }

  /** Copies `in` to `out` up to the end of `in`, and keeps the time of the last byte in `moved`. A read timeout ends
    * the copy with an error only when no byte moved in either direction for `idle`.
    */
  private def copy(in: InputStream, out: OutputStream, moved: AtomicLong, idle: FiniteDuration): Unit =
    val buffer = new Array[Byte](8192)
    var open   = true
    while open do
      try
        val n = in.read(buffer)
        if n < 0 then open = false
        else
          out.write(buffer, 0, n)
          moved.set(System.nanoTime())
      catch case _: SocketTimeoutException if System.nanoTime() - moved.get < idle.toNanos => ()

  private def reply(out: OutputStream, code: Int, reason: String, text: String): Unit =
    val body = (text + "\n").getBytes(UTF_8)
    val head = s"HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
      s"Content-Length: ${body.length}\r\nConnection: close\r\n\r\n"
    out.write(head.getBytes(ISO_8859_1))
    out.write(body)
    out.flush()
