package dspy4s.adapters.internal

import dspy4s.core.contracts.DynamicValues
import upickle.core.BufferedValue
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.Try
import scala.util.matching.Regex

/** The text forms of Python that the prompts of upstream use: `str()` and `repr()` of a value, `json.dumps` with
  * `ensure_ascii=False` and with ASCII escapes, `str.strip`, `str.splitlines`, and `textwrap.dedent`. The adapters use
  * these forms so that a prompt is equal to the upstream prompt, character for character (decision D3).
  *
  * A value is a `DynamicValue`. An `Option` value of a typed record (the `Some` and `None` variants) prints as its
  * element or as `None`. An enum case with no fields prints as its case name.
  */
private[adapters] object PythonText:

  /** Python `str.strip()`: no whitespace at the start and at the end. Python whitespace is `str.isspace`. */
  def strip(text: String): String =
    val start = text.indexWhere(char => !isSpace(char))
    if start < 0 then ""
    else text.substring(start, text.lastIndexWhere(char => !isSpace(char)) + 1)

  /** The characters for which Python `str.isspace` is true. */
  private def isSpace(char: Char): Boolean =
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

  private def isLineBreak(char: Char): Boolean =
    char match
      case '\n' | '\u000b' | '\u000c' | '\r' | '\u001c' | '\u001d' | '\u001e' | '\u0085' | ' ' | ' ' => true
      case _                                                                                         => false

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
    margin.filter(_.nonEmpty).fold(cleaned)(prefix => s"(?m)^${Regex.quote(prefix)}".r.replaceAllIn(cleaned, ""))

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
    writeJson(value, JsonStyle(indent, ascii = false), 0, out)
    out.toString

  /** Python `json.dumps(value)` with the default `ensure_ascii=True`: as [[json]], but each character that is not a
    * printable ASCII character is an escape (a backslash, `u`, and four hexadecimal digits). A character above U+FFFF
    * is two escapes (a surrogate pair).
    */
  def jsonAscii(value: DynamicValue): String =
    val out = new StringBuilder
    writeJson(value, JsonStyle(indent = None, ascii = true), 0, out)
    out.toString

  /** The options of `json.dumps`: `indent`, and `ascii` for `ensure_ascii=True`. */
  private final case class JsonStyle(indent: Option[Int], ascii: Boolean)

  private def writeJson(value: DynamicValue, style: JsonStyle, level: Int, out: StringBuilder): Unit =
    def writeFields(fields: Vector[(String, DynamicValue)]): Unit = writeObject(fields, style, level, out)
    def writeText(text    : String): Unit                         = quote(text, style.ascii, out)
    unwrap(value) match
      case _: DynamicValue.Null.type                             => out ++= "null"
      case DynamicValue.Primitive(PrimitiveValue.String(text))   => writeText(text)
      case DynamicValue.Primitive(PrimitiveValue.Char(char))     => writeText(char.toString)
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))  => out ++= (if flag then "true" else "false")
      case DynamicValue.Primitive(_: PrimitiveValue.Unit.type)   => out ++= "null"
      case DynamicValue.Primitive(PrimitiveValue.Double(number)) => out ++= jsonFloat(number, float(number))
      case DynamicValue.Primitive(PrimitiveValue.Float(number))  => out ++= jsonFloat(number.toDouble, float32(number))
      case NumberText(text)                                      => out ++= text
      case primitive: DynamicValue.Primitive                     => writeText(DynamicValues.renderText(primitive))
      case EnumCase(name)                                        => writeText(name)
      case DynamicValue.Variant(caseName, inner)                 => writeFields(Vector(caseName -> inner))
      case record: DynamicValue.Record                           => writeFields(record.fields.toVector)
      case map: DynamicValue.Map                                 => writeFields(entriesOf(map))
      case sequence: DynamicValue.Sequence                       =>
        writeContainer(sequence.elements.toVector, "[", "]", style.indent, level, out)((item, nested) =>
          writeJson(item, style, nested, out)
        )

  private def writeObject(
      fields: Vector[(String, DynamicValue)],
      style : JsonStyle,
      level : Int,
      out   : StringBuilder
  ): Unit =
    writeContainer(fields, "{", "}", style.indent, level, out) { (field, nested) =>
      quote(field._1, style.ascii, out)
      out ++= ": "
      writeJson(field._2, style, nested, out)
    }

  /** The items between `open` and `close`. With no indent, the separator is `", "`. With an indent, each item is on a
    * line of its own, and the separator is `","`. An empty container is `open` and `close` only.
    */
  private def writeContainer[A](
      items : Vector[A],
      open  : String,
      close : String,
      indent: Option[Int],
      level : Int,
      out   : StringBuilder
  )(writeItem: (A, Int) => Unit): Unit =
    out ++= open
    if items.nonEmpty then
      indent match
        case Some(width) =>
          val inner = "\n" + " " * (width * (level + 1))
          items.zipWithIndex.foreach { (item, index) =>
            if index > 0 then out += ','
            out ++= inner
            writeItem(item, level + 1)
          }
          out ++= "\n" + " " * (width * level)
        case None => items.zipWithIndex.foreach { (item, index) =>
            if index > 0 then out ++= ", "
            writeItem(item, level)
          }
    out ++= close

  private def entriesOf(map: DynamicValue.Map): Vector[(String, DynamicValue)] =
    map.entries.toVector.map((key, item) => keyText(key) -> item)

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
      case char if char < ' ' || (ascii && char > '~') => out ++= f"\\u${char.toInt}%04x"
      case char                                        => out += char
    }
    out += '"'

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
        case DynamicValue.Primitive(PrimitiveValue.Double(number))     => Some(float(number))
        case DynamicValue.Primitive(PrimitiveValue.Float(number))      => Some(float32(number))
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

  /** Python `repr(number)`: the shortest digits that give the same float, a fixed point form for an exponent from -4 to
    * 15 (with `.0` for an integral value), and else the exponent form, for example `1e-05` and `1e+16`.
    */
  def float(number: Double): String =
    if number.isNaN then "nan"
    else if number.isInfinite then (if number > 0 then "inf" else "-inf")
    else
      val sign            = if java.lang.Double.compare(number, 0.0) < 0 then "-" else ""
      val magnitude       = math.abs(number)
      val (digits, point) = shortest(java.lang.Double.toString(magnitude), text => text.toDouble == magnitude)
      sign + layout(digits, point)

  /** [[float]] for a 32-bit float: the shortest digits that give the same 32-bit float. */
  private def float32(number: Float): String =
    if number.isNaN then "nan"
    else if number.isInfinite then (if number > 0 then "inf" else "-inf")
    else
      val sign            = if java.lang.Float.compare(number, 0.0f) < 0 then "-" else ""
      val magnitude       = math.abs(number)
      val (digits, point) = shortest(java.lang.Float.toString(magnitude), text => text.toFloat == magnitude)
      sign + layout(digits, point)

  /** The digits and the decimal point of the Java text of a positive number: the number is `0.<digits> × 10^point`.
    * Java gives the shortest digits, but it can give two digits where one digit also gives the same number. Python then
    * uses the one digit, so this function checks the one-digit forms. `same` tells if a decimal text gives the same
    * number.
    */
  private def shortest(javaText: String, same: String => Boolean): (String, Int) =
    val (mantissa, exponent) = javaText.indexOf('E') match
      case -1 => (javaText, 0)
      case at => (javaText.substring(0, at), javaText.substring(at + 1).toInt)
    val dot     = mantissa.indexOf('.')
    val whole   = if dot < 0 then mantissa else mantissa.substring(0, dot)
    val all     = if dot < 0 then mantissa else whole + mantissa.substring(dot + 1)
    val leading = all.indexWhere(_ != '0')
    if leading < 0 then ("0", 1)
    else
      val digits = all.substring(leading).reverse.dropWhile(_ == '0').reverse
      val point  = whole.length + exponent - leading
      if digits.length != 2 then (digits, point)
      else
        val first      = digits.head.asDigit
        val candidates =
          Vector((first.toString, point), (if first == 9 then ("1", point + 1) else ((first + 1).toString, point)))
            .filter((digit, at) => same(s"0.${digit}E$at"))
        val exact = BigDecimal(s"0.${digits}E$point")
        candidates.minByOption((digit, at) => (BigDecimal(s"0.${digit}E$at") - exact).abs).getOrElse((digits, point))

  /** The Python `repr` layout of `0.<digits> × 10^point` (`format_float_short` with the mode `r`). */
  private def layout(digits: String, point: Int): String =
    if point <= -4 || point > 16 then
      val exponent = point - 1
      val mantissa = if digits.length > 1 then s"${digits.head}.${digits.tail}" else digits
      f"${mantissa}e${if exponent < 0 then "-" else "+"}${math.abs(exponent)}%02d"
    else if point <= 0 then "0." + "0" * -point + digits
    else if point >= digits.length then digits + "0" * (point - digits.length) + ".0"
    else s"${digits.substring(0, point)}.${digits.substring(point)}"

  /** The value of a JSON text, or `None` when the text is not JSON. A number with a decimal point or an exponent is a
    * float, and each other number is an integer, so [[json]] writes the number again as the text has it.
    */
  def parseJson(text: String): Option[DynamicValue] =
    Try(ujson.transform(ujson.Readable.fromString(text), BufferedValue.Builder)).toOption.flatMap(fromBuffered)

  private def fromBuffered(value: BufferedValue): Option[DynamicValue] =
    value match
      case BufferedValue.Str(text, _)                     => Some(primitive(PrimitiveValue.String(text.toString)))
      case BufferedValue.Num(text, decIndex, expIndex, _) => Some(number(text.toString, decIndex < 0 && expIndex < 0))
      case BufferedValue.True(_)                          => Some(primitive(PrimitiveValue.Boolean(true)))
      case BufferedValue.False(_)                         => Some(primitive(PrimitiveValue.Boolean(false)))
      case BufferedValue.Null(_)                          => Some(DynamicValue.Null)
      case BufferedValue.Arr(items, _)                    =>
        val values = items.toVector.map(fromBuffered)
        Option.when(values.forall(_.isDefined))(DynamicValue.Sequence(Chunk.from(values.flatten)))
      case BufferedValue.Obj(items, _, _) =>
        val fields = items.toVector.map {
          case (BufferedValue.Str(key, _), item) => fromBuffered(item).map(key.toString -> _)
          case _                                 => None
        }
        Option.when(fields.forall(_.isDefined))(DynamicValue.Record(Chunk.from(fields.flatten)))
      case _ => None

  private def primitive(value: PrimitiveValue): DynamicValue = DynamicValue.Primitive(value)

  private def number(text: String, integral: Boolean): DynamicValue =
    if integral then
      primitive(
        text.toIntOption.map(PrimitiveValue.Int(_))
          .orElse(text.toLongOption.map(PrimitiveValue.Long(_)))
          .getOrElse(PrimitiveValue.BigInt(BigInt(text)))
      )
    else primitive(PrimitiveValue.Double(text.toDouble))
