package dspy4s.harness.inspect

import dspy4s.harness.host.ExchangeFile
import dspy4s.harness.kernel.SessionId
import zio.blocks.schema.json.Json

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.{ExecutorService, Executors}
import scala.util.Try
import scala.util.control.NonFatal

/** The local web server of the inspector (docs/code-harness/OBSERVABILITY.md, 5.1). It only reads the files of its root
  * (`Store`), and it answers only on 127.0.0.1.
  */
final class Inspector private (server: HttpServer, executor: ExecutorService, val port: Int):

  /** The address of the list of the sessions. */
  def url: String = s"http://127.0.0.1:$port/"

  def close(): Unit =
    server.stop(0)
    executor.shutdownNow(): Unit

object Inspector:

  /** The port of the inspector when `--port` does not give one (9.2, choice 3). */
  val DefaultPort = 7411

  /** Starts the inspector of the root `root` on `127.0.0.1:port`. The port 0 takes a free port. */
  def start(root: Path, port: Int = DefaultPort): Either[String, Inspector] =
    Try {
      val server   = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
      val bound    = server.getAddress.getPort
      val routes   = Routes(Store(root), bound)
      val executor = Executors.newVirtualThreadPerTaskExecutor()
      server.createContext("/", (exchange: HttpExchange) => routes.handle(exchange))
      server.setExecutor(executor)
      server.start()
      Inspector(server, executor, bound)
    }.toEither.left.map(e => s"the inspector did not start on 127.0.0.1:$port: ${e.getMessage}")

/** A reply of the inspector. A page gets the policy of its scripts with its nonce. A reply with `download` gets the
  * header that tells a browser to save it as a file with that name.
  */
private[inspect] final case class Reply(
    status     : Int,
    contentType: String,
    body       : String,
    page       : Boolean        = false,
    download   : Option[String] = None
)

/** The routes of the inspector (5.1, 5.2 and 5.5).
  *
  *   - Only a request whose `Host` header is `127.0.0.1:<port>` or `localhost:<port>` gets data, so a web page that
  *     uses DNS rebinding gets 403. Only `GET` gets data, else 405. No reply has a CORS header.
  *   - A page has a policy that lets only the script with its nonce run, so a script that a text could make does not
  *     run.
  *   - A session id must be valid and must have a journal, and a probe name must name a probe journal, else 404. So a
  *     path cannot leave the root.
  */
