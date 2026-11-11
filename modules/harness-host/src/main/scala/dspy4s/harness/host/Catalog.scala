package dspy4s.harness.host

import dspy4s.harness.cutEnd
import dspy4s.harness.guest.Scope
import dspy4s.harness.guest.SessionSchemas.given
import dspy4s.harness.kernel.{Entry, Fold, Ledger, LedgerState, Logged, Owner, SessionId, SessionOutcome}
import dspy4s.harness.sandbox.SandboxedShell
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodec

import java.nio.file.{Files as JFiles, Path}
import java.sql.{Connection, DriverManager}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Where a session stands, for `list` (CODE_HARNESS_DESIGN.md, 10.2). */
enum SessionStatus derives Schema, CanEqual:

  /** Open, with no turn. */
  case Idle

  /** A turn is open. */
  case InTurn

  /** A stop is open (10.3). */
  case Stopping

  /** The session runs its release plan (8.7). */
  case Releasing

  case Ended(outcome: SessionOutcome)

/** The row of a session in the catalog (10.2): the deterministic part of a `SessionCard`. Since phase 9 it holds all of
  * the card (8.7): the goal and the scope of the last `Activated`, and the ledger, so `list` reads no journal.
  *
  * A proposal for the review: `parent` is the parent of a journal fork. Phase 7 makes no fork, so the fold gives
  * `None`.
  *
  * A proposal for the review: `folder`, `lastAt` and `firstMessage` let a person find a session in a list.
  *
  *   - `folder` is the folder of the scope of the last `Activated`.
  *   - `lastAt` is the time of the last entry.
  *   - `firstMessage` is the text of the first `Input` on one line, at most `FirstMessageLength` characters.
  *
  * The journal fixes each field, so the row stays a fold. The fields have no default values. So a row of an earlier
  * catalog, which has no such fields, does not decode, and `rebuild` folds its journal again.
  */
final case class CatalogRow(
    session     : SessionId,
    owner       : Owner,
    parent      : Option[SessionId],
    status      : SessionStatus,
    lastSeq     : Long,
    folder      : String,
    lastAt      : Long,
    firstMessage: String,
    goal        : Option[String] = None,
    scope       : Option[Scope]  = None,
    ledger      : Ledger         = Ledger.empty
) derives Schema,
      CanEqual

/** The most characters of `CatalogRow.firstMessage`. A character is one code point. */
val FirstMessageLength = 80

/** `text` on one line, with each run of white space as one space, at most `FirstMessageLength` characters. It skips the
  * white space at the start, and it splits at most `8 * FirstMessageLength` units after it, so a long text costs little
  * more than a short one. A line of a text that goes on after it ends with `…`.
  */
private def oneLine(text: String): String =
  val start = text.indexWhere(c => !Character.isWhitespace(c))
  if start < 0 then ""
  else
    // The window has at most `8 * FirstMessageLength` units, and it ends with no surrogate pair cut in two.
    val end   = cutEnd(text, start, 8 * FirstMessageLength)
    val line  = text.substring(start, end).trim.split("\\s+").mkString(" ")
    val count = line.codePointCount(0, line.length)
    // A text that goes on after the window gives a line that ends with a mark of the cut.
    val more = (end until text.length).exists(i => !Character.isWhitespace(text.charAt(i)))
    if count > FirstMessageLength || (more && count == FirstMessageLength) then
      line.substring(0, line.offsetByCodePoints(0, FirstMessageLength - 1)) + "…"
    else if more then line + "…"
    else line

/** The row of `session` as a fold of its journal (10.2). So a lost row comes back from the journal.
  *
  * The owner, the goal and the scope come from the last `Activated`. The status is `InTurn` from a `TurnStarted`,
  * `Stopping` from a `StopRequested` in a turn, `Idle` again after `TurnEnded` and after a new `Activated`, `Releasing`
  * from a `Releasing`, and `Ended` after `Ended`. The folder comes from the last `Activated`, the time from the last
  * entry, and the first message from the first `Input` with a text. The ledger is the fold `ledger` of the same entries
  * (8.7), so the state of the fold holds the state of the ledger. Before the first entry, the row has the owner `Cell`,
  * the status `Idle`, the last `seq` -1, no goal, no scope and an empty ledger.
  */
