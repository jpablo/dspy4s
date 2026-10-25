package dspy4s.lm.runtime

import dspy4s.core.contracts.{LmUsage, TokenCategory, ToolCall, recordSchema}
import dspy4s.lm.contracts.{CacheKey, LmCache, LmOutput, LmResponse}
import zio.blocks.schema.{DynamicValue, Modifier, NameMapper, Schema}
import zio.blocks.schema.json.{JsonCodec, JsonCodecDeriver}

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.{Arrays, Objects}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** An [[LmCache]] with one file for each entry in `directory` (upstream: a versioned plain-data record,
  * `CallResult.dump` and `CACHE_FORMAT` in `dspy/clients/call_result.py`).
  *
  * The file of an entry is `<key>.json`, where `<key>` is the hex text of the [[CacheKey]]. The file is one JSON
  * object:
  * {{{
  * {
  *   "_dspy4s_format": "dspy4s-lm-result-v2",
  *   "outputs": [{
  *     "text": "...",
  *     "tool_calls": [{"name": "...", "args": {...}, "id": "..."}],
  *     "reasoning": "...",
  *     "citations": [{...}],
  *     "metadata": {...}
  *   }],
  *   "usage": {"total_tokens": 9, "prompt_tokens": 4, "completion_tokens": 5, "extras": {"cached_tokens": 3}},
  *   "model_name": "..."
  * }
  * }}}
  * The record has no key for a value that the response does not have: no `id` for a tool call with no id, no
  * `tool_calls` for an output with no tool call, no `reasoning` for an output with an empty reasoning, no `citations`
  * for an output with no citation, and no `usage` or `model_name` for a response with none. An absent key reads as that
  * value. The `args`, the `citations`, and the `metadata` keep their JSON types.
  *
  * A file that is not a record of [[DiskLmCache.RecordFormat]] is a miss, and `get` removes it: a file that is not
  * JSON, a record with another `_dspy4s_format`, or a record with a value of the wrong JSON type. A file that `get`
  * cannot read, for example a directory at the path of a record, is a miss too, and `get` keeps it.
  *
  * A response with no JSON text, for example one with tool-call arguments that are nested too deep for the stack, is
  * not stored: `put` writes no file and does not throw (upstream `Cache.put` logs the failure and continues).
  *
  * `put` encodes the record and writes it to a new temporary file `<key><random>.tmp` in `directory` with no lock. Then
  * it moves the file into place. The move is atomic where the file system supports it, so `get` reads a complete file
  * and takes no lock. A failed write or move leaves no temporary file. A process that stops during a `put` can leave a
  * temporary file. `clear` removes each temporary file, and each list of the directory for the count or the eviction
  * removes each temporary file that is older than 10 minutes. `size` and the count include only the records.
  *
  * The lock of the cache covers the move, the count, and each remove. A remove checks under the lock that the file is
  * still the file that the cache read or listed, and keeps a file that a `put` moved into place after that. Thus a
  * remove does not delete a newer record.
  *
  * The cache keeps a count of its entries (upstream `diskcache` keeps the count in its index). The first `put` lists
  * the directory to set the count. Each file that the cache writes or removes changes the count, so a `put` below
  * `maxEntries` does not list the directory. When the count passes `maxEntries`, the eviction lists the directory,
  * reads the attributes of each entry one time, removes the oldest entries down to 90% of `maxEntries` (rounded down,
  * and 1 at least), and sets the count again from the list. Thus the next eviction comes after 10% of `maxEntries` new
  * entries, not at the next `put`. This rule is the port form of the cull of upstream `diskcache`: each `set` at the
  * size limit removes up to `cull_limit` entries (10 by default) through the SQLite index, which the port does not
  * have. One thread at a time runs the eviction, with no lock: a `put` that passes `maxEntries` during an eviction does
  * not wait and does not evict. A second writer of the directory does not change the count of this cache. Thus the
  * directory can hold more entries than `maxEntries`, until the count of this cache passes `maxEntries`.
  */
