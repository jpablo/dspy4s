package dspy4s.harness.host

import dspy4s.harness.{LineKey, MemoryLine, NodeId, Support}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.jdk.CollectionConverters.*

/** The laws of the journal file of a session (CODE_HARNESS_DESIGN.md, 10.2). */
class SessionFileLaws extends munit.FunSuite:

  /** `n` inputs with `seq` from 0. */
  private def inputs(n: Int): Vector[Logged] =
    Support.logOf(1000L, (0 until n).map(i => Entry.Input(RequestId(s"r$i"), s"text $i", Map.empty))*)

  private def open(path: Path, session: SessionId = id(1)): SessionFile =
    SessionFile.open(path, session).fold(e => fail(e), identity)

  test("File: the table memory keeps the first line of a key, keys a line by its digest, and leaves the journal") {
    val first  = LineKey(NodeId(0, 0), "FAKE-DIGEST-0")
    val parent = LineKey(NodeId(1, 0), "FAKE-DIGEST-1")
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      val file = open(path)
      try
        assertEquals(file.append(inputs(3), flush = true), Right(()))
        val lines = FileLines.open(path, id(1)).fold(e => fail(e), identity)
        try
          assertEquals(lines.newest, 0L)
          assertEquals(
            lines.putAll(Vector(first -> MemoryLine.of("FAKE-FIRST"), parent -> MemoryLine.of("FAKE-PARENT"))),
            1L
          )
          // A write with no new line adds no build number.
          assertEquals(lines.putAll(Vector(first -> MemoryLine.of("FAKE-ANOTHER"))), 1L)
          // The journal still writes while the store has the file open.
          assertEquals(file.append(inputs(5).drop(3), flush = true), Right(()))
        finally lines.close()
        assertEquals(file.readFrom(0), Right(inputs(5)))
      finally file.close()
      val again = FileLines.open(path, id(1)).fold(e => fail(e), identity)
      try
        assertEquals(again.get(first, 1), Some(MemoryLine.of("FAKE-FIRST")))
        assertEquals(again.get(parent, Long.MaxValue), Some(MemoryLine.of("FAKE-PARENT")))
        assertEquals(again.get(first.copy(digest = "FAKE-OTHER"), Long.MaxValue), None)
        assertEquals(again.get(LineKey(NodeId(2, 0), "FAKE-DIGEST-2"), Long.MaxValue), None)
        assertEquals(again.newest, 1L)
      finally again.close()
      val other = FileLines.open(path, id(2)).fold(e => fail(e), identity)
      try
        assertEquals(other.get(first, Long.MaxValue), None)
        assertEquals(other.newest, 0L)
      finally other.close()
    }
  }

  test("File: each write of new lines gets the next build number, and get reads only the lines up to a number") {
    val key = (k: Int) => LineKey(NodeId(0, k.toLong), s"FAKE-DIGEST-$k")
    withRoot { root =>
      val path  = SessionFile.pathOf(root, id(1))
      val file  = open(path)
      val lines = FileLines.open(path, id(1)).fold(e => fail(e), identity)
      try
        assertEquals(lines.putAll(Vector(key(0) -> MemoryLine.of("FAKE-ZERO"))), 1L)
        assertEquals(lines.putAll(Vector.empty), 1L)
        assertEquals(lines.putAll(Vector(key(0) -> MemoryLine.of("FAKE-X"), key(1) -> MemoryLine.of("FAKE-ONE"))), 2L)
        assertEquals(lines.get(key(0), 1), Some(MemoryLine.of("FAKE-ZERO")))
        assertEquals(lines.get(key(1), 1), None)
        assertEquals(lines.get(key(1), 2), Some(MemoryLine.of("FAKE-ONE")))
        assertEquals(lines.get(key(0), 0), None)
      finally
        lines.close()
        file.close()
    }
  }

  test("File: a session file from before the column build opens, and its lines have the build number 0") {
    val old = LineKey(NodeId(0, 0), "FAKE-DIGEST-0")
    val add = LineKey(NodeId(0, 1), "FAKE-DIGEST-1")
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      Files.createDirectories(path.getParent): Unit
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try
        val statement = connection.createStatement()
        try
          statement.execute(
            "create table memory (session text not null, level integer not null, idx integer not null, " +
              "digest text not null, line text not null, primary key (session, level, idx, digest))"
          ): Unit
          statement.execute(s"insert into memory values ('${id(1).value}', 0, 0, 'FAKE-DIGEST-0', 'FAKE-OLD')"): Unit
        finally statement.close()
      finally connection.close()
      // A reader keeps the table as it is.
      val reader = FileLines.readOnly(path, id(1)).fold(e => fail(e), identity)
      try
        assertEquals(reader.get(old, 0), Some(MemoryLine.of("FAKE-OLD")))
        assertEquals(reader.newest, 0L)
      finally reader.close()
      val lines = FileLines.open(path, id(1)).fold(e => fail(e), identity)
      try
        assertEquals(lines.get(old, 0), Some(MemoryLine.of("FAKE-OLD")))
        assertEquals(lines.newest, 0L)
        assertEquals(lines.putAll(Vector(add -> MemoryLine.of("FAKE-NEW"))), 1L)
        assertEquals(lines.get(add, 0), None)
        assertEquals(lines.get(add, 1), Some(MemoryLine.of("FAKE-NEW")))
      finally lines.close()
    }
  }

  test("File: the file of a session is <root>/sessions/<id>.db") {
    withRoot { root =>
      assertEquals(SessionFile.pathOf(root, id(1)), root.resolve("sessions").resolve("fake-session-1.db"))
    }
  }

  test("File: readOnly of a closed journal and of a closed catalog makes no file, and it reads their rows") {
    withRoot { root =>
      val path = SessionFile.pathOf(root, id(1))
      val log  = inputs(3)
      val file = open(path)
      assertEquals(file.append(log, flush = true), Right(()))
      file.close()
      val catalog = Catalog.open(Catalog.pathOf(root)).fold(e => fail(e), identity)
      assertEquals(catalog.put(catalogRow(id(1))(log)), Right(()))
      catalog.close()
      val before = Files.walk(root).iterator().asScala.map(root.relativize).toSet
      val reader = SessionFile.readOnly(path, id(1)).fold(e => fail(e), identity)
      try assertEquals(reader.readFrom(0), Right(log))
      finally reader.close()
      val rows = Catalog.readOnly(Catalog.pathOf(root)).fold(e => fail(e), identity)
      try assertEquals(rows.rows.map(_.session), Vector(id(1)))
      finally rows.close()
      assertEquals(Files.walk(root).iterator().asScala.map(root.relativize).toSet, before)
    }
  }

  test("File: readOnly of a journal whose writer is open sees each commit of the writer") {
    withRoot { root =>
      val path   = SessionFile.pathOf(root, id(1))
      val log    = inputs(4)
      val writer = open(path)
      try
        assertEquals(writer.append(log.take(2), flush = false), Right(()))
        val reader = SessionFile.readOnly(path, id(1)).fold(e => fail(e), identity)
        try
          assertEquals(reader.readFrom(0), Right(log.take(2)))
          assertEquals(writer.append(log.drop(2), flush = false), Right(()))
          assertEquals(reader.readFrom(0), Right(log))
        finally reader.close()
      finally writer.close()
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

  test("File: a commit with a flush runs with synchronous FULL, and a commit with no flush with NORMAL") {
    withRoot { root =>
      val file = open(SessionFile.pathOf(root, id(1)))
      try
        assertEquals(file.append(inputs(1), flush = true), Right(()))
        assertEquals(file.lastSynchronous, "2")
        assertEquals(file.append(inputs(2).drop(1), flush = false), Right(()))
        assertEquals(file.lastSynchronous, "1")
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
