package dspy4s.harness.mcp

import dspy4s.harness.guest.*
import dspy4s.harness.sandbox.Layout
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** The files of the workspace under `root`, for reading only. A path outside the workspace is an error, also through a
  * symbolic link. The server gives the bare `Files`, so a cell cannot call `write` or `edit`, and these methods are
  * errors too.
  *
  * The secret files stay closed, as in each mode of 5.4 and 5.6. A read is an error when the path or its real path is
  * under a path of `hidden`, or when a part of it under the workspace has a secret name of `names`.
  */
final class DirectoryFiles(root: Path, hidden: Vector[Path], names: Set[String]) extends Files:
  private val base = root.toRealPath()

  private def readable(path: String): Path =
    val target = base.resolve(path).normalize
    val real   = if JFiles.exists(target) then target.toRealPath() else target
    if !real.startsWith(base) then throw IllegalArgumentException(s"$path is outside the workspace")
    if secret(target) || secret(real) then throw IllegalArgumentException(s"$path is a secret file")
    real

  /** True when `path` is under a hidden path, or a part of it under the workspace has a secret name. */
  private def secret(path: Path): Boolean =
    hidden.exists(path.startsWith) ||
      path.startsWith(base) && base.relativize(path).asScala.exists(part => Layout.isSecretName(part.toString, names))

  def read(path : String): String                                 = JFiles.readString(readable(path), UTF_8)
  def write(path: String, content: String): Unit                  = readOnly("write")
  def edit(path : String, oldText: String, newText: String): Unit = readOnly("edit")

  private def readOnly(op: String): Nothing = throw UnsupportedOperationException(s"$op: this workspace is read-only")

/** A store in memory. It keeps the JSON text of each value, as the journal does, so `load` reads the value back with
  * the type of the cell that calls it.
  */
final class MemoryStore extends Store:
  private val values = ConcurrentHashMap[String, String]()

  def keys: Vector[String] = values.keySet.asScala.toVector.sorted

  def load[A: Data](key: String): Option[A] =
    Option(values.get(key)).map { text =>
      val json =
        Json.parse(text).fold(e => throw IllegalStateException(s"the text of $key does not parse: $e"), identity)
      summon[Data[A]].decode(json, key).fold(error => throw error, identity)
    }

  def store[A: Data](key: String, value: A): Unit = values.put(key, summon[Data[A]].encode(value).print): Unit

/** The clock of the host, and no inputs. The server has no journal, so it does not record the values of `now`. */
object SystemTurn extends Turn:
  def now(): Long                       = System.currentTimeMillis()
  def input(name: String): Option[Json] = None
  def zoom(ref: String): String         =
    throw IllegalArgumentException(s"zoom($ref): the MCP server has no memory tree, so zoom has nothing to read")
