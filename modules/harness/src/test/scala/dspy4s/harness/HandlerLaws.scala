package dspy4s.harness

import dspy4s.harness.guest.{Data, Llm, Scope, SessionCard, SessionRef, Sessions, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.{HostList, Profiles}
import munit.FunSuite
import zio.blocks.schema.json.Json

import java.nio.file.Files as JFiles
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.{Failure, Success, Try}

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

  test("Recorded: after close, a call does not run, and it commits no intent") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    recorder.close()
    var ran = false
    intercept[RuntimeException](recorder.call("write", Json.String("a.txt")) { ran = true }): Unit
    assert(!ran)
    assertEquals(effects(j), Vector.empty)
  }

  /** A journal that holds the commit of the intent with the id 0 until the intent with the id 1 is in the log, or until
    * 500 ms pass.
    */
  private final class SecondFirst(inner: MemoryJournal) extends Journal:
    val firstWaits: CountDownLatch                                             = CountDownLatch(1)
    val secondDone: CountDownLatch                                             = CountDownLatch(1)
    def state: SessionState                                                    = inner.state
    def log: Vector[Logged]                                                    = inner.log
    def run(command: Command, sender: Source): Either[Rejection, SessionState] =
      val n = command match
        case Command.Commit(Vector(Entry.EffectStarted(id, _, _))) => id.n
        case _                                                     => -1
      if n == 0 then
        firstWaits.countDown()
        secondDone.await(500, TimeUnit.MILLISECONDS): Unit
      val result = inner.run(command, sender)
      if n == 1 then secondDone.countDown()
      result

  test("Recorded: two calls of a cell on two threads both run, also when the second one could commit first") {
    val inner    = journal()
    val cell     = inCell(inner)
    val j        = SecondFirst(inner)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    def start(op: String): (Thread, AtomicReference[Try[String]]) =
      val result = AtomicReference[Try[String]](Failure(IllegalStateException("the call did not end")))
      val thread = Thread(() => result.set(Try(recorder.call(op, Json.Null)(op))))
      thread.start()
      (thread, result)
    val (first, firstResult) = start("first")
    assert(j.firstWaits.await(5, TimeUnit.SECONDS), "the first call did not come to its commit")
    val (second, secondResult) = start("second")
    first.join(5_000)
    second.join(5_000)
    assertEquals(firstResult.get, Success("first"))
    assertEquals(secondResult.get, Success("second"))
    assertEquals(effects(inner).collect { case Entry.EffectStarted(id, _, _) => id.n }, Vector(0, 1))
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

  test("Recorded: each call of Sessions commits its op and its arguments, and the value of its handler (8.7)") {
    val j        = journal()
    val cell     = inCell(j)
    val recorder = Recorder(j)
    recorder.startCell(cell)
    val card = SessionCard(
      SessionRef("s2"),
      "fix",
      Some(Scope.Directory("/w")),
      "User",
      None,
      "Idle",
      4L,
      0,
      Vector.empty,
      0,
      None,
      None
    )
    val inner = new Sessions:
      def list(): Vector[SessionCard]                                                       = Vector(card)
      def tell(session   : SessionRef, message : String): Unit                              = ()
      def start(goal     : String, scope       : Scope, strategy: StrategyName): SessionRef = SessionRef("s3")
      def observe(session: SessionRef, question: String)(using Llm): String                 = "an answer"
      def zoom(session   : SessionRef, ref     : String): String                            = "a line"
    val llm = new Llm:
      def query(prompt        : String): String                 = ""
      def queryBatched(prompts: Vector[String]): Vector[String] = prompts
    val bound = recorded(Bindings(turn = JournalTurn(j, () => 5L), sessions = Some(inner)), recorder)
    bound.sessions.foreach { s =>
      assertEquals(s.list(), Vector(card))
      s.tell(SessionRef("s2"), "go on")
      assertEquals(s.start("fix", Scope.Directory("/w"), StrategyName.Window), SessionRef("s3"))
      assertEquals(s.observe(SessionRef("s2"), "why?")(using llm), "an answer")
      assertEquals(s.zoom(SessionRef("s2"), "1.0"), "a line")
    }
    val started = effects(j).collect { case Entry.EffectStarted(_, op, args) => (op, args) }
    assertEquals(started.map(_._1), Vector("sessions", "tell", "start", "observe", "zoom"))
    val zoomArgs = started.last._2 match
      case Json.Object(fields) => fields.map(_._1).toSet
      case other               => fail(s"the arguments of zoom are $other")
    assertEquals(zoomArgs, Set("session", "ref"))
    // The arguments of start hold the kind of its scope, so the guard of the controller can ask for a Directory.
    started(2)._2 match
      case Json.Object(fields) => assert(fields.contains(("kind", Json.String("Directory"))), fields.toString)
      case other               => fail(s"the arguments of start are $other")
    assert(needsFlush(Entry.EffectStarted(EffectId(cell, 1), "tell", Json.Null)))
    assert(needsFlush(Entry.EffectStarted(EffectId(cell, 2), "start", Json.Null)))
    val done = effects(j).collect { case Entry.EffectDone(_, EffectResult.Value(json, _)) => json }
    assertEquals(done(2), summon[Data[SessionRef]].encode(SessionRef("s3")))
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

  test("Files: a link to a place that does not exist cannot be read or written, and nothing appears outside") {
    withPlace { place =>
      val f      = files(place)
      val file   = place.outside.resolve("new.txt")
      val folder = place.outside.resolve("new-folder")
      JFiles.createSymbolicLink(place.root.resolve("to-file"), file): Unit
      JFiles.createSymbolicLink(place.root.resolve("to-folder"), folder): Unit
      intercept[RuntimeException](f.write("to-file", "changed")): Unit
      intercept[RuntimeException](f.write("to-folder/x.txt", "changed")): Unit
      intercept[RuntimeException](f.read("to-file")): Unit
      assert(!JFiles.exists(file), "a write went out of the root")
      assert(!JFiles.exists(folder), "a write went out of the root")
    }
  }

  test("Files: a file with a second hard link cannot be read or written, because the link can come from outside") {
    withPlace { place =>
      val f = files(place)
      JFiles.createLink(place.root.resolve("hard.txt"), place.outside.resolve("o.txt")): Unit
      intercept[RuntimeException](f.write("hard.txt", "changed")): Unit
      intercept[RuntimeException](f.edit("hard.txt", "FAKE", "x")): Unit
      intercept[RuntimeException](f.read("hard.txt")): Unit
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
