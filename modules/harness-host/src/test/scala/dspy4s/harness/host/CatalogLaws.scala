package dspy4s.harness.host

import dspy4s.harness.Support
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.SandboxedShell
import zio.blocks.schema.Schema

import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.util.Using

/** The laws of the catalog of a host (CODE_HARNESS_DESIGN.md, 10.2). */
class CatalogLaws extends munit.FunSuite:

  private val activated = Entry.Activated(Support.versions, config())

  private def write(root: Path, session: SessionId, log: Vector[Logged]): Unit =
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => fail(e), identity)
    try assertEquals(file.append(log, flush = true), Right(()))
    finally file.close()

  private def catalog(root: Path): Catalog = Catalog.open(Catalog.pathOf(root)).fold(e => fail(e), identity)

  /** Puts the text `row` as the row of `session`, with no codec, as an earlier version or a broken write can leave it.
    */
  private def putText(root: Path, session: SessionId, row: String): Unit =
    catalog(root).close()
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${Catalog.pathOf(root)}")) { connection =>
      Using.resource(connection.prepareStatement("insert or replace into catalog (session, row) values (?, ?)")) {
        put =>
          put.setString(1, session.value)
          put.setString(2, row)
          put.executeUpdate(): Unit
      }
    }

  /** The number of rows in the catalog table, also the rows that do not decode. */
  private def tableRows(root: Path): Int =
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${Catalog.pathOf(root)}")) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("select count(*) from catalog"))(rows => rows.getInt(1))
      }
    }

  private def firstMessageOf(text: String): String =
    catalogRow(id(1))(Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), text, Map.empty))).firstMessage

  /** A row with a folder, no time and no first message. */
  private def stub(n: Int, status: SessionStatus, lastSeq: Long): CatalogRow =
    CatalogRow(id(n), Owner.User, None, status, lastSeq, "/fake/work", 0L, "")

  test("Catalog: the row of a session is a fold of its journal, with the status and the last seq") {
    val log = Support.logOf(
      1000L,
      activated,
      Entry.Input(RequestId("r1"), "x", Map.empty),
      Entry.TurnStarted,
      Entry.StopRequested("FAKE"),
      Entry.TurnEnded(TurnOutcome.Aborted),
      Entry.Ended(SessionOutcome.Parked, "FAKE")
    )
    def row(n: Int) = catalogRow(id(1))(log.take(n))
    val folder      = SandboxedShell.pathOf(config().scope)
    val scope       = Some(config().scope)
    assertEquals(
      row(1),
      CatalogRow(id(1), Owner.User, None, SessionStatus.Idle, 0L, folder, 1000L, "", config().goal, scope)
    )
    assertEquals(row(3).status, SessionStatus.InTurn)
    assertEquals(row(4).status, SessionStatus.Stopping)
    assertEquals(row(5).status, SessionStatus.Idle)
    assertEquals(
      row(6),
      CatalogRow(
        id(1),
        Owner.User,
        None,
        SessionStatus.Ended(SessionOutcome.Parked),
        5L,
        folder,
        1000L,
        "x",
        config().goal,
        scope
      )
    )
  }

  test("Catalog: a Releasing gives the status Releasing until Ended (8.7)") {
    val log = Support.logOf(
      1000L,
      activated,
      Entry.Releasing(Vector.empty),
      Entry.Ended(SessionOutcome.Merged, "FAKE")
    )
    assertEquals(catalogRow(id(1))(log.take(2)).status, SessionStatus.Releasing)
    assertEquals(catalogRow(id(1))(log).status, SessionStatus.Ended(SessionOutcome.Merged))
  }

  test("Catalog: the row holds the goal and the scope of the last Activated, and the ledger of the journal (8.7)") {
    val goal  = config().copy(goal = Some("fake goal"), scope = dspy4s.harness.guest.Scope.Directory("/w/two"))
    val read  = Json.Object(("path", Json.String("/w/a.txt")))
    val value = EffectResult.Value(Json.Null, "")
    val log   = Support.logOf(
      1000L,
      activated,
      Entry.ScopeRecorded("a1b2", true),
      Entry.Activated(Support.versions, goal),
      Entry.EffectStarted(EffectId(1L, 0), "read", read),
      Entry.EffectDone(EffectId(1L, 0), value)
    )
    val row = catalogRow(id(1))(log)
    assertEquals((row.goal, row.scope), (Some("fake goal"), Some(dspy4s.harness.guest.Scope.Directory("/w/two"))))
    assertEquals(row.ledger, ledger(log))
    assertEquals((row.ledger.read, row.ledger.head), (Vector("/w/a.txt"), Some("a1b2")))
  }

  test("Catalog: the row keeps the folder, the time of the last entry, and the first message on one short line") {
    val long = "fix the build\nand then " + "y" * 200
    val log  = Vector(
      Logged(0L, Meta(1000L, Source.Harness, Taint.Trusted, true), activated),
      Logged(1L, Meta(2000L, Source.User, Taint.Trusted, true), Entry.Input(RequestId("r1"), long, Map.empty)),
      Logged(2L, Meta(3000L, Source.User, Taint.Trusted, true), Entry.Input(RequestId("r2"), "later", Map.empty))
    )
    val row = catalogRow(id(1))(log)
    assertEquals(row.folder, SandboxedShell.pathOf(config().scope))
    assertEquals(row.lastAt, 3000L)
    assert(row.firstMessage.startsWith("fix the build and then yyy"), row.firstMessage)
    assert(!row.firstMessage.contains("\n") && row.firstMessage.length <= 80, row.firstMessage)
  }

  test("Catalog: put and row give the row back, and a new open of the file reads the same rows") {
    withRoot { root =>
      val row   = stub(1, SessionStatus.Idle, 3L)
      val first = catalog(root)
      assertEquals(first.put(row), Right(()))
      assertEquals(first.row(id(1)), Some(row))
      first.close()
      val again = catalog(root)
      try assertEquals(again.rows, Vector(row))
      finally again.close()
    }
  }

  test(
    "Catalog: rebuild folds each journal whose row differs: a row that lags after a crash, or is ahead after a power loss"
  ) {
    withRoot { root =>
      val one = Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), "x", Map.empty), Entry.TurnStarted)
      val two = Support.logOf(1000L, activated)
      write(root, id(1), one)
      write(root, id(2), two)
      val c = catalog(root)
      try
        assertEquals(c.put(stub(1, SessionStatus.Idle, 0L)), Right(()))
        // A row ahead of its journal, as after a power loss.
        assertEquals(c.put(stub(2, SessionStatus.InTurn, 9L)), Right(()))
        val rows = Catalog.rebuild(c, SessionFile.folderOf(root)).fold(e => fail(e), identity).rows
        assertEquals(rows, Vector(catalogRow(id(1))(one), catalogRow(id(2))(two)))
        assertEquals(c.rows, rows)
      finally c.close()
    }
  }

  test("Catalog: after a removal of the catalog file, rebuild gives the same rows") {
    withRoot { root =>
      write(root, id(1), Support.logOf(1000L, activated))
      val first  = catalog(root)
      val before = Catalog.rebuild(first, SessionFile.folderOf(root)).fold(e => fail(e), identity)
      first.close()
      Files.delete(Catalog.pathOf(root))
      val again = catalog(root)
      try assertEquals(Catalog.rebuild(again, SessionFile.folderOf(root)), Right(before))
      finally again.close()
    }
  }

  test("Catalog: a row with no file goes away at rebuild") {
    withRoot { root =>
      Files.createDirectories(SessionFile.folderOf(root)): Unit
      val c = catalog(root)
      try
        assertEquals(c.put(stub(9, SessionStatus.Idle, 0L)), Right(()))
        assertEquals(Catalog.rebuild(c, SessionFile.folderOf(root)), Right(Rebuilt(Vector.empty, Vector.empty)))
        assertEquals(c.row(id(9)), None)
      finally c.close()
    }
  }

  test("Catalog: a file that is not a journal goes into failed, and the rows of the other journals come back") {
    withRoot { root =>
      write(root, id(1), Support.logOf(1000L, activated))
      val bad = SessionFile.folderOf(root).resolve("fake-session-9.db")
      Files.writeString(bad, "FAKE-NOT-SQLITE"): Unit
      val c = catalog(root)
      try
        val rebuilt = Catalog.rebuild(c, SessionFile.folderOf(root)).fold(e => fail(e), identity)
        assertEquals(rebuilt.rows.map(_.session), Vector(id(1)))
        assertEquals(rebuilt.failed.map(_._1), Vector(bad))
      finally c.close()
    }
  }

  test("Catalog: a row of an earlier catalog, with no folder, time or first message, is folded again at rebuild") {
    withRoot { root =>
      val log = Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), "FAKE-FIRST", Map.empty))
      write(root, id(1), log)
      // The row as the earlier version wrote it: the same fields, with no folder, time and first message.
      val old =
        summon[Schema[EarlierRow]].jsonCodec.encodeToString(EarlierRow(id(1), Owner.User, None, SessionStatus.Idle, 1L))
      putText(root, id(1), old)
      val c = catalog(root)
      try
        val rows = Catalog.rebuild(c, SessionFile.folderOf(root)).fold(e => fail(e), identity).rows
        assertEquals(rows, Vector(catalogRow(id(1))(log)))
        assert(rows.head.firstMessage == "FAKE-FIRST" && rows.head.lastAt == 1000L, rows.toString)
      finally c.close()
    }
  }

  test("Catalog: the first message never ends inside a character, so the catalog can write its row") {
    val text = "a" * 78 + "\uD83D\uDE00" + "bcd"
    val row  = catalogRow(id(1))(Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), text, Map.empty)))
    // `codePoints` gives a surrogate with no pair as a code point of its own, in the range of the surrogates.
    assert(row.firstMessage.codePoints().allMatch(cp => cp < 0xd800 || cp > 0xdfff), row.firstMessage)
    assert(row.firstMessage.codePointCount(0, row.firstMessage.length) <= FirstMessageLength, row.firstMessage)
    withRoot { root =>
      val c = catalog(root)
      try assertEquals(c.put(row), Right(()))
      finally c.close()
    }
  }

  test("Catalog: a first message of one megabyte gives one short line from its start") {
    val text = "FAKE-START " + ("word " * 200_000)
    val row  = catalogRow(id(1))(Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), text, Map.empty)))
    assert(row.firstMessage.startsWith("FAKE-START word word"), row.firstMessage)
    assert(row.firstMessage.codePointCount(0, row.firstMessage.length) <= FirstMessageLength, row.firstMessage)
  }

  test("Catalog: the first message skips white space of any length at its start, and a cut text ends with …") {
    assertEquals(firstMessageOf(" " * 700 + "hello world"), "hello world")
    // Much white space between short words: the line has fewer than 80 characters, but the message goes on.
    val spaced = firstMessageOf(" " * 600 + ("w" + " " * 30) * 100)
    assert(spaced.endsWith("…") && spaced.startsWith("w w"), spaced)
    assertEquals(firstMessageOf("short and whole"), "short and whole")
  }

  test("Catalog: rebuild keeps the row of a good journal when the catalog cannot write the row") {
    withRoot { root =>
      val log = Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), "FAKE-FIRST", Map.empty))
      write(root, id(1), log)
      catalog(root).close()
      val file = Catalog.pathOf(root).toFile
      assert(file.setWritable(false))
      try
        val c = catalog(root)
        try
          val rebuilt = Catalog.rebuild(c, SessionFile.folderOf(root)).fold(e => fail(e), identity)
          assertEquals(rebuilt.rows, Vector(catalogRow(id(1))(log)))
          assertEquals(rebuilt.failed, Vector.empty)
        finally c.close()
      finally file.setWritable(true): Unit
    }
  }

  test("Catalog: rebuild removes each row with no file, also a row that does not decode") {
    withRoot { root =>
      Files.createDirectories(SessionFile.folderOf(root)): Unit
      putText(
        root,
        id(8),
        summon[Schema[EarlierRow]].jsonCodec.encodeToString(EarlierRow(id(8), Owner.User, None, SessionStatus.Idle, 1L))
      )
      putText(root, id(9), "FAKE-NOT-JSON")
      assertEquals(tableRows(root), 2)
      val c = catalog(root)
      try assertEquals(Catalog.rebuild(c, SessionFile.folderOf(root)), Right(Rebuilt(Vector.empty, Vector.empty)))
      finally c.close()
      assertEquals(tableRows(root), 0)
    }
  }

  test("Catalog: rebuild gives an error, not an exception, for a folder of journals that it cannot read") {
    withRoot { root =>
      val folder = Files.createDirectories(SessionFile.folderOf(root))
      val c      = catalog(root)
      assert(folder.toFile.setReadable(false))
      try assert(Catalog.rebuild(c, folder).isLeft)
      finally
        folder.toFile.setReadable(true): Unit
        c.close()
    }
  }

/** The row of the catalog before the fields `folder`, `lastAt` and `firstMessage`. */
private final case class EarlierRow(
    session: SessionId,
    owner  : Owner,
    parent : Option[SessionId],
    status : SessionStatus,
    lastSeq: Long
) derives Schema