def catalogRow(session: SessionId): Fold[Logged, (CatalogRow, LedgerState), CatalogRow] =
  Fold(
    (CatalogRow(session, Owner.Cell, None, SessionStatus.Idle, -1L, "", 0L, ""), LedgerState.empty),
    (acc, logged) =>
      val (row, ledger) = acc
      val status        = logged.entry match
        case Entry.Activated(_, _)                                        => SessionStatus.Idle
        case Entry.TurnStarted                                            => SessionStatus.InTurn
        case Entry.StopRequested(_) if row.status == SessionStatus.InTurn => SessionStatus.Stopping
        case Entry.TurnEnded(_)                                           => SessionStatus.Idle
        case Entry.Ended(outcome, _)                                      => SessionStatus.Ended(outcome)
        case _                                                            => row.status
      val (owner, folder) = logged.entry match
        case Entry.Activated(_, config) => (config.owner, SandboxedShell.pathOf(config.scope))
        case _                          => (row.owner, row.folder)
      val first = logged.entry match
        case Entry.Input(_, text, _) if row.firstMessage.isEmpty => oneLine(text)
        case _                                                   => row.firstMessage
      val updated = row.copy(
        owner = owner,
        status = status,
        lastSeq = logged.seq,
        folder = folder,
        lastAt = logged.meta.atMillis,
        firstMessage = first
      )
      (updated, ledger)
    ,
    _._1
  )

/** The result of a rebuild of the catalog (10.2): the rows, and each file in `sessions` that is not a journal, with the
  * reason. A bad file does not stop the start of the host, and its session does not open.
  */
final case class Rebuilt(rows: Vector[CatalogRow], failed: Vector[(Path, String)]) derives CanEqual

/** The catalog of a host (10.2): `<root>/catalog.db`, one row for each session. The writer of a session updates its row
  * after a turn ends or when the status changes. After a crash a row can lag, or after a power loss it can be ahead of
  * its journal, so `rebuild` runs at the start of the host. A removal of the file loses nothing.
  *
  * The writers of all sessions of a host share the catalog, so its methods hold a lock. Two hosts on one root can write
  * the file at the same time, so the connection has a busy timeout. A failed write of the catalog never ends a session:
  * the row then lags, and the next `rebuild` corrects it.
  */
final class Catalog private (connection: Connection, val path: Path) extends AutoCloseable:
  import Catalog.codec

  /** Adds or replaces the row of its session. */
  def put(row: CatalogRow): Either[String, Unit] =
    synchronized {
      Try {
        Using.resource(connection.prepareStatement("insert or replace into catalog (session, row) values (?, ?)")) {
          put =>
            put.setString(1, row.session.value)
            put.setString(2, codec.encodeToString(row))
            put.executeUpdate(): Unit
        }
      }.toEither.left.map(e => s"the write to the catalog $path failed: ${e.getMessage}")
    }

  /** The row of `session`, if any. */
  def row(session: SessionId): Option[CatalogRow] =
    synchronized {
      Using.resource(connection.prepareStatement("select row from catalog where session = ?")) { select =>
        select.setString(1, session.value)
        Using.resource(select.executeQuery())(rows => Option.when(rows.next())(rows.getString(1)))
          .flatMap(text => codec.decode(text).toOption)
      }
    }

  /** The rows, in the order of the session ids. */
  def rows: Vector[CatalogRow] =
    synchronized {
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("select row from catalog order by session")) { rows =>
          Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toVector
        }
      }.flatMap(text => codec.decode(text).toOption)
    }

  /** The session of each row, also of a row that does not decode. */
  private[host] def sessions: Vector[SessionId] =
    synchronized {
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("select session from catalog order by session")) { rows =>
          Iterator.continually(rows).takeWhile(_.next()).map(r => SessionId(r.getString(1))).toVector
        }
      }
    }

  /** Removes the row of `session`. */
  private[host] def remove(session: SessionId): Either[String, Unit] =
    synchronized {
      Try {
        Using.resource(connection.prepareStatement("delete from catalog where session = ?")) { delete =>
          delete.setString(1, session.value)
          delete.executeUpdate(): Unit
        }
      }.toEither.left.map(e => s"the write to the catalog $path failed: ${e.getMessage}")
    }

  def close(): Unit = synchronized(connection.close())

