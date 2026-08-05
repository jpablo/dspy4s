package dspy4s.lm.runtime

import dspy4s.lm.contracts.{ContentPart, LmRequest, Message}
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object RequestHash:
  def forRequest(request: LmRequest): String =
    sha256(encodeRequest(request))

  private def encodeRequest(request: LmRequest): String =
    val messages  = request.messages.map(encodeMessage).mkString("[", ",", "]")
    val options   = normalizeDynamic(request.options)
    val requestId = request.requestId.map(quote).getOrElse("null")
    val rolloutId = request.rolloutId.map(_.toString).getOrElse("null")
    s"""{"model":${quote(request.model)},"mode":${quote(
        request.mode.toString
      )},"messages":$messages,"options":$options,"request_id":$requestId,"rollout_id":$rolloutId}"""

  private def encodeMessage(message: Message): String =
    val parts = message.parts.map(encodePart).mkString("[", ",", "]")
    s"""{"role":${quote(message.role.toString)},"text":${message.text.map(
        quote
      ).getOrElse("null")},"parts":$parts,"metadata":${normalizeStringMap(message.metadata)}}"""

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
