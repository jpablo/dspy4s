package dspy4s.core.contracts

import zio.blocks.schema.{DynamicValue, PrimitiveType, PrimitiveValue}

import java.util.Locale
import scala.util.control.Exception.catching
import scala.util.matching.Regex

/** The lenient rules that read a value of a model as a primitive. The typed decode (`ZioSchemaCodec.normalize`), the
  * parsers of the adapters ([[valueOf]]), and the accessors of [[dspy4s.core.data.RawPrediction]] use these rules. Thus
  * a value has one meaning on each path.
  *
  * Each number text and each bool text that upstream `parse_value` reads for its type (`json_repair`, then
  * `ast.literal_eval`, then pydantic lax mode), these rules read with the same value. `docs/port/PORT_DIFFERENCES.md`
  * lists the differences.
  *
  *   - A text can be a number with spaces at the two ends, a currency symbol (`$ € £ ¥`), a `+` at the start, commas
  *     that separate the thousands (`"$2,399"`), and a `_` between two digits (`"1_000"`). See [[cleanNumeric]].
  *   - A number text has one grammar: decimal digits, a decimal point, and an exponent (`42`, `.5`, `5.`, `1e3`). A
  *     form that only Java reads (`3d`, `0x1p3`) is no number. A Python integer literal (`0x10`) is no number.
  *   - A float text can also be `inf`, `infinity`, or `nan`, in any case. `1e400` is an infinite float.
  *   - A text with only digits keeps its exact value. Each other number text has the value of a `Double`, as a Python
  *     float has. An integer target accepts it when that value is whole (`3.0`, `1e3`). Thus `1e400` is no integer.
  *   - A number of one numeric type becomes a number of a different numeric type. An integer target accepts only a
  *     whole number in its range: the rules do not round a number and do not cut it.
  *   - The texts `true`, `yes`, `on`, `t`, and `y`, and the texts `false`, `no`, `off`, `f`, and `n`, in any case,
  *     become a `Boolean`. The number 1 and a number text with the value 1 are true. The number 0 and a number text
  *     with the value 0 are false. Each other number is no `Boolean`.
  *   - A text with exactly one character becomes a `Char`.
  *   - The texts of [[NullTexts]], in any case, are a null for an optional field whose type does not read the text as a
  *     value. An `Option[String]` keeps each text, as upstream keeps it for an `Optional[str]`.
  */
