package dspy4s.harness.host

import dspy4s.harness.kernel.{Logged, SessionId}

import java.nio.file.Path
import java.sql.Connection
import scala.annotation.nowarn

/** The journal file of one session (CODE_HARNESS_DESIGN.md, 10.2): `<root>/sessions/<id>.db`, a SQLite file with one
  * row for each entry.
  *
  *   - The table `journal` has the columns `session`, `seq` and `entry`, with a primary key on `session` and `seq`. The
  *     key rejects a stale writer of the file. Each row holds its session, so a copy of the rows moves a session to
  *     another file or to a server database (10.4).
  *   - `entry` holds the JSON of the `Logged` value, from its `Schema`.
  *   - The file uses WAL, `synchronous=NORMAL` and `fullfsync=ON`. A commit that needs a flush (`needsFlush`, 4.1) runs
  *     its transaction with `synchronous=FULL`, and then sets `NORMAL` again. `synchronous` belongs to a connection, so
  *     the file holds one connection for its life, and no pool replaces it.
  *
  * Only the writer of the session uses the file, so the methods hold no lock.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class SessionFile private (connection: Connection, val path: Path, val session: SessionId)
    extends AutoCloseable:

  /** Adds the rows of one commit in one transaction, or none of them. `flush` runs the transaction with
    * `synchronous=FULL`.
    */
  def append(rows: Vector[Logged], flush: Boolean): Either[String, Unit] = ???

  /** The rows with a `seq` from `from` on, in the order of `seq`. */
  def readFrom(from: Long): Either[String, Vector[Logged]] = ???

  /** The value of the setting `name` of the connection, for example `journal_mode` or `synchronous`. */
  def setting(name: String): String = ???

  def close(): Unit = ???

object SessionFile:

  /** Opens the file at `path`. When the file does not exist, it makes the file with its table and its settings. */
  def open(path: Path, session: SessionId): Either[String, SessionFile] = ???

  /** The path of the file of `session` under the root of a host: `<root>/sessions/<id>.db`. */
  def pathOf(root: Path, session: SessionId): Path = ???
