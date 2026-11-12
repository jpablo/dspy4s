package dspy4s.harness.inspect

import dspy4s.harness.host.{Catalog, CatalogRow, ExchangeFile, SessionFile, catalogRow}
import dspy4s.harness.kernel.SessionId

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** The files of the root `root` of a host, for the inspector (docs/code-harness/OBSERVABILITY.md, 5.1). It only reads:
  * a journal and the catalog open with `mode=ro`, and an exchange file is a plain read. Each call opens its file and
  * closes it again, so the inspector holds no connection while it waits.
  */
final class Store(val root: Path):

  /** The sessions, the newest first: the rows of the catalog, and the fold of each journal that the catalog does not
    * have. With no catalog, each row comes from the fold of its journal (10.2).
    */
  def sessions: Vector[CatalogRow] =
    val listed = Catalog.readOnly(Catalog.pathOf(root)).toOption.fold(Vector.empty[CatalogRow]) { catalog =>
      try Try(catalog.rows).getOrElse(Vector.empty)
      finally catalog.close()
    }
    val known  = listed.map(_.session).toSet
    val folded = journals.filterNot(known).flatMap(id =>
      rows(id, 0L).toOption.map(r => catalogRow(id)(r.flatMap(_.logged.toOption)))
    )
    (listed ++ folded).sortBy(row => -row.lastAt)

  /** The row of `session`: its row in the catalog, else the fold of its journal. */
  def row(session: SessionId): Option[CatalogRow] =
    val listed = Catalog.readOnly(Catalog.pathOf(root)).toOption.flatMap { catalog =>
      try Try(catalog.row(session)).toOption.flatten
      finally catalog.close()
    }
    listed.orElse(rows(session, 0L).toOption.map(r => catalogRow(session)(r.flatMap(_.logged.toOption))))

  /** The ids of the journals of the root, from their file names. */
  def journals: Vector[SessionId] =
    Catalog.journalFiles(SessionFile.folderOf(root)).getOrElse(Vector.empty)
      .map(_.getFileName.toString.stripSuffix(".db")).filter(SessionId.valid).map(SessionId(_))

  /** True when `session` has a journal under the root. */
  def has(session: SessionId): Boolean = Files.isRegularFile(SessionFile.pathOf(root, session))

  /** The rows of the journal of `session` from `from` on. */
  def rows(session: SessionId, from: Long): Either[String, Vector[SessionFile.Row]] =
    read(SessionFile.pathOf(root, session), session, from)

  /** The exchanges of `session` and the size of its exchange file. */
  def exchanges(session: SessionId): (Vector[Exchange], Long) = Exchanges.read(ExchangeFile.pathOf(root, session))

  /** The size of the exchange file of `session`, or 0 with no file. */
  def exchangeSize(session: SessionId): Long =
    Try(Files.size(ExchangeFile.pathOf(root, session))).getOrElse(0L)

  /** The names of the probes of `session`: the journals `<root>/probes/<session>.<cell>.<n>.db`. */
  def probes(session: SessionId): Vector[String] =
    probeNames.filter(_.startsWith(s"${session.value}."))

  /** The session and the rows of the journal of the probe `name`, or an error for a name with no journal. */
  def probe(name: String): Either[String, (SessionId, Vector[SessionFile.Row])] =
    if !probeNames.contains(name) then Left(s"no probe $name")
    else
      val path = probesFolder.resolve(s"$name.db")
      SessionFile.sessionOf(path).flatMap(session => read(path, session, 0L).map(session -> _))

  /** True when `name` names a probe journal under the root. */
  def hasProbe(name: String): Boolean = probeNames.contains(name)

  private def probesFolder: Path = root.resolve("probes")

  private def probeNames: Vector[String] =
    if !Files.isDirectory(probesFolder) then Vector.empty
    else
      Try(Using.resource(Files.list(probesFolder))(_.iterator().asScala.toVector)).getOrElse(Vector.empty)
        .map(_.getFileName.toString).filter(_.endsWith(".db")).map(_.stripSuffix(".db"))
        .filter(name => name.nonEmpty && name.forall(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '_'))
        .filterNot(_.contains("..")).sorted

  private def read(path: Path, session: SessionId, from: Long): Either[String, Vector[SessionFile.Row]] =
    SessionFile.readOnly(path, session).flatMap { file =>
      try file.rows(from)
      finally file.close()
    }
