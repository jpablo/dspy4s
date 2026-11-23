package dspy4s.harness.inspect

import dspy4s.harness.host.{ExchangeFile, SessionFile}
import dspy4s.harness.kernel.SessionId
import zio.blocks.schema.json.Json

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.io.{IOException, OutputStream}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
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

  /** The inspector of the root `root` for a client (5.6): one that starts on `port`, else the inspector of another
    * process that serves the same root on `port`, else one that starts on a free port. Two clients of one root so share
    * one inspector, and a client never stops the inspector of another client. The port 0 always starts a new one.
    */
  def serve(root: Path, port: Int = DefaultPort): Either[String, Served] =
    start(root, port) match
      case Right(started)           => Right(Served.Own(started))
      case Left(error) if port == 0 => Left(error)
      case Left(error)              =>
        if rootAt(port).contains(rootText(root)) then Right(Served.Shared(port))
        else start(root, 0).map(Served.Own(_)).left.map(again => s"$error; $again")

  /** The text of `/root`: the real path of `root`, so two paths of one folder give one text. */
  private[inspect] def rootText(root: Path): String =
    Try(root.toRealPath()).getOrElse(root.toAbsolutePath.normalize()).toString

  /** How long `serve` waits for the answer of the process on a port that it found taken. */
  private val AskMillis = 1_000L

  /** The root of the inspector on `port` of 127.0.0.1 (`/root`), or none when that port gives no such answer in time,
    * for example because another program holds it.
    */
  private def rootAt(port: Int): Option[String] =
    Try {
      val wait     = java.time.Duration.ofMillis(AskMillis)
      val client   = HttpClient.newBuilder().connectTimeout(wait).build()
      val request  = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/root")).timeout(wait).GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      Option.when(response.statusCode() == 200)(response.body())
    }.toOption.flatten

/** The inspector that a client shows with `/inspect` (docs/code-harness/OBSERVABILITY.md, 5.6). */
enum Served:

  /** An inspector that this client started. Its `close` stops it. */
  case Own(inspector: Inspector)

  /** The inspector of another process for the same root, on the port `at`. Its `close` does nothing. */
  case Shared(at: Int)

  /** The port of the inspector. */
  def port: Int =
    this match
      case Own(inspector) => inspector.port
      case Shared(at)     => at

  /** True when this client started the inspector, so it stops with the client. */
  def owned: Boolean =
    this match
      case Own(_)    => true
      case Shared(_) => false

  /** The address of the page of `session`. */
  def link(session: SessionId): String = s"http://127.0.0.1:$port/s/${session.value}"

  def close(): Unit =
    this match
      case Own(inspector) => inspector.close()
      case Shared(_)      => ()

/** A reply of the inspector. A page gets the policy of its scripts with its nonce. A reply with `download` gets the
  * header that tells a browser to save it as a file with that name.
  */
private[inspect] final case class Reply(
    status     : Int,
    contentType: String,
    body       : Body,
    page       : Boolean        = false,
    download   : Option[String] = None
)

