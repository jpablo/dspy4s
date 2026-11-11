package dspy4s.harness.host

import dspy4s.harness.{LineKey, LineStore, MemoryLine}
import dspy4s.harness.kernel.SessionId

import java.nio.file.Path
import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** The built lines of the memory tree of one session (CODE_HARNESS_DESIGN.md, 7.1): the table `memory` of the session
  * file, with the columns `session`, `level`, `idx`, `digest` and `line`, and a primary key on the first four. So a
  * reopen reads the lines, and it makes no new LM call. A line of other messages has another digest, so it is never
  * used.
  *
  * The strategy writes the lines from the worker thread, and the writer of the journal writes from its own thread. So
  * the store has a connection of its own, and the WAL of the file lets the two write one after the other. Each
  * connection of the file waits up to `FileLines.BusyMillis` for the other (`busy_timeout`), so a write of the journal
  * does not fail while the store writes. `putAll` writes the lines of one update in one transaction. A line never
  * changes (the law Immutability), so `putAll` keeps the line of a key that has one.
  *
  * The lines are derived data: a failed read gives no line, and a failed write keeps no line, so the strategy makes the
  * line again. Neither one ends a turn.
  */
final class FileLines private (connection: Connection, val session: SessionId) extends LineStore, AutoCloseable:

  def get(key: LineKey): Option[MemoryLine] =
    synchronized {
      Try {
        Using.resource(connection.prepareStatement(FileLines.Select)) { select =>
          select.setString(1, session.value)
          select.setInt(2, key.id.level)
          select.setLong(3, key.id.index)
          select.setString(4, key.digest)
          Using.resource(select.executeQuery())(rows => Option.when(rows.next())(MemoryLine.of(rows.getString(1))))
        }
      }.toOption.flatten
    }

  def putAll(lines: Vector[(LineKey, MemoryLine)]): Unit =
    synchronized {
      if lines.nonEmpty then
        Try {
          connection.setAutoCommit(false)
          try
            Using.resource(connection.prepareStatement(FileLines.Insert)) { insert =>
              lines.foreach { (key, line) =>
                insert.setString(1, session.value)
                insert.setInt(2, key.id.level)
                insert.setLong(3, key.id.index)
                insert.setString(4, key.digest)
                insert.setString(5, line.text)
                insert.addBatch()
              }
              insert.executeBatch(): Unit
            }
            connection.commit()
          catch
            case e: Exception =>
              Try(connection.rollback()): Unit
              throw e
          finally connection.setAutoCommit(true)
        }: Unit
    }

  def close(): Unit = synchronized(Try(connection.close()): Unit)

object FileLines:

  /** How long a connection of the session file waits for the other one. */
  val BusyMillis = 5_000

  private val Table =
    "create table if not exists memory (session text not null, level integer not null, idx integer not null, " +
      "digest text not null, line text not null, primary key (session, level, idx, digest))"

  private val Select = "select line from memory where session = ? and level = ? and idx = ? and digest = ?"

  // A key that has a line keeps it (the law Immutability).
  private val Insert = "insert or ignore into memory (session, level, idx, digest, line) values (?, ?, ?, ?, ?)"

  /** The store of the session file at `path`. It makes the table `memory` when the file has none. */
  def open(path: Path, session: SessionId): Either[String, FileLines] =
    Try {
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try
        Using.resource(connection.createStatement()) { statement =>
          statement.execute(s"pragma busy_timeout = $BusyMillis"): Unit
          statement.execute(Table): Unit
        }
        FileLines(connection, session)
      catch
        case e: Exception =>
          connection.close()
          throw e
    }.toEither.left.map(e => s"the memory table of $path did not open: ${e.getMessage}")
