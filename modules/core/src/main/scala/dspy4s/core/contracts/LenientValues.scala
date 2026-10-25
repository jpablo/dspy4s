package dspy4s.core.contracts

import zio.blocks.schema.{DynamicValue, PrimitiveType, PrimitiveValue}

import scala.util.control.Exception.catching

/** The lenient rules that read a value of a model as a primitive. The typed decode (`ZioSchemaCodec.normalize`), the
  * text parsers of the adapters, and the accessors of [[dspy4s.core.data.RawPrediction]] use these rules. Thus a value
  * has one meaning on each path.
  *
  *   - A text can be a number with spaces at the two ends, a currency symbol (`$ € £ ¥`), a `+` at the start, and
  *     commas that separate the thousands (`"$2,399"`). See [[cleanNumeric]].
  *   - A number of one numeric type becomes a number of a different numeric type. An integer target accepts only a
  *     whole number in its range: the rules do not round a number and do not cut it.
  *   - The texts `true` and `false`, in any case, become a `Boolean`.
  *   - The texts of [[NullTexts]], in any case, are a null for an optional field.
  */
private[dspy4s] object LenientValues:

  /** The texts that are a null for an optional field, in any case: the empty text, `null`, `none`, and `n/a`. */
  val NullTexts: Vector[String] = Vector("", "null", "none", "n/a")

  /** `true` when `text`, with no spaces at the two ends, is one of [[NullTexts]] in any case. */
  def isNullText(text: String): Boolean =
    val trimmed = text.trim
    NullTexts.exists(_.equalsIgnoreCase(trimmed))

  /** Remove the text of a model around a number, so that `toIntOption`, `toDoubleOption`, and the other parsers can
    * read it. The function removes the spaces at the two ends, a currency symbol (`$ € £ ¥`) and a `+` at the start,
    * and each comma that separates the thousands. It keeps the decimal point and a `-` at the start.
    */
  def cleanNumeric(text: String): String =
    val trimmed  = text.trim
    val noSymbol = if trimmed.nonEmpty && "$€£¥".contains(trimmed.head) then trimmed.tail.trim else trimmed
    val noSign   = if noSymbol.startsWith("+") then noSymbol.tail else noSymbol
    noSign.filter(_ != ',')

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
      case _                           => None
    coerced.fold(value)(DynamicValue.Primitive(_))

  /** A `Boolean`, or the text `true` or `false` in any case. */
  def toBoolean(value: DynamicValue): Option[Boolean] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Some(b)
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => s.trim.toLowerCase match
          case "true"  => Some(true)
          case "false" => Some(false)
          case _       => None
      case _ => None

  /** A whole number in the range of `Byte`, or an integer text. */
  def toByte(value: DynamicValue): Option[Byte] =
    integer(value, _.toByteOption, n => Option.when(n.isValidByte)(n.toByte))

  /** A whole number in the range of `Short`, or an integer text. */
  def toShort(value: DynamicValue): Option[Short] =
    integer(value, _.toShortOption, n => Option.when(n.isValidShort)(n.toShort))

  /** A whole number in the range of `Int`, or an integer text. A number that is not whole is not an `Int`. */
  def toInt(value: DynamicValue): Option[Int] =
    integer(value, _.toIntOption, n => Option.when(n.isValidInt)(n.toInt))

  /** A whole number in the range of `Long`, or an integer text. */
  def toLong(value: DynamicValue): Option[Long] =
    integer(value, _.toLongOption, n => Option.when(n.isValidLong)(n.toLong))

  /** A whole number, or an integer text. */
  def toBigInt(value: DynamicValue): Option[BigInt] =
    integer(value, text => catching(classOf[NumberFormatException]).opt(BigInt(text)), _.toBigIntExact)

  /** A number, or a number text. A `Double` can lose precision. */
  def toFloat(value: DynamicValue): Option[Float] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => cleanNumeric(s).toFloatOption
      case DynamicValue.Primitive(PrimitiveValue.Float(f))  => Some(f)
      case DynamicValue.Primitive(PrimitiveValue.Double(d)) => Some(d.toFloat)
      case DynamicValue.Primitive(other)                    => decimalOf(other).map(_.toFloat)
      case _                                                => None

  /** A number, or a number text. */
  def toDouble(value: DynamicValue): Option[Double] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => cleanNumeric(s).toDoubleOption
      case DynamicValue.Primitive(PrimitiveValue.Double(d)) => Some(d)
      case DynamicValue.Primitive(PrimitiveValue.Float(f))  => Some(f.toDouble)
      case DynamicValue.Primitive(other)                    => decimalOf(other).map(_.toDouble)
      case _                                                => None

  /** A finite number, or a number text. */
  def toBigDecimal(value: DynamicValue): Option[BigDecimal] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) =>
        catching(classOf[NumberFormatException]).opt(BigDecimal(cleanNumeric(s)))
      case DynamicValue.Primitive(other) => decimalOf(other)
      case _                             => None

  /** The integer of an integer text (`parse` reads the clean text), or of a number (`fromNumber` checks that it is
    * whole and in the range).
    */
  private def integer[A](
      value     : DynamicValue,
      parse     : String => Option[A],
      fromNumber: BigDecimal => Option[A]
  ): Option[A] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => parse(cleanNumeric(s))
      case DynamicValue.Primitive(other)                    => decimalOf(other).flatMap(fromNumber)
      case _                                                => None

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