private[inspect] final class Routes(store: Store, port: Int):
  private val random = SecureRandom()

  def handle(exchange: HttpExchange): Unit =
    try
      val seed = new Array[Byte](16)
      random.nextBytes(seed)
      val nonce = Base64.getUrlEncoder.withoutPadding.encodeToString(seed)
      val reply =
        try
          answer(
            exchange.getRequestMethod,
            Option(exchange.getRequestHeaders.getFirst("Host")),
            exchange.getRequestURI,
            nonce
          )
        catch case NonFatal(e) => Reply(500, "text/plain", s"The inspector failed: $e")
      val headers = exchange.getResponseHeaders
      headers.set("Content-Type", s"${reply.contentType}; charset=utf-8")
      headers.set("Cache-Control", "no-store")
      headers.set("X-Content-Type-Options", "nosniff")
      headers.set("Referrer-Policy", "no-referrer")
      if reply.status == 405 then headers.set("Allow", "GET")
      reply.download.foreach(name => headers.set("Content-Disposition", s"""attachment; filename="$name""""))
      if reply.page then
        headers.set("X-Frame-Options", "DENY")
        headers.set(
          "Content-Security-Policy",
          s"default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; connect-src 'self'; " +
            "img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        )
      val bytes = reply.body.getBytes(UTF_8)
      exchange.sendResponseHeaders(reply.status, if bytes.isEmpty then -1 else bytes.length.toLong)
      if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
    finally exchange.close()

  /** The reply to a request with `method`, the `Host` header `host`, and `uri`. */
  def answer(method: String, host: Option[String], uri: URI, nonce: String): Reply =
    if !host.exists(h => h == s"127.0.0.1:$port" || h == s"localhost:$port") then Reply(403, "text/plain", "")
    else if method != "GET" then Reply(405, "text/plain", "Only GET.")
    else
      val parts = uri.getRawPath.split('/').toList.filter(_.nonEmpty)
      parts match
        case Nil                            => page(Right(Pages.sessions(store, nonce)))
        case "s" :: id :: Nil               => withSession(id)(s => page(Pages.session(store, s, nonce)))
        case "s" :: id :: "x" :: xid :: Nil => withSession(id) { s =>
            ExchangePage.html(store, s, xid, query(uri).get("against"), nonce).fold(missing)(html => page(Right(html)))
          }
        case "s" :: id :: "after" :: seq :: Nil => withSession(id) { s =>
            seq.toLongOption.fold(missing) { from =>
              val size = query(uri).get("size").flatMap(_.toLongOption).getOrElse(-1L)
              Pages.after(store, s, from, size).fold(failed, Reply(200, "text/html", _))
            }
          }
        case "p" :: name :: Nil                    => if store.hasProbe(name) then page(Pages.probe(store, name, nonce)) else missing
        case "raw" :: id :: "journal.jsonl" :: Nil =>
          withSession(id)(s => saved(uri, s"${s.value}.journal.jsonl", journal(s)))
        case "raw" :: id :: "http.jsonl" :: Nil =>
          withSession(id)(s => saved(uri, s"${s.value}.http.jsonl", exchangeFile(s)))
        case "raw" :: id :: "x" :: xid :: "request" :: Nil => withSession(id) { s =>
            exchange(s, xid).fold(missing)(x =>
              saved(uri, s"${x.id}.request.json", Reply(200, "application/json", x.body))
            )
          }
        case "raw" :: id :: "x" :: xid :: "reply" :: Nil => withSession(id) { s =>
            exchange(s, xid).fold(missing) { x =>
              saved(uri, s"${x.id}.reply.txt", Reply(200, "text/plain", x.replied.mkString("\n")))
            }
          }
        case _ => missing

  private val missing: Reply = Reply(404, "text/plain", "Not found.")

  private def failed(error: String): Reply = Reply(500, "text/plain", error)

  private def page(html: Either[String, String]): Reply = html.fold(failed, Reply(200, "text/html", _, page = true))

  private def withSession(id: String)(run: SessionId => Reply): Reply =
    if SessionId.valid(id) && store.has(SessionId(id)) then run(SessionId(id)) else missing

  /** `reply` with the name `name` to save it as, when the query of `uri` has `download=1` and the reply has data. A raw
    * path has a text type, so a browser shows it, and this name lets it save the same bytes as a file (5.5). The name
    * keeps only ASCII letters, digits, `-`, `_` and `.`, because an exchange id comes from a file.
    */
  private def saved(uri: URI, name: String, reply: Reply): Reply =
    if reply.status == 200 && query(uri).get("download").contains("1") then
      reply.copy(download = Some(name.map(c => if c.isLetterOrDigit && c < 128 || "-_.".contains(c) then c else '_')))
    else reply

  private def exchange(session: SessionId, id: String): Option[Exchange] =
    store.exchanges(session)._1.find(_.id == id)

  /** Each row of the journal as one JSON line: its `seq`, its `required` flag, and its entry as it is in the file. */
  private def journal(session: SessionId): Reply =
    store.rows(session, 0L).fold(
      failed,
      rows =>
        val lines = rows.map { row =>
          val entry = Json.parse(row.text).getOrElse(Json.String(row.text))
          Json.Object("seq" -> Json.Number(row.seq), "required" -> Json.Boolean(row.required), "entry" -> entry).print
        }
        Reply(200, "text/plain", lines.map(_ + "\n").mkString)
    )

  private def exchangeFile(session: SessionId): Reply =
    val path = ExchangeFile.pathOf(store.root, session)
    if !Files.isRegularFile(path) then Reply(200, "text/plain", "")
    else Reply(200, "text/plain", Files.readString(path, UTF_8))

  private def query(uri: URI): Map[String, String] =
    Option(uri.getRawQuery).toVector.flatMap(_.split('&')).flatMap { pair =>
      pair.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
