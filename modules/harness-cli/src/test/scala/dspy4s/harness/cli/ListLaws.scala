package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.host.{CatalogRow, LocalHost, SessionFile, SessionStatus}
import dspy4s.harness.host.HostSupport.{config, id, withRoot}
import dspy4s.harness.kernel.*

import java.nio.file.{Files, Path}
import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The laws of the list of the sessions of a root, and of the session of `--session` (CODE_HARNESS_DESIGN.md, 10.2 and
  * 10.6).
  */
class ListLaws extends munit.FunSuite:

  private val home = Path.of("/fake/home")
  private val cwd  = Path.of("/fake/work")

  private def row(n: Int, lastAt: Long, status: SessionStatus, first: String): CatalogRow =
    CatalogRow(SessionId(s"fake-session-$n"), Owner.User, None, status, 3L, s"/fake/work-$n", lastAt, first)

  private def journal(root: Path, session: SessionId, first: String): Unit =
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => fail(e), identity)
    val log  =
      Support.logOf(1000L, Entry.Activated(Support.versions, config()), Entry.Input(RequestId("r1"), first, Map.empty))
    try assertEquals(file.append(log, flush = true), Right(()))
    finally file.close()

  test("CLI: parse reads --list, which is off by default, and the usage names it") {
    assertEquals(CliArgs.parse(Vector("--list"), home, cwd).map(_.list), Right(true))
    assertEquals(CliArgs.parse(Vector.empty, home, cwd).map(_.list), Right(false))
    assert(CliArgs.usage.contains("--list"), CliArgs.usage)
  }

  test("CLI: sessionsText gives the newest session first, with its id, status, time, folder and first message") {
    val rows = Vector(
      row(1, 1_000L, SessionStatus.Idle, "FAKE-OLD"),
      row(2, 90_000_000L, SessionStatus.Ended(SessionOutcome.Parked), "FAKE-NEW")
    )
    val text = sessionsText(rows, ZoneOffset.UTC)
    assert(text.indexOf("fake-session-2") < text.indexOf("fake-session-1"), text)
    assert(text.contains("1970-01-02 01:00") && text.contains("/fake/work-2") && text.contains("FAKE-NEW"), text)
    assert(text.contains("ended") && text.contains("idle"), text)
    assert(sessionsText(Vector.empty, ZoneOffset.UTC).contains("no session"))
  }

  test("CLI: resolveSession takes an exact id or a unique start, and gives an error for none, more, or no text") {
    val ids = Vector(1, 12, 2).map(n => SessionId(s"fake-session-$n"))
    assertEquals(resolveSession(ids, "fake-session-1"), Right(SessionId("fake-session-1")))
    assertEquals(resolveSession(ids, "fake-session-12"), Right(SessionId("fake-session-12")))
    assertEquals(resolveSession(ids, "fake-session-2"), Right(SessionId("fake-session-2")))
    assert(resolveSession(ids, "fake-session").left.exists(_.contains("3 sessions")))
    assert(resolveSession(ids, "FAKE-NOTHING").left.exists(_.contains("No session")))
    // An empty id, for example from a shell variable with no value, opens no session.
    Vector("", "  ").foreach(text => assert(resolveSession(ids.take(1), text).isLeft, text))
  }

  test("CLI: journals gives the session of each journal file, with no catalog and no other file") {
    withRoot { root =>
      journal(root, id(1), "FAKE-FIRST-MESSAGE")
      // A list makes the catalog, and its removal loses nothing.
      assert(Cli.listText(root, ZoneOffset.UTC).isRight)
      Files.delete(root.resolve("catalog.db"))
      val folder = SessionFile.folderOf(root)
      Files.writeString(folder.resolve("x.db-wal"), ""): Unit
      Files.writeString(folder.resolve("x.lock"), ""): Unit
      Files.createDirectories(folder.resolve("d.db")): Unit
      assertEquals(LocalHost.journals(root), Right(Vector(id(1))))
      assertEquals(LocalHost.journals(root.resolve("FAKE-NO-ROOT")), Right(Vector.empty))
    }
  }

  test("CLI: listText and journals give an error, not an exception, for a folder that they cannot read") {
    withRoot { root =>
      journal(root, id(1), "FAKE-FIRST-MESSAGE")
      val folder = SessionFile.folderOf(root)
      assert(folder.toFile.setReadable(false))
      try
        assert(Cli.listText(root, ZoneOffset.UTC).isLeft)
        assert(LocalHost.journals(root).isLeft)
      finally folder.toFile.setReadable(true): Unit
    }
  }

  test("CLI: sessionsText shows a new line in an id or a folder as an escape, so it adds no line to the list") {
    val bad  = CatalogRow(SessionId("a\nFAKE-LINE  idle"), Owner.User, None, SessionStatus.Idle, 3L, "/x\ny", 0L, "")
    val text = sessionsText(Vector(bad), ZoneOffset.UTC)
    assertEquals(text.linesIterator.size, 1, text)
    assert(text.contains("\\u000a"), text)
  }

  test("CLI: listText reads the sessions of a root, with the first message of each journal, and names a bad file") {
    withRoot { root =>
      journal(root, id(1), "FAKE-FIRST-MESSAGE")
      Files.writeString(SessionFile.pathOf(root, SessionId("fake-bad")), "FAKE-NOT-A-JOURNAL"): Unit
      val text = Cli.listText(root, ZoneOffset.UTC).fold(e => fail(e), identity)
      assert(text.contains(id(1).value) && text.contains("FAKE-FIRST-MESSAGE"), text)
      assert(text.contains("fake-bad.db"), text)
    }
  }

  test("CLI: listText makes no file in a root that has no sessions") {
    withRoot { root =>
      val empty = root.resolve("FAKE-NO-ROOT")
      assert(Cli.listText(empty, ZoneOffset.UTC).exists(_.contains("no session")))
      assert(!Files.exists(empty), "the list made the folder of the root")
      assert(Cli.listText(root, ZoneOffset.UTC).exists(_.contains("no session")))
      assertEquals(Using.resource(Files.list(root))(_.iterator().asScala.toVector), Vector.empty)
    }
  }
