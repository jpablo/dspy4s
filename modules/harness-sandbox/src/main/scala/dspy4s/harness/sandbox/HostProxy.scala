package dspy4s.harness.sandbox

import java.io.{BufferedInputStream, ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, URI}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.Locale
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import scala.util.Try

/** An HTTP proxy on the loopback address (CODE_HARNESS_DESIGN.md, 5.4). It takes a plain request with an absolute
  * `http` URL, and `CONNECT` for TLS. It lets a request through only when its host is on the list, and it answers 403
  * for another host with no connection to that host. The proxy resolves the names, so a child needs no DNS: a Seatbelt
  * profile with the network lets a child reach only the port of this proxy.
  */
final class HostProxy private (hosts: HostList, server: ServerSocket, pool: ExecutorService) extends AutoCloseable:

  /** The port on the loopback address. */
  def port: Int = server.getLocalPort

  def close(): Unit =
    server.close()
    pool.shutdownNow(): Unit

  private def acceptAll(): Unit =
    try
      while !server.isClosed do
        val client = server.accept()
        pool.execute(() => serve(client))
    catch case _: IOException => () // the server closed

  private def serve(client: Socket): Unit =
    try
      client.setSoTimeout(HostProxy.IdleMillis)
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
      case None                                   => HostProxy.reply(client.getOutputStream, 400, "Bad Request", s"No host in $target.")
      case Some((host, _)) if !hosts.allows(host) => refuse(client, host)
      case Some((host, port))                     => HostProxy.connect(host, port) match
          case None           => unreachable(client, host)
          case Some(upstream) =>
            client.getOutputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(ISO_8859_1))
            relay(client, in, upstream)

  /** A plain request: the proxy sends it to the host in origin form, with `Connection: close`. */
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
      case Some((_, (host, _))) if !hosts.allows(host) => refuse(client, host)
      case Some((u, (host, port)))                     =>
        val path    = Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/") + Option(u.getRawQuery).fold("")("?" + _)
        val kept    = headers.filterNot(h => HostProxy.Dropped.exists(h.toLowerCase(Locale.ROOT).startsWith))
        val request = (s"$method $path $version" +: kept :+ "Connection: close").mkString("", "\r\n", "\r\n\r\n")
        HostProxy.connect(host, port) match
          case None           => unreachable(client, host)
          case Some(upstream) =>
            upstream.getOutputStream.write(request.getBytes(ISO_8859_1))
            relay(client, in, upstream)

  /** Copies the bytes both ways. The end of the answer closes both sockets. */
  private def relay(client: Socket, in: InputStream, upstream: Socket): Unit =
    val back = CompletableFuture.runAsync(
      () =>
        try HostProxy.copy(upstream.getInputStream, client.getOutputStream)
        finally
          upstream.close()
          client.close()
      ,
      pool
    )
    try
      HostProxy.copy(in, upstream.getOutputStream)
      upstream.shutdownOutput()
    catch case _: IOException => ()
    Try(back.join()): Unit

  private def unreachable(client: Socket, host: String): Unit =
    HostProxy.reply(client.getOutputStream, 502, "Bad Gateway", s"The proxy cannot reach $host.")

  private def refuse(client: Socket, host: String): Unit =
    HostProxy.reply(client.getOutputStream, 403, "Forbidden", s"$host is not on the host list of this session.")

object HostProxy:

  /** Starts a proxy for `hosts` on a free port of the loopback address. Its threads do not keep the JVM alive. */
  def start(hosts: HostList): HostProxy =
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val pool   = Executors.newCachedThreadPool { (task: Runnable) =>
      val thread = Thread(task, "dspy4s-host-proxy")
      thread.setDaemon(true)
      thread
    }
    val proxy = HostProxy(hosts, server, pool)
    pool.execute(() => proxy.acceptAll())
    proxy

  private val IdleMillis    = 60_000
  private val ConnectMillis = 10_000
  private val HeadLimit     = 64 * 1024
  private val End           = 0x0d0a0d0a // the four bytes of "\r\n\r\n"

  /** The headers that stay with the proxy: they are for this hop only. */
  private val Dropped = Vector("proxy-", "connection:", "keep-alive:")

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

  /** A connection to the host, or none when the name does not resolve or the host does not answer. */
  private def connect(host: String, port: Int): Option[Socket] =
    val socket = Socket()
    Try(socket.connect(InetSocketAddress(host, port), ConnectMillis)).toOption.map(_ => socket).orElse {
      socket.close()
      None
    }

  private def copy(in: InputStream, out: OutputStream): Unit = in.transferTo(out): Unit

  private def reply(out: OutputStream, code: Int, reason: String, text: String): Unit =
    val body = (text + "\n").getBytes(UTF_8)
    val head = s"HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
      s"Content-Length: ${body.length}\r\nConnection: close\r\n\r\n"
    out.write(head.getBytes(ISO_8859_1))
    out.write(body)
    out.flush()
