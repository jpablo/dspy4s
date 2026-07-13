package dspy4s.adapters.internal

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import ujson.Value
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import scala.util.Try

/** Shared JSON → `DynamicValue` conversion used by both `JSONAdapter` and `ChatAdapter`. Keeping a single
  * implementation guarantees list / record fields decode identically regardless of which adapter parsed them. */
private[adapters] object JsonDynamic:

  /** Recursively convert a parsed `ujson.Value` into the dspy4s `DynamicValue` spine. */
  def fromUjson(value: Value): DynamicValue =
    value match
      case ujson.Str(v) => DynamicValue.Primitive(PrimitiveValue.String(v))
      case ujson.Num(v) =>
        if v.isWhole && v >= Int.MinValue && v <= Int.MaxValue then
          DynamicValue.Primitive(PrimitiveValue.Int(v.toInt))
        else if v.isWhole && v >= Long.MinValue && v <= Long.MaxValue then
          DynamicValue.Primitive(PrimitiveValue.Long(v.toLong))
        else DynamicValue.Primitive(PrimitiveValue.Double(v))
      case ujson.Bool(v)      => DynamicValue.Primitive(PrimitiveValue.Boolean(v))
      case _: ujson.Null.type => DynamicValue.Null
      case obj: ujson.Obj =>
        DynamicValue.Record(Chunk.from(
          obj.value.iterator.map { case (k, v) => k -> fromUjson(v) }.toSeq
        ))
      case arr: ujson.Arr =>
        DynamicValue.Sequence(Chunk.from(arr.value.toVector.map(fromUjson)))

  /** Recursively convert a `DynamicValue` into a `ujson.Value` — the inverse of [[fromUjson]]. Non-JSON
    * shapes (variants, exotic primitives) fall back to their rendered-text string. Field order is preserved,
    * so a serialized record shows the model fields in signature order. */
  def toUjson(value: DynamicValue): Value =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(v))  => ujson.Str(v)
      case DynamicValue.Primitive(PrimitiveValue.Int(v))     => ujson.Num(v)
      case DynamicValue.Primitive(PrimitiveValue.Long(v))    => ujson.Num(v.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Double(v))  => ujson.Num(v)
      case DynamicValue.Primitive(PrimitiveValue.Float(v))   => ujson.Num(v.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Boolean(v)) => ujson.Bool(v)
      case _: DynamicValue.Null.type                         => ujson.Null
      case rec: DynamicValue.Record =>
        ujson.Obj.from(rec.fields.iterator.map { case (k, v) => k -> toUjson(v) })
      case seq: DynamicValue.Sequence =>
        ujson.Arr.from(seq.elements.iterator.map(toUjson))
      case m: DynamicValue.Map =>
        ujson.Obj.from(m.entries.iterator.map { case (k, v) => DynamicValues.renderText(k) -> toUjson(v) })
      case other => ujson.Str(DynamicValues.renderText(other))

  /** Parse raw text as JSON and convert it to a `DynamicValue`. Parse failures map to a `ParseError`. */
  def parse(text: String): Either[DspyError, DynamicValue] =
    Try(ujson.read(text)).toEither.left
      .map(error => ParseError("adapter", error.getMessage))
      .map(fromUjson)
