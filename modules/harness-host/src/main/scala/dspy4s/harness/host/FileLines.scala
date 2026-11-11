package dspy4s.harness.host

import dspy4s.harness.{LineKey, LineStore, MemoryLine}
import dspy4s.harness.kernel.SessionId

import java.nio.file.Path
import java.sql.Connection
import scala.annotation.nowarn

/** The built lines of the memory tree of one session (CODE_HARNESS_DESIGN.md, 7.1): the table `memory` of the session
  * file, with the columns `session`, `level`, `index`, `digest` and `line`, and a primary key on the first four. So a
  * reopen reads the lines, and it makes no new LM call. A line of other messages has another digest, so it is never
  * used.
  *
  * The strategy writes the lines from the worker thread, and the writer of the journal writes from its own thread. So
  * the store has a connection of its own, and the WAL of the file lets the two write one after the other. Each
  * connection of the file waits up to `FileLines.BusyMillis` for the other (`busy_timeout`), so a write of the journal
  * does not fail while the store writes. `putAll` writes the lines of one update in one transaction. A line never
  * changes (the law Immutability), so `putAll` keeps the line of a key that has one.
  */
@nowarn("msg=unused") // the skeleton: the proof of phase 8 uses the connection
final class FileLines private (connection: Connection, val session: SessionId) extends LineStore, AutoCloseable:

  def get(key: LineKey): Option[MemoryLine] = ???

  def putAll(lines: Vector[(LineKey, MemoryLine)]): Unit = ???

  def close(): Unit = ???

object FileLines:

  /** How long a connection of the session file waits for the other one. */
  val BusyMillis = 5_000

  /** The store of the session file at `path`. It makes the table `memory` when the file has none. */
  def open(path: Path, session: SessionId): Either[String, FileLines] = ???
