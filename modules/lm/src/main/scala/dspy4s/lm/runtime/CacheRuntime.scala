package dspy4s.lm.runtime

import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.TokenCategory
import dspy4s.lm.contracts.ToolCall
import zio.blocks.schema.{DynamicValue, Schema}

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object RequestHash:
  def forRequest(request: LmRequest): String =
    sha256(encodeRequest(request))

  private def encodeRequest(request: LmRequest): String =
    val messages = request.messages.map(encodeMessage).mkString("[", ",", "]")
    val options = normalizeDynamic(request.options)
    val requestId = request.requestId.map(quote).getOrElse("null")
    val rolloutId = request.rolloutId.map(_.toString).getOrElse("null")
    s"""{"model":${quote(request.model)},"mode":${quote(request.mode.toString)},"messages":$messages,"options":$options,"request_id":$requestId,"rollout_id":$rolloutId}"""

  private def encodeMessage(message: Message): String =
    val parts = message.parts.map(encodePart).mkString("[", ",", "]")
    s"""{"role":${quote(message.role.toString)},"text":${message.text.map(quote).getOrElse("null")},"parts":$parts,"metadata":${normalizeStringMap(message.metadata)}}"""

  private def encodePart(part: ContentPart): String =
    s"""{"kind":${quote(part.kind)},"payload":${quote(part.payload)},"metadata":${normalizeStringMap(part.metadata)}}"""

  /** Canonical, order-independent string for the cache key. Records and Maps sort their keys so that two requests
    * differing only in option insertion order collide onto the same entry (which `Schema.dynamic.jsonCodec` would
    * not, since it preserves insertion order). Primitives are tagged by their case-class name so a string `"1"`
    * cannot collide with an int `1`. */
  private def normalizeDynamic(value: DynamicValue): String =
    value match
      case DynamicValue.Primitive(p)      => quote(p.toString)
      case DynamicValue.Sequence(elems)   => elems.iterator.map(normalizeDynamic).mkString("[", ",", "]")
      case DynamicValue.Variant(name, v)  => s"{${quote(name)}:${normalizeDynamic(v)}}"
      case DynamicValue.Record(fields) =>
        fields.iterator
          .map { case (k, v) => quote(k) -> normalizeDynamic(v) }
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k:$v" }
          .mkString("{", ",", "}")
      case DynamicValue.Map(entries) =>
        entries.iterator
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

object NoopLmCache extends LmCache:
  override def get(request: LmRequest): Option[LmResponse] = None
  override def put(request: LmRequest, response: LmResponse): Unit = ()

private object DiskCacheModel:
  final case class PersistedResponse(
      outputs: Array[PersistedOutput],
      usage: PersistedUsage | Null,
      modelName: String | Null
  ) extends Serializable

  final case class PersistedOutput(
      text: String,
      toolCalls: Array[PersistedToolCall],
      metadataJson: String
  ) extends Serializable

  // Tool-call args and output metadata are `DynamicValue.Record`s; persist them as their natural JSON (via
  // zio-blocks' DynamicValue JSON codec), faithfully -- not the old lossy String-flattening.
  final case class PersistedToolCall(
      name: String,
      argsJson: String
  ) extends Serializable

  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  def encodeRecord(record: DynamicValue.Record): String =
    new String(dynamicJsonCodec.encode(record), StandardCharsets.UTF_8)

  def decodeRecord(json: String | Null): DynamicValue.Record =
    Option(json) match
      case Some(j) =>
        dynamicJsonCodec.decode(j.getBytes(StandardCharsets.UTF_8)) match
          case Right(rec: DynamicValue.Record) => rec
          case _                               => DynamicValue.Record.empty
      case None => DynamicValue.Record.empty

  final case class PersistedUsage(
      totalTokens: Long,
      promptTokens: Long,
      completionTokens: Long,
      details: java.util.Map[String, java.lang.Long]
  ) extends Serializable

