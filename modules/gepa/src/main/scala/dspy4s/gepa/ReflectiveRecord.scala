package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** One example that the reflection LM reads to improve the instruction of a component: gepa's
  * `{Inputs, Generated Outputs, Feedback}` record (dspy's `ReflectiveExample`). The reflective dataset of a component
  * is a sequence of these records. See PORT_GAPS G-12 and G-45.
  *
  * The record holds values, not text. The [[InstructionProposer]] selects the text form.
  *
  * @param inputs
  *   the input values of one predictor call of one trajectory. An input of the type
  *   [[dspy4s.core.contracts.TypeRef.history]] is under the key [[ReflectiveRecord.ContextKey]], and that key is first.
  * @param generatedOutputs
  *   the record of the output values of that call. For a call that failed, it is one string: the raw response of the
  *   model in a fence, or a note that the call gave no output.
  * @param feedback
  *   the feedback of the [[dspy4s.gepa.contracts.FeedbackMetric]] for that predictor. For a call that failed, it is the
  *   output structure to follow, or the error of the call.
  *
  * The record of a call that failed exists only with `addFormatFailureAsFeedback`.
  */
final case class ReflectiveRecord(
    inputs          : DynamicValue.Record,
    generatedOutputs: DynamicValue,
    feedback        : String
):

  /** The record with the gepa keys, in the gepa order: `Inputs`, `Generated Outputs`, `Feedback`. */
  def toRecord: DynamicValue.Record =
    DynamicValues.record(
      ReflectiveRecord.InputsKey   -> inputs,
      ReflectiveRecord.OutputsKey  -> generatedOutputs,
      ReflectiveRecord.FeedbackKey := feedback
    )

object ReflectiveRecord:
  val InputsKey: String   = "Inputs"
  val OutputsKey: String  = "Generated Outputs"
  val FeedbackKey: String = "Feedback"

  /** The key of [[ReflectiveRecord.inputs]] that holds the conversation history of the call (dspy's `"Context"`). */
  val ContextKey: String = "Context"

  /** The JSON text of `records`: one JSON array of the [[ReflectiveRecord.toRecord]] objects, on one line.
    *
    * The form is that of Python's `json.dumps(records, ensure_ascii=False)`, which is the text that the upstream JSON
    * adapter gives to the reflection LM:
    *   - `", "` separates the items and `": "` separates a key from its value.
    *   - The keys of a record keep their order.
    *   - A character that is not ASCII stays as it is. A quote, a backslash, and a control character get an escape.
    *
    * A number has the text that Python gives: digits for an integer, and the text of `repr` for a `Float` and a
    * `Double`.
    *
    * A value that Python's `json.dumps` does not write gets one of these forms:
    *   - A `DynamicValue.Variant` whose value is an empty record (the case of an enum) is the name of the case, as a
    *     string. An `Option` is different: the case `None` with no field is `null`, and the case `Some` with the one
    *     field `value` is that value. Each other variant is an object with one key, the name of the case.
    *   - A `DynamicValue.Map` is an object. A key that is not a string is the text of that key, as Python does for a
    *     number and a boolean.
    *   - A primitive that is not a string, a number, or a boolean is the text that an adapter shows for it
    *     ([[DynamicValues.renderText]]), as a string. The unit value is `null`.
    */
  def toJson(records: Vector[ReflectiveRecord]): String =
    records.iterator.map(record => json(record.toRecord)).mkString("[", ", ", "]")

  private def json(value: DynamicValue): String =
    value match
      case DynamicValue.Primitive(primitive) => scalar(primitive).fold(quote, identity)
      case record: DynamicValue.Record       => jsonObject(record.fields.iterator)
      case sequence: DynamicValue.Sequence   => sequence.elements.iterator.map(json).mkString("[", ", ", "]")
      case map: DynamicValue.Map             => jsonObject(map.entries.iterator.map((key, item) => keyText(key) -> item))
      case DynamicValue.Variant(name, item)  => variant(name, item)
      case _: DynamicValue.Null.type         => "null"

  private def jsonObject(entries: Iterator[(String, DynamicValue)]): String =
    entries.map((key, item) => s"${quote(key)}: ${json(item)}").mkString("{", ", ", "}")

  // The form that zio-blocks gives to an `Option`: the case `Some` with one field, and the case `None` with no field.
  private val SomeCase  = "Some"
  private val SomeField = "value"
  private val NoneCase  = "None"

  /** The JSON text of the case `name` of a variant, with the value `item`. */
  private def variant(name: String, item: DynamicValue): String =
    val fields = item match
      case record: DynamicValue.Record => Some(DynamicValues.recordEntries(record))
      case _                           => None
    (name, fields) match
      case (NoneCase, Some(Vector()))                  => "null"
      case (_, Some(Vector()))                         => quote(name)
      case (SomeCase, Some(Vector((SomeField, held)))) => json(held)
      case _                                           => jsonObject(Iterator(name -> item))

  /** The text of a primitive: `Right` for the text of a JSON number, boolean, or null, and `Left` for the content of a
    * JSON string.
    */
  private def scalar(primitive: PrimitiveValue): Either[String, String] =
    primitive match
      case PrimitiveValue.String(text)       => Left(text)
      case PrimitiveValue.Boolean(flag)      => Right(flag.toString)
      case PrimitiveValue.Byte(number)       => Right(number.toString)
      case PrimitiveValue.Short(number)      => Right(number.toString)
      case PrimitiveValue.Int(number)        => Right(number.toString)
      case PrimitiveValue.Long(number)       => Right(number.toString)
      case PrimitiveValue.BigInt(number)     => Right(number.toString)
      case PrimitiveValue.BigDecimal(number) => Right(number.toString)
      case PrimitiveValue.Float(number)      =>
        Right(if java.lang.Float.isFinite(number) then pythonFloat(number.toString) else number.toString)
      case PrimitiveValue.Double(number) =>
        Right(if java.lang.Double.isFinite(number) then pythonFloat(number.toString) else number.toString)
      case _: PrimitiveValue.Unit.type => Right("null")
      case other                       => Left(DynamicValues.renderText(DynamicValue.Primitive(other)))

  /** The text that Python's `repr` gives for a finite float. `shortest` is the text that the JVM gives for the same
    * value. The two texts have the same digits. They put the point and the exponent in different positions: Python uses
    * an exponent only for a value below `1e-4` or from `1e16`.
    *
    * Exception: for a few values near the smallest `Double`, the JVM gives two digits and Python gives one digit. The
    * result keeps the two digits.
    *
    * `NaN` and an infinite value have the same text in the JVM and in Python, so they do not come here.
    */
  private def pythonFloat(shortest: String): String =
    val decimal = new java.math.BigDecimal(shortest).stripTrailingZeros
    val sign    = if shortest.startsWith("-") then "-" else ""
    if decimal.signum == 0 then s"${sign}0.0"
    else
      val digits = decimal.unscaledValue.abs.toString
      // The power of ten of the first digit.
      val exponent = digits.length - decimal.scale - 1
      if exponent < -4 || exponent >= 16 then
        val mantissa = if digits.length == 1 then digits else s"${digits.take(1)}.${digits.drop(1)}"
        f"$sign${mantissa}e${if exponent < 0 then "-" else "+"}${math.abs(exponent)}%02d"
      else if exponent < 0 then s"${sign}0.${"0" * (-exponent - 1)}$digits"
      else if digits.length <= exponent + 1 then s"$sign$digits${"0" * (exponent + 1 - digits.length)}.0"
      else s"$sign${digits.take(exponent + 1)}.${digits.drop(exponent + 1)}"

  /** The key of a JSON object for the key of a `DynamicValue.Map`. */
  private def keyText(key: DynamicValue): String =
    key match
      case DynamicValue.Primitive(primitive) => scalar(primitive).merge
      case other                             => json(other)

  /** `text` as a JSON string, with the escapes of Python's `json.dumps(text, ensure_ascii=False)`. */
  private def quote(text: String): String =
    text.iterator.map {
      case '"'                      => "\\\""
      case '\\'                     => "\\\\"
      case '\n'                     => "\\n"
      case '\r'                     => "\\r"
      case '\t'                     => "\\t"
      case '\b'                     => "\\b"
      case '\f'                     => "\\f"
      case control if control < ' ' => f"\\u${control.toInt}%04x"
      case other                    => other.toString
    }.mkString("\"", "", "\"")
