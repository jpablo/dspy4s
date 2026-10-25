package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}
import zio.blocks.schema.json.WriterConfig

import java.nio.charset.StandardCharsets
import java.util.Objects
import scala.collection.immutable.VectorMap
import scala.util.control.NonFatal

/** `"name" := value` builds a schema-lifted `(name, DynamicValue)` entry for the data-bag constructors (`Example(...)`,
  * [[DynamicValues.recordFromEntries]], the dynamic `ProgramRuntime.run`). The value is converted with zio-blocks'
  * `Schema.toDynamicValue`, so it is lifted losslessly and a value type without a `Schema` is a compile error (contrast
  * the legacy `Any`-based [[DynamicValues.fromAny]]). Import via `dspy4s.core.contracts.:=`. A value that is already a
  * `DynamicValue` can use the plain `"name" -> value`.
  */
extension (key: String)
  infix def :=[A](value: A)(using schema: Schema[A]): (String, DynamicValue) = (key, schema.toDynamicValue(value))

/** `record.updated(name, value)` updates or appends a field by name. When the record has fields with that name, each of
  * them takes `value` in its position. Else the new field goes at the end. Import via `dspy4s.core.contracts.updated`.
  */
extension (record: DynamicValue.Record)
  def updated(name: String, value: DynamicValue): DynamicValue.Record =
    val fields = record.fields
    if fields.indexWhere(_._1 == name) < 0 then DynamicValue.Record(fields :+ (name -> value))
    else DynamicValue.Record(fields.map(field => if field._1 == name then name -> value else field))

/** Convenience helpers around `zio.blocks.schema.DynamicValue` / `DynamicValue.Record`. The callers at the user-input
  * boundary use them to lift plain Scala values into the interchange format, for example `Example("q" := "hello")` and
  * `DynamicValues.record("text" := text)`. The `Shape` codecs (`Shape.encode` / `Shape.decode` backed by zio-blocks
  * Schema) never go through these helpers; they produce and consume `DynamicValue` directly.
  */
