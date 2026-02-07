package dspy4s.lm.runtime

import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

object RequestHash:
  def forRequest(request: LmRequest): String =
    sha256(encodeRequest(request))

  private def encodeRequest(request: LmRequest): String =
    val messages = request.messages.map(encodeMessage).mkString("[", ",", "]")
    val options = normalizeAny(request.options)
    val requestId = request.requestId.map(quote).getOrElse("null")
    s"""{"model":${quote(request.model)},"mode":${quote(request.mode.toString)},"messages":$messages,"options":$options,"request_id":$requestId}"""

  private def encodeMessage(message: Message): String =
    val parts = message.parts.map(encodePart).mkString("[", ",", "]")
    s"""{"role":${quote(message.role.toString)},"text":${message.text.map(quote).getOrElse("null")},"parts":$parts,"metadata":${normalizeAny(message.metadata)}}"""

  private def encodePart(part: ContentPart): String =
    s"""{"kind":${quote(part.kind)},"payload":${quote(part.payload)},"metadata":${normalizeAny(part.metadata)}}"""

  private def normalizeAny(value: Any): String =
    value match
      case null            => "null"
      case s: String       => quote(s)
      case c: Char         => quote(c.toString)
      case b: Boolean      => b.toString
      case n: Byte         => n.toString
      case n: Short        => n.toString
      case n: Int          => n.toString
      case n: Long         => n.toString
      case n: Float        => n.toString
      case n: Double       => n.toString
      case n: BigInt       => n.toString
      case n: BigDecimal   => n.toString
      case opt: Option[?]  => opt.map(normalizeAny).getOrElse("null")
      case map: Map[?, ?] =>
        map.iterator
          .map { case (k, v) => quote(String.valueOf(k)) -> normalizeAny(v) }
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k:$v" }
          .mkString("{", ",", "}")
      case iterable: Iterable[?] =>
        iterable.iterator.map(normalizeAny).mkString("[", ",", "]")
      case array: Array[?] =>
        array.iterator.map(normalizeAny).mkString("[", ",", "]")
      case product: Product =>
        val values = product.productIterator.toVector
        val names = product.productElementNames.toVector
        if names.nonEmpty && names.size == values.size then
          names.zip(values).map { case (name, item) =>
            quote(name) -> normalizeAny(item)
          }.sortBy(_._1).map { case (k, v) => s"$k:$v" }.mkString("{", ",", "}")
        else values.map(normalizeAny).mkString("[", ",", "]")
      case other =>
        quote(other.toString)

  private def quote(text: String): String =
    val escaped = text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""

  private def sha256(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"$byte%02x").mkString

final class InMemoryLmCache(maxEntries: Int = 1024) extends LmCache:
  require(maxEntries > 0, "maxEntries must be greater than 0")

  private val store = new LinkedHashMap[String, LmResponse](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, LmResponse]): Boolean =
      this.size() > maxEntries

  override def get(request: LmRequest): Option[LmResponse] =
    val key = RequestHash.forRequest(request)
    this.synchronized {
      Option(store.get(key)).map(_.copy(cacheHit = true, usage = None))
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    val key = RequestHash.forRequest(request)
    this.synchronized {
      store.put(key, response.copy(cacheHit = false))
      ()
    }

  def clear(): Unit =
    this.synchronized {
      store.clear()
    }

  def size: Int =
    this.synchronized {
      store.size()
    }
