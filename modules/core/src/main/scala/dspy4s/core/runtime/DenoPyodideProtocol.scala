package dspy4s.core.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.DynamicValues.asLong
import dspy4s.core.contracts.DynamicValues.asString
import dspy4s.core.contracts.DynamicValues.encodeJson
import dspy4s.core.contracts.DynamicValues.isNull
import dspy4s.core.contracts.DynamicValues.recordGet as field
import dspy4s.core.contracts.DynamicValues.tryEncodeJson
import dspy4s.core.contracts.:=
import zio.blocks.schema.DynamicValue

import java.security.SecureRandom
import java.util.HexFormat

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

  private lazy val random = new SecureRandom()

  /** A new request id: 32 lower-case hexadecimal characters from a secure random source (upstream
    * `secrets.token_hex(16)`). Sandboxed code can write a line to the output of the process. With a random id, the code
    * cannot make a line that the client accepts as the response to a request.
    */
  def newRequestId(): String =
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    HexFormat.of().formatHex(bytes)

  /** The JSON object on one line of the process, or `None` for a line that is not a JSON object (for example a
    * diagnostic line of Pyodide).
    */
  def decodeRecord(line: String): Option[DynamicValue.Record] =
    val trimmed = line.trim
    if trimmed.startsWith("{") then DynamicValues.parseJsonRecord(trimmed) else None

  // Each encoder gives the JSON line of a message. `Left` has the cause when the JSON writer cannot write the message,
  // for example a text with a lone UTF-16 surrogate (see `DynamicValues.tryEncodeJson`). Such a message is not
  // sent, so the caller decides if the session continues.

  def encodeRequest(method: String, params: DynamicValue.Record, id: String): Either[String, String] =
    tryEncodeJson(DynamicValues.record("jsonrpc" := "2.0", "method" := method, "params" -> params, "id" := id))

  def encodeNotification(method: String, params: Option[DynamicValue.Record]): Either[String, String] =
    val entries = Vector[(String, DynamicValue)]("jsonrpc" := "2.0", "method" := method) ++
      params.map(p => ("params", p: DynamicValue)).toVector
    tryEncodeJson(DynamicValues.recordFromEntries(entries))

  def encodeResult(id: DynamicValue, result: DynamicValue.Record): Either[String, String] =
    tryEncodeJson(DynamicValues.record("jsonrpc" := "2.0", "result" -> result, "id" -> id))

  def encodeError(id: DynamicValue, code: Int, message: String): Either[String, String] =
    tryEncodeJson(DynamicValues.record(
      "jsonrpc" := "2.0",
      "error"   -> DynamicValues.record("code" := code, "message" := message),
      "id"      -> id
    ))

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
