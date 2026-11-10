package dspy4s.harness

import dspy4s.harness.guest.Data
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.{HostList, Profiles}
import munit.FunSuite
import zio.blocks.schema.json.Json

import java.nio.file.Files as JFiles

import Support.*

/** The laws of the handlers of a call (CODE_HARNESS_DESIGN.md, 5.3): `recorded`, `Files.local`, `Store.snapshot` and
  * the `Turn` of a session.
  */
class HandlerLaws extends FunSuite:

  private def withPlace(body: Place => Unit): Unit =
    val place = Place()
    try body(place)
    finally place.close()

  private def files(place: Place): LocalFiles =
    LocalFiles(
      place.root,
      Profiles.forMode(HarnessEnv.taskMode, place.root, place.layout, HostList.empty),
      place.layout.secretNames
    )

  private def effects(j: Journal): Vector[Entry] =
    j.log.map(_.entry).collect {
      case e: Entry.EffectStarted => e
      case e: Entry.EffectDone    => e
    }

  // ── recorded ──

  test("Recorded: a call commits its intent before it runs, and its outcome after") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    var seen   = Option.empty[Entry]
    val result = recorder.call("read", Json.String("a.txt")) {
      seen = j.log.lastOption.map(_.entry)
      "text"
    }
    assertEquals(result, "text")
    assertEquals(seen, Some(Entry.EffectStarted(EffectId(cell, 0), "read", Json.String("a.txt"))))
    j.log.last.entry match
      case Entry.EffectDone(id, EffectResult.Value(json, _)) =>
        assertEquals(id, EffectId(cell, 0))
        assertEquals(json, summon[Data[String]].encode("text"))
      case other => fail(s"the last entry is $other")
  }

  test("Recorded: the calls of a cell get the ids 0, 1 and 2") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    (0 until 3).foreach(i => recorder.call("now", Json.Null)(i.toLong): Unit)
    val ids = effects(j).collect { case Entry.EffectStarted(id, _, _) => id }
    assertEquals(ids, Vector(EffectId(cell, 0), EffectId(cell, 1), EffectId(cell, 2)))
  }

  test("Recorded: after a stop, a call does not run, and it commits no intent") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    j.run(Command.Abort("stop"), Source.User): Unit
    var ran = false
    intercept[RuntimeException](recorder.call("write", Json.String("a.txt")) { ran = true }): Unit
    assert(!ran)
    assertEquals(effects(j), Vector.empty)
  }

  test("Recorded: a call that throws gets an Error outcome, and the exception goes on to the cell") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    intercept[IllegalArgumentException] {
      recorder.call[String]("read", Json.String("none.txt"))(throw IllegalArgumentException("no such file"))
    }: Unit
    j.log.last.entry match
      case Entry.EffectDone(id, EffectResult.Error(message)) =>
        assertEquals(id, EffectId(cell, 0))
        assert(message.contains("no such file"), message)
      case other => fail(s"the last entry is $other")
  }

  test("Recorded: each guest API call of the bindings goes through the recorder, and input does not") {
    withPlace { place =>
      val j        = journal()
      val cell     = inCell(j)
      val recorder = Recorder(j)
      val store    = SnapshotStore()
      recorder.startCell(cell)
      store.begin(Map.empty)
      val bound = recorded(
        Bindings(turn = JournalTurn(j, () => 5L), files = Some(files(place)), store = Some(store)),
        recorder
      )
      bound.files.foreach { f =>
        f.write("b.txt", "x")
        f.read("b.txt"): Unit
        f.edit("b.txt", "x", "y")
      }
      bound.store.foreach { s =>
        s.store("k", 1)
        s.load[Int]("k"): Unit
        s.keys: Unit
      }
      bound.turn.now(): Unit
      bound.turn.input("q"): Unit
      val ops = effects(j).collect { case Entry.EffectStarted(_, op, _) => op }
      assertEquals(ops, Vector("write", "read", "edit", "store", "load", "keys", "now"))
    }
  }

  // ── Files.local ──

  test("Files: read, write and edit work under the root") {
    withPlace { place =>
      val f = files(place)
      assertEquals(f.read("a.txt"), "FAKE-A")
      f.write("dir/b.txt", "one two")
      f.edit("dir/b.txt", "two", "2")
      assertEquals(JFiles.readString(place.root.resolve("dir/b.txt")), "one 2")
    }
  }

  test("Files: a path outside the root fails, also through a symbolic link") {
    withPlace { place =>
      val f = files(place)
      JFiles.createSymbolicLink(place.root.resolve("link"), place.outside.resolve("o.txt")): Unit
      intercept[RuntimeException](f.read("../outside/o.txt")): Unit
      intercept[RuntimeException](f.read(place.outside.resolve("o.txt").toString)): Unit
      intercept[RuntimeException](f.read("link")): Unit
      intercept[RuntimeException](f.write("link", "changed")): Unit
      intercept[RuntimeException](f.read(place.secrets.resolve("id_fake").toString)): Unit
      assertEquals(JFiles.readString(place.outside.resolve("o.txt")), "FAKE-OUTSIDE")
    }
  }

  test("Files: a file with a secret name that exists cannot be read or written") {
    withPlace { place =>
      val f = files(place)
      JFiles.createDirectories(place.root.resolve("app")): Unit
      JFiles.writeString(place.root.resolve("app/.env.local"), "FAKE-LOCAL"): Unit
      intercept[RuntimeException](f.read(".env")): Unit
      intercept[RuntimeException](f.read("app/.env.local")): Unit
      intercept[RuntimeException](f.write(".env", "changed")): Unit
      intercept[RuntimeException](f.edit(".env", "FAKE", "x")): Unit
      assertEquals(JFiles.readString(place.root.resolve(".env")), "FAKE-ENV")
    }
  }

  test("Files: a new file with a secret name can be written, and after that it is hidden (5.4)") {
    withPlace { place =>
      val f = files(place)
      f.write(".env.new", "FAKE-NEW")
      assertEquals(JFiles.readString(place.root.resolve(".env.new")), "FAKE-NEW")
      intercept[RuntimeException](f.read(".env.new")): Unit
    }
  }

  test("Files: .git can be read and not written") {
    withPlace { place =>
      val f = files(place)
      assertEquals(f.read(".git/config"), "FAKE-GIT")
      intercept[RuntimeException](f.write(".git/config", "changed")): Unit
      intercept[RuntimeException](f.write(".git/hooks/pre-commit", "changed")): Unit
      assertEquals(JFiles.readString(place.root.resolve(".git/config")), "FAKE-GIT")
    }
  }

  test("Files: edit replaces the one place of a text, and fails with no place or with two places") {
    withPlace { place =>
      val f = files(place)
      f.write("c.txt", "one two two")
      f.edit("c.txt", "one", "1")
      intercept[RuntimeException](f.edit("c.txt", "two", "2")): Unit
      intercept[RuntimeException](f.edit("c.txt", "three", "3")): Unit
      assertEquals(JFiles.readString(place.root.resolve("c.txt")), "1 two two")
    }
  }

  // ── Store.snapshot ──

  private final case class Point(x: Int, y: Int)

  test("Store: load reads the snapshot and the writes of the cell") {
    val s = SnapshotStore()
    s.begin(Map("a" -> summon[Data[Int]].encode(1)))
    assertEquals(s.load[Int]("a"), Some(1))
    s.store("b", "x")
    assertEquals(s.load[String]("b"), Some("x"))
    assertEquals(s.load[String]("none"), None)
    assertEquals(s.keys, Vector("a", "b"))
  }

  test("Store: the writes are only in the buffer, and begin starts again with no writes") {
    val s = SnapshotStore()
    s.begin(Map("a" -> summon[Data[Int]].encode(1)))
    s.store("b", "x")
    assertEquals(s.writes, Map("b" -> summon[Data[String]].encode("x")))
    s.begin(Map.empty)
    assertEquals(s.writes, Map.empty)
    assertEquals(s.keys, Vector.empty)
  }

  test("Store: a value comes back with the type of the cell that loads it") {
    val s = SnapshotStore()
    s.begin(Map.empty)
    s.store("p", Point(1, 2))
    assertEquals(s.load[Point]("p"), Some(Point(1, 2)))
  }

  // ── Turn ──

  test("Turn: input reads the values of the Input entries, and now reads the clock") {
    val j = journal()
    open(j, values = Map("q" -> Json.String("v")))
    val t = JournalTurn(j, () => 42L)
    assertEquals(t.input("q"), Some(Json.String("v")))
    assertEquals(t.input("none"), None)
    assertEquals(t.now(), 42L)
  }
