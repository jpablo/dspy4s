package dspy4s.core.contracts

import ujson.JsVisitor
import upickle.core.{ArrVisitor, ObjVisitor, StringVisitor, Visitor}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.matching.Regex

/** The text forms of Python that the prompts of upstream use: `str()` and `repr()` of a value, `json.dumps` with
  * `ensure_ascii=False` and with ASCII escapes, `str.strip`, `str.splitlines`, and `textwrap.dedent`. The adapters use
  * these forms so that a prompt is equal to the upstream prompt, character for character (decision D3). The parsers
  * also read a JSON text as Python `json.loads` reads it, and a JSON string and a Python string literal as upstream
  * `parse_value` reads them.
  *
  * A value is a `DynamicValue`. An `Option` value of a typed record (the `Some` and `None` variants) prints as its
  * element or as `None`. An enum case with no fields prints as its case name.
  */
private[dspy4s] object PythonText:

  /** Python `str.strip()`: no whitespace at the start and at the end. Python whitespace is `str.isspace`. Java
    * `String.strip` is different: it keeps U+0085, U+00A0, U+2007, and U+202F.
    */
  def strip(text: String): String =
    val start = text.indexWhere(char => !isSpace(char))
    if start < 0 then ""
    else text.substring(start, text.lastIndexWhere(char => !isSpace(char)) + 1)

  /** Python `str.lstrip()`: no whitespace at the start. */
  def lstrip(text: String): String =
    val start = text.indexWhere(char => !isSpace(char))
    if start < 0 then "" else text.substring(start)

  /** Python `str.rstrip()`: no whitespace at the end. */
  def rstrip(text: String): String =
    text.substring(0, text.lastIndexWhere(char => !isSpace(char)) + 1)

  /** The characters for which Python `str.isspace` is true (the list of the dspy venv, Python 3.12). Each one is in the
    * Basic Multilingual Plane, so a test of each `Char` is a test of each code point.
    */
  def isSpace(char: Char): Boolean =
    char match
      case ' ' | '\t' | '\n' | '\u000b' | '\u000c' | '\r' | '\u001c' | '\u001d' | '\u001e' | '\u001f' | '\u0085' |
          ' ' | ' ' | ' ' | ' ' | ' ' | ' ' | '　' => true
      case other => other >= ' ' && other <= ' '

  /** Python `str.splitlines()`: the lines of `text` with no line break. `\r\n` is one line break. There is no empty
    * last line for a line break at the end.
    */
  def splitLines(text: String): Vector[String] =
    val lines = Vector.newBuilder[String]
    var start = 0
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      if isLineBreak(char) then
        lines += text.substring(start, index)
        if char == '\r' && index + 1 < text.length && text.charAt(index + 1) == '\n' then index += 1
        start = index + 1
      index += 1
    if start < text.length then lines += text.substring(start)
    lines.result()

  /** The line breaks of Python `str.splitlines`. Each one is also whitespace ([[isSpace]]). */
  def isLineBreak(char: Char): Boolean =
    char match
      case '\n' | '\u000b' | '\u000c' | '\r' | '\u001c' | '\u001d' | '\u001e' | '\u0085' | ' ' | ' ' => true
      case _                                                                                         => false

  /** The text of the JSON string `text`: the text between the double quotes at its two ends, with each JSON escape
    * decoded (`json_repair.loads` of a JSON string). `None` when `text` is not one JSON string: it has no double quote
    * at each end, it has a double quote or a line break inside, or it has an escape that JSON does not have.
    */
  def jsonString(text: String): Option[String] =
    quotedBody(text, '"').flatMap(body => unescape(body, '"', jsonEscape))

  /** The text of the Python string literal `text` (`ast.literal_eval` of a `str` literal): the text between the quotes
    * (`'` or `"`) at its two ends, after an optional prefix `u`, `U`, `r`, or `R`. Each Python escape is decoded,
    * except in a raw literal (`r` or `R`). An escape that Python does not know keeps its backslash. `None` when `text`
    * is not one such literal: another prefix, a quote of the literal or a line break inside, a bad escape, or a
    * `\N{...}` escape. A literal in triple quotes, two literals in sequence, and a literal in parentheses are not read.
    */
  def stringLiteral(text: String): Option[String] =
    val (prefix, literal) = text.span(_.isLetter)
    val raw               = prefix == "r" || prefix == "R"
    Option
      .when(prefix.isEmpty || prefix == "u" || prefix == "U" || raw)(literal)
      .flatMap(_.headOption)
      .filter(quote => quote == '\'' || quote == '"')
      .flatMap(quote =>
        quotedBody(literal, quote).flatMap(body => unescape(body, quote, if raw then rawEscape else pythonEscape))
      )

  /** The text between the two `quote` characters at the ends of `text`, or `None`. */
  def quotedBody(text: String, quote: Char): Option[String] =
    Option.when(text.length > 1 && text.head == quote && text.last == quote)(text.substring(1, text.length - 1))

  /** The text of the body `body` of a string in `quote` characters, with each escape that `escape` decodes. `escape`
    * gets the body and the index after a backslash, and gives the decoded text and the next index, or `None` for a bad
    * escape. `None` when the body has a bad escape, a `quote` character, or a line break that is not in an escape.
    */
  private def unescape(body: String, quote: Char, escape: (String, Int) => Option[(String, Int)]): Option[String] =
    val out   = new StringBuilder
    var index = 0
    var valid = true
    while valid && index < body.length do
      val char = body.charAt(index)
      if char == '\\' then
        escape(body, index + 1) match
          case Some((decoded, next)) =>
            out.append(decoded)
            index = next
          case None => valid = false
      else if char == quote || char == '\n' || char == '\r' then valid = false
      else
        out.append(char)
        index += 1
    Option.when(valid)(out.toString)

  /** A JSON escape: `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, or `\u` and four hexadecimal digits. */
  private def jsonEscape(body: String, index: Int): Option[(String, Int)] =
    charAt(body, index).flatMap {
      case 'u'  => hexCode(body, index + 1, 4).map(code => (code.toChar.toString, index + 5))
      case char => JsonEscapes.get(char).map(decoded => (decoded, index + 1))
    }

  private val JsonEscapes: Map[Char, String] =
    Map('"' -> "\"", '\\' -> "\\", '/' -> "/", 'b' -> "\b", 'f' -> "\f", 'n' -> "\n", 'r' -> "\r", 't' -> "\t")

  /** A Python escape of a `str` literal: a line break (a line continuation, which gives no text), a simple escape, one
    * to three octal digits, or `\x`, `\u`, and `\U` with two, four, and eight hexadecimal digits. Each other character
    * keeps the backslash. `\N{...}` is not read.
    */
  private def pythonEscape(body: String, index: Int): Option[(String, Int)] =
    charAt(body, index).flatMap {
      case '\n' => Some(("", index + 1))
      case '\r' => Some(("", if charAt(body, index + 1).contains('\n') then index + 2 else index + 1))
      case 'x'  => hexCode(body, index + 1, 2).map(code => (Character.toString(code), index + 3))
      case 'u'  => hexCode(body, index + 1, 4).map(code => (Character.toString(code), index + 5))
      case 'U'  => hexCode(body, index + 1, 8).filter(Character.isValidCodePoint).map(code =>
          (Character.toString(code), index + 9)
        )
      case 'N'                     => None
      case octal if isOctal(octal) =>
        val digits = body.substring(index, math.min(index + 3, body.length)).takeWhile(isOctal)
        Some((Character.toString(Integer.parseInt(digits, 8)), index + digits.length))
      case char => Some((PythonEscapes.getOrElse(char, s"\\$char"), index + 1))
    }

  private val PythonEscapes: Map[Char, String] = Map(
    '\\' -> "\\",
    '\'' -> "'",
    '"'  -> "\"",
    'a'  -> "\u0007",
    'b'  -> "\b",
    'f'  -> "\f",
    'n'  -> "\n",
    'r'  -> "\r",
    't'  -> "\t",
    'v'  -> "\u000b"
  )

  /** An escape of a raw Python literal: the backslash and the next character stay. */
  private def rawEscape(body: String, index: Int): Option[(String, Int)] =
    charAt(body, index).map(char => (s"\\$char", index + 1))

  private def isOctal(char: Char): Boolean = char >= '0' && char <= '7'

  /** The character at `index` of `text`, or `None` after the end. */
  private def charAt(text: String, index: Int): Option[Char] =
    Option.when(index < text.length)(text.charAt(index))

  /** The number of the `count` hexadecimal digits at `index` of `text`, or `None`. */
  private def hexCode(text: String, index: Int, count: Int): Option[Int] =
    Option
      .when(index + count <= text.length)(text.substring(index, index + count))
      .filter(digits => digits.forall(char => Character.digit(char, 16) >= 0))
      .map(digits => Integer.parseUnsignedInt(digits, 16))

  /** Python `textwrap.dedent`: the text with no common leading spaces and tabs. A line of spaces and tabs only becomes
    * an empty line.
    */
  def dedent(text: String): String =
    val cleaned = WhitespaceOnlyLine.replaceAllIn(text, "")
    val indents = LeadingWhitespace.findAllMatchIn(cleaned).map(_.group(1)).toVector
    val margin  = indents.headOption.map { first =>
      indents.tail.foldLeft(first) { (margin, indent) =>
        if indent.startsWith(margin) then margin
        else if margin.startsWith(indent) then indent
        else margin.zip(indent).takeWhile(_ == _).map(_._1).mkString
      }
    }
    margin.filter(_.nonEmpty).fold(cleaned)(withoutMargin(cleaned, _))

  /** `text` with no `margin` at the start of each line. Only `\n` ends a line, as in Python. */
  private def withoutMargin(text: String, margin: String): String =
    val out   = new java.lang.StringBuilder(text.length)
    var start = 0
    while start < text.length do
      val lineEnd = text.indexOf('\n', start)
      val end     = if lineEnd < 0 then text.length else lineEnd + 1
      out.append(text, if text.startsWith(margin, start) then start + margin.length else start, end)
      start = end
    out.toString

  // The patterns of `textwrap`. The flag `d` makes `\n` the only line break, as in Python.
  private val WhitespaceOnlyLine: Regex = "(?md)^[ \t]+$".r
  private val LeadingWhitespace: Regex  = "(?md)(^[ \t]*)(?:[^ \t\n])".r

  /** Python `str(value)`. A string is the text itself. A list, a dict, or a record is the JSON text of [[json]]. */
  def str(value: DynamicValue): String =
    unwrap(value) match
      case DynamicValue.Primitive(PrimitiveValue.String(text))  => text
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag)) => if flag then "True" else "False"
      case DynamicValue.Primitive(PrimitiveValue.Char(char))    => char.toString
      case DynamicValue.Primitive(_: PrimitiveValue.Unit.type)  => "None"
      case _: DynamicValue.Null.type                            => "None"
      case EnumCase(name)                                       => name
      case NumberText(text)                                     => text
      case primitive: DynamicValue.Primitive                    => DynamicValues.renderText(primitive)
      case other                                                => json(other)

  /** Python `json.dumps(value, ensure_ascii=False)`, with the separators `", "` and `": "`. With `indent`, the form of
    * `json.dumps(value, indent=indent, ensure_ascii=False)`.
    */
  def json(value: DynamicValue, indent: Option[Int] = None): String =
    val out = new StringBuilder
    writeJson(value, new JsonStyle(indent, ascii = false), 0, out)
    out.toString

  /** Python `json.dumps(value)` with the default `ensure_ascii=True`: as [[json]], but each character that is not a
    * printable ASCII character is an escape (a backslash, `u`, and four hexadecimal digits). A character above U+FFFF
    * is two escapes (a surrogate pair).
    */
  def jsonAscii(value: DynamicValue): String =
    val out = new StringBuilder
    writeJson(value, new JsonStyle(indent = None, ascii = true), 0, out)
    out.toString

  /** The options of `json.dumps`: `indent`, and `ascii` for `ensure_ascii=True`. One style serves one call of [[json]]
    * or [[jsonAscii]]. It keeps the line start of each indent level that the call uses, so that the call builds each
    * indent text one time.
    */
  private final class JsonStyle(indent: Option[Int], val ascii: Boolean):
    private val lineStarts = mutable.ArrayBuffer.empty[String]

    /** The text between two items: `","` with an indent, else `", "`. */
    val separator: String = if indent.isDefined then "," else ", "

    /** The level of the items of a container at `level`: one level more with an indent. */
    def nested(level: Int): Int = if indent.isDefined then level + 1 else level

    /** The text before an item and before the close of a container at `level`: a line break and the indent of `level`
      * with an indent, else no text.
      */
    def lineStart(level: Int): String =
      indent.fold("") { width =>
        while lineStarts.length <= level do lineStarts += "\n" + " " * (width * lineStarts.length)
        lineStarts(level)
      }

  private def writeJson(value: DynamicValue, style: JsonStyle, level: Int, out: StringBuilder): Unit =
    def writeFields(fields: Iterator[(String, DynamicValue)]): Unit = writeObject(fields, style, level, out)
    def writeText(text    : String): Unit                           = quote(text, style.ascii, out)
    unwrap(value) match
      case _: DynamicValue.Null.type                             => out ++= "null"
      case DynamicValue.Primitive(PrimitiveValue.String(text))   => writeText(text)
      case DynamicValue.Primitive(PrimitiveValue.Char(char))     => writeText(char.toString)
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))  => out ++= (if flag then "true" else "false")
      case DynamicValue.Primitive(_: PrimitiveValue.Unit.type)   => out ++= "null"
      case DynamicValue.Primitive(PrimitiveValue.Double(number)) => out ++= jsonFloat(number, PythonRepr.float(number))
      case DynamicValue.Primitive(PrimitiveValue.Float(number))  => out ++=
          jsonFloat(number.toDouble, PythonRepr.float32(number))
      case NumberText(text)                      => out ++= text
      case primitive: DynamicValue.Primitive     => writeText(DynamicValues.renderText(primitive))
      case EnumCase(name)                        => writeText(name)
      case DynamicValue.Variant(caseName, inner) => writeFields(Iterator.single(caseName -> inner))
      case record: DynamicValue.Record           => writeFields(record.fields.iterator)
      case map: DynamicValue.Map                 => writeFields(map.entries.iterator.map((key, item) => keyText(key) -> item))
      case sequence: DynamicValue.Sequence       =>
        writeContainer(sequence.elements.iterator, '[', ']', style, level, out)((item, nested) =>
          writeJson(item, style, nested, out)
        )

  private def writeObject(
      fields: Iterator[(String, DynamicValue)],
      style : JsonStyle,
      level : Int,
      out   : StringBuilder
  ): Unit =
    writeContainer(fields, '{', '}', style, level, out) { (field, nested) =>
      quote(field._1, style.ascii, out)
      out ++= ": "
      writeJson(field._2, style, nested, out)
    }

  /** The items between `open` and `close`. With no indent, the separator is `", "`. With an indent, each item is on a
    * line of its own, and the separator is `","`. An empty container is `open` and `close` only.
    */
  private def writeContainer[A](
      items: Iterator[A],
      open : Char,
      close: Char,
      style: JsonStyle,
      level: Int,
      out  : StringBuilder
  )(writeItem: (A, Int) => Unit): Unit =
    out += open
    if items.hasNext then
      val nested            = style.nested(level)
      def writeNext(): Unit =
        out ++= style.lineStart(nested)
        writeItem(items.next(), nested)
      writeNext()
      while items.hasNext do
        out ++= style.separator
        writeNext()
      out ++= style.lineStart(level)
    out += close

  /** The key of a map entry: Python `json.dumps` writes a bool, a number, and a null key as its JSON text. */
  private def keyText(key: DynamicValue): String =
    unwrap(key) match
      case DynamicValue.Primitive(PrimitiveValue.String(text))  => text
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag)) => if flag then "true" else "false"
      case _: DynamicValue.Null.type                            => "null"
      case NumberText(text)                                     => text
      case other                                                => DynamicValues.renderText(other)

  /** The JSON string of `text` (Python `encode_basestring`): `"` and `\` escaped, a control character escaped, and each
    * other character as it is. With `ascii` (Python `encode_basestring_ascii`), each character that is not in the range
    * from a space to `~` is also an escape.
    */
  private def quote(text: String, ascii: Boolean, out: StringBuilder): Unit =
    out += '"'
    text.foreach {
      case '"'                                         => out ++= "\\\""
      case '\\'                                        => out ++= "\\\\"
      case '\n'                                        => out ++= "\\n"
      case '\r'                                        => out ++= "\\r"
      case '\t'                                        => out ++= "\\t"
      case '\b'                                        => out ++= "\\b"
      case '\f'                                        => out ++= "\\f"
      case char if char < ' ' || (ascii && char > '~') => unicodeEscape(char, out)
      case char                                        => out += char
    }
    out += '"'

  /** The escape of `char`: a backslash, `u`, and four lower-case hexadecimal digits. */
  private def unicodeEscape(char: Char, out: StringBuilder): Unit =
    out ++= "\\u"
    out += HexDigits((char >> 12) & 0xf)
    out += HexDigits((char >> 8) & 0xf)
    out += HexDigits((char >> 4) & 0xf)
    out += HexDigits(char & 0xf)

  private val HexDigits: String = "0123456789abcdef"

  /** The Python text of an integer, a float, or a decimal value. */
  private object NumberText:
    def unapply(value: DynamicValue): Option[String] =
      value match
        case DynamicValue.Primitive(PrimitiveValue.Int(number))        => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.Long(number))       => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.Short(number))      => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.Byte(number))       => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.BigInt(number))     => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.BigDecimal(number)) => Some(number.toString)
        case DynamicValue.Primitive(PrimitiveValue.Double(number))     => Some(PythonRepr.float(number))
        case DynamicValue.Primitive(PrimitiveValue.Float(number))      => Some(PythonRepr.float32(number))
        case _                                                         => None

  /** An enum case with no fields: its case name. */
  private object EnumCase:
    def unapply(value: DynamicValue): Option[String] =
      value match
        case DynamicValue.Variant(caseName, DynamicValue.Record(fields)) if fields.isEmpty => Some(caseName)
        case _                                                                             => None

  /** A float in a JSON text: `NaN`, `Infinity`, and `-Infinity` for the values that are not finite, as Python
    * `json.dumps`. Each other value is `text`.
    */
  private def jsonFloat(number: Double, text: String): String =
    if number.isNaN then "NaN"
    else if number.isPosInfinity then "Infinity"
    else if number.isNegInfinity then "-Infinity"
    else text

  /** The element of an `Option` value (the `Some` variant), and a null for the `None` variant. Each other value stays
    * as it is.
    */
  private def unwrap(value: DynamicValue): DynamicValue =
    value match
      case DynamicValue.Variant("None", DynamicValue.Record(fields)) if fields.isEmpty => DynamicValue.Null
      case DynamicValue.Variant("Some", DynamicValue.Record(fields))
          if fields.size == 1 && fields.headOption.exists(_._1 == "value") =>
        fields.headOption.fold(value)(field => unwrap(field._2))
      case other => other

  /** The value of the JSON text `text` (Python `json.loads`), or a [[ParseError]] when the text is not JSON. A number
    * with a decimal point or an exponent is a float (a `Double`). Each other number is an integer: an `Int`, a `Long`,
    * or a `BigInt`, by its size. Thus an integer keeps its exact value, and [[json]] writes each number again as the
    * text has it. When an object has a key two times, the last value wins at the position of the first key, as in a
    * Python `dict`.
    *
    * The reader of ujson has no recursion, and the visitor builds each container in one pass, so a deep value does not
    * fill the stack. If a `StackOverflowError` occurs all the same, the result is a `Left`: a text from a model cannot
    * stop the caller.
    */
  def parseJson(text: String): Either[DspyError, DynamicValue] =
    try Right(ujson.transform(ujson.Readable.fromString(text), JsonValues))
    catch
      case _: StackOverflowError => Left(ParseError("json", "the value is nested too deep"))
      case NonFatal(error)       => Left(ParseError("json", DspyError.messageOf(error)))

  /** The visitor that builds the value of [[parseJson]] as the reader of ujson reads the text. */
  private object JsonValues extends JsVisitor[DynamicValue, DynamicValue]:
    def visitNull(index : Int): DynamicValue = DynamicValue.Null
    def visitTrue(index : Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(true))
    def visitFalse(index: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(false))

    def visitString(text: CharSequence, index: Int): DynamicValue = DynamicValues.string(text.toString)

    def visitFloat64StringParts(text: CharSequence, decIndex: Int, expIndex: Int, index: Int): DynamicValue =
      number(text.toString, integral = decIndex < 0 && expIndex < 0)

    def visitArray(length: Int, index: Int): ArrVisitor[DynamicValue, DynamicValue] =
      new ArrVisitor[DynamicValue, DynamicValue]:
        private val items                                     = Chunk.newBuilder[DynamicValue]
        def subVisitor: Visitor[?, ?]                         = JsonValues
        def visitValue(value: DynamicValue, index: Int): Unit = items += value
        def visitEnd(index  : Int): DynamicValue              = DynamicValue.Sequence(items.result())

    def visitJsonableObject(length: Int, index: Int): ObjVisitor[DynamicValue, DynamicValue] =
      new ObjVisitor[DynamicValue, DynamicValue]:
        private val fields                                       = mutable.LinkedHashMap.empty[String, DynamicValue]
        private var key                                          = ""
        def visitKey(index     : Int): Visitor[?, ?]             = StringVisitor
        def visitKeyValue(value: Any): Unit                      = key = value.toString
        def subVisitor: Visitor[?, ?]                            = JsonValues
        def visitValue(value   : DynamicValue, index: Int): Unit = fields.update(key, value)
        def visitEnd(index     : Int): DynamicValue              = DynamicValue.Record(Chunk.from(fields))

  /** The value of the JSON number `text`: an integer when `integral`, else a float. */
  private def number(text: String, integral: Boolean): DynamicValue =
    if integral then
      DynamicValue.Primitive(
        text.toIntOption.map(PrimitiveValue.Int(_))
          .orElse(text.toLongOption.map(PrimitiveValue.Long(_)))
          .getOrElse(PrimitiveValue.BigInt(BigInt(text)))
      )
    else DynamicValue.Primitive(PrimitiveValue.Double(text.toDouble))
