package dspy4s.harness.inspect

import scala.annotation.tailrec

import Html.escape

/** A subset of Markdown as HTML, for the messages and the replies of the chat view (docs/code-harness/OBSERVABILITY.md,
  * 5.7). The model writes its replies in Markdown, and a raw view showed each mark, for example ```` ``` ```` and `**`.
  *
  * The blocks: a code fence with its language (a fence of Scala has the colors of `Highlight.source`), a heading, a
  * rule, a quote, a list with a nested list, a table, and a paragraph. A line end in a paragraph stays a line end, as
  * in a chat. The inline parts: a code span, `**strong**`, `*em*`, `~~del~~`, a link, and a bare URL.
  *
  * The rules of safety:
  *   - Each character of the text goes through `Html.escape`, so a text makes no markup. The renderer makes only the
  *     tags of `Tags`, and each of them is closed.
  *   - Only a link to `https:`, `http:` or `mailto:` gets an `href`. Each other link, for example a relative path of
  *     the repository or `javascript:`, shows its text, with the target in its `title`.
  */
object Markdown:

  /** The tags that the renderer makes. `br` and `hr` have no end tag. */
  val Tags: Set[String] = Set(
    "p",
    "br",
    "code",
    "strong",
    "em",
    "del",
    "ul",
    "ol",
    "li",
    "blockquote",
    "h3",
    "h4",
    "h5",
    "hr",
    "pre",
    "table",
    "thead",
    "tbody",
    "tr",
    "th",
    "td",
    "div",
    "span",
    "button",
    "a"
  )

  /** The HTML of `text`. */
  def html(text: String): String = blocks(text.replace("\r\n", "\n").split("\n", -1).toVector)

  /** The HTML of `line` for a row of a view (L31): its inline parts, as in a paragraph, with no block. A heading mark
    * at its start goes away, and a line end is a space.
    */
  def inline(line: String): String = spans(HeadingMark.replaceFirstIn(line.replace("\r", "").replace('\n', ' '), ""))

  /** The mark of a heading at the start of a line, with its spaces. */
  private val HeadingMark = """^ {0,3}#{1,6}\s+""".r

  // ── The blocks ──

  private val Fence   = """^ {0,3}(`{3,}|~{3,})\s*([^`\s]*)[^`]*$""".r
  private val Heading = """^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""".r
  private val Rule    = """^ {0,3}([-*_])(\s*\1){2,}\s*$""".r
  private val Item    = """^( *)([-*+]|\d{1,9}[.)])(\s+)(.*)$""".r
  private val Divider = """^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""".r

  private def blocks(lines: Vector[String]): String =
    @tailrec def go(i: Int, out: Vector[String]): Vector[String] =
      if i >= lines.size then out
      else
        val line = lines(i)
        if line.isBlank then go(i + 1, out)
        else
          line match
            case Fence(mark, lang) =>
              val body = lines.drop(i + 1).takeWhile(l => !closes(l, mark))
              go(i + 2 + body.size, out :+ code(lang, body))
            case Heading(marks, title) =>
              val tag = marks.length match
                case 1 => "h3"
                case 2 => "h4"
                case _ => "h5"
              go(i + 1, out :+ s"<$tag>${spans(title)}</$tag>")
            case Rule(_, _*)       => go(i + 1, out :+ "<hr>")
            case _ if quoted(line) =>
              val part = lines.drop(i).takeWhile(quoted)
              go(i + part.size, out :+ s"<blockquote>${blocks(part.map(unquote))}</blockquote>")
            case Item(_, _, _, _) =>
              val (html, used) = list(lines, i)
              go(i + used, out :+ html)
            case _ if table(lines, i) =>
              val rows = lines.drop(i + 2).takeWhile(l => !l.isBlank && l.contains('|'))
              go(i + 2 + rows.size, out :+ tableHtml(lines(i), lines(i + 1), rows))
            case _ =>
              val rest = lines.drop(i + 1).takeWhile(l => !l.isBlank && !starts(l))
              val text = (line +: rest).map(_.trim).mkString("\n")
              go(i + 1 + rest.size, out :+ s"<p>${spans(text)}</p>")
    go(0, Vector.empty).mkString

  /** True when `line` starts a block that ends a paragraph: a fence, a heading, a rule, a quote or a list item. */
  private def starts(line: String): Boolean =
    line match
      case Fence(_, _) | Heading(_, _) | Rule(_, _*) | Item(_, _, _, _) => true
      case _                                                            => quoted(line)

  private def closes(line: String, mark: String): Boolean =
    val trimmed = line.trim
    trimmed.nonEmpty && trimmed.forall(_ == mark.head) && trimmed.length >= mark.length

  /** The languages of a fence that get the colors of Scala (L26). */
  private val ScalaNames = Set("scala", "scala3", "sc")

  private def code(lang: String, body: Vector[String]): String =
    val text  = body.mkString("\n")
    val shown = if ScalaNames(lang.toLowerCase) then Highlight.sourceHtml(text) else escape(text)
    s"""<div class="md-code"><div class="md-code-head"><span>${escape(lang)}</span>""" +
      """<button data-copy-code>Copy</button></div>""" + s"<pre><code>$shown</code></pre></div>"

  private def quoted(line: String): Boolean = line.dropWhile(_ == ' ').startsWith(">")

  private def unquote(line: String): String =
    val rest = line.dropWhile(_ == ' ').drop(1)
    if rest.startsWith(" ") then rest.drop(1) else rest

  private def indent(line: String): Int = line.takeWhile(_ == ' ').length

  /** The list that starts at `lines(start)`, and the count of its lines. An item holds the next lines with a deeper
    * indent than its marker, also after a blank line. A line of the same indent with a marker of the same kind starts
    * the next item.
    */
  private def list(lines: Vector[String], start: Int): (String, Int) =
    lines(start) match
      case Item(base, first, _, _) => listFrom(lines, start, base.length, first)
      case other                   => (s"<p>${spans(other)}</p>", 1)

  private def listFrom(lines: Vector[String], start: Int, base: Int, first: String): (String, Int) =
    val ordered                                      = first.head.isDigit
    def item(line: String): Option[(String, String)] =
      line match
        case Item(lead, mark, space, text) if lead.length == base && mark.head.isDigit == ordered =>
          Some((" " * (lead.length + mark.length + space.length), text))
        case _ => None
    @tailrec def items(i: Int, out: Vector[Vector[String]]): (Vector[Vector[String]], Int) =
      item(lines.lift(i).getOrElse("\u0000")) match
        case None              => (out, i)
        case Some((pad, text)) =>
          @tailrec def body(j: Int): Int =
            lines.lift(j) match
              case Some(l) if !l.isBlank && indent(l) > base                                             => body(j + 1)
              case Some(l) if l.isBlank && lines.lift(j + 1).exists(n => !n.isBlank && indent(n) > base) => body(j + 1)
              case _                                                                                     => j
          val end  = body(i + 1)
          val more = lines.slice(i + 1, end).map(l => l.drop(indent(l).min(pad.length)))
          items(end, out :+ (text +: more))
    val (all, end) = items(start, Vector.empty)
    val number     = first.takeWhile(_.isDigit)
    val open       = if !ordered then "<ul>" else if number.toInt == 1 then "<ol>" else s"""<ol start="${number.toInt}">"""
    val close      = if ordered then "</ol>" else "</ul>"
    (all.map(lines => s"<li>${blocks(lines)}</li>").mkString(open, "", close), end - start)

  /** True when `lines(i)` is the head of a table: it has a `|`, and the next line is a divider such as `|---|:--:|`. */
  private def table(lines: Vector[String], i: Int): Boolean =
    lines(i).contains('|') && lines.lift(i + 1).exists(l => l.contains('-') && Divider.matches(l))

  private def cells(line: String): Vector[String] =
    val inner = line.trim.stripPrefix("|")
    val body  = if inner.endsWith("|") && !inner.endsWith("\\|") then inner.dropRight(1) else inner
    body.split("""(?<!\\)\|""", -1).toVector.map(_.trim.replace("\\|", "|"))

  private def tableHtml(head: String, divider: String, rows: Vector[String]): String =
    val aligns = cells(divider).map { c =>
      if c.startsWith(":") && c.endsWith(":") then " style=\"text-align:center\""
      else if c.endsWith(":") then " style=\"text-align:right\""
      else ""
    }
    def row(tag: String, line: String): String =
      cells(line).zipWithIndex.map((c, k) => s"<$tag${aligns.lift(k).getOrElse("")}>${spans(c)}</$tag>")
        .mkString("<tr>", "", "</tr>")
    s"""<div class="md-table"><table><thead>${row("th", head)}</thead><tbody>""" +
      rows.map(row("td", _)).mkString + "</tbody></table></div>"

  // ── The inline parts ──

  /** The marks that a backslash keeps as text. */
  private val Escapable = """\`*_{}[]()#+-.!|~>""".toSet

  /** The HTML of the inline parts of `text`. Each character that starts no part goes through `Html.escape`. */
  private def spans(text: String): String =
    val out                       = StringBuilder()
    @tailrec def go(i: Int): Unit =
      if i < text.length then
        val c = text(i)
        if c == '\\' && text.lift(i + 1).exists(Escapable) then
          out ++= escape(text(i + 1).toString)
          go(i + 2)
        else if c == '\n' then
          out ++= "<br>"
          go(i + 1)
        else if c == '`' then
          val run = ticks(text, i)
          closing(text, i + run, run) match
            case Some(end) =>
              out ++= s"<code>${escape(codeText(text.substring(i + run, end)))}</code>"
              go(end + run)
            case None =>
              out ++= "`" * run
              go(i + run)
        else
          span(text, i) match
            case Some((html, next)) =>
              out ++= html
              go(next)
            case None =>
              out ++= escape(c.toString)
              go(i + 1)
    go(0)
    out.result()

  /** The part that starts at `text(i)`: strong, em, del, a link or a bare URL, with the index after it. */
  private def span(text: String, i: Int): Option[(String, Int)] =
    val c = text(i)
    if (c == '*' || c == '_') && text.startsWith(s"$c$c", i) then emphasis(text, i, s"$c$c", "strong")
    else if c == '*' || c == '_' then emphasis(text, i, c.toString, "em")
    else if c == '~' && text.startsWith("~~", i) then emphasis(text, i, "~~", "del")
    else if c == '[' then link(text, i)
    else if c == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i)) &&
      !text.lift(i - 1).exists(_.isLetterOrDigit)
    then bare(text, i)
    else None

  /** An emphasis that opens with `mark` at `text(i)`. The text after an opening mark is not white space, and the text
    * before a closing mark is not white space. An underscore in a word, as in `snake_case`, is no mark.
    */
  private def emphasis(text: String, i: Int, mark: String, tag: String): Option[(String, Int)] =
    val from      = i + mark.length
    val underline = mark.head == '_'
    val opens     = text.lift(from).exists(n => !n.isWhitespace && n != mark.head) &&
      !(underline && text.lift(i - 1).exists(_.isLetterOrDigit))
    @tailrec def find(j: Int): Option[Int] =
      if j >= text.length then None
      else if text(j) == '`' then
        val run = ticks(text, j)
        find(closing(text, j + run, run).fold(j + run)(_ + run))
      else if text.startsWith(mark, j) && j > from && !text(j - 1).isWhitespace && text(j - 1) != mark.head &&
        !text.lift(j + mark.length).contains(mark.head) &&
        !(underline && text.lift(j + mark.length).exists(_.isLetterOrDigit))
      then Some(j)
      else find(j + 1)
    if !opens then None
    else find(from).map(end => (s"<$tag>${spans(text.substring(from, end))}</$tag>", end + mark.length))

  /** A link `[label](target)` at `text(i)`. A target with a safe scheme gets an `href`, and each other target shows
    * only its label, with the target in the `title`.
    */
  private def link(text: String, i: Int): Option[(String, Int)] =
    val close = text.indexOf(']', i + 1)
    if close < 0 || !text.startsWith("(", close + 1) then None
    else
      @tailrec def end(j: Int, depth: Int): Int =
        if j >= text.length then -1
        else
          text(j) match
            case '(' => end(j + 1, depth + 1)
            case ')' => if depth == 0 then j else end(j + 1, depth - 1)
            case _   => end(j + 1, depth)
      end(close + 2, 0) match
        case -1 => None
        case to =>
          val label  = spans(text.substring(i + 1, close))
          val target = text.substring(close + 2, to).trim.split("\\s+").headOption.getOrElse("")
            .stripPrefix("<").stripSuffix(">")
          Some((anchor(target, label), to + 1))

  /** A bare URL at `text(i)`, up to white space. Marks at its end, such as a period, stay out of the URL. */
  private def bare(text: String, i: Int): Option[(String, Int)] =
    val raw   = text.substring(i).takeWhile(c => !c.isWhitespace && !"<>\"'`".contains(c))
    val url   = raw.reverse.dropWhile(".,;:!?)]".contains(_)).reverse
    val fixed =
      if url.count(_ == '(') > url.count(_ == ')') && raw.drop(url.length).startsWith(")") then url + ")" else url
    Option.when(fixed.length > "https://".length)((anchor(fixed, escape(fixed)), i + fixed.length))

  /** True for a target that a page can open with no risk: `https:`, `http:` or `mailto:`. */
  def safe(target: String): Boolean =
    val lower = target.toLowerCase(java.util.Locale.ROOT)
    (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) &&
    !target.exists(c => c.isWhitespace || c.isControl)

  private def anchor(target: String, label: String): String =
    if safe(target) then s"""<a href="${escape(target)}" rel="noopener noreferrer" target="_blank">$label</a>"""
    else s"""<span class="md-link" title="${escape(target)}">$label</span>"""

  private def ticks(text: String, i: Int): Int =
    text.indexWhere(_ != '`', i) match
      case -1 => text.length - i
      case j  => j - i

  /** The start of the run of exactly `run` backticks that closes a code span, from `from` on. */
  private def closing(text: String, from: Int, run: Int): Option[Int] =
    @tailrec def find(j: Int): Option[Int] =
      text.indexOf('`', j) match
        case -1 => None
        case k  =>
          val n = ticks(text, k)
          if n == run then Some(k) else find(k + n)
    find(from)

  /** The text of a code span: one space at each end goes away when the text has both, as in CommonMark. */
  private def codeText(text: String): String =
    if text.length >= 2 && text.startsWith(" ") && text.endsWith(" ") && !text.isBlank then text.drop(1).dropRight(1)
    else text