final class DiskLmCache(directory: Path, maxEntries: Int = 200000) extends LmCache:
  import DiskCacheModel.*
  require(maxEntries > 0, "maxEntries must be greater than 0")
  Files.createDirectories(directory)

  override def get(request: LmRequest): Option[LmResponse] =
    val key = RequestHash.forRequest(request)
    val path = keyPath(key)
    this.synchronized {
      if !Files.exists(path) then None
      else
        try
          val bytes = Files.readAllBytes(path)
          deserialize(bytes).map { response =>
            response.copy(cacheHit = true, usage = None)
          }
        catch
          case NonFatal(_) =>
            Files.deleteIfExists(path)
            None
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    val key = RequestHash.forRequest(request)
    val path = keyPath(key)
    this.synchronized {
      try
        val bytes = serialize(response)
        val temp = path.resolveSibling(path.getFileName.toString + ".tmp")
        Files.write(
          temp,
          bytes,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        moveIntoPlace(temp, path)
        evictOverflow()
      catch
        case NonFatal(_) =>
          ()
    }

  def size: Int =
    this.synchronized {
      entryPaths.size
    }

  def clear(): Unit =
    this.synchronized {
      entryPaths.foreach(path => Files.deleteIfExists(path))
    }

  private def keyPath(key: String): Path =
    directory.resolve(s"$key.bin")

  private def moveIntoPlace(temp: Path, target: Path): Unit =
    try
      val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch
      case _: AtomicMoveNotSupportedException =>
        val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)

  private def serialize(response: LmResponse): Array[Byte] =
    val persisted = toPersisted(response)
    val bytes = ByteArrayOutputStream()
    val out = ObjectOutputStream(bytes)
    try
      out.writeObject(persisted)
      out.flush()
      bytes.toByteArray
    finally
      out.close()
      bytes.close()

  private def deserialize(bytes: Array[Byte]): Option[LmResponse] =
    val input = ObjectInputStream(ByteArrayInputStream(bytes))
    try
      input.readObject() match
        case persisted: PersistedResponse => Some(fromPersisted(persisted))
        case _                            => None
    finally input.close()

  private def entryPaths: Vector[Path] =
    val stream = Files.list(directory)
    try
      stream.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".bin"))
        .toVector
    finally stream.close()

  private def evictOverflow(): Unit =
    val entries = entryPaths
      .sortBy(path => Files.getLastModifiedTime(path).toMillis)
    val overflow = entries.size - maxEntries
    if overflow > 0 then
      entries.take(overflow).foreach(path => Files.deleteIfExists(path))

  private def toPersisted(response: LmResponse): PersistedResponse =
    val outputs = response.outputs.map { output =>
      val toolCalls = output.toolCalls.map { call =>
        PersistedToolCall(call.name, encodeRecord(call.args))
      }.toArray
      PersistedOutput(
        text = output.text,
        toolCalls = toolCalls,
        metadataJson = encodeRecord(output.metadata)
      )
    }.toArray
    val usage = response.usage.map { u =>
      PersistedUsage(
        totalTokens = u.totalTokens,
        promptTokens = u.promptTokens,
        completionTokens = u.completionTokens,
        details = toJavaLongMap(u.extras.map { case (category, value) => category.wireName -> value })
      )
    }.orNull
    PersistedResponse(outputs = outputs, usage = usage, modelName = response.modelName.orNull)

  private def fromPersisted(response: PersistedResponse): LmResponse =
    val outputs = Option(response.outputs).getOrElse(Array.empty[PersistedOutput]).toVector.map { output =>
      val toolCalls = Option(output.toolCalls).getOrElse(Array.empty[PersistedToolCall]).toVector.map { call =>
        ToolCall(name = call.name, args = decodeRecord(call.argsJson))
      }
      LmOutput(
        text = Option(output.text).getOrElse(""),
        toolCalls = toolCalls,
        metadata = decodeRecord(output.metadataJson)
      )
    }
    val usage = Option(response.usage).map { u =>
      LmUsage(
        totalTokens = u.totalTokens,
        promptTokens = u.promptTokens,
        completionTokens = u.completionTokens,
        extras = fromJavaLongMap(u.details).map { case (name, value) => TokenCategory.fromWire(name) -> value }
      )
    }
    LmResponse(
      outputs = outputs,
      usage = usage,
      modelName = Option(response.modelName),
      cacheHit = false
    )

  private def toJavaLongMap(values: Map[String, Long]): java.util.Map[String, java.lang.Long] =
    val map = java.util.HashMap[String, java.lang.Long]()
    values.foreach { case (key, value) =>
      map.put(key, value)
    }
    map

  private def fromJavaLongMap(values: java.util.Map[String, java.lang.Long] | Null): Map[String, Long] =
    Option(values).map(_.asScala.iterator.map { case (k, v) => k -> v.toLong }.toMap).getOrElse(Map.empty)

final case class CompositeLmCache(memory: Option[LmCache], disk: Option[LmCache]) extends LmCache:
  override def get(request: LmRequest): Option[LmResponse] =
    memory.flatMap(_.get(request)).orElse {
      disk.flatMap(_.get(request)).map { response =>
        memory.foreach(_.put(request, response))
        response
      }
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    memory.foreach(_.put(request, response))
    disk.foreach(_.put(request, response))

object CacheDefaults:
  val defaultDiskDir: Path =
    Option(System.getenv("DSPY4S_CACHEDIR"))
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.home"), ".dspy4s_cache"))

final case class LmCacheConfig(
    enableDiskCache: Boolean = true,
    enableMemoryCache: Boolean = true,
    diskCacheDir: Path = CacheDefaults.defaultDiskDir,
    diskMaxEntries: Int = 200000,
    memoryMaxEntries: Int = 1000000,
    fallbackToMemoryOnDiskFailure: Boolean = true
):
  require(!enableMemoryCache || memoryMaxEntries > 0, "memoryMaxEntries must be positive when memory cache is enabled")
  require(!enableDiskCache || diskMaxEntries > 0, "diskMaxEntries must be positive when disk cache is enabled")

object LmCaches:
  def build(config: LmCacheConfig): LmCache =
    val memory = if config.enableMemoryCache then Some(new InMemoryLmCache(config.memoryMaxEntries)) else None
    val disk = buildDisk(config)
    CompositeLmCache(memory, disk) match
      case CompositeLmCache(Some(single), None) => single
      case CompositeLmCache(None, Some(single)) => single
      case CompositeLmCache(None, None)         => NoopLmCache
      case composite                            => composite

  private def buildDisk(config: LmCacheConfig): Option[LmCache] =
    if !config.enableDiskCache then None
    else
      try Some(new DiskLmCache(config.diskCacheDir, config.diskMaxEntries))
      catch
        case NonFatal(_) if config.fallbackToMemoryOnDiskFailure => None
        case NonFatal(error)                                     => throw error

object LmCacheRegistry:
  private val activeRef = new AtomicReference[LmCache](LmCaches.build(LmCacheConfig()))

  def current: LmCache = activeRef.get()

  def configure(config: LmCacheConfig): LmCache =
    val cache = LmCaches.build(config)
    activeRef.set(cache)
    cache

  def resetDefault(): Unit =
    activeRef.set(LmCaches.build(LmCacheConfig()))
