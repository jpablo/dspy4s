package dspy4s.harness.host

import dspy4s.harness.Support
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.nio.file.{Files, Path}

/** The laws of the catalog of a host (CODE_HARNESS_DESIGN.md, 10.2). */
class CatalogLaws extends munit.FunSuite:

  private val activated = Entry.Activated(Support.versions, config())

  private def write(root: Path, session: SessionId, log: Vector[Logged]): Unit =
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => fail(e), identity)
    try assertEquals(file.append(log, flush = true), Right(()))
    finally file.close()

  private def catalog(root: Path): Catalog = Catalog.open(root.resolve("catalog.db")).fold(e => fail(e), identity)

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
    assertEquals(row(1), CatalogRow(id(1), Owner.User, None, SessionStatus.Idle, 0L))
    assertEquals(row(3).status, SessionStatus.InTurn)
    assertEquals(row(4).status, SessionStatus.Stopping)
    assertEquals(row(5).status, SessionStatus.Idle)
    assertEquals(row(6), CatalogRow(id(1), Owner.User, None, SessionStatus.Ended(SessionOutcome.Parked), 5L))
  }

  test("Catalog: put and row give the row back, and a new open of the file reads the same rows") {
    withRoot { root =>
      val row   = CatalogRow(id(1), Owner.User, None, SessionStatus.Idle, 3L)
      val first = catalog(root)
      assertEquals(first.put(row), Right(()))
      assertEquals(first.row(id(1)), Some(row))
      first.close()
      val again = catalog(root)
      try assertEquals(again.rows, Vector(row))
      finally again.close()
    }
  }

  test("Catalog: rebuild folds each journal whose row lags, so a crash before the update of a row loses nothing") {
    withRoot { root =>
      val one = Support.logOf(1000L, activated, Entry.Input(RequestId("r1"), "x", Map.empty), Entry.TurnStarted)
      val two = Support.logOf(1000L, activated)
      write(root, id(1), one)
      write(root, id(2), two)
      val c = catalog(root)
      try
        assertEquals(c.put(CatalogRow(id(1), Owner.User, None, SessionStatus.Idle, 0L)), Right(()))
        val rows = Catalog.rebuild(c, root.resolve("sessions")).fold(e => fail(e), identity)
        assertEquals(rows, Vector(catalogRow(id(1))(one), catalogRow(id(2))(two)))
        assertEquals(c.rows, rows)
      finally c.close()
    }
  }

  test("Catalog: after a removal of the catalog file, rebuild gives the same rows") {
    withRoot { root =>
      write(root, id(1), Support.logOf(1000L, activated))
      val first  = catalog(root)
      val before = Catalog.rebuild(first, root.resolve("sessions")).fold(e => fail(e), identity)
      first.close()
      Files.delete(root.resolve("catalog.db"))
      val again = catalog(root)
      try assertEquals(Catalog.rebuild(again, root.resolve("sessions")), Right(before))
      finally again.close()
    }
  }

  test("Catalog: a row with no file goes away at rebuild") {
    withRoot { root =>
      Files.createDirectories(root.resolve("sessions")): Unit
      val c = catalog(root)
      try
        assertEquals(c.put(CatalogRow(id(9), Owner.User, None, SessionStatus.Idle, 0L)), Right(()))
        assertEquals(Catalog.rebuild(c, root.resolve("sessions")), Right(Vector.empty))
        assertEquals(c.row(id(9)), None)
      finally c.close()
    }
  }
