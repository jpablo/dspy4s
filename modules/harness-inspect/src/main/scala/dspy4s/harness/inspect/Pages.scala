package dspy4s.harness.inspect

import dspy4s.harness.host.SessionFile
import dspy4s.harness.kernel.SessionId

import Html.escape

/** The pages of the inspector (docs/code-harness/OBSERVABILITY.md, 5.2). */
object Pages:

  /** The list of the sessions of the root, the newest first, with the probes of each session. */
  def sessions(store: Store, nonce: String): String =
    val rows = store.sessions.map { row =>
      val id     = row.session.value
      val probes = store.probes(row.session)
        .map(name => s"""<a href="/p/${escape(name)}">${escape(name.stripPrefix(s"$id."))}</a>""").mkString(" ")
      val first = if row.firstMessage.isEmpty then "(no message)" else row.firstMessage
      "<tr>" +
        s"<td>${Html.date(row.lastAt)}</td><td>${escape(row.status.toString)}</td>" +
        s"""<td class="first"><a href="/s/${escape(id)}">${escape(first)}</a>""" +
        (if probes.isEmpty then "" else s"""<div class="probes">probes: $probes</div>""") + "</td>" +
        s"<td>${escape(row.folder)}</td><td><code>${escape(id.take(8))}</code></td>" +
        s"<td>${row.parent.fold("")(p => s"""<a href="/s/${escape(p.value)}">${escape(p.value.take(8))}</a>""")}</td>" +
        "</tr>"
    }
    val table =
      if rows.isEmpty then "<p>The root has no session.</p>"
      else
        "<table><thead><tr><th>Last</th><th>Status</th><th>First message</th><th>Folder</th><th>Session</th>" +
          s"<th>Parent</th></tr></thead><tbody>${rows.mkString}</tbody></table>"
    Html.page(
      "Sessions",
      nonce,
      s"""<header class="bar"><h1>Sessions</h1><div class="facts">${escape(store.root.toString)}</div></header>""" +
        s"<main>$table</main>"
    )

  /** The most entries that the page of a session shows: the last ones. In a live run of 2026-11-16, the page of a
    * session with 502,350 entries filled a heap of 12 GB. The raw journal has all the rows.
    */
  val LastEntries: Int = 2_000

  /** The page of a session: the chat and the trajectory of its last `LastEntries` rows (5.7), and the same rows in a
    * tree, with each exchange after its entry. The page opens on the chat, and it follows the session. When the page
    * does not show all the entries, a note in the bar gives the two counts and links to the raw journal.
    */
  def session(store: Store, session: SessionId, nonce: String): Either[String, String] =
    store.last(session, LastEntries).map { (skipped, rows) =>
      val (all, size) = store.exchanges(session)
      // An exchange after an entry that the page does not show stays out too.
      val exchanges = if skipped == 0 then all else all.filter(_.after >= skipped)
      val row       = store.row(session)
      val title     = row.map(_.firstMessage).filter(_.nonEmpty).getOrElse(session.value)
      val whole     = skipped + rows.size
      // The page updates the status and the counts when it follows the session. The counts are of the whole session.
      val facts = Vector(
        Some(escape(session.value)),
        row.map(r => escape(r.folder)),
        Some(s"""<span id="status">${escape(row.fold("")(_.status.toString))}</span>"""),
        Some(s"""<span id="count-entries">$whole</span> entries"""),
        Some(s"""<span id="count-exchanges">${all.size}</span> exchanges""")
      ).flatten.mkString(" · ") + """<span id="totals"></span>"""
      val base = s"/raw/${session.value}"
      val note =
        if skipped == 0 then ""
        else
          s"""<span class="cut">The page shows the last <span id="count-shown">${rows.size}</span> of """ +
            s"""<span id="count-all">$whole</span> entries. <a href="$base/journal.jsonl">journal.jsonl</a> has all """ +
            "of them.</span>"
      // The raw paths are in a menu, so the bar stays short (5.7).
      val raw = """<details class="files"><summary>Files</summary><div class="menu">""" +
        Html.rawLinks(base, Vector("journal.jsonl", "http.jsonl")) + "</div></details>"
      val bar     = header(title, facts, modes + controls(withPredictors = true) + note + raw)
      val prompts = promptsOf(store, session, exchanges, Long.MinValue)
      val story   = Story.html(session, Story.of(rows, exchanges, prompts), System.currentTimeMillis())
      val cols    = """<div class="r cols"><span>Kind</span><span>Summary</span><span class="n">Time</span>""" +
        """<span class="n">In → out</span><span class="n">Cached</span><span class="n">Speed</span></div>"""
      val main = s"""<main id="story" data-session="${session.value}">$cols$story</main>""" +
        s"""<main id="rows" data-session="${session.value}" data-last="${lastOf(rows)}" data-size="$size" """ +
        s"""data-skipped="$skipped">""" + tree(session, rows, exchanges) + "</main>"
      Html.page(s"Session ${session.value.take(8)}", nonce, bar + main, "mode-chat view-card")
    }

  /** The items of the chat and the trajectory from the entry `from` (5.7): the rows from `from`, and the exchanges
    * whose `after` is not below `from`. The follow of the page puts them in place of its items from that `seq`.
    * `skipped` is the first entry of the page, so a new prompt is new on the page as on its first load (5.8).
    */
  def story(store: Store, session: SessionId, from: Long, skipped: Long): Either[String, String] =
    store.rows(session, from).map { rows =>
      val (all, _) = store.exchanges(session)
      val shown    = if skipped <= 0L then all else all.filter(_.after >= skipped)
      val prompts  = promptsOf(store, session, shown, from)
      Story.html(session, Story.of(rows, all.filter(_.after >= from), prompts), System.currentTimeMillis())
    }

  /** The policy calls of `shown` with an `after` from `from` on that have a new prompt (5.8), by their ids. The
    * comparison starts at the first policy call of `shown`, so the first call of the page has a new prompt. Each one
    * has the last `Activated` at or before it.
    */
  private def promptsOf(store: Store, session: SessionId, shown: Vector[Exchange], from: Long)
      : Map[String, PromptMark] =
    val (earlier, later) = shown.filter(Prompt.policy).sortBy(_.after).partition(_.after < from)
    Prompt.starts(later, earlier.lastOption).map { (x, prompt, before) =>
      x.id -> PromptMark(x, prompt, before, store.activation(session, x.after))
    }.toMap

  /** The page of a probe: its journal, with no exchanges. The exchanges of a probe are in the file of its caller. */
  def probe(store: Store, name: String, nonce: String): Either[String, String] =
    store.probe(name).map { (session, rows) =>
      val facts = escape(s"the probe $name · session ${session.value} · ${rows.size} entries")
      val main  = s"""<main id="rows">${rows.map(EntryViews.html).mkString}</main>"""
      Html.page(s"Probe $name", nonce, header(s"Probe $name", facts, controls(withPredictors = false)) + main)
    }

  /** The rows after `seq` (5.2): each entry with a greater `seq`, in their order, the `seq` of the last one, and the
    * status of the session. When the size of the exchange file is not `size`, the fragment also has each exchange row
    * again, with the new size.
    */
  def after(store: Store, session: SessionId, seq: Long, size: Long): Either[String, String] =
    store.rows(session, seq + 1).map { rows =>
      val last    = if rows.isEmpty then seq else lastOf(rows)
      val status  = escape(store.row(session).fold("")(_.status.toString))
      val entries =
        s"""<div id="entries" data-last="$last" data-status="$status">${rows.map(EntryViews.html).mkString}</div>"""
      // The size comes first, so a poll with no new exchange does not read the file.
      val changed =
        if store.exchangeSize(session) == size then ""
        else
          val (exchanges, now) = store.exchanges(session)
          s"""<div id="exchanges" data-size="$now">${exchanges.map(Exchanges.html(session, _)).mkString}</div>"""
      entries + changed
    }

  /** The entries of `rows` in their order, each with the exchanges whose `after` is its `seq` or is between its `seq`
    * and the next one. An exchange with an `after` before the first entry comes first.
    */
  def tree(session: SessionId, rows: Vector[SessionFile.Row], exchanges: Vector[Exchange]): String =
    val seqs    = rows.map(_.seq)
    val placed  = exchanges.groupBy(x => seqs.filter(_ <= x.after).lastOption)
    val before  = placed.getOrElse(None, Vector.empty).map(Exchanges.html(session, _)).mkString
    val entries = rows.map(row =>
      EntryViews.html(row) + placed.getOrElse(Some(row.seq), Vector.empty).map(Exchanges.html(session, _)).mkString
    )
    before + entries.mkString

  private def lastOf(rows: Vector[SessionFile.Row]): Long = rows.lastOption.fold(-1L)(_.seq)

  private def header(title: String, facts: String, controls: String): String =
    s"""<header class="bar"><div class="facts"><a href="/">Sessions</a></div><h1>${escape(title)}</h1>""" +
      s"""<div class="facts">$facts</div><div class="controls">$controls</div></header>"""

  /** The buttons of the three views of a session page (5.7): the chat, the trajectory and the entries. */
  private val modes: String = """<span class="group">View: <button data-mode="chat" class="on">Chat</button>""" +
    """<button data-mode="trajectory">Trajectory</button><button data-mode="entries">Entries</button></span>"""

  private def controls(withPredictors: Boolean): String =
    val views = """<span class="group entries-only">Entry: <button data-view="card" class="on">Card</button>""" +
      """<button data-view="scala">Scala</button><button data-view="json">JSON</button></span>"""
    val kinds = Html.kinds.map(k => s"""<label><input type="checkbox" data-hide="kind-$k" checked> $k</label>""")
      .mkString("""<div class="group">Kinds: """, "", "</div>")
    val predictors =
      if !withPredictors then ""
      else
        Html.predictors.map(p => s"""<label><input type="checkbox" data-hide="pred-$p" checked> $p</label>""")
          .mkString("""<div class="group">Exchanges: """, "", "</div>")
    val follow = if withPredictors then """<label><input type="checkbox" id="follow" checked> Follow</label>""" else ""
    views + follow + s"""<details class="filters entries-only"><summary>Filters</summary>$kinds$predictors</details>"""
