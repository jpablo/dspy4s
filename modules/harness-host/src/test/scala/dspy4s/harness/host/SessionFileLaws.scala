package dspy4s.harness.host

import dspy4s.harness.Support
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.nio.file.{Files, Path}
import java.sql.DriverManager

/** The laws of the journal file of a session (CODE_HARNESS_DESIGN.md, 10.2). */
class SessionFileLaws extends munit.FunSuite:

  /** `n` inputs with `seq` from 0. */
  private def inputs(n: Int): Vector[Logged] =
    Support.logOf(1000L, (0 until n).map(i => Entry.Input(RequestId(s"r$i"), s"text $i", Map.empty))*)

  private def open(path: Path, session: SessionId = id(1)): SessionFile =
    SessionFile.open(path, session).fold(e => fail(e), identity)

  test("File: the file of a session is <root>/sessions/<id>.db") {
    withRoot { root =>
      assertEquals(SessionFile.pathOf(root, id(1)), root.resolve("sessions").resolve("fake-session-1.db"))
    }
  }

  test("File: append and readFrom give the rows in the order of seq, and a new open of the file reads the same rows") {
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      val log  = inputs(5)
      val file = open(path)
      assertEquals(file.append(log.take(2), flush = false), Right(()))
      assertEquals(file.append(log.drop(2), flush = true), Right(()))
      assertEquals(file.readFrom(0), Right(log))
      assertEquals(file.readFrom(3), Right(log.drop(3)))
      file.close()
      val again = open(path)
      try assertEquals(again.readFrom(0), Right(log))
      finally again.close()
    }
  }

  test("File: a commit with a seq that the file has fails and adds none of its rows, so a stale writer adds nothing") {
    withRoot { root =>
      val log  = inputs(4)
      val file = open(SessionFile.pathOf(root, id(1)))
      try
        assertEquals(file.append(log.take(3), flush = false), Right(()))
        assert(file.append(log.slice(2, 4), flush = false).isLeft)
        assertEquals(file.readFrom(0), Right(log.take(3)))
      finally file.close()
    }
  }

  test("File: the file uses WAL and synchronous NORMAL, also after a commit with a flush") {
    withRoot { root =>
      val file = open(SessionFile.pathOf(root, id(1)))
      try
        assertEquals(file.setting("journal_mode").toLowerCase, "wal")
        assertEquals(file.append(inputs(1), flush = true), Right(()))
        // SQLite gives 1 for NORMAL.
        assertEquals(file.setting("synchronous"), "1")
      finally file.close()
    }
  }

  test("File: each row holds its session, so a copy of the file holds the same journal only for that session") {
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      val log  = inputs(3)
      val file = open(path)
      assertEquals(file.append(log, flush = true), Right(()))
      file.close()
      val copy = Files.copy(path, root.resolve("copy.db"))
      val same = open(copy)
      try assertEquals(same.readFrom(0), Right(log))
      finally same.close()
      val other = open(copy, id(2))
      try assertEquals(other.readFrom(0), Right(Vector.empty))
      finally other.close()
    }
  }

  test("File: a row that does not decode is skipped when it is not required, stops the read when it is required, " +
    "and lastSeq counts it") {
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      val file = open(path)
      try
        assertEquals(file.lastSeq, -1L)
        assertEquals(file.append(inputs(2), flush = true), Right(()))
        // A row of a later build: its entry is not a `Logged` of this build.
        def insert(seq: Long, required: Boolean): Unit =
          val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
          try
            val st = c.prepareStatement("insert into journal (session, seq, required, entry) values (?, ?, ?, ?)")
            st.setString(1, id(1).value)
            st.setLong(2, seq)
            st.setBoolean(3, required)
            st.setString(4, "{\"FakeLaterEntry\": {}}")
            st.executeUpdate(): Unit
          finally c.close()
        insert(2L, required = false)
        assertEquals(file.readFrom(0), Right(inputs(2)))
        assertEquals(file.lastSeq, 2L)
        insert(3L, required = true)
        assert(file.readFrom(0).isLeft)
      finally file.close()
    }
  }

  test("File: open of a file that is not a journal gives an error") {
    withRoot { root =>
      val path = Files.createDirectories(root.resolve("sessions")).resolve("fake-session-1.db")
      Files.writeString(path, "FAKE-NOT-SQLITE"): Unit
      assert(SessionFile.open(path, id(1)).isLeft)
    }
  }
