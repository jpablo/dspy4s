package dspy4s.harness.inspect

import zio.blocks.schema.json.Json

import Html.escape

/** The kind of a piece of the text of a view, for its color (docs/code-harness/OBSERVABILITY.md, 5.3). */
enum Tint derives CanEqual:
  case Plain, Punct, Key, Str, Num, Lit, Type

/** One piece of the text of a view, with its kind. */
final case class Token(tint: Tint, text: String) derives CanEqual

/** The colors of the Scala view and of the JSON view (5.3). A lexer cuts a text into pieces, and the pieces, joined,
  * give the text again, for any text. So a text that is not JSON or not Scala keeps each of its characters.
  *
  *   - JSON: a string before a `:` is a key, another string is a string, then the numbers, `true`, `false` and `null`,
  *     and the marks `{}[],:`.
  *   - Scala (the text of `ScalaText`): a name before ` = ` is a key (a field), a name with a capital letter is a type
  *     (a case or a collection), a `"""` block, a `"` literal or a `'` literal is a string, then the numbers (also
  *     `41L`), `true`, `false` and `null`, and the marks `(),={}[]:` and `->`.
  */
object Highlight:

  private val literals = Set("true", "false", "null")

  private val jsonMarks  = "{}[],:"
  private val scalaMarks = "(),={}[]:"

  /** The pieces of a JSON text. */
  def json(text: String): Vector[Token] =
    val out = Vector.newBuilder[Token]
    var i   = 0
    while i < text.length do
      val c           = text(i)
      val (tint, end) =
        if c == '"' then
          val stop = quoted(text, i, '"')
          (if next(text, stop).contains(':') then Tint.Key else Tint.Str, stop)
        else if c == '-' || c.isDigit then (Tint.Num, number(text, i))
        else if c.isLetter then
          val stop = span(text, i + 1, _.isLetterOrDigit)
          (if literals.contains(text.substring(i, stop)) then Tint.Lit else Tint.Plain, stop)
        else if jsonMarks.contains(c) then (Tint.Punct, i + 1)
        else
          (
            Tint.Plain,
            span(text, i + 1, ch => !(ch == '"' || ch == '-' || ch.isLetterOrDigit || jsonMarks.contains(ch)))
          )
      out += Token(tint, text.substring(i, end))
      i = end
    out.result()

  /** The pieces of a text of `ScalaText`. */
  def scala(text: String): Vector[Token] =
    val out = Vector.newBuilder[Token]
    var i   = 0
    while i < text.length do
      val c           = text(i)
      val (tint, end) =
        if text.startsWith("\"\"\"", i) then (Tint.Str, block(text, i))
        else if c == '"' || c == '\'' then (Tint.Str, quoted(text, i, c))
        else if text.startsWith("->", i) then (Tint.Punct, i + 2)
        else if c.isDigit || c == '-' && i + 1 < text.length && text(i + 1).isDigit then (Tint.Num, number(text, i))
        else if name(c) then
          val stop = span(text, i + 1, ch => name(ch) || ch.isDigit)
          val word = text.substring(i, stop)
          val tint =
            if literals.contains(word) then Tint.Lit
            else if field(text, stop) then Tint.Key
            else if word.head.isUpper then Tint.Type
            else Tint.Plain
          (tint, stop)
        else if scalaMarks.contains(c) then (Tint.Punct, i + 1)
        else
          (
            Tint.Plain,
            span(
              text,
              i + 1,
              ch => !(ch == '"' || ch == '\'' || ch == '-' || ch.isDigit || name(ch) || scalaMarks.contains(ch))
            )
          )
      out += Token(tint, text.substring(i, end))
      i = end
    out.result()

  /** The HTML of `tokens`: the escape of each piece, and a span with the class of its kind for a piece with a color.
    * The marks and the plain text have no span, so a page stays small.
    */
  def html(tokens: Vector[Token]): String =
    tokens.map { t =>
      t.tint match
        case Tint.Plain | Tint.Punct => escape(t.text)
        case tint                    => s"""<span class="t-${tint.toString.toLowerCase}">${escape(t.text)}</span>"""
    }.mkString

  /** The HTML of a text that can be JSON: its layout with colors (`JsonText.pretty`), else its escape. */
  def jsonHtml(text: String): String =
    Json.parse(text).fold(_ => escape(text), value => html(json(JsonText.pretty(value))))

  /** The first index from `from` whose character does not satisfy `p`, or the length of `text`. */
  private def span(text: String, from: Int, p: Char => Boolean): Int =
    val j = text.indexWhere(ch => !p(ch), from)
    if j < 0 then text.length else j

  /** The end of the literal at `start` with the quote `quote`: after its closing quote, or the end of the text. A `\`
    * takes the next character with it.
    */
  private def quoted(text: String, start: Int, quote: Char): Int =
    var j = start + 1
    while j < text.length && text(j) != quote do j += (if text(j) == '\\' then 2 else 1)
    math.min(j + 1, text.length)

  /** The end of the `"""` block at `start`: after the last quote of the first run of three or more quotes. */
  private def block(text: String, start: Int): Int =
    val close = text.indexOf("\"\"\"", start + 3)
    if close < 0 then text.length else span(text, close + 3, _ == '"')

  /** The end of the number at `start`: digits, letters such as `L` and `E`, `.`, and a sign after `e` or `E`. */
  private def number(text: String, start: Int): Int =
    var j = start + 1
    while j < text.length &&
      (text(j).isLetterOrDigit || text(j) == '.' || ("+-".contains(text(j)) && "eE".contains(text(j - 1))))
    do j += 1
    j

  /** The first character after `from` that is not a space or a tab. */
  private def next(text: String, from: Int): Option[Char] =
    val j = text.indexWhere(ch => ch != ' ' && ch != '\t', from)
    Option.when(j >= 0)(text(j))

  /** True when a ` = ` follows `stop`, so the name before it is a field. `ScalaText` writes no other `=`. */
  private def field(text: String, stop: Int): Boolean =
    val j = text.indexWhere(_ != ' ', stop)
    j >= 0 && text(j) == '='

  private def name(c: Char): Boolean = c.isLetter || c == '_' || c == '$'