/** The body of a reply. */
private[inspect] enum Body:

  /** A text. The reply has its exact length. */
  case Text(text: String)

  /** The bytes that `write` sends to the stream of the reply when it reads them. The reply is chunked and has no
    * length, so the server does not hold a whole file (5.5). `close` frees what `write` reads, for example an open
    * journal. It runs after `write`, also when `write` fails or does not run.
    */
  case Stream(write: OutputStream => Unit, close: () => Unit)

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
        catch case NonFatal(e) => Reply(500, "text/plain", Body.Text(s"The inspector failed: $e"))
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
      reply.body match
        case Body.Text(text) =>
          val bytes = text.getBytes(UTF_8)
          exchange.sendResponseHeaders(reply.status, if bytes.isEmpty then -1 else bytes.length.toLong)
          if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
        case Body.Stream(write, close) =>
          // The length 0 makes a chunked reply.
          try
            exchange.sendResponseHeaders(reply.status, 0)
            write(exchange.getResponseBody)
          finally close()
      exchange.close()
    // A failure after the headers must not look like a complete reply. `exchange.close()` sends the last chunk, so after
    // a failure only an exchange with no headers closes here. A later failure goes on to the server, and the server
    // closes the connection with no last chunk (JDK 21, `ServerImpl.Exchange.run`).
    finally if exchange.getResponseCode == -1 then exchange.close()

  /** The reply to a request with `method`, the `Host` header `host`, and `uri`. */
  def answer(method: String, host: Option[String], uri: URI, nonce: String): Reply =
    if !host.exists(h => h == s"127.0.0.1:$port" || h == s"localhost:$port") then
      Reply(403, "text/plain", Body.Text(""))
    else if method != "GET" then Reply(405, "text/plain", Body.Text("Only GET."))
    else
      val parts = uri.getRawPath.split('/').toList.filter(_.nonEmpty)
      parts match
        case Nil                            => page(Right(Pages.sessions(store, nonce)))
        case "root" :: Nil                  => Reply(200, "text/plain", Body.Text(Inspector.rootText(store.root)))
        case "s" :: id :: Nil               => withSession(id)(s => page(Pages.session(store, s, nonce)))
        case "s" :: id :: "x" :: xid :: Nil => withSession(id) { s =>
            val asked = query(uri)
            ExchangePage.html(store, s, xid, asked.get("against"), asked.get("tab"), nonce)
              .fold(missing)(html => page(Right(html)))
          }
        case "s" :: id :: "story" :: Nil => withSession(id) { s =>
            val asked   = query(uri)
            val skipped = asked.get("skipped").flatMap(_.toLongOption).getOrElse(0L)
            asked.get("from").flatMap(_.toLongOption).fold(missing) { from =>
              Pages.story(store, s, from, skipped).fold(failed, html => Reply(200, "text/html", Body.Text(html)))
            }
          }
        case "s" :: id :: "after" :: seq :: Nil => withSession(id) { s =>
            seq.toLongOption.fold(missing) { from =>
              val size = query(uri).get("size").flatMap(_.toLongOption).getOrElse(-1L)
              Pages.after(store, s, from, size).fold(failed, html => Reply(200, "text/html", Body.Text(html)))
            }
          }
        case "p" :: name :: Nil                    => if store.hasProbe(name) then page(Pages.probe(store, name, nonce)) else missing
        case "raw" :: id :: "journal.jsonl" :: Nil =>
          withSession(id)(s => saved(uri, s"${s.value}.journal.jsonl", journal(s)))
        case "raw" :: id :: "http.jsonl" :: Nil =>
          withSession(id)(s => saved(uri, s"${s.value}.http.jsonl", exchangeFile(s)))
        case "raw" :: id :: "x" :: xid :: "request" :: Nil => withSession(id) { s =>
            exchange(s, xid).fold(missing)(x =>
              saved(uri, s"${x.id}.request.json", Reply(200, "application/json", Body.Text(x.body)))
            )
          }
        case "raw" :: id :: "x" :: xid :: "reply" :: Nil => withSession(id) { s =>
            exchange(s, xid).fold(missing) { x =>
              saved(uri, s"${x.id}.reply.txt", Reply(200, "text/plain", Body.Text(x.replied.mkString("\n"))))
            }
          }
        case _ => missing

  private val missing: Reply = Reply(404, "text/plain", Body.Text("Not found."))

  private def failed(error: String): Reply = Reply(500, "text/plain", Body.Text(error))

  private def page(html: Either[String, String]): Reply =
    html.fold(failed, h => Reply(200, "text/html", Body.Text(h), page = true))

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

  /** Each row of the journal as one JSON line: its `seq`, its `required` flag, and its entry as it is in the file. The
    * journal opens before the headers go, so an error of its open gets the reply 500. Each line goes when the read gets
    * its row, so the server does not hold the whole journal. A read that fails later throws, so the reply has no last
    * chunk (`handle`).
    */
  private def journal(session: SessionId): Reply =
    store.journal(session).fold(
      failed,
      file =>
        def write(out: OutputStream): Unit =
          file.eachRow(0L)(row => out.write(s"${line(row)}\n".getBytes(UTF_8))).left.foreach(e => throw IOException(e))
        Reply(200, "text/plain", Body.Stream(write, () => file.close()))
    )

  /** The JSON line of `row` in the raw journal, with no end of line. */
  private def line(row: SessionFile.Row): String =
    val entry = Json.parse(row.text).getOrElse(Json.String(row.text))
    Json.Object("seq" -> Json.Number(row.seq), "required" -> Json.Boolean(row.required), "entry" -> entry).print

  /** The exchange file with its bytes as they are on the disk, or an empty text when the session has no file. The file
    * opens before the headers go, and its bytes go when the server reads them.
    */
  private def exchangeFile(session: SessionId): Reply =
    val path = ExchangeFile.pathOf(store.root, session)
    if !Files.isRegularFile(path) then Reply(200, "text/plain", Body.Text(""))
    else
      val in = Files.newInputStream(path)
      Reply(200, "text/plain", Body.Stream(out => in.transferTo(out): Unit, () => in.close()))

  private def query(uri: URI): Map[String, String] =
    Option(uri.getRawQuery).toVector.flatMap(_.split('&')).flatMap { pair =>
      pair.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
