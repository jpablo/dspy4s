package dspy4s.harness.inspect

import dspy4s.harness.kernel.SessionId

import Html.escape

/** The item of a new prompt in the chat and the trajectory of a session (docs/code-harness/OBSERVABILITY.md, 5.8): a
  * `SYSTEM` row, and under it a box with a tab for each part of the prompt. A click on the row opens the box.
  *
  *   - `Instructions`: the system message up to its first fixed field.
  *   - A tab for each fixed field of the session: `Task`, and `API` with the calls of the API reference.
  *   - `Settings`: the request, its options, the place and the sizes of the parts, and the last `Activated`.
  *   - `Changes`, only for a prompt that changed: the diff of each part that changed. The box opens on this tab.
  */
object PromptView:

  /** The row and the box of `mark`. */
  def html(session: SessionId, mark: PromptMark): String =
    val prompt  = mark.prompt
    val calls   = prompt.calls
    val changed = mark.before.isDefined
    val size    = prompt.instructions.length + prompt.fields.map(_._2.length).sum
    val facts   = Vector(
      prompt.option("model").map(Prompt.words),
      prompt.option("reasoning_effort").map(e => s"effort ${Prompt.words(e)}"),
      Some(s"${Story.thousands(size)} characters"),
      Option.when(calls.nonEmpty)(Story.plural(calls.size, "API call"))
    ).flatten.mkString(" · ")
    val title =
      if changed then s"<b>The prompt changed:</b> ${escape(mark.changes.mkString(", "))}" else "<b>The prompt</b>"
    val head = s"""<summary class="r k-system"><span class="kind">SYSTEM</span><div class="sum">$title · """ +
      s"""${escape(facts)} <span class="link">${link(session, mark.call)}</span></div>""" +
      """<span class="n"></span>""" * 4 + "</summary>"
    val fields = prompt.fields.map((name, value) => (s"field-$name", label(name, calls), field(name, value, calls)))
    val tabs   = (("instructions", "Instructions", instructions(prompt.instructions)) +: fields) ++
      Vector(("settings", "Settings", settings(session, mark))) ++
      Option.when(changed)(("changes", "Changes", changes(session, mark)))
    val first   = if changed then "changes" else "instructions"
    val buttons = tabs.map { (key, name, _) =>
      s"""<button data-ptab="${escape(key)}"${if key == first then " class=\"on\"" else ""}>${escape(name)}</button>"""
    }.mkString("""<div class="ptabs">""", "", "</div>")
    val views = tabs.map { (key, _, body) =>
      s"""<div class="pview" data-pview="${escape(key)}"${if key == first then "" else " hidden"}>$body</div>"""
    }.mkString
    s"""<details class="prompt">$head<div class="pbox">$buttons$views</div></details>"""

  private def link(session: SessionId, x: Exchange): String =
    s"""<a href="/s/${escape(session.value)}/x/${escape(x.id)}">${escape(x.predictor.getOrElse("call"))}</a>"""

  /** The name of the tab of a field: `API` with the count of its calls for the API reference, `Task` for the task. */
  private def label(name: String, calls: Vector[ApiCall]): String =
    name match
      case "apiReference" => if calls.isEmpty then "API" else s"API (${calls.size})"
      case "task"         => "Task"
      case other          => other

  /** The size of a text in words: its characters and its lines. */
  private[inspect] def size(text: String): String =
    s"${Story.plural(text.length.toLong, "character")}, ${Story.plural(text.linesIterator.size.toLong, "line")}"

  /** `text` with each marker line of the chat format in a span, so the format of the fields stands out. */
  private def marked(text: String): String =
    text.linesIterator.map(line =>
      if Prompt.isMarker(line) then s"""<span class="mk">${escape(line)}</span>""" else escape(line)
    ).mkString("\n")

  /** The view of the instructions: their parts (L27), and a button for their text with each marker line in color.
    * Instructions with no parts show their text only.
    */
  private def instructions(text: String): String =
    val note = s"""<p class="note">The system message up to its first fixed field: ${escape(size(text))}."""
    Instructions.parse(text) match
      case None        => s"""$note</p><pre class="ptext">${marked(text)}</pre>"""
      case Some(parts) => s"""$note ${Html.forms("parts" -> "Parts", "raw" -> "Raw")}</p>""" +
          s"""<div class="pinstr" data-form-view="parts">${Instructions.html(parts)}</div>""" +
          s"""<pre class="ptext" data-form-view="raw" hidden>${marked(text)}</pre>"""

  /** The view of a fixed field. The task shows its Markdown, with a button for its raw text. The API reference shows
    * its calls first, each with its signature and its comment behind a click, and then its Scala source behind a click.
    * The source and the signatures have the colors of Scala (L26).
    */
  private def field(name: String, value: String, calls: Vector[ApiCall]): String =
    val shown = if name == "apiReference" then Highlight.sourceHtml(value) else escape(value)
    val text  = s"""<pre class="ptext">$shown</pre>"""
    if name == "task" then
      // The task is Markdown, so the tab shows it as Markdown, and a button shows its raw text.
      val forms = Html.forms("markdown" -> "Markdown", "raw" -> "Raw")
      s"""<p class="note">The field task: ${escape(size(value))}. $forms</p>""" +
        s"""<div class="md ptask" data-form-view="markdown">${Markdown.html(value)}</div>""" +
        s"""<pre class="ptext" data-form-view="raw" hidden>${escape(value)}</pre>"""
    else if name != "apiReference" || calls.isEmpty then
      s"""<p class="note">The field ${escape(name)}: ${escape(size(value))}.</p>$text"""
    else
      val list = calls.map { c =>
        s"""<details class="call"><summary><code>${escape(c.name)}</code> <span class="about">""" +
          s"""${escape(c.about)}</span></summary><pre>${Highlight.sourceHtml(c.signature)}</pre><p>${escape(
              c.doc
            )}</p></details>"""
      }.mkString("""<div class="calls">""", "", "</div>")
      s"""<p class="note">${escape(Story.plural(calls.size, "call"))} of <code>object api</code>. """ +
        s"""The field apiReference: ${escape(size(value))}.</p>$list""" +
        s"""<details class="source"><summary>The Scala source</summary>$text</details>"""

  /** The facts of the request, and the last `Activated` at or before the call, as its card in the entries view. */
  private def settings(session: SessionId, mark: PromptMark): String =
    val prompt = mark.prompt
    val reason =
      if mark.before.isEmpty then "the first policy call of the page"
      else s"changed: ${mark.changes.mkString(", ")}"
    val place =
      if prompt.inSystem then "at the end of the system message"
      else "in the last user message, so the endpoint cannot reuse the start of the request from its cache"
    val plain =
      Vector(Some("reason" -> reason), mark.call.at.map(at => "time" -> Html.time(at)), Some("url" -> prompt.url))
        .flatten ++ prompt.options.map((name, value) => name -> Prompt.words(value)) ++
        Vector("fixed fields" -> place, "instructions" -> size(prompt.instructions)) ++
        prompt.fields.map((name, value) => name -> size(value))
    val rows = s"""<dt>call</dt><dd>${link(session, mark.call)} after #${mark.call.after}</dd>""" +
      plain.map((k, v) => s"<dt>${escape(k)}</dt><dd>${escape(v)}</dd>").mkString
    val activation = mark.activation.flatMap(_.logged.toOption).fold(
      """<p class="note">The journal has no Activated before this call.</p>"""
    )(logged =>
      s"""<h3>The activation: #${logged.seq} · ${escape(Html.time(logged.meta.atMillis))}</h3>""" +
        s"""<div class="v-card">${EntryViews.card(logged)}</div>"""
    )
    s"<dl>$rows</dl>$activation"

  /** The line diff of each part that changed, against the prompt of the call before it. */
  private def changes(session: SessionId, mark: PromptMark): String =
    mark.before.fold("") { (earlier, before) =>
      val base  = s"/s/${escape(session.value)}/x"
      val whole = s"""$base/${escape(mark.call.id)}?against=${escape(earlier.id)}"""
      val head  = s"""<p class="note">Against the prompt of the <a href="$base/${escape(earlier.id)}">policy call """ +
        s"""after #${earlier.after}</a>. <a href="$whole">The diff of the two requests</a> also shows the memory and """ +
        "the messages.</p>"
      head + mark.changes.map { name =>
        val lines = (p: Prompt) => p.part(name).fold(Vector.empty[String])(_.linesIterator.toVector)
        val hunks = LineDiff.hunks(LineDiff.diff(lines(before), lines(mark.prompt)))
        s"<h3>${escape(name)}</h3>" +
          (if hunks.isEmpty then """<p class="note">Only the white space at the end changed.</p>"""
           else hunks.map(ExchangePage.hunk).mkString)
      }.mkString
    }
