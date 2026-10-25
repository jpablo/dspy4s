package dspy4s.lm.providers

import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

/** The JSON rules of the provider layer. A provider body has no null field, because a provider can read an absent field
  * and a null field in different ways ([[encode]]). A body from the network can be any text ([[decode]]). The generic
  * JSON helpers are in [[dspy4s.core.contracts.DynamicValues]].
  */
private[lm] object DynamicJson:
  private val codec = Schema.dynamic.jsonCodec

  /** The JSON text of a provider body. Each record field with a null value is removed first. */
  def encode(value: DynamicValue): String =
    DynamicValues.encodeJson(if hasNullField(value) then stripNull(value) else value)

  /** Parse a JSON string. Text that is not JSON gives a `ParseError`. The codec is recursive, so a value that is nested
    * too deep for the stack of the thread gives a `ParseError` too. Thus a body from the network cannot stop the caller
    * with a `StackOverflowError`.
    */
  def decode(json: String): Either[DspyError, DynamicValue] =
    try
      codec.decode(json.getBytes(StandardCharsets.UTF_8)) match
        case Right(dv) => Right(dv)
        case Left(err) => Left(ParseError("json", s"Invalid JSON: ${err.toString.take(200)}"))
    catch case _: StackOverflowError => Left(ParseError("json", "Invalid JSON: the value is nested too deep"))

  /** True when a record in `value` has a field with a null value. This check copies nothing, so [[encode]] copies a
    * body only when the body has such a field.
    */
  private def hasNullField(value: DynamicValue): Boolean =
    value match
      case rec: DynamicValue.Record =>
        rec.fields.exists((_, field) => DynamicValues.isNull(field) || hasNullField(field))
      case seq: DynamicValue.Sequence => seq.elements.exists(hasNullField)
      case _                          => false

  /** Remove each record field with a null value, at each level. */
  private def stripNull(value: DynamicValue): DynamicValue =
    value match
      case rec: DynamicValue.Record => DynamicValue.Record(Chunk.from(rec.fields.iterator.collect {
          case (k, v) if !DynamicValues.isNull(v) => k -> stripNull(v)
        }.toSeq))
      case seq: DynamicValue.Sequence => DynamicValue.Sequence(Chunk.from(seq.elements.iterator.map(stripNull).toSeq))
      case other                      => other