final class DiskLmCache private[runtime] (directory: Path, maxEntries: CacheCapacity, files: DiskLmCache.EntryFiles)
    extends LmCache:
  import DiskLmCache.*
  Files.createDirectories(directory)

  def this(directory: Path, maxEntries: CacheCapacity = CacheCapacity(200000)) =
    this(directory, maxEntries, DiskLmCache.EntryFiles.system)

  /** The count of the entries, or `None` before the first `put` and after `clear`. The lock of the cache guards it. */
  private var entryCount: Option[Int] = None

  /** `true` while a thread runs the eviction. */
  private val evicting = AtomicBoolean(false)

  /** The number of entries that an eviction keeps: 90% of `maxEntries`, rounded down, and 1 at least, so the entry of
    * the `put` stays.
    */
  private val evictionTarget: Int = math.max(1, (maxEntries.toLong * 9L / 10L).toInt)

  /** The response of the record of `key`. A file that the function cannot read is a miss, and the file stays. A file
    * whose bytes are not a record of [[RecordFormat]] is a miss, and the function removes it, when the file still has
    * these bytes.
    */
  override def get(key: CacheKey): Option[LmResponse] =
    val path = recordPath(key)
    val read =
      try Some(Files.readAllBytes(path))
      catch case NonFatal(_) => None
    read.flatMap { bytes =>
      val response = decode(bytes)
      if response.isEmpty then removeIf(path)(current => Arrays.equals(Files.readAllBytes(current), bytes))
      response
    }

  /** Store the record of `response` for `key`. A response with no JSON text writes no file. A failure of the file
    * system writes no record, and the function does not throw.
    */
  override def put(key: CacheKey, response: LmResponse): Unit =
    encode(response).foreach { bytes =>
      try
        countEntries()
        val temp = Files.createTempFile(directory, key.hex, TempSuffix)
        try
          val _ = Files.write(temp, bytes)
          if moveIntoPlace(temp, recordPath(key)) then evictOverflow()
        finally
          val _ = Files.deleteIfExists(temp)
      catch
        case NonFatal(_) => ()
    }

  /** The number of records in `directory`. A temporary file is not a record. */
  def size: Int =
    files.list(directory).count(_.isRecord)

  /** Remove each record and each temporary file in `directory`. */
  def clear(): Unit =
    files.list(directory).foreach(entry => Files.deleteIfExists(entry.path))
    this.synchronized {
      entryCount = None
    }

  private def recordPath(key: CacheKey): Path =
    directory.resolve(s"${key.hex}$RecordSuffix")

  /** Set the count from a list of the directory when the count is not known. The list takes no lock. */
  private def countEntries(): Unit =
    if this.synchronized(entryCount.isEmpty) then
      val listed = records(files.list(directory)).size
      this.synchronized {
        if entryCount.isEmpty then entryCount = Some(listed)
      }

  /** The records of `entries`. The function removes each temporary file of `entries` that is older than
    * [[StaleTempMillis]]: the `put` of that file stopped, for example because its process stopped.
    */
  private def records(entries: Vector[Entry]): Vector[Entry] =
    val (found, temporary) = entries.partition(_.isRecord)
    val oldest             = System.currentTimeMillis() - StaleTempMillis
    temporary.filter(_.modifiedMillis < oldest).foreach { entry =>
      try
        val _ = Files.deleteIfExists(entry.path)
      catch
        case NonFatal(_) => ()
    }
    found

  /** Move `temp` to `target`, and add the record to the count when it is a new file. `true` when the count passes
    * `maxEntries`. A failure of the file system throws.
    */
  private def moveIntoPlace(temp: Path, target: Path): Boolean =
    this.synchronized {
      val created = !Files.exists(target)
      try
        val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch
        case _: AtomicMoveNotSupportedException => val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
      if created then changeCount(1)
      entryCount.exists(_ > maxEntries)
    }

  /** Remove the file at `path` when `unchanged` gives `true` for it, and remove it from the count when the file was
    * there. The check and the remove occur under the lock of the cache, and [[moveIntoPlace]] takes the same lock, so
    * no `put` moves a new record to `path` between them. A failure of the file system keeps the file.
    */
  private def removeIf(path: Path)(unchanged: Path => Boolean): Unit =
    try
      this.synchronized {
        if unchanged(path) && Files.deleteIfExists(path) then changeCount(-1)
      }
    catch
      case NonFatal(_) => ()

  private def changeCount(delta: Int): Unit =
    entryCount = entryCount.map(count => math.max(0, count + delta))

  /** List the directory, set the count from the list, and remove the oldest entries above [[evictionTarget]]. When a
    * second thread runs the eviction, the function does nothing. The function keeps an entry whose file changed after
    * the list, for example a record that a `put` moved into place.
    */
  private def evictOverflow(): Unit =
    if evicting.compareAndSet(false, true) then
      try
        val entries = records(files.list(directory))
        this.synchronized {
          entryCount = Some(entries.size)
        }
        val overflow = entries.size - evictionTarget
        if overflow > 0 then
          entries.sortBy(_.modifiedMillis).take(overflow).foreach { entry =>
            removeIf(entry.path)(current => entryOf(current).exists(_.sameFile(entry)))
          }
      finally evicting.set(false)

