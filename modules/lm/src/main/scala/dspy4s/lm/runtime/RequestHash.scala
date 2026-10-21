package dspy4s.lm.runtime

import dspy4s.core.contracts.{DspyError, ToolCall, ValidationError}
import dspy4s.lm.contracts.{ContentPart, LmRequest, Message}
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal

object RequestHash:

  /** The cache key of `request`. The encoder is recursive, so a value in the request that is nested too deep for the
    * stack of the thread throws a `StackOverflowError`. [[forRequestChecked]] gives a `Left` for that request.
    */
  def forRequest(request: LmRequest): String =
    sha256(encodeRequest(request))

  /** The cache key of `request` like [[forRequest]], or a `ValidationError` when the function cannot build the key, for
    * example when a value in the request is nested too deep for the stack of the thread, as the arguments of a tool
    * call in a message can be (the rule of `DynamicJson.encodeExactChecked`). A cache stores and reads nothing for such
    * a request, so the model gets the call (upstream `Cache.get` and `Cache.put` catch the exception of `cache_key`,
    * log it, and go on with no cache, `dspy/clients/cache.py`).
    */
  def forRequestChecked(request: LmRequest): Either[DspyError, String] =
    try Right(forRequest(request))
    catch
      case _: StackOverflowError => Left(ValidationError("The request has no cache key: a value is nested too deep"))
      case NonFatal(error)       =>
        Left(ValidationError(s"The request has no cache key: ${Option(error.getMessage).getOrElse(error.toString)}"))

  private def encodeRequest(request: LmRequest): String =
    val messages  = request.messages.map(encodeMessage).mkString("[", ",", "]")
    val options   = normalizeDynamic(request.options)
    val requestId = request.requestId.map(quote).getOrElse("null")
    val rolloutId = request.rolloutId.map(_.toString).getOrElse("null")
    s"""{"model":${quote(request.model)},"mode":${quote(
        request.mode.toString
      )},"messages":$messages,"options":$options,"request_id":$requestId,"rollout_id":$rolloutId${encodeIdentity(
        request.cacheIdentity
      )}}"""

  /** The cache identity of the model for the hashed text (upstream TypeSafe `_request` has `provider` and `base_url`).
    * The text has it only when it is not empty. Thus a request with no identity keeps the key that it had before this
    * field existed, and each entry of a disk cache stays valid.
    */
  private def encodeIdentity(identity: DynamicValue.Record): String =
    if identity.fields.isEmpty then "" else s""","cache_identity":${normalizeDynamic(identity)}"""

  private def encodeMessage(message: Message): String =
    val parts      = message.parts.map(encodePart).mkString("[", ",", "]")
    val toolFields = encodeToolFields(message)
    s"""{"role":${quote(message.role.toString)},"text":${message.text.map(
        quote
      ).getOrElse("null")},"parts":$parts,"metadata":${normalizeStringMap(message.metadata)}$toolFields}"""

  /** The tool fields of a message for the hashed text: the tool calls, the tool call id, and the name. A field is in
    * the text only when the message has it. Thus a message with no tool field keeps the key that it had before these
    * fields existed, and each entry of a disk cache stays valid.
    */
  private def encodeToolFields(message: Message): String =
    val toolCalls = Option.when(message.toolCalls.nonEmpty)(
      s""","tool_calls":${message.toolCalls.map(encodeToolCall).mkString("[", ",", "]")}"""
    )
    val toolCallId = message.toolCallId.map(callId => s""","tool_call_id":${quote(callId)}""")
    val name       = message.name.map(toolName => s""","name":${quote(toolName)}""")
    Vector(toolCalls, toolCallId, name).flatten.mkString

  private def encodeToolCall(call: ToolCall): String =
    val id = call.id.map(quote).getOrElse("null")
    s"""{"id":$id,"name":${quote(call.name)},"args":${normalizeDynamic(call.args)}}"""

  private def encodePart(part: ContentPart): String =
    s"""{"kind":${quote(part.kind)},"payload":${quote(part.payload)},"metadata":${normalizeStringMap(part.metadata)}}"""

  /** Canonical, order-independent string for the cache key. Records and Maps sort their keys so that two requests
    * differing only in option insertion order collide onto the same entry (which `Schema.dynamic.jsonCodec` would not,
    * since it preserves insertion order). Primitives are tagged by their case-class name so a string `"1"` cannot
    * collide with an int `1`.
    */
  private def normalizeDynamic(value: DynamicValue): String =
    value match
      case DynamicValue.Primitive(p)     => quote(p.toString)
      case DynamicValue.Sequence(elems)  => elems.iterator.map(normalizeDynamic).mkString("[", ",", "]")
      case DynamicValue.Variant(name, v) => s"{${quote(name)}:${normalizeDynamic(v)}}"
      case DynamicValue.Record(fields)   => fields.iterator
          .map { case (k, v) => quote(k) -> normalizeDynamic(v) }
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k:$v" }
          .mkString("{", ",", "}")
      case DynamicValue.Map(entries) => entries.iterator
          .map { case (k, v) => normalizeDynamic(k) -> normalizeDynamic(v) }
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k:$v" }
          .mkString("{", ",", "}")
      case _ => "null" // DynamicValue.Null

  private def normalizeStringMap(map: Map[String, String]): String =
    map.iterator
      .map { case (k, v) => quote(k) -> quote(v) }
      .toVector
      .sortBy(_._1)
      .map { case (k, v) => s"$k:$v" }
      .mkString("{", ",", "}")

  private def quote(text: String): String =
    val escaped = text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s"\"$escaped\""

  private def sha256(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"$byte%02x").mkString
