package dspy4s.harness.inspect

import dspy4s.harness.host.SessionFile
import dspy4s.harness.kernel.{EffectResult, Entry, Logged, SessionId, Source, Taint}
import zio.blocks.schema.json.Json

import Html.escape

/** The three views of a journal row (docs/code-harness/OBSERVABILITY.md, 5.3): the Scala value, the JSON of the file,
  * and a formatted card. Each text of a view goes through `Html.escape` before it goes into a page.
  */
object EntryViews:

  /** The Scala view: the entry of the decoded row, or the error of its decode. The bar of the row (`html`) shows its
    * `seq` and each field of its meta, so the view leaves them out.
    */
  def scala(row: SessionFile.Row): String =
    row.logged.fold(error => s"The row ${row.seq} does not decode: $error", logged => ScalaText.of(logged.entry))

  /** The JSON view: the `entry` field of the row as it is in the file, with indents. The bar of the row shows its `seq`
    * and its meta, as for the Scala view. A row that does not decode shows its whole text, so its meta stays in view,
    * and a text that is not JSON stays as it is.
    */
  def json(row: SessionFile.Row): String = jsonOf(row).fold(row.text)(JsonText.pretty)

  /** The JSON value of the view of `row` (`json`), or none for a text that is not JSON. The value comes from the text
    * of the file and not from a new encode of the decoded row, so the view keeps what the file has.
    */
  private def jsonOf(row: SessionFile.Row): Option[Json] =
    Json.parse(row.text).toOption.map { whole =>
      if row.logged.isLeft then whole
      else whole.fields.collectFirst { case ("entry", value) => value }.getOrElse(whole)
    }

  /** The depth of an entry in the tree of a session: 0 for the parts of a session and of a turn, 1 for a step and for
    * the entries that can come at any place, and 2 for the parts of a step.
    */
  def depth(entry: Entry): Int =
    entry match
      case Entry.Activated(_, _) | Entry.Input(_, _, _) | Entry.TurnStarted | Entry.TurnEnded(_) | Entry.Ended(_, _) |
          Entry.Releasing(_) | Entry.Released(_) => 0
      case Entry.StepStarted(_) | Entry.StopRequested(_) | Entry.JobsStopRequested(_, _) | Entry.Granted(_, _, _) |
          Entry.ScopeRecorded(_, _) | Entry.ModelChanged(_) => 1
      case Entry.Acted(_, _, _) | Entry.EffectStarted(_, _, _) | Entry.EffectDone(_, _) | Entry.Observed(_, _, _) => 2

  /** The card of an entry. The `match` covers each case of `Entry`, so a new case does not compile until it has a card.
    */
  def card(logged: Logged): String =
    logged.entry match
      case Entry.Activated(versions, config) => facts(
          "model"     -> config.model.model,
          "kind"      -> config.kind.toString,
          "steps"     -> config.steps.toString,
          "strategy"  -> config.strategy.toString,
          "owner"     -> ScalaText.line(config.owner),
          "scope"     -> ScalaText.line(config.scope),
          "goal"      -> config.goal.getOrElse("none"),
          "compiler"  -> versions.compiler,
          "guest API" -> versions.guestApi
        ) + folded("The preamble", config.preamble)
      case Entry.Input(request, text, values) => paragraph(text) +
          (if values.isEmpty then "" else facts(values.toVector.map((k, v) => k -> v.print)*)) +
          note(s"request ${request.value}")
      case Entry.TurnStarted                  => "<h2>Turn</h2>"
      case Entry.StepStarted(step)            => s"<h2>Step $step</h2>"
      case Entry.Acted(reasoning, text, code) =>
        (if reasoning.trim.isEmpty then "" else folded("Reasoning", reasoning)) + paragraph(text) +
          code.fold("")(c => s"<pre><code>${escape(c)}</code></pre>")
      case Entry.EffectStarted(id, op, args) =>
        s"<p><code>${escape(op)}</code> <code>${escape(args.print)}</code></p>" + note(s"call ${id.cell}.${id.n}")
      case Entry.EffectDone(id, result) =>
        val shown = result match
          case EffectResult.Value(_, rendered)  => s"<pre>${escape(rendered)}</pre>"
          case EffectResult.Error(message)      => s"""<p class="error">${escape(message)}</p>"""
          case EffectResult.NeedsApproval(rule) => paragraph(s"The call needs an approval: ${ScalaText.line(rule)}")
          case EffectResult.Unknown             => paragraph("Unknown: a stop left the call open.")
        shown + note(s"call ${id.cell}.${id.n}")
      case Entry.Observed(output, outcome, writes) => paragraph(outcome.words) + s"<pre>${escape(output)}</pre>" +
          (if writes.isEmpty then "" else note(s"store: ${writes.keys.toVector.sorted.mkString(", ")}"))
      case Entry.StopRequested(reason)            => paragraph(s"A stop: $reason")
      case Entry.JobsStopRequested(which, reason) => paragraph(s"A stop of jobs ${ScalaText.line(which)}: $reason")
      case Entry.Granted(request, rule, lifetime) => paragraph(s"Granted ${ScalaText.line(rule)} ${lifetime.words}") +
          note(s"request ${request.value}")
      case Entry.ScopeRecorded(head, clean) =>
        paragraph(s"The scope is at $head, ${if clean then "with no change" else "with changes"}.")
      case Entry.ModelChanged(model) => paragraph("The model of the session changed. The next step uses it.") +
          facts(("model" -> model.model) +: model.options.toVector.sortBy(_._1).map((k, v) => k -> v.print)*)
      case Entry.Releasing(plan) => "<p>The release plan:</p><ul>" +
          plan.map(item => s"<li>${escape(ScalaText.line(item))}</li>").mkString + "</ul>"
      case Entry.Released(step)         => paragraph(ScalaText.line(step))
      case Entry.TurnEnded(outcome)     => paragraph(outcome.report)
      case Entry.Ended(outcome, reason) => paragraph(outcome.report(reason))

  /** The HTML of a row: its meta line, the buttons of its views, and its three views. A row that does not decode has
    * the kind `Undecoded`, and its card holds the error.
    */
  def html(row: SessionFile.Row): String =
    val (kind, depthOf, classes, meta, shown) = row.logged match
      case Right(logged) =>
        val source  = logged.meta.source.productPrefix
        val outside = logged.meta.taint == Taint.External
        val marks   = s"src-$source" + (if outside then " taint-external" else "")
        val line    = s"""<span class="time">${Html.time(logged.meta.atMillis)}</span>""" +
          s"""<span class="src">${escape(source)}</span>""" + sender(logged.meta.source) +
          (if outside then """<span class="taint">external</span>""" else "")
        (logged.entry.productPrefix, depth(logged.entry), marks, line, card(logged))
      case Left(error) =>
        ("Undecoded", 0, "", "", s"""<p class="error">${escape(s"The row does not decode: $error")}</p>""")
    val views = """<span class="views"><button data-v="card">card</button><button data-v="scala">scala</button>""" +
      """<button data-v="json">json</button></span>"""
    s"""<section class="row entry kind-$kind depth-$depthOf $classes" id="e${row.seq}" data-seq="${row.seq}">""" +
      s"""<div class="meta"><span class="seq">#${row.seq}</span><span class="kind">$kind</span>$meta""" +
      (if row.required then "" else """<span class="note">not required</span>""") + views + "</div>" +
      s"""<div class="v v-card">$shown</div>""" +
      s"""<pre class="v v-scala">${row.logged.fold(
          _ => escape(scala(row)),
          _ => Highlight.html(Highlight.scala(scala(row)))
        )}</pre>""" +
      s"""<pre class="v v-json">${jsonOf(row).fold(escape(row.text))(value =>
          Highlight.html(Highlight.json(JsonText.pretty(value)))
        )}</pre></section>"""

  /** The fields of a source with fields, for the bar: the session of a child, and the session and the call of a parent,
    * with a link to the page of the session.
    */
  private def sender(source: Source): String =
    def link(session: SessionId) = s"""<a href="/s/${escape(session.value)}">${escape(session.value)}</a>"""
    source match
      case Source.Child(session)          => s"""<span class="src-of">${link(session)}</span>"""
      case Source.Parent(session, effect) =>
        s"""<span class="src-of">${link(session)} call ${effect.cell}.${effect.n}</span>"""
      case _ => ""

  private def paragraph(text: String): String = s"<p>${escape(text)}</p>"

  private def note(text: String): String = s"""<p class="note">${escape(text)}</p>"""

  private def folded(title: String, text: String): String =
    s"<details><summary>${escape(title)}</summary><pre>${escape(text)}</pre></details>"

  private def facts(pairs: (String, String)*): String =
    pairs.map((k, v) => s"<dt>${escape(k)}</dt><dd>${escape(v)}</dd>").mkString("<dl>", "", "</dl>")
