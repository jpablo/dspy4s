package dspy4s.harness.inspect

import scala.annotation.tailrec

import Html.escape

/** The kind of a piece of the text of a view, for its color (docs/code-harness/OBSERVABILITY.md, 5.3). */
enum Tint derives CanEqual:
  case Plain, Punct, Key, Str, Num, Lit, Type, Keyword, Def, Annotation, Comment

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
  *   - Scala source code (`source`, L26): the comments, the keywords, the name after `def`, `val` or `var`, the types,
  *     the annotations, the strings and the numbers.
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

  /** The keywords of Scala 3, with `using`, which a program seldom uses as a name. `true`, `false` and `null` are
    * literals.
    */
  private val keywords: Set[String] =
    ("abstract case catch class def do else enum export extends final finally for given if implicit import lazy match " +
      "new object override package private protected return sealed super then this throw trait try type using val var " +
      "while with yield").split(' ').toSet

  /** The soft modifiers: a keyword only before a keyword or another soft modifier, as `inline def` or `update def`. */
  private val modifiers = Set("inline", "opaque", "transparent", "open", "infix", "update", "erased", "tracked")

  /** The keywords whose next name is a definition, and the keywords whose next name is a type. */
  private val definers = Set("def", "val", "var")
  private val typers   = Set("class", "trait", "object", "enum", "type")

  /** The pieces of Scala source code (5.3 and L26), for the API reference, the code of a cell and a Scala fence of a
    * reply. A comment is one piece, and a block comment can hold another one. A string with one quote ends at its line
    * end, and the `${...}` of an interpolation is a part of its string. A soft keyword is a keyword only in its place:
    * `end` at the start of a line, `as` and `derives` between spaces, `extension` before `(` or `[`, and a soft
    * modifier before a keyword. The name after `def`, `val` or `var` is a definition.
    */
  def source(text: String): Vector[Token] =
    val out  = Vector.newBuilder[Token]
    var last = Option.empty[Token] // the last piece that is not a comment and not white space
    var i    = 0
    while i < text.length do
      val c           = text(i)
      val (tint, end) =
        if text.startsWith("//", i) then (Tint.Comment, lineEnd(text, i))
        else if text.startsWith("/*", i) then (Tint.Comment, comment(text, i))
        else if c == '"' then (Tint.Str, string(text, i, interpolated = false))
        else if c == '\'' then character(text, i).fold((Tint.Plain, i + 1))(stop => (Tint.Str, stop))
        else if c == '`' then (Tint.Plain, quotedName(text, i))
        else if c == '@' && i + 1 < text.length && name(text(i + 1)) then (Tint.Annotation, annotation(text, i + 1))
        else if c.isDigit then (Tint.Num, numeral(text, i + 1, text.startsWith("0x", i) || text.startsWith("0X", i)))
        else if name(c) then
          val stop = span(text, i + 1, ch => name(ch) || ch.isDigit)
          if stop < text.length && text(stop) == '"' then (Tint.Str, string(text, stop, interpolated = true))
          else (word(text, i, stop, last), stop)
        else (Tint.Plain, span(text, i + 1, ch => !starts(ch)))
      val token = Token(tint, text.substring(i, end))
      out += token
      if tint != Tint.Comment && !(tint == Tint.Plain && token.text.isBlank) then last = Some(token)
      i = end
    out.result()

  /** The HTML of Scala source code: the escape of `text`, with the colors of `source`. */
  def sourceHtml(text: String): String = html(source(text))

  /** The HTML of `tokens`: the escape of each piece, and a span with the class of its kind for a piece with a color.
    * The marks and the plain text have no span, so a page stays small.
    */
  def html(tokens: Vector[Token]): String =
    tokens.map { t =>
      t.tint match
        case Tint.Plain | Tint.Punct => escape(t.text)
        case tint                    => s"""<span class="t-${tint.toString.toLowerCase}">${escape(t.text)}</span>"""
    }.mkString

  /** The code view of `lines` (5.4), as an editor shows a JSON file: a number for each line, the colors of JSON, and
    * the rest of each line after its first spaces in a block with that indent. So a long line wraps under its own
    * indent, and not at the left edge. A JSON string has no line end, so each line gets its colors alone, also a
    * `data:` line of a stream.
    */
  def code(lines: Vector[String]): String =
    lines.zipWithIndex.map { (line, i) =>
      val indent = line.takeWhile(_ == ' ').length
      s"""<div class="ln"><span class="n">${i + 1}</span><span class="c" style="--i:$indent">""" +
        s"""${html(json(line.drop(indent)))}</span></div>"""
    }.mkString("""<div class="code">""", "", "</div>")

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

  // ── The parts of Scala source ──

  /** True for a character that can start a piece with a color, so a plain piece of Scala source ends before it. */
  private def starts(ch: Char): Boolean = name(ch) || ch.isDigit || "\"'`@/".contains(ch)

  /** The color of the name from `start` to `stop`, from its place: the piece before it and the text around it. */
  private def word(text: String, start: Int, stop: Int, last: Option[Token]): Tint =
    val word  = text.substring(start, stop)
    val after = last.collect { case Token(Tint.Keyword, k) => k }
    if literals.contains(word) then Tint.Lit
    else if keywords.contains(word) then Tint.Keyword
    else if after.exists(definers.contains) then Tint.Def
    else if after.exists(typers.contains) || word.head.isUpper then Tint.Type
    else if soft(text, start, stop, word) then Tint.Keyword
    else Tint.Plain

  /** True when the soft keyword `word` from `start` to `stop` is in the place of a keyword. */
  private def soft(text: String, start: Int, stop: Int, word: String): Boolean =
    word match
      case "as" | "derives" => charAt(text, start - 1).contains(' ') && charAt(text, stop).contains(' ')
      case "extension"      => next(text, stop).exists(ch => ch == '(' || ch == '[')
      case "end"            =>
        val before = text.substring(text.lastIndexOf('\n', start - 1) + 1, start)
        before.forall(ch => ch == ' ' || ch == '\t') && charAt(text, stop).contains(' ') &&
        next(text, stop).exists(name)
      case _ => modifiers.contains(word) && nextWord(text, stop).exists(w => keywords(w) || modifiers(w))

  private def charAt(text: String, k: Int): Option[Char] = Option.when(k >= 0 && k < text.length)(text(k))

  /** The name after the spaces and the tabs from `from`, on the same line. */
  private def nextWord(text: String, from: Int): Option[String] =
    val start = span(text, from, ch => ch == ' ' || ch == '\t')
    val stop  = span(text, start, ch => name(ch) || ch.isDigit)
    Option.when(stop > start && name(text(start)))(text.substring(start, stop))

  /** The index of the first line end from `from`, or the length of `text`. */
  private def lineEnd(text: String, from: Int): Int =
    val j = text.indexOf('\n', from)
    if j < 0 then text.length else j

  /** The end of the block comment at `start`: after the mark that closes it, with each inner block comment closed
    * first.
    */
  private def comment(text: String, start: Int): Int =
    @tailrec def go(j: Int, depth: Int): Int =
      if depth == 0 || j >= text.length then j
      else if text.startsWith("/*", j) then go(j + 2, depth + 1)
      else if text.startsWith("*/", j) then go(j + 2, depth - 1)
      else go(j + 1, depth)
    go(start + 2, 1)

  /** The end of the string at the quote `quote`: a `"""` block, or a string of one line. In an interpolated string, a
    * `${...}` is a part of the string, also with a quote in it.
    */
  private def string(text: String, quote: Int, interpolated: Boolean): Int =
    if text.startsWith("\"\"\"", quote) then block(text, quote)
    else
      @tailrec def go(j: Int): Int =
        if j >= text.length || text(j) == '\n' then j
        else if text(j) == '"' then j + 1
        else if text(j) == '\\' && j + 1 < text.length && text(j + 1) != '\n' then go(j + 2)
        else if interpolated && text.startsWith("${", j) then go(braces(text, j + 1))
        else go(j + 1)
      go(quote + 1)

  /** The end of the braces at `open`: after the `}` that closes them, or the line end. */
  private def braces(text: String, open: Int): Int =
    @tailrec def go(j: Int, depth: Int): Int =
      if depth == 0 || j >= text.length || text(j) == '\n' then j
      else if text(j) == '{' then go(j + 1, depth + 1)
      else if text(j) == '}' then go(j + 1, depth - 1)
      else go(j + 1, depth)
    go(open + 1, 1)

  /** The end of the character at the quote `start`, such as `'a'`, `'\n'` or `'\u0041'`, or none for a quote that
    * starts no character, such as the quote of `'{ code }`.
    */
  private def character(text: String, start: Int): Option[Int] =
    if charAt(text, start + 1).contains('\\') then
      val close = text.indexOf('\'', start + 3)
      Option.when(close >= start + 3 && close <= start + 7 && !text.substring(start, close).contains('\n'))(close + 1)
    else Option.when(charAt(text, start + 2).contains('\'') && !charAt(text, start + 1).contains('\n'))(start + 3)

  /** The end of the name in backquotes at `start`, or the next index when no backquote closes it on its line. */
  private def quotedName(text: String, start: Int): Int =
    val close = text.indexOf('`', start + 1)
    if close < 0 || text.substring(start, close).contains('\n') then start + 1 else close + 1

  /** The end of the annotation whose name starts at `from`, with the names after each `.`. */
  @tailrec private def annotation(text: String, from: Int): Int =
    val stop = span(text, from, ch => name(ch) || ch.isDigit)
    if stop + 1 < text.length && text(stop) == '.' && name(text(stop + 1)) then annotation(text, stop + 1) else stop

  /** The end of a number of Scala source from `j`: letters, digits and `_`, a `.` before a digit, and a sign after `e`
    * or `E` when the number is not hexadecimal. So `1.max(2)` has the number `1`.
    */
  @tailrec private def numeral(text: String, j: Int, hex: Boolean): Int =
    def is(k: Int, p: Char => Boolean): Boolean = k < text.length && p(text(k))
    if is(j, ch => ch.isLetterOrDigit || ch == '_') then numeral(text, j + 1, hex)
    else if is(j, _ == '.') && is(j + 1, _.isDigit) then numeral(text, j + 2, hex)
    else if !hex && is(j, ch => ch == '+' || ch == '-') && "eE".contains(text(j - 1)) && is(j + 1, _.isDigit) then
      numeral(text, j + 2, hex)
    else j
