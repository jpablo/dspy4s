package dspy4s.harness.host

import dspy4s.harness.kernel.{Fold, Logged, Owner, SessionId, SessionOutcome}
import zio.blocks.schema.Schema

import java.nio.file.Path
import java.sql.Connection
import scala.annotation.nowarn

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

/** The row of `session` as a fold of its journal (10.2). So a lost row comes back from the journal. */
def catalogRow(session: SessionId): Fold[Logged, CatalogRow, CatalogRow] = ???

/** The catalog of a host (10.2): `<root>/catalog.db`, one row for each session. The writer of a session updates its row
  * after a turn ends or when the status changes. After a crash a row can lag, so `rebuild` runs at the start of the
  * host. A removal of the file loses nothing.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class Catalog private (connection: Connection, val path: Path) extends AutoCloseable:

  /** Adds or replaces the row of its session. */
  def put(row: CatalogRow): Either[String, Unit] = ???

  /** The row of `session`, if any. */
  def row(session: SessionId): Option[CatalogRow] = ???

  /** The rows, in the order of the session ids. */
  def rows: Vector[CatalogRow] = ???

  def close(): Unit = ???

object Catalog:

  /** Opens the catalog at `path`, and makes it when it does not exist. */
  def open(path: Path): Either[String, Catalog] = ???

  /** The rows after a start of the host (10.2). It lists the files in `sessions`, folds each journal whose last `seq`
    * differs from its row, and puts the new row. A row with no file goes away.
    */
  def rebuild(catalog: Catalog, sessions: Path): Either[String, Vector[CatalogRow]] = ???
