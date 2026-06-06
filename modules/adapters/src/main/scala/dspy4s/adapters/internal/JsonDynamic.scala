package dspy4s.adapters.internal

import dspy4s.core.contracts.DspyError
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

  /** Parse raw text as JSON and convert it to a `DynamicValue`. Parse failures map to a `ParseError`. */
  def parse(text: String): Either[DspyError, DynamicValue] =
    Try(ujson.read(text)).toEither.left
      .map(error => ParseError("adapter", error.getMessage))
      .map(fromUjson)
