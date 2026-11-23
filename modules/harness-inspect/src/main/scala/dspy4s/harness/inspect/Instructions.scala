package dspy4s.harness.inspect

import Html.escape

/** A field of a signature in the instructions: its name, its type and its description. */
final case class FieldText(name: String, kind: String, description: String) derives CanEqual

/** The parts of the instructions of a chat adapter (docs/code-harness/OBSERVABILITY.md, 5.8 and L27), as
  * `PromptText.systemMessage` of the adapters writes them.
  *
  * @param inputs
  *   the input fields, in their order
  * @param outputs
  *   the output fields, in their order
  * @param markers
  *   each marker of the structure, in its order, with the text of its placeholder, for example `{code}` with the note
  *   of an output field. The marker `completed` has no placeholder.
  * @param objective
  *   the instructions of the signature, with no indent
  * @param rest
  *   the text after the objective, for example `PolicyAdapter.FixedHeader`
  */
final case class Instructions(
    inputs   : Vector[FieldText],
    outputs  : Vector[FieldText],
    markers  : Vector[(String, String)],
    objective: String,
    rest     : String
) derives CanEqual

object Instructions:

  private val InputsHead    = "Your input fields are:"
  private val OutputsHead   = "Your output fields are:"
  private val StructureHead =
    "All interactions will be structured in the following way, with the appropriate values filled in."
  private val ObjectiveHead = "In adhering to this structure, your objective is:"

  /** The indent of each line of the objective (upstream `ChatAdapter.format_task_description`). */
  private val Indent = " " * 8

  /** The line that starts a field of a list, for example ``1. `task` (str): What to do.`` */
  private val FieldLine = """\d+\. `([^`]+)` \(([^)]*)\):? ?(.*)""".r

  private val Completed = "[[ ## completed ## ]]"

  /** The parts of `text`, or none for a text that is not the instructions of a chat adapter. Each line of the text is
    * in one part: the four head lines, a field line or a line of its description, a marker or a line of its
    * placeholder, a line of the objective after its indent, or a line of the rest. A line before the first field of a
    * list or before the first marker gives none.
    */
  def parse(text: String): Option[Instructions] =
    val lines     = text.linesIterator.toVector
    val outputs   = lines.indexOf(OutputsHead)
    val structure = lines.indexOf(StructureHead)
    val objective = lines.indexWhere(_.stripTrailing == ObjectiveHead)
    val order     = lines.headOption.contains(InputsHead) && 0 < outputs && outputs < structure && structure < objective
    for
      _       <- Option.when(order)(())
      inputs  <- fields(lines.slice(1, outputs))
      outs    <- fields(lines.slice(outputs + 1, structure))
      markers <- markersOf(lines.slice(structure + 1, objective))
    yield
      val after = lines.drop(objective + 1)
      val body  = after.takeWhile(_.startsWith(Indent))
      Instructions(inputs, outs, markers, trim(body.map(_.drop(Indent.length))), trim(after.drop(body.size)))

  /** The HTML of `parts`: the objective as Markdown, a table of the input fields and of the output fields, the order of
    * the markers, and the rest. An output field with a note has its note under its description.
    */
  def html(parts: Instructions): String =
    val notes   = parts.markers.flatMap((name, holder) => note(name, holder).map(name -> _)).toMap
    val order   = parts.markers.map((name, _) => s"<code>${escape(name)}</code>").mkString(" → ")
    val example = parts.markers.headOption.fold("")((name, _) =>
      s", for example <code>${escape(s"[[ ## $name ## ]]")}</code>"
    )
    s"""<h3>The objective</h3><div class="md">${Markdown.html(parts.objective)}</div>""" +
      s"<h3>The input fields</h3>${table(parts.inputs, Map.empty)}" +
      s"<h3>The output fields</h3>${table(parts.outputs, notes)}" +
      s"<h3>The format</h3><p>The fields come in this order, each after its marker$example: $order.</p>" +
      (if parts.rest.isEmpty then "" else s"""<div class="rest">${Markdown.html(parts.rest)}</div>""")

  /** The instructions of a system message, and the fixed fields after them: each marker after the marker `completed`
    * starts a field. A system message with no such marker is all instructions.
    */
  def split(system: String): (String, Vector[(String, String)]) =
    val lines = system.linesIterator.toVector
    val done  = lines.indexOf(Completed)
    val start = if done < 0 then -1 else lines.indexWhere(Prompt.isMarker, done + 1)
    if start < 0 then (system, Vector.empty) else (trim(lines.take(start)), Prompt.sectionsOf(lines.drop(start)))

  /** The view of the value of a field: the task and the text of the policy as Markdown, the API reference and the code
    * with the colors of Scala (L26), and each other field as its text.
    */
  def fieldHtml(name: String, value: String): String =
    name match
      case "task"                  => s"""<div class="md ptask">${Markdown.html(value)}</div>"""
      case "text"                  => s"""<div class="md">${Markdown.html(value)}</div>"""
      case "apiReference" | "code" => s"""<pre class="ptext">${Highlight.sourceHtml(value)}</pre>"""
      case _                       => s"""<pre class="ptext">${escape(value)}</pre>"""

  /** The parts of a system message for the page of an exchange (5.4): the parts of its instructions, and then each
    * fixed field behind a click. None for a system message whose instructions have no parts.
    */
  def systemHtml(system: String): Option[String] =
    val (head, fixed) = split(system)
    parse(head).map { parts =>
      val shown = fixed.map((name, value) =>
        s"""<details class="fixed"><summary><code>${escape(name)}</code> · ${escape(PromptView.size(value))}""" +
          s"</summary>${fieldHtml(name, value)}</details>"
      )
      html(parts) + (if shown.isEmpty then "" else s"<h3>The fixed fields</h3>${shown.mkString}")
    }

  /** The fields of a numbered list: a field line starts a field, and each other line that is not blank goes on with the
    * description of the field before it. A line before the first field gives none.
    */
  private def fields(lines: Vector[String]): Option[Vector[FieldText]] =
    lines.filterNot(_.isBlank).foldLeft(Option(Vector.empty[FieldText])) { (found, line) =>
      found.flatMap { all =>
        line match
          case FieldLine(name, kind, description) => Some(all :+ FieldText(name, kind, description))
          case _                                  => all.lastOption.map(last =>
              all.init :+
                last.copy(description = if last.description.isEmpty then line else s"${last.description}\n$line")
            )
      }
    }

  /** The markers of the structure, each with its placeholder: the lines after it that are not blank, up to the next
    * marker. A line before the first marker gives none.
    */
  private def markersOf(lines: Vector[String]): Option[Vector[(String, String)]] =
    lines.filterNot(_.isBlank).foldLeft(Option(Vector.empty[(String, String)])) { (found, line) =>
      found.flatMap { all =>
        Prompt.markerOf(line) match
          case Some(name) => Some(all :+ (name -> ""))
          case None       => all.lastOption.map((name, holder) =>
              all.init :+ (name -> (if holder.isEmpty then line else s"$holder\n$line"))
            )
      }
    }

  /** The note of the placeholder of the marker `name`: its text after `{name}` and `# note:`, or none. */
  private def note(name: String, holder: String): Option[String] =
    Some(holder.stripPrefix(s"{$name}").trim.stripPrefix("# note:").trim).filter(_.nonEmpty)

  private def table(fields: Vector[FieldText], notes: Map[String, String]): String =
    fields.map { f =>
      val note = notes.get(f.name).fold("")(n => s"""<div class="fnote">${escape(n)}</div>""")
      s"""<tr><td><code>${escape(f.name)}</code></td><td class="ty">${escape(f.kind)}</td>""" +
        s"<td>${Markdown.html(f.description)}$note</td></tr>"
    }.mkString("""<table class="fields">""", "", "</table>")

  /** `lines` with no blank lines at the start and at the end, as one text. */
  private[inspect] def trim(lines: Vector[String]): String =
    lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse.mkString("\n")

/** A message with its fields in markers, as `FramedTurns` of the adapters writes it
  * (docs/code-harness/OBSERVABILITY.md, 5.4 and L28): a user message, a reply, or a step of the history.
  *
  * @param lead
  *   the text before the first marker
  * @param fields
  *   each field with its value, in their order, but the marker `completed`
  * @param completed
  *   true when the text has the marker `completed`, the end of a reply
  * @param rest
  *   the reminder of the output fields at the end of a user message, and the text after the marker `completed`
  */
final case class Framed(lead: String, fields: Vector[(String, String)], completed: Boolean, rest: String)
    derives CanEqual

object Framed:

  /** The start of the reminder of the output fields (upstream `ChatAdapter.user_message_output_requirements`). */
  private val ReminderStart = "Respond with the corresponding output fields"

  /** The fields of `text`, or none for a text with no marker. Each line of the text is in one part: the lead, a marker,
    * a line of a value, or a line of the rest. The reminder is the last paragraph of the last field when it starts with
    * `ReminderStart`.
    */
  def parse(text: String): Option[Framed] =
    val lines = text.linesIterator.toVector
    val first = lines.indexWhere(Prompt.isMarker)
    Option.when(first >= 0) {
      val (ends, fields)    = Prompt.sectionsOf(lines.drop(first)).partition(_._1 == "completed")
      val (shown, reminder) = fields.lastOption.fold((fields, "")) { (name, value) =>
        val inner = value.linesIterator.toVector
        val at    = inner.lastIndexWhere(_.startsWith(ReminderStart))
        if at < 0 || (at > 0 && !inner(at - 1).isBlank) then (fields, "")
        else (fields.init :+ (name -> Instructions.trim(inner.take(at))), Instructions.trim(inner.drop(at)))
      }
      val rest = (reminder +: ends.map(_._2)).filter(_.nonEmpty).mkString("\n\n")
      Framed(Instructions.trim(lines.take(first)), shown, ends.nonEmpty, rest)
    }

  /** The HTML of `framed`: the lead, each field with its name and the view of its value (`Instructions.fieldHtml`), the
    * end mark, and the rest as Markdown. An empty value shows the word `empty`.
    */
  def html(framed: Framed): String =
    val lead   = if framed.lead.isEmpty then "" else s"""<pre class="ptext">${escape(framed.lead)}</pre>"""
    val fields = framed.fields.map { (name, value) =>
      val shown = if value.isEmpty then """<div class="fempty">empty</div>""" else Instructions.fieldHtml(name, value)
      s"""<div class="field"><div class="fname"><code>${escape(name)}</code></div>$shown</div>"""
    }.mkString
    val end  = if framed.completed then """<div class="fend">completed</div>""" else ""
    val rest = if framed.rest.isEmpty then "" else s"""<div class="rest">${Markdown.html(framed.rest)}</div>"""
    lead + fields + end + rest