object DiskLmCache:
  /** The value of the `_dspy4s_format` key of a disk record. */
  val RecordFormat: String = "dspy4s-lm-result-v2"

  private val RecordSuffix = ".json"
  private val TempSuffix   = ".tmp"

  /** The age in milliseconds after which a list of the directory removes a temporary file: 10 minutes. */
  private val StaleTempMillis = 10L * 60L * 1000L

  /** One entry file: its path, the time of its last change in milliseconds, and the key of the file system for the file
    * (`fileKey` of `BasicFileAttributes`, `None` when the file system has no key). An entry is a record or a temporary
    * file of a `put`.
    */
  private[runtime] final case class Entry(path: Path, modifiedMillis: Long, fileKey: Option[AnyRef]):
    def isRecord: Boolean = path.getFileName.toString.endsWith(RecordSuffix)

    /** `true` when `other` has the time of the last change and the file key of this entry. A file that a `put` moved
      * into place or wrote again after the list gives `false`.
      */
    def sameFile(other: Entry): Boolean =
      modifiedMillis == other.modifiedMillis && Objects.equals(fileKey.orNull, other.fileKey.orNull)

  /** The list of the entry files, for the size, the count, the eviction, and `clear`. A test can count the lists. */
  private[runtime] trait EntryFiles:
    /** The entries in `directory`: each regular file with the suffix of a record or of a temporary file. The function
      * reads the attributes of each file one time. A file that goes away before the function reads its attributes is
      * not an entry.
      */
    def list(directory: Path): Vector[Entry]

  private[runtime] object EntryFiles:
    /** The list that reads the file system. */
    val system: EntryFiles = directory =>
      val stream = Files.list(directory)
      try stream.iterator().asScala.filter(isEntryName).flatMap(entryOf).toVector
      finally stream.close()

    private def isEntryName(path: Path): Boolean =
      val name = path.getFileName.toString
      name.endsWith(RecordSuffix) || name.endsWith(TempSuffix)

  /** The entry of the regular file at `path`, or `None` when no regular file is there. */
  private def entryOf(path: Path): Option[Entry] =
    try
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      Option.when(attributes.isRegularFile)(
        Entry(path, attributes.lastModifiedTime.toMillis, Option(attributes.fileKey))
      )
    catch
      case _: IOException => None

  /** The JSON record of a response (upstream `CallResult.dump`). The `SnakeCase` name mapper gives the snake_case keys
    * of the camelCase fields. The codec writes no key for a `None`, an empty collection, or a field with its default
    * value, and it reads an absent key as `None`, as an empty collection, or as the default value. It ignores the keys
    * that it does not know.
    */
  private final case class DiskRecord(
      @Modifier.rename("_dspy4s_format")
      format   : String,
      outputs  : Vector[DiskOutput],
      usage    : Option[DiskUsage],
      modelName: Option[String]
  ) derives Schema

  private final case class DiskOutput(
      text     : String,
      toolCalls: Vector[ToolCall],
      reasoning: String                      = "",
      citations: Vector[DynamicValue.Record] = Vector.empty,
      metadata : DynamicValue.Record
  ) derives Schema

  /** The usage of a record. The keys of `extras` are the wire names of the token categories. */
  private final case class DiskUsage(
      totalTokens     : Long,
      promptTokens    : Long,
      completionTokens: Long,
      extras          : Map[String, Long]
  ) derives Schema

  private val codec: JsonCodec[DiskRecord] =
    Schema[DiskRecord].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

  /** The JSON bytes of the record of `response`, or `None` when the response has no JSON text. The writer throws on a
    * number that is not finite and on a text with a lone UTF-16 surrogate. The writer is recursive, so a value that is
    * nested too deep for the stack of the thread gives `None` too (the rule of `DynamicValues.tryEncodeJson`).
    */
  private def encode(response: LmResponse): Option[Array[Byte]] =
    val usage = response.usage.map { value =>
      DiskUsage(
        totalTokens = value.totalTokens,
        promptTokens = value.promptTokens,
        completionTokens = value.completionTokens,
        extras = value.extras.map((category, count) => category.wireName -> count)
      )
    }
    val record = DiskRecord(
      format = RecordFormat,
      outputs = response.outputs.map { output =>
        DiskOutput(output.text, output.toolCalls, output.reasoning, output.citations, output.metadata)
      },
      usage = usage,
      modelName = response.modelName
    )
    try Some(codec.encode(record))
    catch
      case _: StackOverflowError => None
      case NonFatal(_)           => None

  /** The response of the JSON bytes of a record (upstream `CallResult.load`). `None` when the bytes are not a record of
    * [[RecordFormat]]. The reader is recursive, so a value that is nested too deep for the stack of the thread gives
    * `None` too.
    */
  private def decode(bytes: Array[Byte]): Option[LmResponse] =
    val record =
      try codec.decode(bytes).toOption
      catch case _: StackOverflowError => None
    record.filter(_.format == RecordFormat).map { found =>
      LmResponse(
        outputs = found.outputs.map { output =>
          LmOutput(output.text, output.toolCalls, output.reasoning, output.citations, output.metadata)
        },
        usage = found.usage.map { value =>
          LmUsage(
            totalTokens = value.totalTokens,
            promptTokens = value.promptTokens,
            completionTokens = value.completionTokens,
            extras = value.extras.map((name, count) => TokenCategory.fromWire(name) -> count)
          )
        },
        modelName = found.modelName
      )
    }
