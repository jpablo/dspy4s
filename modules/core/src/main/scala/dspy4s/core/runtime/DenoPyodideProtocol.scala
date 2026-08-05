package dspy4s.core.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** JSON-RPC wire format shared by the Deno/Pyodide process and its Scala client. */
private[runtime] object DenoPyodideProtocol:
  val MaxSkippedLines = 100
  val ToolErrorCode   = -32008 // upstream's CodeInterpreterError app-error code

  enum ExecuteOutcome:
    case Output(text: String)
    case Submitted(json: String)
    case UserError(message: String)

  private lazy val jsonCodec = Schema.dynamic.jsonCodec

  def encodeJson(value: DynamicValue): String =
    new String(jsonCodec.encode(value), StandardCharsets.UTF_8)

  def decodeJson(line: String): Option[DynamicValue] =
    val trimmed = line.trim
    if !trimmed.startsWith("{") then None
    else jsonCodec.decode(trimmed.getBytes(StandardCharsets.UTF_8)).toOption

  def encodeRequest(method: String, params: DynamicValue.Record, id: Int): String =
    encodeJson(DynamicValues.record("jsonrpc" := "2.0", "method" := method, "params" -> params, "id" := id))

  def encodeNotification(method: String, params: Option[DynamicValue.Record]): String =
    val entries = Vector[(String, DynamicValue)]("jsonrpc" := "2.0", "method" := method) ++
      params.map(p => ("params", p: DynamicValue)).toVector
    encodeJson(DynamicValues.recordFromEntries(entries))

  def encodeResult(id: DynamicValue, result: DynamicValue.Record): String =
    encodeJson(DynamicValues.recordFromEntries(Vector[(String, DynamicValue)](
      "jsonrpc" := "2.0",
      "result"  -> result,
      "id"      -> id
    )))

  def encodeError(id: DynamicValue, code: Int, message: String): String =
    encodeJson(DynamicValues.recordFromEntries(Vector[(String, DynamicValue)](
      "jsonrpc" := "2.0",
      "error"   -> DynamicValues.record("code" := code, "message" := message),
      "id"      -> id
    )))

  def field(record: DynamicValue.Record, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(record, name)

  def asString(dv: DynamicValue): Option[String] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => Some(s)
      case _                                                => None

  /** Tolerant number extraction: the dynamic JSON codec may decode numbers as Int/Long/Double/BigDecimal. */
  def asLong(dv: DynamicValue): Option[Long] =
    dv match
      case DynamicValue.Primitive(p) => p match
          case PrimitiveValue.Int(n)        => Some(n.toLong)
          case PrimitiveValue.Long(n)       => Some(n)
          case PrimitiveValue.Double(n)     => Some(n.toLong)
          case PrimitiveValue.Float(n)      => Some(n.toLong)
          case PrimitiveValue.BigDecimal(n) => Some(n.toLong)
          case PrimitiveValue.BigInt(n)     => Some(n.toLong)
          case PrimitiveValue.Short(n)      => Some(n.toLong)
          case PrimitiveValue.Byte(n)       => Some(n.toLong)
          case _                            => None
      case _ => None
