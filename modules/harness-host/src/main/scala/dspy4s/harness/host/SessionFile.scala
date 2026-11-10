package dspy4s.harness.host

import dspy4s.harness.kernel.{Logged, SessionId}
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodec

import java.nio.file.{Files as JFiles, Path}
import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** The journal file of one session (CODE_HARNESS_DESIGN.md, 10.2): `<root>/sessions/<id>.db`, a SQLite file with one
  * row for each entry.
  *
  *   - The table `journal` has the columns `session`, `seq`, `required` and `entry`, with a primary key on `session`
  *     and `seq`. The key rejects a stale writer of the file. Each row holds its session, so a copy of the rows moves a
  *     session to another file or to a server database (10.4).
  *   - `entry` holds the JSON of the `Logged` value, from its `Schema`. `required` is `Meta.required` in its own
  *     column, so a reader knows what to do with a row that it cannot decode (4.1): it skips the row when `required` is
  *     false, and it stops when `required` is true.
  *   - The file uses WAL, `synchronous=NORMAL` and `fullfsync=ON`. A commit that needs a flush (`needsFlush`, 4.1) runs
  *     its transaction with `synchronous=FULL`, and then sets `NORMAL` again. `synchronous` belongs to a connection, so
  *     the file holds one connection for its life, and no pool replaces it.
  *
  * Only the writer of the session uses the file, so the methods hold no lock. The lock against a second host is the
  * lock of `LocalHost`.
  */
final class SessionFile private (connection: Connection, val path: Path, val session: SessionId)
    extends AutoCloseable:

  /** Adds the rows of one commit in one transaction, or none of them. `flush` runs the transaction with
    * `synchronous=FULL`.
    */
  def append(rows: Vector[Logged], flush: Boolean): Either[String, Unit] =
    if rows.isEmpty then Right(())
    else
      Try {
        // `synchronous` cannot change inside a transaction, so it changes before the transaction and after it.
        if flush then pragma("synchronous = FULL")
        try
          lastSync = setting("synchronous")
          connection.setAutoCommit(false)
          try
            Using.resource(connection.prepareStatement(SessionFile.Insert)) { insert =>
              rows.foreach { row =>
                insert.setString(1, session.value)
                insert.setLong(2, row.seq)
                insert.setBoolean(3, row.meta.required)
                insert.setString(4, SessionFile.codec.encodeToString(row))
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
        finally if flush then pragma("synchronous = NORMAL")
      }.toEither.left.map(e => s"the write to the journal $path failed: ${e.getMessage}")

  /** The rows with a `seq` from `from` on, in the order of `seq`. A row that does not decode is skipped when it is not
    * required, and gives an error when it is required.
    */
  def readFrom(from: Long): Either[String, Vector[Logged]] =
    Try {
      Using.resource(connection.prepareStatement(SessionFile.Select)) { select =>
        select.setString(1, session.value)
        select.setLong(2, from)
        Using.resource(select.executeQuery()) { rows =>
          val out   = Vector.newBuilder[Logged]
          var error = Option.empty[String]
          while error.isEmpty && rows.next() do
            SessionFile.codec.decode(rows.getString(3)) match
              case Right(logged)                       => out += logged: Unit
              case Left(problem) if rows.getBoolean(2) =>
                error = Some(s"the required row ${rows.getLong(1)} of the journal $path does not decode: $problem")
              case Left(_) => ()
          error.toLeft(out.result())
        }
      }
    }.toEither.left.map(e => s"the read of the journal $path failed: ${e.getMessage}").flatten

  /** The largest `seq` of the session in the file, also of a row that does not decode, or -1 for no row. The writer
    * gives the next entry the `seq` after it.
    */
  def lastSeq: Long =
    Using.resource(connection.prepareStatement(SessionFile.Last)) { last =>
      last.setString(1, session.value)
      Using.resource(last.executeQuery()) { rows =>
        val seq = if rows.next() then rows.getLong(1) else -1L
        if rows.wasNull() then -1L else seq
      }
    }

  /** The value of `synchronous` in the transaction of the last commit: "2" for FULL and "1" for NORMAL. */
  private[host] def lastSynchronous: String = lastSync

  @volatile private var lastSync = ""

  /** The value of the setting `name` of the connection, for example `journal_mode` or `synchronous`. */
  def setting(name: String): String =
    require(name.matches("[a-z_]+"), s"not a setting: $name")
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery(s"pragma $name")) { rows =>
        if rows.next() then rows.getString(1) else ""
      }
    }

  def close(): Unit = connection.close()

  private def pragma(text: String): Unit =
    Using.resource(connection.createStatement())(_.execute(s"pragma $text")): Unit

object SessionFile:

  private val codec: JsonCodec[Logged] = summon[Schema[Logged]].jsonCodec

  private val Insert = "insert into journal (session, seq, required, entry) values (?, ?, ?, ?)"
  private val Select = "select seq, required, entry from journal where session = ? and seq >= ? order by seq"
  private val Last   = "select max(seq) from journal where session = ?"

  private val Table =
    "create table if not exists journal (session text not null, seq integer not null, required integer not null, " +
      "entry text not null, primary key (session, seq))"

  /** Opens the file at `path`. When the file does not exist, it makes the file with its table and its settings. A file
    * that is not a journal gives an error.
    */
  def open(path: Path, session: SessionId): Either[String, SessionFile] =
    Try {
      JFiles.createDirectories(path.toAbsolutePath.getParent): Unit
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try
        Using.resource(connection.createStatement()) { statement =>
          // A file that is not SQLite fails at its first statement.
          statement.execute("pragma journal_mode = WAL"): Unit
          statement.execute("pragma synchronous = NORMAL"): Unit
          statement.execute("pragma fullfsync = ON"): Unit
          statement.execute(Table): Unit
        }
        SessionFile(connection, path, session)
      catch
        case e: Exception =>
          connection.close()
          throw e
    }.toEither.left.map(e => s"the journal $path did not open: ${e.getMessage}")

  /** The path of the file of `session` under the root of a host: `<root>/sessions/<id>.db`. */
  def pathOf(root: Path, session: SessionId): Path = root.resolve("sessions").resolve(s"${session.value}.db")
