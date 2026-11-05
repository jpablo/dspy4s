package dspy4s.lm.providers

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.PythonRepr
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The text of a JSON value as Python gives it after `json.loads`: `str(value or "")`, the rule of upstream
  * `parse_stream_events` in `dspy/_vendor/lm15/providers/openai_chat.py` for a field that it reads as text.
  *
  * The JSON codec of the port reads a number with no fraction part in the range of a `Long` as an integer, also when
  * the JSON text has a decimal point or an exponent. Thus `1.0` gives `1`, where Python gives `1.0`.
  */
private[providers] object PythonStr:

  /** `str(value or "")`: a string stays as it is. A value that is false in Python (null, `false`, a zero, an empty
    * string, an empty array, an empty object) gives no text. Each other value gives the text of Python `str`.
    */
  def orEmpty(value: DynamicValue): Option[String] =
    DynamicValues.asString(value).orElse(Option.when(truthy(value))(repr(value)))

  /** Python `bool(value)`. */
  private def truthy(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type                                 => false
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))      => flag
      case DynamicValue.Primitive(PrimitiveValue.String(text))       => text.nonEmpty
      case DynamicValue.Primitive(PrimitiveValue.BigDecimal(number)) => number.signum != 0
      case DynamicValue.Primitive(PrimitiveValue.Double(number))     => number != 0.0
      case items: DynamicValue.Sequence                              => items.elements.nonEmpty
      case record: DynamicValue.Record                               => record.fields.nonEmpty
      case other                                                     => DynamicValues.asLong(other).forall(_ != 0L)

  /** Python `repr(value)`. The `str` of a value that is not a string is its `repr`. */
  private def repr(value: DynamicValue): String =
    value match
      case _: DynamicValue.Null.type                             => "None"
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))  => if flag then "True" else "False"
      case DynamicValue.Primitive(PrimitiveValue.String(text))   => quote(text)
      case DynamicValue.Primitive(PrimitiveValue.Int(number))    => number.toString
      case DynamicValue.Primitive(PrimitiveValue.Long(number))   => number.toString
      case DynamicValue.Primitive(PrimitiveValue.BigInt(number)) => number.toString
      // The codec gives a decimal value for a number out of the range of a Long, or with a fraction part. With no
      // decimal point and no exponent (scale 0), the number is a Python int. Each other number is a Python float.
      case DynamicValue.Primitive(PrimitiveValue.BigDecimal(number)) =>
        if number.scale == 0 then number.toString else PythonRepr.float(number.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Double(number)) => PythonRepr.float(number)
      case items: DynamicValue.Sequence                          => items.elements.map(repr).mkString("[", ", ", "]")
      case record: DynamicValue.Record                           =>
        record.fields.iterator.map((key, item) => s"${quote(key)}: ${repr(item)}").mkString("{", ", ", "}")
      case other => DynamicValues.renderText(other)

  /** Python `repr(text)`: single quotes, or double quotes when the text has a single quote and no double quote. A
    * backslash, the quote, a tab, a line feed, and a carriage return get a backslash escape. Each other character that
    * Python does not print (`str.isprintable`) gets a `\x`, `\u`, or `\U` escape.
    */
  private def quote(text: String): String =
    val mark = if text.contains('\'') && !text.contains('"') then "\"" else "'"
    val out  = new StringBuilder(mark)
    var at   = 0
    while at < text.length do
      val point = text.codePointAt(at)
      out ++= escape(point, mark)
      at += Character.charCount(point)
    out ++= mark
    out.toString

  /** The text of one character (a code point) in a Python string literal that `mark` quotes. */
  private def escape(point: Int, mark: String): String =
    val shown = new String(Character.toChars(point))
    if shown == "\\" || shown == mark then s"\\$shown"
    else if point == '\t'.toInt then "\\t"
    else if point == '\n'.toInt then "\\n"
    else if point == '\r'.toInt then "\\r"
    else if point < 0x20 || point == 0x7f then f"\\x$point%02x"
    else if point < 0x7f || printable(point) then shown
    else if point <= 0xff then f"\\x$point%02x"
    else if point <= 0xffff then f"\\u$point%04x"
    else f"\\U$point%08x"

  /** The character categories that Python does not print (`str.isprintable`): "Other" and "Separator". */
  private val hiddenCategories: Set[Int] = Set(
    Character.CONTROL,
    Character.FORMAT,
    Character.SURROGATE,
    Character.PRIVATE_USE,
    Character.UNASSIGNED,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.SPACE_SEPARATOR
  ).map(_.toInt)

  /** Python `str.isprintable` for one character above ASCII. */
  private def printable(point: Int): Boolean = !hiddenCategories.contains(Character.getType(point))
