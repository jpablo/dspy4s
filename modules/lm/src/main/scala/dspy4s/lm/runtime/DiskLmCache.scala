package dspy4s.lm.runtime

import dspy4s.core.contracts.{LmUsage, TokenCategory, ToolCall}
import dspy4s.lm.contracts.{LmCache, LmOutput, LmRequest, LmResponse}
import zio.blocks.schema.{DynamicValue, Schema}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private object DiskCacheModel:
  final case class PersistedResponse(
      outputs  : Array[PersistedOutput],
      usage    : PersistedUsage | Null,
      modelName: String | Null
  ) extends Serializable

  final case class PersistedOutput(
      text        : String,
      toolCalls   : Array[PersistedToolCall],
      metadataJson: String
  ) extends Serializable

  // Tool-call args and output metadata are `DynamicValue.Record`s; persist them as their natural JSON (via
  // zio-blocks' DynamicValue JSON codec), faithfully -- not the old lossy String-flattening.
  final case class PersistedToolCall(
      name    : String,
      argsJson: String
  ) extends Serializable

  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  def encodeRecord(record: DynamicValue.Record): String =
    new String(dynamicJsonCodec.encode(record), StandardCharsets.UTF_8)

  def decodeRecord(json: String | Null): DynamicValue.Record =
    Option(json) match
      case Some(j) => dynamicJsonCodec.decode(j.getBytes(StandardCharsets.UTF_8)) match
          case Right(rec: DynamicValue.Record) => rec
          case _                               => DynamicValue.Record.empty
      case None => DynamicValue.Record.empty

  final case class PersistedUsage(
      totalTokens     : Long,
      promptTokens    : Long,
      completionTokens: Long,
      details         : java.util.Map[String, java.lang.Long]
  ) extends Serializable

final class DiskLmCache(directory: Path, maxEntries: CacheCapacity = CacheCapacity(200000)) extends LmCache:
  import DiskCacheModel.*
  Files.createDirectories(directory)

  override def get(request: LmRequest): Option[LmResponse] =
    val key  = RequestHash.forRequest(request)
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
    val key  = RequestHash.forRequest(request)
    val path = keyPath(key)
    this.synchronized {
      try
        val bytes = serialize(response)
        val temp  = path.resolveSibling(path.getFileName.toString + ".tmp")
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
        case NonFatal(_) => ()
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
      case _: AtomicMoveNotSupportedException => val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)

  private def serialize(response: LmResponse): Array[Byte] =
    val persisted = toPersisted(response)
    val bytes     = ByteArrayOutputStream()
    val out       = ObjectOutputStream(bytes)
    try
      out.writeObject(persisted)
      out.flush()
      bytes.toByteArray
    finally
      out.close()
      bytes.close()

  private def deserialize(bytes: Array[Byte]): Option[LmResponse] =
    val input = ObjectInputStream(ByteArrayInputStream(bytes))
    try input.readObject() match
        case persisted: PersistedResponse => Some(fromPersisted(persisted))
        case _                            => None
    finally input.close()

  private def entryPaths: Vector[Path] =
    val stream = Files.list(directory)
    try stream.iterator().asScala
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
    values.foreach { case (key, value) => map.put(key, value) }
    map

  private def fromJavaLongMap(values: java.util.Map[String, java.lang.Long] | Null): Map[String, Long] =
    Option(values).map(_.asScala.iterator.map { case (k, v) => k -> v.toLong }.toMap).getOrElse(Map.empty)
