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

  /** The page of a session: its rows in a tree, with each exchange after its entry. The page follows the session. */
  def session(store: Store, session: SessionId, nonce: String): Either[String, String] =
    store.rows(session, 0L).map { rows =>
      val (exchanges, size) = store.exchanges(session)
      val row               = store.row(session)
      val title             = row.map(_.firstMessage).filter(_.nonEmpty).getOrElse(session.value)
      // The page updates the status and the two counts when it follows the session.
      val facts = Vector(
        Some(escape(session.value)),
        row.map(r => escape(r.folder)),
        Some(s"""<span id="status">${escape(row.fold("")(_.status.toString))}</span>"""),
        Some(s"""<span id="count-entries">${rows.size}</span> entries"""),
        Some(s"""<span id="count-exchanges">${exchanges.size}</span> exchanges""")
      ).flatten.mkString(" · ")
      val raw = s"""<span class="group">Raw: <a href="/raw/${session.value}/journal.jsonl">journal.jsonl</a> """ +
        s"""<a href="/raw/${session.value}/http.jsonl">http.jsonl</a></span>"""
      val bar  = header(title, facts, controls(withPredictors = true) + raw)
      val main = s"""<main id="rows" data-session="${session.value}" data-last="${lastOf(rows)}" data-size="$size">""" +
        tree(session, rows, exchanges) + "</main>"
      Html.page(s"Session ${session.value.take(8)}", nonce, bar + main)
    }

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

  private def controls(withPredictors: Boolean): String =
    val views = """<span class="group">View: <button data-view="card" class="on">Card</button>""" +
      """<button data-view="scala">Scala</button><button data-view="json">JSON</button></span>"""
    val kinds = Html.kinds.map(k => s"""<label><input type="checkbox" data-hide="kind-$k" checked> $k</label>""")
      .mkString("""<div class="group">Kinds: """, "", "</div>")
    val predictors =
      if !withPredictors then ""
      else
        Html.predictors.map(p => s"""<label><input type="checkbox" data-hide="pred-$p" checked> $p</label>""")
          .mkString("""<div class="group">Exchanges: """, "", "</div>")
    val follow = if withPredictors then """<label><input type="checkbox" id="follow" checked> Follow</label>""" else ""
    views + follow + s"""<details class="filters"><summary>Filters</summary>$kinds$predictors</details>"""