private[dspy4s] object LenientValues:

  /** The texts that are a null for an optional field, in any case: the empty text, `null`, `none`, and `n/a`. */
  val NullTexts: Vector[String] = Vector("", "null", "none", "n/a")

  /** `true` when `text`, with no spaces at the two ends, is one of [[NullTexts]] in any case. */
  def isNullText(text: String): Boolean =
    val trimmed = text.trim
    NullTexts.exists(_.equalsIgnoreCase(trimmed))

  /** The text of an integer after [[cleanNumeric]]. */
  private val IntegerText: Regex = "-?[0-9]+".r

  /** The text of a number after [[cleanNumeric]]: an integer, a decimal form, or either of them with an exponent. */
  private val NumberText: Regex = "-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?".r

  /** A `_` between two digits. A `_` at a different position stays, thus the text is no number. */
  private val DigitSeparator: Regex = "(?<=[0-9])_(?=[0-9])".r

  /** A number whose integer part has commas that separate the thousands: `1,200`, `1,234.5`. */
  private val ThousandsGroups: Regex = "-?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?".r

  /** Remove the text of a model around a number, so that [[IntegerText]] and [[NumberText]] can read it. The function
    * removes the spaces at the two ends, a currency symbol (`$ € £ ¥`) and a `+` at the start, each `_` between two
    * digits, and the commas of a text that has them only to separate the thousands ([[ThousandsGroups]]). Each other
    * comma stays, thus the text is no number: a decimal comma (`3,5`) must not become a larger number. The function
    * keeps the decimal point and a `-` at the start. A `+` before a `-` stays, thus the text is no number.
    */
  def cleanNumeric(text: String): String =
    val trimmed  = text.trim
    val noSymbol = if trimmed.nonEmpty && "$€£¥".contains(trimmed.head) then trimmed.tail.trim else trimmed
    val noSign   = if noSymbol.startsWith("+") && !noSymbol.startsWith("+-") then noSymbol.tail else noSymbol
    val digits   = DigitSeparator.replaceAllIn(noSign, "")
    if ThousandsGroups.matches(digits) then digits.filter(_ != ',') else digits

  /** `value` in the form of the primitive type `target`, when a rule of this object applies. Else `value` stays as it
    * is, and the decode of the target gives the error.
    */
  def coerce(value: DynamicValue, target: PrimitiveType[?]): DynamicValue =
    val coerced: Option[PrimitiveValue] = target match
      case _: PrimitiveType.Boolean    => toBoolean(value).map(PrimitiveValue.Boolean(_))
      case _: PrimitiveType.Byte       => toByte(value).map(PrimitiveValue.Byte(_))
      case _: PrimitiveType.Short      => toShort(value).map(PrimitiveValue.Short(_))
      case _: PrimitiveType.Int        => toInt(value).map(PrimitiveValue.Int(_))
      case _: PrimitiveType.Long       => toLong(value).map(PrimitiveValue.Long(_))
      case _: PrimitiveType.BigInt     => toBigInt(value).map(PrimitiveValue.BigInt(_))
      case _: PrimitiveType.Float      => toFloat(value).map(PrimitiveValue.Float(_))
      case _: PrimitiveType.Double     => toDouble(value).map(PrimitiveValue.Double(_))
      case _: PrimitiveType.BigDecimal => toBigDecimal(value).map(PrimitiveValue.BigDecimal(_))
      case _: PrimitiveType.Char       => toChar(value).map(PrimitiveValue.Char(_))
      case _                           => None
    coerced.fold(value)(DynamicValue.Primitive(_))

  /** The value of `value` for an output field of the type `typeRef`, when `typeRef` is [[TypeRef.int]],
    * [[TypeRef.double]], or [[TypeRef.bool]]. An integer has the type of [[integerOf]]. `None` when `value` does not
    * fit the type, and for each other type.
    */
  def valueOf(value: DynamicValue, typeRef: TypeRef): Option[DynamicValue] =
    typeRef match
      case TypeRef.int    => toBigInt(value).map(integerOf)
      case TypeRef.double => toDouble(value).map(number => DynamicValue.Primitive(PrimitiveValue.Double(number)))
      case TypeRef.bool   => toBoolean(value).map(flag => DynamicValue.Primitive(PrimitiveValue.Boolean(flag)))
      case _              => None

  /** The integer `whole` as the smallest of `Int`, `Long`, and `BigInt` that holds it. A Python `int` has no size
    * limit, so an `int` output keeps its exact value. The typed decode then reads the value into the type of the field.
    */
  private def integerOf(whole: BigInt): DynamicValue =
    DynamicValue.Primitive(
      if whole.isValidInt then PrimitiveValue.Int(whole.toInt)
      else if whole.isValidLong then PrimitiveValue.Long(whole.toLong)
      else PrimitiveValue.BigInt(whole)
    )

  /** A text with exactly one character. The JSON schema of a `Char` is such a string. */
  private def toChar(value: DynamicValue): Option[Char] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) if s.length == 1 => s.headOption
      case _                                                                 => None

  /** A `Boolean`, a bool text in any case, or a number or a number text with the value 1 or 0. */
  def toBoolean(value: DynamicValue): Option[Boolean] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Some(b)
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => s.trim.toLowerCase(Locale.ROOT) match
          case "true" | "yes" | "on" | "t" | "y"  => Some(true)
          case "false" | "no" | "off" | "f" | "n" => Some(false)
          case _                                  => booleanOfNumber(value)
      case _ => booleanOfNumber(value)

  /** A whole number in the range of `Byte`, or a number text with such a value. */
  def toByte(value: DynamicValue): Option[Byte] =
    integer(value, n => Option.when(n.isValidByte)(n.toByte))

  /** A whole number in the range of `Short`, or a number text with such a value. */
  def toShort(value: DynamicValue): Option[Short] =
    integer(value, n => Option.when(n.isValidShort)(n.toShort))

  /** A whole number in the range of `Int`, or a number text with such a value. A number that is not whole is not an
    * `Int`.
    */
  def toInt(value: DynamicValue): Option[Int] =
    integer(value, n => Option.when(n.isValidInt)(n.toInt))

  /** A whole number in the range of `Long`, or a number text with such a value. */
  def toLong(value: DynamicValue): Option[Long] =
    integer(value, n => Option.when(n.isValidLong)(n.toLong))

  /** A whole number, or a number text with such a value. */
  def toBigInt(value: DynamicValue): Option[BigInt] =
    integer(value, _.toBigIntExact)

  /** A number, or a number text. A `Double` can lose precision. */
  def toFloat(value: DynamicValue): Option[Float] =
    numberOf(value).flatMap {
      case PrimitiveValue.Float(f)  => Some(f)
      case PrimitiveValue.Double(d) => Some(d.toFloat)
      case other                    => decimalOf(other).map(_.toFloat)
    }

  /** A number, or a number text. */
  def toDouble(value: DynamicValue): Option[Double] =
    numberOf(value).flatMap {
      case PrimitiveValue.Double(d) => Some(d)
      case PrimitiveValue.Float(f)  => Some(f.toDouble)
      case other                    => decimalOf(other).map(_.toDouble)
    }

  /** A finite number, or a finite number text with its exact value. */
  def toBigDecimal(value: DynamicValue): Option[BigDecimal] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) =>
        val clean = cleanNumeric(s)
        if NumberText.matches(clean) then catching(classOf[NumberFormatException]).opt(BigDecimal(clean)) else None
      case DynamicValue.Primitive(other) => decimalOf(other)
      case _                             => None

  /** The integer of a number or a number text. `fromNumber` checks that it is whole and in the range. */
  private def integer[A](value: DynamicValue, fromNumber: BigDecimal => Option[A]): Option[A] =
    numberOf(value).flatMap(decimalOf).flatMap(fromNumber)

  /** `true` for the value 1, and `false` for the value 0, of a number or a number text. */
  private def booleanOfNumber(value: DynamicValue): Option[Boolean] =
    numberOf(value).flatMap(decimalOf).flatMap { number =>
      if number == BigDecimal(1) then Some(true)
      else if number == BigDecimal(0) then Some(false)
      else None
    }

  /** The primitive of `value`, with the number of [[numberOfText]] in place of a text. The primitive can be one that is
    * not a number, and [[decimalOf]] then gives `None`.
    */
  private def numberOf(value: DynamicValue): Option[PrimitiveValue] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => numberOfText(s)
      case DynamicValue.Primitive(other)                    => Some(other)
      case _                                                => None

  /** The number of `text`: a `BigInt` for a text with only digits, else a `Double` ([[NumberText]] and [[floatWord]]).
    * `None` when `text` is no number.
    */
  private def numberOfText(text: String): Option[PrimitiveValue] =
    val clean = cleanNumeric(text)
    if IntegerText.matches(clean) then Some(PrimitiveValue.BigInt(BigInt(clean)))
    else if NumberText.matches(clean) then Some(PrimitiveValue.Double(clean.toDouble))
    else floatWord(text).map(PrimitiveValue.Double(_))

  /** The float of the text `inf`, `infinity`, or `nan`, in any case, with spaces at the two ends and a sign (Python
    * `float`). It has no currency symbol.
    */
  private def floatWord(text: String): Option[Double] =
    text.trim.toLowerCase(Locale.ROOT) match
      case "inf" | "+inf" | "infinity" | "+infinity" => Some(Double.PositiveInfinity)
      case "-inf" | "-infinity"                      => Some(Double.NegativeInfinity)
      case "nan" | "+nan" | "-nan"                   => Some(Double.NaN)
      case _                                         => None

  /** The exact value of a numeric primitive. `None` for each other primitive, and for a `NaN` or an infinite number. */
  private def decimalOf(value: PrimitiveValue): Option[BigDecimal] =
    value match
      case PrimitiveValue.Byte(n)                 => Some(BigDecimal(n.toInt))
      case PrimitiveValue.Short(n)                => Some(BigDecimal(n.toInt))
      case PrimitiveValue.Int(n)                  => Some(BigDecimal(n))
      case PrimitiveValue.Long(n)                 => Some(BigDecimal(n))
      case PrimitiveValue.BigInt(n)               => Some(BigDecimal(n))
      case PrimitiveValue.Float(n) if n.isFinite  => Some(BigDecimal.decimal(n))
      case PrimitiveValue.Double(n) if n.isFinite => Some(BigDecimal(n))
      case PrimitiveValue.BigDecimal(n)           => Some(n)
      case _                                      => None