object DynamicValues:

  /** Lift a plain Scala value into a `DynamicValue`. Pass-through for values that are already a `DynamicValue`. Used
    * only at user-facing boundaries, not in the `Shape` codecs.
    */
  def fromAny(value: Any): DynamicValue =
    if Objects.isNull(value) then DynamicValue.Null
    else
      value match
        case dv: DynamicValue        => dv
        case s: String               => DynamicValue.Primitive(PrimitiveValue.String(s))
        case b: Boolean              => DynamicValue.Primitive(PrimitiveValue.Boolean(b))
        case i: Int                  => DynamicValue.Primitive(PrimitiveValue.Int(i))
        case l: Long                 => DynamicValue.Primitive(PrimitiveValue.Long(l))
        case f: Float                => DynamicValue.Primitive(PrimitiveValue.Float(f))
        case d: Double               => DynamicValue.Primitive(PrimitiveValue.Double(d))
        case seq: Seq[?]             => DynamicValue.Sequence(Chunk.from(seq.map(fromAny)))
        case m: collection.Map[?, ?] =>
          val entries = m.iterator.collect { case (k: String, v) => k -> fromAny(v) }
          DynamicValue.Record(Chunk.from(entries))
        case other => DynamicValue.Primitive(PrimitiveValue.String(other.toString))

  /** Build a `DynamicValue.Record` from already-lifted `(name, DynamicValue)` pairs. The `"name" := value` extension
    * usually makes these pairs. Convenience for varargs APIs like `Example.apply` and the dynamic `ProgramRuntime.run`.
    */
  def recordFromEntries(entries: Seq[(String, DynamicValue)]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(entries))

  /** Varargs convenience for building a `DynamicValue.Record` from `:=` entries, e.g.
    * `record("temperature" := 0.7, "max_tokens" := 256)`. The JSON-shaped counterpart to a `Map(...)` literal for
    * provider config / option bags.
    */
  def record(entries: (String, DynamicValue)*): DynamicValue.Record =
    recordFromEntries(entries)

  /** The JSON string `text`. */
  def string(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  /** The JSON array of `values`, in order. */
  def sequence(values: Seq[DynamicValue]): DynamicValue = DynamicValue.Sequence(Chunk.from(values))

  /** The value of the field `name` of a record. `None` when `value` is not a record, or when the record has no such
    * field.
    */
  def recordGet(value: DynamicValue, name: String): Option[DynamicValue] =
    value match
      case rec: DynamicValue.Record =>
        val index = rec.fields.indexWhere(_._1 == name)
        if index < 0 then None else Some(rec.fields(index)._2)
      case _ => None

  /** The record in `value`. `None` for another value. */
  def asRecord(value: DynamicValue): Option[DynamicValue.Record] =
    value match
      case rec: DynamicValue.Record => Some(rec)
      case _                        => None

  /** The elements of an array. Another value gives no element. */
  def asSequence(value: DynamicValue): Vector[DynamicValue] =
    value match
      case seq: DynamicValue.Sequence => seq.elements.iterator.toVector
      case _                          => Vector.empty

  /** The elements of the array in the field `name` of a record. An absent field, a null, or a value that is not an
    * array gives no element.
    */
  def sequenceAt(value: DynamicValue, name: String): Vector[DynamicValue] =
    recordGet(value, name).map(asSequence).getOrElse(Vector.empty)

  /** The dynamic JSON codec of zio-blocks. */
  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** The value of a JSON text, from the dynamic JSON codec of zio-blocks, or a `Left` with the cause when the text is
    * not JSON. The reader is recursive, so a value that is nested too deep for the stack of the thread gives a `Left`
    * too. Thus a text from the network or from a file cannot stop the caller with a `StackOverflowError`.
    */
  def tryDecodeJson(text: String): Either[String, DynamicValue] =
    try dynamicJsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).left.map(_.toString)
    catch case _: StackOverflowError => Left("the value is nested too deep")

  /** The value of a JSON text, from the dynamic JSON codec of zio-blocks. `None` for invalid JSON, and for a value that
    * is nested too deep for the stack ([[tryDecodeJson]]).
    */
  def decodeJson(text: String): Option[DynamicValue] =
    tryDecodeJson(text).toOption

  /** Decode a JSON text and keep it only when it is a JSON object, as a `DynamicValue.Record`. `None` for invalid JSON,
    * a value that is nested too deep for the stack, or a non-object payload. The shared "loose JSON text -> record"
    * boundary used by the strategy protocols (ReAct tool args, RLM SUBMIT payloads, Refine advice maps); each caller
    * supplies its own fallback semantics.
    */
  def parseJsonRecord(text: String): Option[DynamicValue.Record] =
    decodeJson(text).collect { case record: DynamicValue.Record => record }

  /** The compact JSON text of `value`, from the dynamic JSON codec of zio-blocks. Each null stays. */
  def encodeJson(value: DynamicValue): String = dynamicJsonCodec.encodeToString(value)

  /** The JSON text of `value`, written with `config`, or a `Left` with the cause when the value has no JSON text. The
    * JSON writer of zio-blocks throws on a text with a lone UTF-16 surrogate (for example the first half of an emoji in
    * a text that was cut at a fixed length) and on a number that is not finite. The writer is recursive, so a value
    * that is nested too deep for the stack of the thread gives a `Left` too.
    */
  def tryEncodeJson(value: DynamicValue, config: WriterConfig = WriterConfig): Either[String, String] =
    try Right(dynamicJsonCodec.encodeToString(value, config))
    catch
      case _: StackOverflowError => Left("the value is nested too deep")
      case NonFatal(error)       => Left(DspyError.describe(error))

  /** True for the JSON `null`. */
  def isNull(dv: DynamicValue): Boolean =
    dv match
      case _: DynamicValue.Null.type => true
      case _                         => false

  /** Project a `String` primitive; `None` for anything else. */
  def asString(dv: DynamicValue): Option[String] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => Some(s)
      case _                                                => None

  /** Tolerant `Long` projection: any numeric primitive (JSON codecs may decode numbers as Int / Long / Double /
    * BigDecimal / BigInt) or a clean numeric string.
    */
  def asLong(dv: DynamicValue): Option[Long] =
    dv match
      case DynamicValue.Primitive(p) => p match
          case PrimitiveValue.Int(n)        => Some(n.toLong)
          case PrimitiveValue.Long(n)       => Some(n)
          case PrimitiveValue.Short(n)      => Some(n.toLong)
          case PrimitiveValue.Byte(n)       => Some(n.toLong)
          case PrimitiveValue.Double(n)     => Some(n.toLong)
          case PrimitiveValue.Float(n)      => Some(n.toLong)
          case PrimitiveValue.BigDecimal(n) => Some(n.toLong)
          case PrimitiveValue.BigInt(n)     => Some(n.toLong)
          case PrimitiveValue.String(s)     => s.toLongOption
          case _                            => None
      case _ => None

  /** Strict `Double` projection: any numeric primitive. The dynamic JSON codec of zio-blocks gives an `Int`, a `Long`,
    * or a `BigDecimal`, and the other sources can give each other number type. A string or a Boolean is not a number.
    */
  def asDouble(dv: DynamicValue): Option[Double] =
    dv match
      case DynamicValue.Primitive(p) => p match
          case PrimitiveValue.Int(n)        => Some(n.toDouble)
          case PrimitiveValue.Long(n)       => Some(n.toDouble)
          case PrimitiveValue.Double(n)     => Some(n)
          case PrimitiveValue.Float(n)      => Some(n.toDouble)
          case PrimitiveValue.BigDecimal(n) => Some(n.toDouble)
          case PrimitiveValue.BigInt(n)     => Some(n.toDouble)
          case PrimitiveValue.Short(n)      => Some(n.toDouble)
          case PrimitiveValue.Byte(n)       => Some(n.toDouble)
          case _                            => None
      case _ => None

  /** Read a required `String` field from a record, with structured errors: a non-String value is a [[ValidationError]],
    * a missing field a [[NotFoundError]] on the `prediction_field` resource. `label` names the producing component in
    * both messages. The shared body of the `extractReasoning` / `extractRationale` helpers across the composite
    * programs.
    */
  def requireString(rec: DynamicValue.Record, field: String, label: String): Either[DspyError, String] =
    recordGet(rec, field) match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
      case Some(other)                                            => Left(ValidationError(s"$label field '$field' must be a String, got: $other"))
      case None                                                   => Left(NotFoundError(
          resource = "prediction_field",
          message = s"Required field '$field' is missing from the $label prediction"
        ))

  /** Merge `overlay` onto `base`, as [[updated]] for each field of `overlay`, in order. The result has the fields of
    * `base` in their order, and a field with a key of `overlay` takes the last `overlay` value of that key. The new
    * keys of `overlay` go at the end, in the order of their first occurrence, with their last value. The record
    * analogue of `base ++ overlay` for a `Map`. The single home for the "spread one option bag under another" semantics
    * shared by adapter request-options, predictor config, and provider default-options merges.
    */
  def mergeRecords(base: DynamicValue.Record, overlay: DynamicValue.Record): DynamicValue.Record =
    if overlay.fields.isEmpty then base
    else
      // A key keeps the position of its first occurrence and takes its last value.
      val overlayValues = VectorMap.from(overlay.fields)
      val baseKeys      = base.fields.iterator.map(_._1).toSet
      DynamicValue.Record(
        base.fields.map(field => overlayValues.get(field._1).fold(field)(field._1 -> _)) ++
          Chunk.from(overlayValues.iterator.filterNot(field => baseKeys.contains(field._1)))
      )

  /** Filter fields by name. Preserves order. */
  def recordFilterKeys(rec: DynamicValue.Record, pred: String => Boolean): DynamicValue.Record =
    DynamicValue.Record(rec.fields.filter((k, _) => pred(k)))

  /** Field names in declaration order. */
  def recordKeys(rec: DynamicValue.Record): Vector[String] =
    rec.fields.iterator.map(_._1).toVector

  /** Iterate `(name, value)` pairs. */
  def recordEntries(rec: DynamicValue.Record): Vector[(String, DynamicValue)] =
    rec.fields.iterator.toVector

  /** Reverse of [[fromAny]]: project a `DynamicValue` back into plain Scala values. Records become `Map[String, Any]`,
    * sequences become `List[Any]`, variants flatten to their case-name string (DSPy wire convention), primitives unwrap
    * to their host type. Used at observability boundaries (TraceEntry payloads, callback event bags) where the consumer
    * expects a free-form Map.
    */
  def toAny(dv: DynamicValue): Any =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s))    => s
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b))   => b
      case DynamicValue.Primitive(PrimitiveValue.Int(n))       => n
      case DynamicValue.Primitive(PrimitiveValue.Long(n))      => n
      case DynamicValue.Primitive(PrimitiveValue.Float(n))     => n
      case DynamicValue.Primitive(PrimitiveValue.Double(n))    => n
      case DynamicValue.Primitive(PrimitiveValue.Short(n))     => n
      case DynamicValue.Primitive(PrimitiveValue.Byte(n))      => n
      case DynamicValue.Primitive(PrimitiveValue.Char(c))      => c
      case DynamicValue.Primitive(_: PrimitiveValue.Unit.type) => ()
      case DynamicValue.Primitive(other)                       => other.toString
      case variant: DynamicValue.Variant                       => variant.caseName.getOrElse(toAny(variant.value))
      case seq: DynamicValue.Sequence                          => seq.elements.iterator.map(toAny).toList
      case rec: DynamicValue.Record                            => rec.fields.iterator.map((k, v) => k -> toAny(v)).toMap
      case m: DynamicValue.Map                                 => m.entries.iterator.map((k, v) => toAny(k) -> toAny(v)).toMap
      case _: DynamicValue.Null.type                           => null

  /** Convenience: `toAny` specialized to a `Record`, returning a `Map[String, Any]`. */
  def recordToMap(rec: DynamicValue.Record): Map[String, Any] =
    rec.fields.iterator.map((k, v) => k -> toAny(v)).toMap

  /** Plain-text rendering for `DynamicValue`. Adapters use it to render input field values into the prompt. Recursively
    * flattens records / sequences / variants into a JSON-ish textual form. Primitives use the `toString` of their
    * [[toAny]] value; null renders as `null`.
    */
  def renderText(dv: DynamicValue): String =
    dv match
      // A `String` primitive gives its text as it is, also when the text is `null`.
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => s
      case primitive: DynamicValue.Primitive                => toAny(primitive).toString
      case variant: DynamicValue.Variant                    => variant.caseName.getOrElse(renderText(variant.value))
      case seq: DynamicValue.Sequence                       => seq.elements.iterator.map(renderText).mkString("[", ", ", "]")
      case rec: DynamicValue.Record                         =>
        rec.fields.iterator.map((k, v) => s"\"$k\": ${renderText(v)}").mkString("{", ", ", "}")
      case m: DynamicValue.Map =>
        m.entries.iterator.map((k, v) => s"${renderText(k)}: ${renderText(v)}").mkString("{", ", ", "}")
      case _: DynamicValue.Null.type => "null"
