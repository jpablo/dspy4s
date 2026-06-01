package dspy4s.lm.providers

import dspy4s.core.contracts.{DspyError, ParseError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** JSON (de)serialization and navigation for the provider layer, on `DynamicValue` — the principled replacement
  * for the hand-rolled `Any`-based `JsonCodec`. Serialization delegates to zio-blocks' total `DynamicValue`
  * JSON codec; [[stripNull]] mirrors the old `stripNone` (providers distinguish an absent field from `null`).
  * The navigation helpers replace the `asMap`/`asVector`/`asLong` projections the `Map[String, Any]` parser used. */
private[lm] object DynamicJson:
  private val codec = Schema.dynamic.jsonCodec

  /** Render a `DynamicValue` to a JSON string, dropping `Null` fields first (provider-bound: absent != null). */
  def encode(value: DynamicValue): String =
    new String(codec.encode(stripNull(value)), StandardCharsets.UTF_8)

  def decode(json: String): Either[DspyError, DynamicValue] =
    codec.decode(json.getBytes(StandardCharsets.UTF_8)) match
      case Right(dv)  => Right(dv)
      case Left(err)  => Left(ParseError("json", s"Invalid JSON: ${err.toString.take(200)}"))

  /** Recursively drop record fields whose value is `Null`. */
  def stripNull(value: DynamicValue): DynamicValue = value match
    case rec: DynamicValue.Record =>
      DynamicValue.Record(Chunk.from(rec.fields.iterator.collect {
        case (k, v) if !isNull(v) => k -> stripNull(v)
      }.toSeq))
    case seq: DynamicValue.Sequence =>
      DynamicValue.Sequence(Chunk.from(seq.elements.iterator.map(stripNull).toSeq))
    case other => other

  private def isNull(v: DynamicValue): Boolean = v match
    case _: DynamicValue.Null.type => true
    case _                         => false

  // ── Navigation (replacing Map's asMap / asVector / asLong / string extraction) ──────────────────

  def field(value: DynamicValue, name: String): Option[DynamicValue] = value match
    case rec: DynamicValue.Record => rec.fields.iterator.collectFirst { case (k, v) if k == name => v }
    case _                        => None

  def asRecord(value: DynamicValue): Option[DynamicValue.Record] = value match
    case rec: DynamicValue.Record => Some(rec)
    case _                        => None

  def asSequence(value: DynamicValue): Vector[DynamicValue] = value match
    case seq: DynamicValue.Sequence => seq.elements.iterator.toVector
    case _                          => Vector.empty

  def asString(value: DynamicValue): Option[String] = value match
    case DynamicValue.Primitive(PrimitiveValue.String(s)) => Some(s)
    case _                                                => None

  def asLong(value: DynamicValue): Option[Long] = value match
    case DynamicValue.Primitive(PrimitiveValue.Long(n))   => Some(n)
    case DynamicValue.Primitive(PrimitiveValue.Int(n))    => Some(n.toLong)
    case DynamicValue.Primitive(PrimitiveValue.Short(n))  => Some(n.toLong)
    case DynamicValue.Primitive(PrimitiveValue.Byte(n))   => Some(n.toLong)
    case DynamicValue.Primitive(PrimitiveValue.Double(n)) => Some(n.toLong)
    case DynamicValue.Primitive(PrimitiveValue.Float(n))  => Some(n.toLong)
    case DynamicValue.Primitive(PrimitiveValue.String(s)) => s.toLongOption
    case _                                                => None
