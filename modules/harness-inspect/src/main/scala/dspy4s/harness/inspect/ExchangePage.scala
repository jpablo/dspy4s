package dspy4s.harness.inspect

import dspy4s.harness.kernel.SessionId
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8

import Html.escape

/** The page of one exchange (docs/code-harness/OBSERVABILITY.md, 5.4): the chat view, the exact request, the exact
  * reply, and the diff of its request against the request of another exchange.
  */
object ExchangePage:

  /** The page of the exchange `id` of `session`, or none for an id that the exchange file does not have. The diff is
    * against the exchange `against`, else against the last earlier exchange of the same predictor.
    */
  def html(store: Store, session: SessionId, id: String, against: Option[String], nonce: String): Option[String] =
    val (exchanges, _) = store.exchanges(session)
    exchanges.find(_.id == id).map { x =>
      val target = against.flatMap(a => exchanges.find(e => e.id == a && e.id != x.id))
        .orElse(exchanges.filter(e => e.index < x.index && e.predictor == x.predictor).lastOption)
      val tab   = if against.isDefined then "diff" else "chat"
      val title = store.row(session).map(_.firstMessage).filter(_.nonEmpty).getOrElse(session.value)
      val bar   = header(session, x, exchanges, title, tab)
      val views = s"""<section class="x-view x-chat">${Chat.html(x)}</section>""" +
        s"""<section class="x-view x-request">${request(session, x)}</section>""" +
        s"""<section class="x-view x-reply">${reply(session, x)}</section>""" +
        s"""<section class="x-view x-diff">${diff(x, target, exchanges)}</section>"""
      Html.page(
        s"Exchange ${x.index + 1} of ${session.value.take(8)}",
        nonce,
        bar + s"<main>$views</main>",
        s"tab-$tab"
      )
    }

  private def header(session: SessionId, x: Exchange, all: Vector[Exchange], title: String, tab: String): String =
    val back = s"/s/${session.value}#x${escape(x.id)}"
    val near = (Vector(x.index - 1 -> "earlier", x.index + 1 -> "later")).flatMap { (i, name) =>
      all.lift(i).map(e => s"""<a href="/s/${session.value}/x/${escape(e.id)}">$name</a>""")
    }
    val facts = Exchanges.facts(x).map(escape).mkString(" · ")
    val tabs  = Vector("chat" -> "Chat", "request" -> "Request", "reply" -> "Reply", "diff" -> "Diff").map {
      (key, name) =>
        s"""<button data-tab="$key"${if key == tab then " class=\"on\"" else ""}>$name</button>"""
    }.mkString("""<span class="group">""", "", "</span>")
    s"""<header class="bar"><div class="facts"><a href="/">Sessions</a> / <a href="$back">${escape(
        title
      )}</a></div>""" +
      s"""<h1>Exchange ${x.index + 1} of ${all.size} · ${escape(x.predictor.getOrElse("none"))}</h1>""" +
      s"""<div class="facts">$facts · after #${x.after}</div><div class="controls">$tabs""" +
      s"""<span class="group">${near.mkString(" ")}</span>""" +
      "</div></header>"

  /** The URL, the headers, the code view of the body with indents, and the buttons that copy and save the exact body.
    */
  private def request(session: SessionId, x: Exchange): String =
    val headers = x.requestHeaders.map((k, v) => s"<dt>${escape(k)}</dt><dd>${escape(v)}</dd>").mkString
    s"<dl><dt>URL</dt><dd>${escape(x.url)}</dd>$headers<dt>body</dt><dd>${x.body.getBytes(UTF_8).length} bytes</dd></dl>" +
      tools(session, x, "request") + Highlight.code(pretty(x.body).linesIterator.toVector) +
      s"""<pre id="request-exact" hidden>${escape(x.body)}</pre>"""

  /** The status, the headers, the code view of the reply (each line of a stream, or the body with indents), and the
    * buttons that copy and save the exact reply.
    */
  private def reply(session: SessionId, x: Exchange): String =
    if x.open then """<p class="note">The exchange has no reply yet.</p>"""
    else
      val lines = if x.stream then x.replied else pretty(x.replied.mkString("\n")).linesIterator.toVector
      val facts =
        Vector(x.status.map(s => "status" -> s.toString), x.end.map(e => "end" -> e), x.error.map(e => "error" -> e))
          .flatten ++ x.replyHeaders.toVector.sortBy(_._1)
      facts.map((k, v) => s"<dt>${escape(k)}</dt><dd>${escape(v)}</dd>").mkString("<dl>", "", "</dl>") +
        tools(session, x, "reply") + Highlight.code(lines) +
        s"""<pre id="reply-exact" hidden>${escape(x.replied.mkString("\n"))}</pre>"""

  /** The button that copies the exact `item` (`request` or `reply`) of `x` from its hidden copy, and the link that
    * saves it as a file (5.5).
    */
  private def tools(session: SessionId, x: Exchange, item: String): String =
    val saved = s"/raw/${session.value}/x/${escape(x.id)}/$item?download=1"
    s"""<p class="tools"><button data-copy="$item-exact">Copy</button> <a class="button" href="$saved">Download</a></p>"""

  /** The diff of the text of the request of `target` and of `x` (`Chat.requestLines`), in parts with three lines around
    * each change, and a choice of the exchange to compare with.
    */
  private def diff(x: Exchange, target: Option[Exchange], all: Vector[Exchange]): String =
    val choices = all.filterNot(_.id == x.id).map { e =>
      val chosen = if target.exists(_.id == e.id) then " selected" else ""
      s"""<option value="${escape(e.id)}"$chosen>#${e.index + 1} ${escape(e.predictor.getOrElse("none"))}</option>"""
    }
    val none   = if target.isEmpty then """<option value="" selected>none</option>""" else ""
    val select = s"""<p><label>Against: <select id="against">$none${choices.mkString}</select></label></p>"""
    target match
      case None        => select + """<p class="note">No earlier exchange of this predictor.</p>"""
      case Some(other) =>
        val edits   = LineDiff.diff(Chat.requestLines(other.body), Chat.requestLines(x.body))
        val added   = edits.count { case Edit.Add(_) => true; case _ => false }
        val removed = edits.count { case Edit.Remove(_) => true; case _ => false }
        val hunks   = LineDiff.hunks(edits)
        val summary =
          s"""<p class="note">Against exchange ${other.index + 1}: $added lines added, $removed lines removed.</p>"""
        if hunks.isEmpty then select + summary + """<p class="note">The two requests have the same text.</p>"""
        else select + summary + hunks.map(hunk).mkString

  private def hunk(h: Hunk): String =
    val lines = h.edits.map {
      case Edit.Keep(line)   => s"""<span class="d ctx">  ${escape(line)}</span>"""
      case Edit.Remove(line) => s"""<span class="d del">- ${escape(line)}</span>"""
      case Edit.Add(line)    => s"""<span class="d add">+ ${escape(line)}</span>"""
    }
    s"""<div class="hunk"><div class="hunk-head">@@ −${h.fromA} +${h.fromB} @@</div>""" +
      s"""<pre class="diff">${lines.mkString}</pre></div>"""

  /** `text` with indents when it is JSON, else as it is. */
  private def pretty(text: String): String = Json.parse(text).fold(_ => text, JsonText.pretty)
