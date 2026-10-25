package dspy4s.lm.runtime

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.{:=, DynamicValues, LmUsage, TokenCategory, ToolCall}
import dspy4s.lm.contracts.{LmCache, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.providers.DynamicJson
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.io.{ByteArrayInputStream, ObjectInputFilter, ObjectInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The classes of the legacy disk record: the Java serialization that `DiskLmCache` wrote before the JSON record.
  * `DiskLmCache` only reads them now, to move a legacy entry to the JSON record.
  *
  * Each class has a fixed `serialVersionUID`. The value is the one that the JVM computed for the class before
  * `PersistedToolCall.id` existed, so an entry that an older version wrote stays readable. With a fixed value, Java
  * serialization accepts a new field: an old entry gives the field its default (`null`). Thus a new field must have a
  * correct meaning when it is `null`. Do not rename a field and do not change the type of a field.
  */
private[runtime] object DiskCacheModel:
  @SerialVersionUID(-8046790753251875400L)
  final case class PersistedResponse(
      outputs  : Array[PersistedOutput],
      usage    : PersistedUsage | Null,
      modelName: String | Null
  ) extends Serializable

  @SerialVersionUID(-6858508434379838130L)
  final case class PersistedOutput(
      text        : String,
      toolCalls   : Array[PersistedToolCall],
      metadataJson: String
  ) extends Serializable

  // Tool-call args and output metadata are `DynamicValue.Record`s; persist them as their natural JSON (via
  // zio-blocks' DynamicValue JSON codec), faithfully -- not the old lossy String-flattening.
  // `id` is null for a call with no id, and for an entry that a version without this field wrote.
  @SerialVersionUID(3838258375993650620L)
  final case class PersistedToolCall(
      name    : String,
      argsJson: String,
      id      : String | Null
  ) extends Serializable

  /** The JSON text of a record, as a legacy entry holds it. */
  def encodeRecord(record: DynamicValue.Record): String = DynamicJson.encodeExact(record)

  /** The record of a JSON text that a legacy entry holds. Text that is not a JSON object gives an empty record. */
  def decodeRecord(json: String | Null): DynamicValue.Record =
    Option(json).flatMap(text => DynamicJson.decode(text).toOption).flatMap(DynamicJson.asRecord)
      .getOrElse(DynamicValue.Record.empty)

  @SerialVersionUID(-3539090977201704575L)
  final case class PersistedUsage(
      totalTokens     : Long,
      promptTokens    : Long,
      completionTokens: Long,
      details         : java.util.Map[String, java.lang.Long]
  ) extends Serializable

  /** The classes that a legacy entry holds, other than arrays: the record classes, and the JDK classes of
    * `PersistedUsage.details` and of the `String` fields. `java.lang.Number` is the superclass of `java.lang.Long`.
    */
  private val legacyClasses: Set[Class[?]] = Set(
    classOf[PersistedResponse],
    classOf[PersistedOutput],
    classOf[PersistedToolCall],
    classOf[PersistedUsage],
    classOf[java.lang.String],
    classOf[java.lang.Long],
    classOf[java.lang.Number],
    classOf[java.util.HashMap[?, ?]]
  )

  /** The element classes of the arrays that a legacy entry holds. Each array has one dimension. `HashMap.readObject`
    * makes the filter check a `java.util.Map$Entry[]` with the size of the table of the map.
    */
  private val legacyArrayElements: Set[Class[?]] = Set(
    classOf[PersistedOutput],
    classOf[PersistedToolCall],
    classOf[java.util.Map.Entry[?, ?]]
  )

  /** The maximum depth of the object graph of a legacy entry. A legacy entry has 6 levels: `PersistedResponse`, the
    * outputs array, `PersistedOutput`, the tool-call array, `PersistedToolCall`, and its `String` fields.
    */
  private val MaxLegacyDepth: Long = 8L

  /** The filter of a legacy entry of `streamBytes` bytes. It accepts only the classes in [[legacyClasses]], and the
    * arrays of one dimension of the classes in [[legacyArrayElements]]. It rejects each other class before Java
    * serialization reads an object of that class. It also rejects a graph deeper than [[MaxLegacyDepth]], and an array
    * with more elements than the entry has bytes, because each element takes at least one byte.
    */
  private def legacyFilter(streamBytes: Long): ObjectInputFilter =
    info =>
      if info.depth() > MaxLegacyDepth then ObjectInputFilter.Status.REJECTED
      else
        Option(info.serialClass()) match
          case None                                     => ObjectInputFilter.Status.UNDECIDED
          case Some(serialClass) if serialClass.isArray =>
            if legacyArrayElements.contains(serialClass.getComponentType) && info.arrayLength() <= streamBytes then
              ObjectInputFilter.Status.ALLOWED
            else ObjectInputFilter.Status.REJECTED
          case Some(serialClass) =>
            if legacyClasses.contains(serialClass) then ObjectInputFilter.Status.ALLOWED
            else ObjectInputFilter.Status.REJECTED

  /** The response of the bytes of a legacy entry. A class that the filter rejects gives an `InvalidClassException`
    * before Java serialization reads an object of that class. A top object of another class gives `None`.
    */
  def readLegacy(bytes: Array[Byte]): Option[LmResponse] =
    val input = ObjectInputStream(ByteArrayInputStream(bytes))
    try
      input.setObjectInputFilter(legacyFilter(bytes.length.toLong))
      input.readObject() match
        case persisted: PersistedResponse => Some(fromPersisted(persisted))
        case _                            => None
    finally input.close()

  private def fromPersisted(response: PersistedResponse): LmResponse =
    val outputs = Option(response.outputs).getOrElse(Array.empty[PersistedOutput]).toVector.map { output =>
      val toolCalls = Option(output.toolCalls).getOrElse(Array.empty[PersistedToolCall]).toVector.map { call =>
        ToolCall(name = call.name, args = decodeRecord(call.argsJson), id = Option(call.id))
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

  private def fromJavaLongMap(values: java.util.Map[String, java.lang.Long] | Null): Map[String, Long] =
    Option(values).map(_.asScala.iterator.map { case (k, v) => k -> v.toLong }.toMap).getOrElse(Map.empty)

/** An [[LmCache]] with one file for each entry in `directory` (upstream: a versioned plain-data record,
  * `CallResult.dump` and `CACHE_FORMAT` in `dspy/clients/call_result.py`).
  *
  * The file of an entry is `<key>.json`, where the key is [[RequestHash.forRequestChecked]] of the request. A request
  * with no key is a miss, and `put` writes no file for it. The file is one JSON object:
  * {{{
  * {
  *   "_dspy4s_format": "dspy4s-lm-result-v1",
  *   "outputs": [{"text": "...", "tool_calls": [{"name": "...", "args": {...}, "id": "..."}], "metadata": {...}}],
  *   "usage": {"total_tokens": 9, "prompt_tokens": 4, "completion_tokens": 5, "extras": {"cached_tokens": 3}},
  *   "model_name": "..."
  * }
  * }}}
  * A tool call with no id has no `id` key. `usage` and `model_name` are `null` when the response has none. The `args`
  * and the `metadata` keep their JSON types.
  *
  * A file that is not JSON, or that has another `_dspy4s_format`, is a miss, and `get` removes it.
  *
  * A legacy entry, `<key>.bin`, holds the Java serialization of [[DiskCacheModel]]. `get` reads it with an
  * `ObjectInputFilter` that accepts only the classes of that record and the JDK classes that they hold, so a file with
  * another class does not run the code of that class. A legacy entry that the filter accepts is a hit: `get` writes it
  * again as `<key>.json` and removes the `.bin` file. A legacy entry that the filter rejects, or that does not decode,
  * is a miss, and `get` removes it. `put` writes only the JSON record.
  *
  * A response with no JSON text, for example one with tool-call arguments that are nested too deep for the stack, is
  * not stored: `put` writes no file and does not throw (upstream `Cache.put` logs the failure and continues).
  *
  * `size`, `clear`, and the eviction of the oldest entries count the two kinds of file.
  *
  * The cache keeps a count of its entries (upstream `diskcache` keeps the count in its index). The first `put` lists
  * the directory to set the count. Each file that the cache writes or removes changes the count, so a `put` below
  * `maxEntries` does not list the directory. When the count passes `maxEntries`, the eviction lists the directory,
  * reads the time of each entry one time, removes the oldest entries down to 90% of `maxEntries` (rounded down, and 1
  * at least), and sets the count again from the list. Thus the next eviction comes after 10% of `maxEntries` new
  * entries, not at the next `put`. This rule is the port form of the cull of upstream `diskcache`: each `set` at the
  * size limit removes up to `cull_limit` entries (10 by default) through the SQLite index, which the port does not
  * have. A second writer of the directory does not change the count of this cache. Thus the directory can hold more
  * entries than `maxEntries`, until the count of this cache passes `maxEntries`.
  */
final class DiskLmCache private[runtime] (directory: Path, maxEntries: CacheCapacity, files: DiskLmCache.EntryFiles)
    extends LmCache:
  import DiskLmCache.*
  Files.createDirectories(directory)

  def this(directory: Path, maxEntries: CacheCapacity = CacheCapacity(200000)) =
    this(directory, maxEntries, DiskLmCache.EntryFiles.system)

  /** The count of the entries, or `None` before the first `put` and after `clear`. */
  private var entryCount: Option[Int] = None

  /** The number of entries that an eviction keeps: 90% of `maxEntries`, rounded down, and 1 at least, so the entry of
    * the `put` stays.
    */
  private val evictionTarget: Int = math.max(1, (maxEntries.toLong * 9L / 10L).toInt)

  override def get(request: LmRequest): Option[LmResponse] =
    RequestHash.forRequestChecked(request).toOption.flatMap { key =>
      this.synchronized {
        readRecord(recordPath(key))
          .orElse(readLegacyEntry(key))
          .map(response => response.copy(cacheHit = true))
      }
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    RequestHash.forRequestChecked(request).foreach { key =>
      this.synchronized {
        try
          if entryCount.isEmpty then entryCount = Some(entryPaths.size)
          if store(key, response) then
            remove(legacyPath(key))
            if entryCount.exists(_ > maxEntries) then evictOverflow()
        catch
          case NonFatal(_) => ()
      }
    }

  def size: Int =
    this.synchronized {
      entryPaths.size
    }

  def clear(): Unit =
    this.synchronized {
      entryCount = None
      entryPaths.foreach(path => Files.deleteIfExists(path))
    }

  private def recordPath(key: String): Path =
    directory.resolve(s"$key$RecordSuffix")

  private def legacyPath(key: String): Path =
    directory.resolve(s"$key$LegacySuffix")

  /** The response of the JSON record at `path`. A file that is not a record of [[RecordFormat]] is a miss, and the
    * function removes it.
    */
  private def readRecord(path: Path): Option[LmResponse] =
    if !Files.exists(path) then None
    else
      val response =
        try DynamicJson.decode(Files.readString(path)).toOption.flatMap(decodeResponse)
        catch
          case NonFatal(_) => None
      if response.isEmpty then remove(path)
      response

  /** The response of the legacy entry of `key`. An entry that the filter accepts moves to the JSON record. Each other
    * entry is a miss, and the function removes it.
    */
  private def readLegacyEntry(key: String): Option[LmResponse] =
    val path = legacyPath(key)
    if !Files.exists(path) then None
    else
      val response =
        try DiskCacheModel.readLegacy(Files.readAllBytes(path))
        catch
          case NonFatal(_) => None
      response match
        case Some(value) =>
          // When the write fails, the legacy entry stays, so that a later `get` can move it.
          try
            if store(key, value) then remove(path)
          catch
            case NonFatal(_) => ()
        case None => remove(path)
      response

  /** Write the JSON record of `response` for `key`, and add the record to the count when it is a new file. A response
    * with no JSON text gives `false`, and the function writes no file. A failure of the file system throws.
    */
  private def store(key: String, response: LmResponse): Boolean =
    val path    = recordPath(key)
    val created = !Files.exists(path)
    val written = writeRecord(path, response)
    if written && created then changeCount(1)
    written

  private def writeRecord(path: Path, response: LmResponse): Boolean =
    DynamicJson.encodeExactChecked(encodeResponse(response)) match
      case Left(_)     => false
      case Right(json) =>
        val temp = path.resolveSibling(path.getFileName.toString + ".tmp")
        val _    = Files.write(
          temp,
          json.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        moveIntoPlace(temp, path)
        true

  /** Remove the file at `path`, and remove it from the count when the file was there. */
  private def remove(path: Path): Unit =
    try
      if Files.deleteIfExists(path) then changeCount(-1)
    catch
      case NonFatal(_) => ()

  private def changeCount(delta: Int): Unit =
    entryCount = entryCount.map(count => math.max(0, count + delta))

  private def moveIntoPlace(temp: Path, target: Path): Unit =
    try
      val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch
      case _: AtomicMoveNotSupportedException => val _ = Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)

  private def entryPaths: Vector[Path] =
    files.list(directory)

  /** List the directory, set the count from the list, and remove the oldest entries above [[evictionTarget]]. The
    * function reads the time of each entry one time. A file that goes away before the function reads its time is not an
    * entry.
    */
  private def evictOverflow(): Unit =
    val entries = entryPaths.flatMap { path =>
      try Some(path -> files.modifiedMillis(path))
      catch case NonFatal(_) => None
    }
    entryCount = Some(entries.size)
    val overflow = entries.size - evictionTarget
    if overflow > 0 then entries.sortBy(_._2).take(overflow).foreach((path, _) => remove(path))

object DiskLmCache:
  /** The value of the `_dspy4s_format` key of a disk record. */
  val RecordFormat: String = "dspy4s-lm-result-v1"

  private val FormatKey    = "_dspy4s_format"
  private val RecordSuffix = ".json"
  private val LegacySuffix = ".bin"

  /** The file operations of the count and of the eviction of the entries. A test can count them. */
  private[runtime] trait EntryFiles:
    /** The entry files in `directory`: each regular file with the suffix of a record or of a legacy entry. */
    def list(directory: Path): Vector[Path]

    /** The time of the last change of the file at `path`, in milliseconds. */
    def modifiedMillis(path: Path): Long

  private[runtime] object EntryFiles:
    /** The operations of the file system. */
    val system: EntryFiles = new EntryFiles:
      override def list(directory: Path): Vector[Path] =
        val stream = Files.list(directory)
        try stream.iterator().asScala
            .filter { path =>
              val name = path.getFileName.toString
              Files.isRegularFile(path) && (name.endsWith(RecordSuffix) || name.endsWith(LegacySuffix))
            }
            .toVector
        finally stream.close()

      override def modifiedMillis(path: Path): Long =
        Files.getLastModifiedTime(path).toMillis

  /** The JSON record of a response (upstream `CallResult.dump`). */
  private def encodeResponse(response: LmResponse): DynamicValue.Record =
    DynamicValues.record(
      FormatKey    := RecordFormat,
      "outputs"    -> sequence(response.outputs.map(encodeOutput)),
      "usage"      -> response.usage.map(encodeUsage).getOrElse(DynamicValue.Null),
      "model_name" -> response.modelName.map(string).getOrElse(DynamicValue.Null)
    )

  private def encodeOutput(output: LmOutput): DynamicValue.Record =
    DynamicValues.record(
      "text"       := output.text,
      "tool_calls" -> sequence(output.toolCalls.map(encodeToolCall)),
      "metadata"   -> output.metadata
    )

  private def encodeToolCall(call: ToolCall): DynamicValue.Record =
    DynamicValues.recordFromEntries(
      Vector[(String, DynamicValue)]("name" := call.name, "args" -> call.args) ++ call.id.map(id => "id" := id)
    )

  private def encodeUsage(usage: LmUsage): DynamicValue.Record =
    val extras = usage.extras.map((category, value) => category.wireName -> value).toVector.sortBy(_._1)
    DynamicValues.record(
      "total_tokens"      := usage.totalTokens,
      "prompt_tokens"     := usage.promptTokens,
      "completion_tokens" := usage.completionTokens,
      "extras"            -> DynamicValues.recordFromEntries(extras.map((name, value) => name := value))
    )

  /** The response of a JSON record (upstream `CallResult.load`). `None` when the value is not a record of
    * [[RecordFormat]], or when a field has the wrong JSON type. An absent `usage`, `model_name`, or `id` is the same as
    * `null`.
    */
  private def decodeResponse(value: DynamicValue): Option[LmResponse] =
    for
      record <- DynamicJson.asRecord(value)
      format <- DynamicValues.recordGet(record, FormatKey).flatMap(DynamicJson.asString)
      if format == RecordFormat
      items     <- DynamicValues.recordGet(record, "outputs").flatMap(elements)
      outputs   <- Traverse.option(items)(decodeOutput)
      usage     <- nullable(DynamicValues.recordGet(record, "usage"))(decodeUsage)
      modelName <- nullable(DynamicValues.recordGet(record, "model_name"))(DynamicJson.asString)
    yield LmResponse(outputs = outputs, usage = usage, modelName = modelName)

  private def decodeOutput(value: DynamicValue): Option[LmOutput] =
    for
      record    <- DynamicJson.asRecord(value)
      text      <- DynamicValues.recordGet(record, "text").flatMap(DynamicJson.asString)
      items     <- DynamicValues.recordGet(record, "tool_calls").flatMap(elements)
      toolCalls <- Traverse.option(items)(decodeToolCall)
      metadata  <- DynamicValues.recordGet(record, "metadata").flatMap(DynamicJson.asRecord)
    yield LmOutput(text = text, toolCalls = toolCalls, metadata = metadata)

  private def decodeToolCall(value: DynamicValue): Option[ToolCall] =
    for
      record <- DynamicJson.asRecord(value)
      name   <- DynamicValues.recordGet(record, "name").flatMap(DynamicJson.asString)
      args   <- DynamicValues.recordGet(record, "args").flatMap(DynamicJson.asRecord)
      id     <- nullable(DynamicValues.recordGet(record, "id"))(DynamicJson.asString)
    yield ToolCall(name = name, args = args, id = id)

  private def decodeUsage(value: DynamicValue): Option[LmUsage] =
    for
      record       <- DynamicJson.asRecord(value)
      total        <- DynamicValues.recordGet(record, "total_tokens").flatMap(wholeNumber)
      prompt       <- DynamicValues.recordGet(record, "prompt_tokens").flatMap(wholeNumber)
      completion   <- DynamicValues.recordGet(record, "completion_tokens").flatMap(wholeNumber)
      extrasRecord <- DynamicValues.recordGet(record, "extras").flatMap(DynamicJson.asRecord)
      extras       <- Traverse.option(DynamicValues.recordEntries(extrasRecord)) { (name, item) =>
                  wholeNumber(item).map(number => TokenCategory.fromWire(name) -> number)
                }
    yield LmUsage(totalTokens = total, promptTokens = prompt, completionTokens = completion, extras = extras.toMap)

  private def string(text: String): DynamicValue =
    DynamicValue.Primitive(PrimitiveValue.String(text))

  private def sequence(values: Vector[DynamicValue]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(values))

  /** The elements of a JSON array. `None` for another value. */
  private def elements(value: DynamicValue): Option[Vector[DynamicValue]] =
    value match
      case items: DynamicValue.Sequence => Some(items.elements.iterator.toVector)
      case _                            => None

  /** A JSON integer that fits in a `Long`. `None` for another value. */
  private def wholeNumber(value: DynamicValue): Option[Long] =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Int(number))  => Some(number.toLong)
      case DynamicValue.Primitive(PrimitiveValue.Long(number)) => Some(number)
      case _                                                   => None

  /** The decoded value of a field that can be `null`. An absent field and `null` give `Some(None)`. A value that does
    * not decode gives `None`.
    */
  private def nullable[A](value: Option[DynamicValue])(decode: DynamicValue => Option[A]): Option[Option[A]] =
    value match
      case None | Some(_: DynamicValue.Null.type) => Some(None)
      case Some(present)                          => decode(present).map(Some(_))
