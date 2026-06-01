package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.util.Objects

/** Convenience helpers around `zio.blocks.schema.DynamicValue` / `DynamicValue.Record`. Used at the
  * user-input boundary — `Example("q" -> "hello")`, `DynamicPredict.run("text" -> text)` — where
  * callers pass plain Scala values that need to be lifted into the spine type. The codec spine
  * (`Shape.encode` / `Shape.decode` backed by zio-blocks Schema) never goes through these helpers;
  * it produces and consumes `DynamicValue` directly.
  */
object DynamicValues:

  /** Lift a plain Scala value into a `DynamicValue`. Pass-through for values that are already a
    * `DynamicValue`. Used only at user-facing boundaries — not in the codec spine. */
  def fromAny(value: Any): DynamicValue =
    if Objects.isNull(value) then DynamicValue.Null
    else
      value match
        case dv: DynamicValue  => dv
        case s: String         => DynamicValue.Primitive(PrimitiveValue.String(s))
        case b: Boolean        => DynamicValue.Primitive(PrimitiveValue.Boolean(b))
        case i: Int            => DynamicValue.Primitive(PrimitiveValue.Int(i))
        case l: Long           => DynamicValue.Primitive(PrimitiveValue.Long(l))
        case f: Float          => DynamicValue.Primitive(PrimitiveValue.Float(f))
        case d: Double         => DynamicValue.Primitive(PrimitiveValue.Double(d))
        case seq: Seq[?]       =>
          DynamicValue.Sequence(Chunk.from(seq.map(fromAny)))
        case m: collection.Map[?, ?] =>
          val entries = m.iterator.collect { case (k: String, v) => k -> fromAny(v) }.toSeq
          DynamicValue.Record(Chunk.from(entries))
        case other             => DynamicValue.Primitive(PrimitiveValue.String(other.toString))

  /** Build a `DynamicValue.Record` from a sequence of `(name, value)` pairs. Convenience for varargs
    * APIs like `Example.apply` and `DynamicPredict.run`. */
  def recordFromEntries(entries: Seq[(String, Any)]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(entries.map((k, v) => k -> fromAny(v))))

  /** Lookup by field name. Returns `None` if the record has no such field. */
  def recordGet(rec: DynamicValue.Record, name: String): Option[DynamicValue] =
    rec.fields.iterator.collectFirst { case (k, v) if k == name => v }

  /** Update or append: if the record already has `name`, replace the value; otherwise append at the
    * end (preserving insertion order). */
  def recordUpdated(rec: DynamicValue.Record, name: String, value: DynamicValue): DynamicValue.Record =
    val existing = rec.fields.iterator.exists(_._1 == name)
    if existing then
      DynamicValue.Record(Chunk.from(rec.fields.iterator.map {
        case (k, _) if k == name => k -> value
        case kv                  => kv
      }.toSeq))
    else
      DynamicValue.Record(Chunk.from(rec.fields.iterator.toSeq :+ (name -> value)))

  /** Filter fields by name. Preserves order. */
  def recordFilterKeys(rec: DynamicValue.Record, pred: String => Boolean): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(rec.fields.iterator.filter((k, _) => pred(k)).toSeq))

  /** Field names in declaration order. */
  def recordKeys(rec: DynamicValue.Record): Vector[String] =
    rec.fields.iterator.map(_._1).toVector

  /** Iterate `(name, value)` pairs. */
  def recordEntries(rec: DynamicValue.Record): Vector[(String, DynamicValue)] =
    rec.fields.iterator.toVector

  /** Reverse of [[fromAny]]: project a `DynamicValue` back into plain Scala values. Records become
    * `Map[String, Any]`, sequences become `List[Any]`, variants flatten to their case-name string (DSPy wire
    * convention), primitives unwrap to their host type. Used at observability boundaries (TraceEntry payloads,
    * callback event bags) where the consumer expects a free-form Map. */
  def toAny(dv: DynamicValue): Any = dv match
    case DynamicValue.Primitive(PrimitiveValue.String(s))  => s
    case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b
    case DynamicValue.Primitive(PrimitiveValue.Int(n))     => n
    case DynamicValue.Primitive(PrimitiveValue.Long(n))    => n
    case DynamicValue.Primitive(PrimitiveValue.Float(n))   => n
    case DynamicValue.Primitive(PrimitiveValue.Double(n))  => n
    case DynamicValue.Primitive(PrimitiveValue.Short(n))   => n
    case DynamicValue.Primitive(PrimitiveValue.Byte(n))    => n
    case DynamicValue.Primitive(PrimitiveValue.Char(c))    => c
    case DynamicValue.Primitive(_: PrimitiveValue.Unit.type) => ()
    case DynamicValue.Primitive(other)                     => other.toString
    case variant: DynamicValue.Variant                     =>
      variant.caseName.getOrElse(toAny(variant.value))
    case seq: DynamicValue.Sequence                        =>
      seq.elements.iterator.map(toAny).toList
    case rec: DynamicValue.Record                          =>
      rec.fields.iterator.map((k, v) => k -> toAny(v)).toMap
    case m: DynamicValue.Map                               =>
      m.entries.iterator.map((k, v) => toAny(k) -> toAny(v)).toMap
    case _: DynamicValue.Null.type                         => null

  /** Convenience: `toAny` specialized to a `Record`, returning a `Map[String, Any]`. */
  def recordToMap(rec: DynamicValue.Record): Map[String, Any] =
    rec.fields.iterator.map((k, v) => k -> toAny(v)).toMap

  /** Plain-text rendering for `DynamicValue` -- used by adapters to render input field values into
    * the prompt. Recursively flattens records / sequences / variants into a JSON-ish textual form.
    * Primitives use their natural `toString`; null renders as `null`. */
  def renderText(dv: DynamicValue): String = dv match
    case DynamicValue.Primitive(PrimitiveValue.String(s))  => s
    case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => b.toString
    case DynamicValue.Primitive(PrimitiveValue.Int(n))     => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Long(n))    => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Float(n))   => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Double(n))  => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Short(n))   => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Byte(n))    => n.toString
    case DynamicValue.Primitive(PrimitiveValue.Char(c))    => c.toString
    case DynamicValue.Primitive(_: PrimitiveValue.Unit.type) => "()"
    case DynamicValue.Primitive(other)                     => other.toString
    case variant: DynamicValue.Variant                     =>
      variant.caseName.getOrElse(renderText(variant.value))
    case seq: DynamicValue.Sequence                        =>
      seq.elements.iterator.map(renderText).mkString("[", ", ", "]")
    case rec: DynamicValue.Record                          =>
      rec.fields.iterator.map((k, v) => s"\"$k\": ${renderText(v)}").mkString("{", ", ", "}")
    case m: DynamicValue.Map                               =>
      m.entries.iterator.map((k, v) => s"${renderText(k)}: ${renderText(v)}").mkString("{", ", ", "}")
    case _: DynamicValue.Null.type                         => "null"
