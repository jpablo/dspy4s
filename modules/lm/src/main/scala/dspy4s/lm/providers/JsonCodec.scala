package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ParseError
import ujson.Value

object JsonCodec:

  def encode(value: Any): Value = value match
    case s: String        => ujson.Str(s)
    case b: Boolean       => ujson.Bool(b)
    case n: Long          => ujson.Num(n.toDouble)
    case n: Int           => ujson.Num(n.toDouble)
    case n: Short         => ujson.Num(n.toDouble)
    case n: Byte          => ujson.Num(n.toDouble)
    case n: Double        => ujson.Num(n)
    case n: Float         => ujson.Num(n.toDouble)
    case n: BigInt        => ujson.Num(n.toDouble)
    case n: BigDecimal    => ujson.Num(n.toDouble)
    case None             => ujson.Null
    case Some(inner)      => encode(inner)
    case m: Map[?, ?]    =>
      ujson.Obj.from(m.iterator.collect {
        case (k: String, v) if !isNone(v) => k -> encode(v)
      })
    case seq: Iterable[?] =>
      ujson.Arr.from(seq.iterator.filterNot(isNone).map(encode))
    case arr: Array[?]    => ujson.Arr.from(arr.iterator.filterNot(isNone).map(encode))
    case other            => ujson.Str(String.valueOf(other))

  def decode(value: Value): Any =
    value match
      case ujson.Str(s)     => s
      case ujson.Bool(b)    => b
      case ujson.Num(n)     =>
        if n.toDouble.isWhole && n >= Long.MinValue.toDouble && n <= Long.MaxValue.toDouble then n.toLong else n
      case ujson.Null       => None
      case obj: ujson.Obj   =>
        obj.value.iterator.map { (k, v) => k -> decode(v) }.toMap
      case arr: ujson.Arr   =>
        arr.value.toVector.map(decode)

  def encodeString(payload: Map[String, Any]): String =
    ujson.write(encode(payload))

  def decodeString(json: String): Either[DspyError, Map[String, Any]] =
    decodeValue(json).map {
      case obj: ujson.Obj =>
        obj.value.iterator.map { (k, v) => k -> decode(v) }.toMap
      case other =>
        Map("value" -> decode(other))
    }

  private def decodeValue(json: String): Either[DspyError, Value] =
    try Right(ujson.read(json))
    catch
      case error: ujson.ParseException =>
        Left(ParseError("json", s"Invalid JSON: ${error.getMessage.take(200)}"))
      case error: Exception =>
        Left(ParseError("json", Option(error.getMessage).getOrElse("parse failure")))

  private def isNone(value: Any): Boolean = value match
    case None        => true
    case null        => true
    case _ : Void    => true
    case _           => false

  def stripNone(payload: Map[String, Any]): Map[String, Any] =
    payload.iterator.flatMap { case (k, v) =>
      v match
        case None | null           => None
        case m: Map[?, ?]          => Some(k -> stripNone(m.asInstanceOf[Map[String, Any]]))
        case seq: Iterable[?]     =>
          val cleaned = seq.filterNot(isNone).map {
            case m: Map[?, ?] => stripNone(m.asInstanceOf[Map[String, Any]])
            case other        => other
          }
          Some(k -> cleaned)
        case other                 => Some(k -> other)
    }.toMap
