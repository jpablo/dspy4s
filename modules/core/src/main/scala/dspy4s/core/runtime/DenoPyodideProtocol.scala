package dspy4s.core.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** JSON-RPC wire format shared by the Deno/Pyodide process and its Scala client. */
private[runtime] object DenoPyodideProtocol:
  val MaxSkippedLines = 100
  val ToolErrorCode   = -32008 // upstream's CodeInterpreterError app-error code

  /** The JSON-RPC 2.0 protocol error codes, from parse error (-32700) to internal error (-32603). */
  val ProtocolErrorCodes: Set[Long] = Set(-32700L, -32600L, -32601L, -32602L, -32603L)

  /** The code that upstream uses for an error response that has no code (`JSONRPC_APP_ERRORS["Unknown"]`). */
  val UnknownErrorCode = -32099L

  /** The JSON-RPC application range is from -32099 to -32000. The runner reports each Python exception of the user code
    * with a code in this range.
    */
  def isApplicationError(code: Long): Boolean = code >= UnknownErrorCode && code <= -32000L

  enum ExecuteOutcome:
    case Output(text: String)
    case Submitted(json: String)
    case UserError(message: String)

  /** The class of a sandbox message that is not the response to a request (see [[outOfBand]]). */
  enum OutOfBand derives CanEqual:
    /** The message is a request or a response. The caller handles it. */
    case No

    /** The message has a `method` and no `id`. The caller skips it. */
    case Notification

    /** The message is an error without an id. The caller skips it and continues to read. */
    case UnsolicitedError(message: String)

    /** The message is a protocol error without an id. The sandbox did not process a request, so the session cannot
      * continue.
      */
    case ProtocolError(message: String)

  private lazy val jsonCodec = Schema.dynamic.jsonCodec
  private lazy val random    = new SecureRandom()

  /** A new request id: 32 lower-case hexadecimal characters from a secure random source (upstream
    * `secrets.token_hex(16)`). Sandboxed code can write a line to the output of the process. With a random id, the code
    * cannot make a line that the client accepts as the response to a request.
    */
  def newRequestId(): String =
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    bytes.map(byte => f"$byte%02x").mkString

  def encodeJson(value: DynamicValue): String =
    new String(jsonCodec.encode(value), StandardCharsets.UTF_8)

  def decodeJson(line: String): Option[DynamicValue] =
    val trimmed = line.trim
    if !trimmed.startsWith("{") then None
    else jsonCodec.decode(trimmed.getBytes(StandardCharsets.UTF_8)).toOption

  def encodeRequest(method: String, params: DynamicValue.Record, id: String): String =
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

  /** Classify a message that no request waits for (upstream `_handle_out_of_band_message`). A notification has a
    * `method` and no `id` member. An error is out of band when its `id` is absent or null: the runner reports an
    * unhandled promise rejection that way.
    */
  def outOfBand(msg: DynamicValue.Record): OutOfBand =
    val id = field(msg, "id")
    if field(msg, "method").isDefined && id.isEmpty then OutOfBand.Notification
    else
      field(msg, "error") match
        case Some(error) if id.forall(isNull) =>
          val record  = Some(error).collect { case r: DynamicValue.Record => r }
          val message = record.flatMap(field(_, "message")).flatMap(asString).getOrElse(encodeJson(error))
          if record.flatMap(field(_, "code")).flatMap(asLong).exists(ProtocolErrorCodes.contains) then
            OutOfBand.ProtocolError(message)
          else OutOfBand.UnsolicitedError(message)
        case _ => OutOfBand.No

  /** The diagnostic text of a notification: the `message` of its `params`, or the full message when `params` has no
    * `message` (upstream `_last_diagnostic`).
    */
  def notificationMessage(msg: DynamicValue.Record): String =
    field(msg, "params").collect { case r: DynamicValue.Record => r }.flatMap(field(_, "message")).flatMap(asString)
      .getOrElse(encodeJson(msg))

  private def isNull(dv: DynamicValue): Boolean =
    dv match
      case _: DynamicValue.Null.type => true
      case _                         => false

  def field(record: DynamicValue.Record, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(record, name)

  def asString(dv: DynamicValue): Option[String] = DynamicValues.asString(dv)

  /** Tolerant number extraction: the dynamic JSON codec may decode numbers as Int/Long/Double/BigDecimal. */
  def asLong(dv: DynamicValue): Option[Long] = DynamicValues.asLong(dv)
