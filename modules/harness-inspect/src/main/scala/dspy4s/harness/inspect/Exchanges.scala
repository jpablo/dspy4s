package dspy4s.harness.inspect

import dspy4s.harness.kernel.SessionId
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import Html.escape

/** One exchange of an exchange file (docs/code-harness/OBSERVABILITY.md, 4.3): its request line, its reply line if it
  * has one, and its place in the file. An exchange with no reply line is open.
  */
final case class Exchange(index: Int, request: Json, reply: Option[Json]):
  import Exchanges.{bool, number, strings, text}

  def id: String                = text(request, "id").getOrElse("")
  def after: Long               = number(request, "after").getOrElse(-1L)
  def predictor: Option[String] = text(request, "predictor")
  def probe: Option[String]     = text(request, "probe")
  def stream: Boolean           = bool(request, "stream").getOrElse(false)
  def at: Option[Long]          = number(request, "at")
  def body: String              = text(request, "body").getOrElse("")
  def url: String               = text(request, "url").getOrElse("")

  /** The headers of the request, with their masks. */
  def requestHeaders: Map[String, String] =
    Exchanges.field(request, "headers").toVector.flatMap(_.fields.toVector).collect {
      case (name, Json.String(value)) => name -> value
    }.toMap

  /** The headers of the reply, or none before the status. */
  def replyHeaders: Map[String, String] =
    reply.flatMap(Exchanges.field(_, "headers")).toVector.flatMap(_.fields.toVector).collect {
      case (name, Json.String(value)) => name -> value
    }.toMap
  def first: Option[Long]   = reply.flatMap(number(_, "first"))
  def ended: Option[Long]   = reply.flatMap(number(_, "at"))
  def end: Option[String]   = reply.flatMap(text(_, "end"))
  def status: Option[Long]  = reply.flatMap(number(_, "status"))
  def error: Option[String] = reply.flatMap(text(_, "error"))
  def open: Boolean         = reply.isEmpty

  /** The lines of the reply of a stream, or the body of a plain reply in one line. */
  def replied: Vector[String] =
    reply.fold(Vector.empty)(r => if stream then strings(r, "lines") else text(r, "body").toVector)

  /** The tokens of the usage of the reply: the prompt and the completion. A stream has its usage in a late line. */
  def usage: Option[(Long, Long)] =
    def of(json: Json): Option[(Long, Long)] =
      Exchanges.field(json, "usage").flatMap(u => number(u, "prompt_tokens").zip(number(u, "completion_tokens")))
    if stream then
      replied.reverseIterator.map(_.trim).filter(_.startsWith("data:")).map(_.stripPrefix("data:").trim)
        .flatMap(line => Json.parse(line).toOption.flatMap(of)).nextOption()
    else replied.headOption.flatMap(Json.parse(_).toOption).flatMap(of)

object Exchanges:

  /** The exchanges of the file at `path` in the order of their request lines, and the size of the file. A line that is
    * not JSON is skipped: the last line of a file that a process wrote when it stopped can be cut.
    */
  def read(path: Path): (Vector[Exchange], Long) =
    if !Files.isRegularFile(path) then (Vector.empty, 0L)
    else
      val size  = Try(Files.size(path)).getOrElse(0L)
      val lines = Try(Files.readAllLines(path, UTF_8).asScala.toVector).getOrElse(Vector.empty)
        .flatMap(line => Json.parse(line).toOption)
      val replies  = lines.filter(text(_, "kind").contains("reply")).flatMap(r => text(r, "id").map(_ -> r)).toMap
      val requests = lines.filter(text(_, "kind").contains("request"))
      (requests.zipWithIndex.map((r, i) => Exchange(i, r, text(r, "id").flatMap(replies.get))), size)

  /** The row of an exchange in the tree of a session, after the entry of its `after`. The `request` and `reply` links
    * open the page of the exchange on that view. A raw path shows the exact text with no layout, so no row links to it.
    */
  def html(session: SessionId, x: Exchange): String =
    val predictor = x.predictor.getOrElse("none")
    val parts     = facts(x)
    val base      = s"/s/${session.value}/x/${escape(x.id)}"
    val links     = s"""<a href="$base?tab=request">request</a><a href="$base?tab=reply">reply</a>"""
    s"""<section class="row exchange pred-${escape(predictor)} depth-2${if x.open then " open" else ""}" id="x${escape(
        x.id
      )}" """ +
      s"""data-after="${x.after}"><div class="meta">""" +
      s"""<a class="kind" href="$base">exchange</a>""" +
      s"""<span class="pred">${escape(predictor)}</span>""" +
      parts.map(p => s"<span>${escape(p)}</span>").mkString + links + "</div></section>"

  /** The facts of an exchange in words: the probe, stream or plain, the status, the end, the times, the bytes, the
    * tokens and the error. The row in the tree and the page of the exchange show them.
    */
  def facts(x: Exchange): Vector[String] =
    val seconds = (from: Option[Long], to: Option[Long]) => from.zip(to).map((a, b) => f"${(b - a) / 1000.0}%.2f s")
    Vector(
      x.probe.map(p => s"probe $p"),
      Some(if x.stream then "stream" else "plain"),
      x.status.map(s => s"status $s"),
      x.end.orElse(Option.when(x.open)("open")),
      seconds(x.at, x.first).map(s => s"first line $s"),
      seconds(x.at, x.ended).map(s => s"all $s"),
      Some(s"${x.body.getBytes(UTF_8).length} → ${x.replied.map(_.getBytes(UTF_8).length).sum} bytes"),
      x.usage.map((p, c) => s"$p → $c tokens"),
      x.error.map(e => s"error: $e")
    ).flatten

  def field(json: Json, key: String): Option[Json] = json.fields.collectFirst { case (k, v) if k == key => v }

  def text(json: Json, key: String): Option[String] = field(json, key).collect { case Json.String(value) => value }

  def number(json: Json, key: String): Option[Long] =
    field(json, key).collect { case Json.Number(value) => value }.flatMap(n => Try(n.toLong).toOption)

  def bool(json: Json, key: String): Option[Boolean] = field(json, key).collect { case Json.Boolean(value) => value }

  def strings(json: Json, key: String): Vector[String] =
    field(json, key).toVector.flatMap(_.elements.toVector).collect { case Json.String(value) => value }