object Catalog:

  private val codec: JsonCodec[CatalogRow] = summon[Schema[CatalogRow]].jsonCodec

  /** The path of the catalog under the root of a host: `<root>/catalog.db`. */
  def pathOf(root: Path): Path = root.resolve("catalog.db")

  /** Opens the catalog at `path`, and makes it when it does not exist. */
  def open(path: Path): Either[String, Catalog] =
    Try {
      JFiles.createDirectories(path.toAbsolutePath.getParent): Unit
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try
        Using.resource(connection.createStatement()) { statement =>
          statement.execute("pragma busy_timeout = 5000"): Unit
          statement.execute("pragma journal_mode = WAL"): Unit
          statement.execute("create table if not exists catalog (session text primary key, row text not null)"): Unit
        }
        Catalog(connection, path)
      catch
        case e: Exception =>
          connection.close()
          throw e
    }.toEither.left.map(e => s"the catalog $path did not open: ${e.getMessage}")

  /** The rows after a start of the host (10.2). It lists the files in `sessions`, folds each journal whose last `seq`
    * differs from its row, and puts the new row. A row with no file goes away. A file that is not a journal goes into
    * `failed`. Only an error of the catalog itself gives `Left`.
    */
  def rebuild(catalog: Catalog, sessions: Path): Either[String, Rebuilt] =
    for
      files  <- journalFiles(sessions)
      results = files.map { file =>
                  val session = SessionId(file.getFileName.toString.stripSuffix(".db"))
                  file -> SessionFile.open(file, session).flatMap { journal =>
                    try catalog.row(session) match
                        case Some(row) if row.lastSeq == journal.lastSeq => Right(row)
                        // The catalog is a copy of the journals, so a failed put loses nothing: the row is good, and
                        // the next rebuild puts it again.
                        case _ => journal.readFrom(0).map(catalogRow(session)(_)).map(row =>
                            catalog.put(row).fold(_ => row, _ => row)
                          )
                    finally journal.close()
                  }
                }
      present = files.map(f => SessionId(f.getFileName.toString.stripSuffix(".db"))).toSet
      _      <- catalog.sessions.filterNot(present).foldLeft(Right(()): Either[String, Unit]) { (done, session) =>
             done.flatMap(_ => catalog.remove(session))
           }
    yield Rebuilt(
      results.collect { case (_, Right(row)) => row }.sortBy(_.session.value),
      results.collect { case (file, Left(reason)) => file -> reason }
    )

  /** The journal files of the folder `sessions`: each regular file whose name ends with `.db`, in the order of the
    * names. No folder gives no file. A folder that the host cannot read gives an error.
    */
  def journalFiles(sessions: Path): Either[String, Vector[Path]] =
    if !JFiles.isDirectory(sessions) then Right(Vector.empty)
    else
      Try(Using.resource(JFiles.list(sessions))(_.iterator().asScala.toVector)).toEither.left
        .map(e => s"the folder of the journals $sessions did not open: $e")
        .map(_.filter(p => p.getFileName.toString.endsWith(".db") && JFiles.isRegularFile(p)).sortBy(
          _.getFileName.toString
        ))
