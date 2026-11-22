package dspy4s.harness.host

import dspy4s.harness.{LineKey, LineStore, MemoryLine}
import dspy4s.harness.kernel.SessionId

import java.nio.file.Path
import org.sqlite.SQLiteConfig

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** The built lines of the memory tree of one session (CODE_HARNESS_DESIGN.md, 7.1): the table `memory` of the session
  * file, with the columns `session`, `level`, `idx`, `digest`, `line` and `build`, and a primary key on the first four.
  * So a reopen reads the lines, and it makes no new LM call. A line of other messages has another digest, so it is
  * never used.
  *
  * The column `build` holds the build number of the write that added the line (7.2, choice 3). A file from before the
  * column gets it with the value 0 for each line when it opens, so its lines have the build number 0. A file that opens
  * to read only keeps its table, and its lines have the build number 0 when the table has no column `build`.
  *
  * The strategy writes the lines from the worker thread, and the writer of the journal writes from its own thread. So
  * the store has a connection of its own, and the WAL of the file lets the two write one after the other. Each
  * connection of the file waits up to `FileLines.BusyMillis` for the other (`busy_timeout`), so a write of the journal
  * does not fail while the store writes. `putAll` writes the lines of one update in one transaction, with the next
  * build number. A line never changes (the law Immutability), so `putAll` keeps the line of a key that has one.
  *
  * The lines are derived data: a failed read gives no line, and a failed write keeps no line, so the strategy makes the
  * line again. Neither one ends a turn.
  */
final class FileLines private (connection: Connection, val session: SessionId, built: Boolean)
    extends LineStore, AutoCloseable:

  def get(key: LineKey, upTo: Long): Option[MemoryLine] =
    synchronized {
      Try {
        Using.resource(connection.prepareStatement(if built then FileLines.Select else FileLines.SelectOld)) { select =>
          select.setString(1, session.value)
          select.setInt(2, key.id.level)
          select.setLong(3, key.id.index)
          select.setString(4, key.digest)
          select.setLong(5, if built then upTo else 0L)
          Using.resource(select.executeQuery())(rows => Option.when(rows.next())(MemoryLine.of(rows.getString(1))))
        }
      }.toOption.flatten.filter(_ => built || upTo >= 0)
    }

  def newest: Long =
    synchronized {
      if !built then 0L
      else
        Try {
          Using.resource(connection.prepareStatement(FileLines.Newest)) { select =>
            select.setString(1, session.value)
            Using.resource(select.executeQuery())(rows => if rows.next() then rows.getLong(1) else 0L)
          }
        }.getOrElse(0L)
    }

  def putAll(lines: Vector[(LineKey, MemoryLine)]): Long =
    synchronized {
      if lines.nonEmpty && built then
        Try {
          val fresh = newest + 1
          connection.setAutoCommit(false)
          try
            Using.resource(connection.prepareStatement(FileLines.Insert)) { insert =>
              lines.foreach { (key, line) =>
                insert.setString(1, session.value)
                insert.setInt(2, key.id.level)
                insert.setLong(3, key.id.index)
                insert.setString(4, key.digest)
                insert.setString(5, line.text)
                insert.setLong(6, fresh)
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
      newest
    }

  def close(): Unit = synchronized(Try(connection.close()): Unit)

object FileLines:

  /** How long a connection of the session file waits for the other one. */
  val BusyMillis = 5_000

  private val Table =
    "create table if not exists memory (session text not null, level integer not null, idx integer not null, " +
      "digest text not null, line text not null, build integer not null default 0, " +
      "primary key (session, level, idx, digest))"

  // A table from before the build numbers gets the column, and each of its lines the build number 0 (7.2, choice 3).
  private val AddBuild = "alter table memory add column build integer not null default 0"

  private val Select =
    "select line from memory where session = ? and level = ? and idx = ? and digest = ? and build <= ?"

  // A table with no column `build`: each line has the build number 0, and the fifth value has no use.
  private val SelectOld = "select line from memory where session = ? and level = ? and idx = ? and digest = ? and ? = 0"

  private val Newest = "select coalesce(max(build), 0) from memory where session = ?"

  // A key that has a line keeps it (the law Immutability).
  private val Insert =
    "insert or ignore into memory (session, level, idx, digest, line, build) values (?, ?, ?, ?, ?, ?)"

  /** True when the table `memory` of `connection` has the column `build`. */
  private def hasBuild(connection: Connection): Boolean =
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery("pragma table_info(memory)")) { rows =>
        Iterator.continually(rows.next()).takeWhile(identity).exists(_ => rows.getString("name") == "build")
      }
    }

  /** The store of the session file at `path`, to read it (8.7): it makes no table and writes nothing, so `putAll` keeps
    * no line. A file with no table `memory` gives no line, and a table with no column `build` gives the build number 0
    * to each line.
    */
  def readOnly(path: Path, session: SessionId): Either[String, FileLines] =
    Try {
      val settings = SQLiteConfig()
      settings.setReadOnly(true)
      settings.setBusyTimeout(BusyMillis)
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path", settings.toProperties)
      try FileLines(connection, session, Try(hasBuild(connection)).getOrElse(false))
      catch
        case e: Exception =>
          connection.close()
          throw e
    }.toEither.left.map(e => s"the memory table of $path did not open: ${e.getMessage}")

  /** The store of the session file at `path`. It makes the table `memory` when the file has none, and it adds the
    * column `build` to a table from before the column.
    */
  def open(path: Path, session: SessionId): Either[String, FileLines] =
    Try {
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try
        Using.resource(connection.createStatement()) { statement =>
          statement.execute(s"pragma busy_timeout = $BusyMillis"): Unit
          statement.execute(Table): Unit
          if !hasBuild(connection) then statement.execute(AddBuild): Unit
        }
        FileLines(connection, session, built = true)
      catch
        case e: Exception =>
          connection.close()
          throw e
    }.toEither.left.map(e => s"the memory table of $path did not open: ${e.getMessage}")
