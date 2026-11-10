package dspy4s.harness.host

import dspy4s.harness.kernel.{Entry, Fold, Logged, Owner, SessionId, SessionOutcome}
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

  case Ended(outcome: SessionOutcome)

/** The row of a session in the catalog (10.2): the deterministic part of a `SessionCard`.
  *
  * A proposal for the review: `parent` is the parent of a journal fork. Phase 7 makes no fork, so the fold gives
  * `None`.
  */
final case class CatalogRow(
    session: SessionId,
    owner  : Owner,
    parent : Option[SessionId],
    status : SessionStatus,
    lastSeq: Long
) derives Schema,
      CanEqual

/** The row of `session` as a fold of its journal (10.2). So a lost row comes back from the journal.
  *
  * The owner comes from the last `Activated`. The status is `InTurn` from a `TurnStarted`, `Stopping` from a
  * `StopRequested` in a turn, `Idle` again after `TurnEnded` and after a new `Activated`, and `Ended` after `Ended`.
  * Before the first entry, the row has the owner `Cell`, the status `Idle` and the last `seq` -1.
  */
def catalogRow(session: SessionId): Fold[Logged, CatalogRow, CatalogRow] =
  Fold(
    CatalogRow(session, Owner.Cell, None, SessionStatus.Idle, -1L),
    (row, logged) =>
      val status = logged.entry match
        case Entry.Activated(_, _)                                        => SessionStatus.Idle
        case Entry.TurnStarted                                            => SessionStatus.InTurn
        case Entry.StopRequested(_) if row.status == SessionStatus.InTurn => SessionStatus.Stopping
        case Entry.TurnEnded(_)                                           => SessionStatus.Idle
        case Entry.Ended(outcome, _)                                      => SessionStatus.Ended(outcome)
        case _                                                            => row.status
      val owner = logged.entry match
        case Entry.Activated(_, config) => config.owner
        case _                          => row.owner
      row.copy(owner = owner, status = status, lastSeq = logged.seq)
    ,
    identity
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
    val files =
      if !JFiles.isDirectory(sessions) then Vector.empty
      else
        Using.resource(JFiles.list(sessions))(_.iterator().asScala.toVector)
          .filter(p => p.getFileName.toString.endsWith(".db")).sortBy(_.getFileName.toString)
    val results = files.map { file =>
      val session = SessionId(file.getFileName.toString.stripSuffix(".db"))
      file -> SessionFile.open(file, session).flatMap { journal =>
        try catalog.row(session) match
            case Some(row) if row.lastSeq == journal.lastSeq => Right(row)
            case _                                           => journal.readFrom(0).map(catalogRow(session)(_)).flatMap(row => catalog.put(row).map(_ => row))
        finally journal.close()
      }
    }
    val kept    = results.collect { case (_, Right(row)) => row }
    val failed  = results.collect { case (file, Left(reason)) => file -> reason }
    val present = files.map(f => f.getFileName.toString.stripSuffix(".db")).toSet
    for
      _ <- catalog.rows.filterNot(row => present(row.session.value)).foldLeft(Right(()): Either[String, Unit]) {
             (done, row) => done.flatMap(_ => catalog.remove(row.session))
           }
    yield Rebuilt(kept.sortBy(_.session.value), failed)
