package dspy4s.lm.providers

import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

/** JSON (de)serialization and navigation for the provider layer, on `DynamicValue` — the principled replacement for the
  * hand-rolled `Any`-based `JsonCodec`. Serialization delegates to zio-blocks' total `DynamicValue` JSON codec;
  * [[stripNull]] mirrors the old `stripNone` (providers distinguish an absent field from `null`). The navigation
  * helpers replace the `asMap`/`asVector`/`asLong` projections the `Map[String, Any]` parser used.
  */
private[lm] object DynamicJson:
  private val codec = Schema.dynamic.jsonCodec

  /** Render a `DynamicValue` to a JSON string, dropping `Null` fields first (provider-bound: absent != null). */
  def encode(value: DynamicValue): String =
    new String(codec.encode(stripNull(value)), StandardCharsets.UTF_8)

  /** Render a `DynamicValue` to a JSON string and keep each `Null` field. Use it for a value that must keep its exact
    * content, for example the arguments of a tool call.
    */
  def encodeExact(value: DynamicValue): String =
    new String(codec.encode(value), StandardCharsets.UTF_8)

  /** Parse a JSON string. Text that is not JSON gives a `ParseError`. The codec is recursive, so a value that is nested
    * too deep for the stack of the thread gives a `ParseError` too: a body from the network must not stop the caller
    * with a `StackOverflowError`.
    */
  def decode(json: String): Either[DspyError, DynamicValue] =
    try
      codec.decode(json.getBytes(StandardCharsets.UTF_8)) match
        case Right(dv) => Right(dv)
        case Left(err) => Left(ParseError("json", s"Invalid JSON: ${err.toString.take(200)}"))
    catch case _: StackOverflowError => Left(ParseError("json", "Invalid JSON: the value is nested too deep"))

  /** Recursively drop record fields whose value is `Null`. */
  def stripNull(value: DynamicValue): DynamicValue =
    value match
      case rec: DynamicValue.Record => DynamicValue.Record(Chunk.from(rec.fields.iterator.collect {
          case (k, v) if !isNull(v) => k -> stripNull(v)
        }.toSeq))
      case seq: DynamicValue.Sequence => DynamicValue.Sequence(Chunk.from(seq.elements.iterator.map(stripNull).toSeq))
      case other                      => other

  private def isNull(v: DynamicValue): Boolean =
    v match
      case _: DynamicValue.Null.type => true
      case _                         => false

  // ── Navigation (replacing Map's asMap / asVector / asLong / string extraction) ──────────────────

  def field(value: DynamicValue, name: String): Option[DynamicValue] =
    asRecord(value).flatMap(rec => DynamicValues.recordGet(rec, name))

  def asRecord(value: DynamicValue): Option[DynamicValue.Record] =
    value match
      case rec: DynamicValue.Record => Some(rec)
      case _                        => None

  def asSequence(value: DynamicValue): Vector[DynamicValue] =
    value match
      case seq: DynamicValue.Sequence => seq.elements.iterator.toVector
      case _                          => Vector.empty

  def asString(value: DynamicValue): Option[String] = DynamicValues.asString(value)

  def asLong(value: DynamicValue): Option[Long] = DynamicValues.asLong(value)
