package dspy4s.harness.host

import dspy4s.harness.{Secrets, Support}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/** The laws of the writer of a session and of the timers (CODE_HARNESS_DESIGN.md, 10.2). */
class WriterLaws extends munit.FunSuite:

  private val activate = Command.Activate(Support.versions, config())

  /** A writer of a new file for `session` under `root`, with the secrets `secrets`. */
  private def writer(root: Path, session: SessionId = id(1), secrets: Secrets = Secrets.none): (Writer, SessionFile) =
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => fail(e), identity)
    (Writer(file, Vector.empty, () => 1000L, secrets, Feeds(), 64, _ => ()), file)

  /** The rows of the file of `session`, from a new connection. */
  private def rows(root: Path, session: SessionId = id(1)): Vector[Logged] =
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => fail(e), identity)
    try file.readFrom(0).fold(e => fail(e), identity)
    finally file.close()

  test("Writer: the commits of several threads get seq numbers with no gap, and the state is the fold of the file") {
    withRoot { root =>
      val (w, _) = writer(root)
      assert(w.run(activate, Source.Harness).isRight)
      val threads = (1 to 8).map { t =>
        Thread.ofVirtual().start { () =>
          (1 to 20).foreach(i => w.run(message(s"r-$t-$i"), Source.User): Unit)
        }
      }
      threads.foreach(_.join())
      val log = w.log
      assertEquals(log.map(_.seq), log.indices.map(_.toLong).toVector)
      assertEquals(log.collect { case Logged(_, _, Entry.Input(_, _, _)) => () }.size, 160)
      assertEquals(w.state, state(log))
      w.close()
      assertEquals(rows(root), log)
    }
  }

  test("Writer: run returns after the rows are in the file, so a new reader of the file sees them") {
    withRoot { root =>
      val (w, _) = writer(root)
      try
        assert(w.run(activate, Source.Harness).isRight)
        assert(w.run(message("r1", "FAKE-SEEN"), Source.User).isRight)
        assert(rows(root).exists(_.entry == Entry.Input(RequestId("r1"), "FAKE-SEEN", Map.empty)))
      finally w.close()
    }
  }

  test("Writer: an interrupt of a thread that waits for its commit does not cut the commit, and the flag stays set") {
    withRoot { root =>
      val (w, _) = writer(root)
      try
        assert(w.run(activate, Source.Harness).isRight)
        val result = AtomicReference[Either[HostError, SessionState]]()
        val flag   = AtomicReference[Boolean](false)
        val thread = Thread.ofPlatform().start { () =>
          Thread.currentThread().interrupt()
          result.set(w.run(message("r1"), Source.User))
          flag.set(Thread.currentThread().isInterrupted)
        }
        thread.join(10_000)
        assert(result.get.isRight, String.valueOf(result.get))
        assert(flag.get)
        assert(w.state.requests(RequestId("r1")))
      finally w.close()
    }
  }

  test("Writer: after close, each command fails with Ended at once, also a command that comes during the close") {
    withRoot { root =>
      val (w, _) = writer(root)
      assert(w.run(activate, Source.Harness).isRight)
      val started = CountDownLatch(4)
      val threads = (1 to 4).map { t =>
        Thread.ofVirtual().start { () =>
          started.countDown()
          var i = 0
          while w.run(message(s"r-$t-$i"), Source.User).isRight do i += 1
        }
      }
      assert(started.await(10, TimeUnit.SECONDS))
      w.close()
      threads.foreach(_.join(10_000))
      assert(threads.forall(!_.isAlive), "a sender waits for ever")
      assert(w.ended)
      assertEquals(w.run(message("late"), Source.User), Left(HostError.Ended))
      assertEquals(w.post(message("late"), Source.User), Left(HostError.Ended))
    }
  }

  test("Writer: a failed write ends this session with WriteFailed, and another session goes on") {
    withRoot { root =>
      val (broken, file) = writer(root, id(1))
      val (other, _)     = writer(root, id(2))
      try
        assert(broken.run(activate, Source.Harness).isRight)
        assert(other.run(activate, Source.Harness).isRight)
        file.close()
        assert(broken.run(message("r1"), Source.User) match
          case Left(HostError.WriteFailed(_)) => true
          case _                              => false)
        assert(broken.ended)
        assert(other.run(message("r2"), Source.User).isRight)
      finally
        broken.close()
        other.close()
    }
  }

  test("Writer: a fenced ticket gets Fenced for each later command, and the other senders go on") {
    withRoot { root =>
      val (w, _) = writer(root)
      try
        assert(w.run(activate, Source.Harness).isRight)
        w.fence(WorkerTicket(7))
        assertEquals(w.run(message("r1"), Source.Harness, Some(WorkerTicket(7))), Left(HostError.Fenced))
        assert(w.run(message("r2"), Source.User).isRight)
        assert(w.run(message("r3"), Source.Harness, Some(WorkerTicket(8))).isRight)
        assert(!w.state.requests(RequestId("r1")))
      finally w.close()
    }
  }

  test("Writer: WriterJournal gives a rejection of decide, and throws for a fenced ticket") {
    withRoot { root =>
      val (w, _) = writer(root)
      try
        assert(w.run(activate, Source.Harness).isRight)
        assert(w.run(message("r1"), Source.User).isRight)
        val journal = WriterJournal(w, WorkerTicket(1))
        assertEquals(
          journal.run(Command.Close(SessionOutcome.Parked, "FAKE"), Source.User).map(_ => ()),
          Left(Rejection.TurnOpen)
        )
        w.fence(WorkerTicket(1))
        intercept[IllegalStateException](journal.run(message("r2"), Source.User)): Unit
      finally w.close()
    }
  }

  test("Writer: the mask applies before the commit, so the file holds no known secret") {
    withRoot { root =>
      val secret = "fake-secret-value-0123456789"
      val (w, _) = writer(root, secrets = Secrets(Map("FAKE_KEY" -> secret)))
      assert(w.run(activate, Source.Harness).isRight)
      assert(w.run(message("r1", s"the key is $secret"), Source.User).isRight)
      w.close()
      assert(!rows(root).mkString.contains(secret))
    }
  }

  test(
    "Writer: expire of a stopped open turn fences its ticket and closes the turn, and expire of an ended turn does nothing"
  ) {
    withRoot { root =>
      val (w, _) = writer(root)
      try
        val one = WorkerTicket(1)
        val two = WorkerTicket(2)
        assert(w.run(activate, Source.Harness).isRight)
        assert(w.run(message("m1"), Source.User).isRight)
        val first = w.state.lastTurn
        assert(w.run(Command.Commit(Vector(Entry.StepStarted(0))), Source.Harness, Some(one)).isRight)
        assert(w.run(Command.Abort("FAKE-STOP"), Source.User).isRight)
        w.expire(first, one)
        assert(eventually()(w.state.position == Position.NoTurn), w.log.toString)
        assert(w.log.exists(_.entry == Entry.TurnEnded(TurnOutcome.Aborted)))
        assertEquals(
          w.run(Command.Commit(Vector(Entry.StepStarted(1))), Source.Harness, Some(one)),
          Left(HostError.Fenced)
        )
        // A later turn that stops too: the expire of the first turn does nothing to it, and its own expire closes it.
        assert(w.run(message("m2"), Source.User).isRight)
        val second = w.state.lastTurn
        assert(w.run(Command.Commit(Vector(Entry.StepStarted(0))), Source.Harness, Some(two)).isRight)
        assert(w.run(Command.Abort("FAKE-SECOND-STOP"), Source.User).isRight)
        w.expire(first, two)
        assert(w.run(message("m3"), Source.User).isRight)
        assertEquals(
          w.log.count(_.entry == Entry.TurnEnded(TurnOutcome.Aborted)),
          1,
          "the expire of the first turn closed the second turn"
        )
        w.expire(second, two)
        // The waiting message m3 then starts the next turn, so the law counts the turns that ended Aborted.
        assert(
          eventually()(w.log.count(_.entry == Entry.TurnEnded(TurnOutcome.Aborted)) == 2),
          w.log.map(_.entry).mkString("\n")
        )
      finally w.close()
    }
  }

  test("Writer: a commit with an entry that needs a flush runs with synchronous FULL, and another commit with NORMAL") {
    withRoot { root =>
      val (w, file) = writer(root)
      try
        assert(w.run(activate, Source.Harness).isRight)
        assert(w.run(message("m1"), Source.User).isRight)
        // An Input needs a flush (4.1), and a StepStarted does not.
        assertEquals(file.lastSynchronous, "2")
        assert(w.run(Command.Commit(Vector(Entry.StepStarted(0))), Source.Harness).isRight)
        assertEquals(file.lastSynchronous, "1")
      finally w.close()
    }
  }

  test("Writer: a failed write calls the failure hook with the error, once") {
    withRoot { root =>
      val file   = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
      val errors = java.util.concurrent.ConcurrentLinkedQueue[String]()
      val w      = Writer(file, Vector.empty, () => 1000L, Secrets.none, Feeds(), 64, _ => (), e => errors.add(e): Unit)
      try
        assert(w.run(activate, Source.Harness).isRight)
        file.close()
        assert(w.run(message("m1"), Source.User).isLeft)
        assert(w.run(message("m2"), Source.User).isLeft)
        assertEquals(errors.size, 1)
      finally w.close()
    }
  }

  test("Timer: a timer runs its put once after its delay, and a cancel before the delay stops it") {
    val timers = Timers()
    try
      val ran   = CountDownLatch(1)
      val start = System.nanoTime()
      val _     = timers.after(200.millis)(() => ran.countDown())
      assert(ran.await(5, TimeUnit.SECONDS))
      assert(System.nanoTime() - start >= 200_000_000L)
      val never = CountDownLatch(1)
      // A pause of the host between `after` and `cancel` must not run the timer, so its delay is long.
      val cancel = timers.after(1.second)(() => never.countDown())
      cancel.cancel()
      assert(!never.await(2, TimeUnit.SECONDS))
    finally timers.close()
  }

  test("Timer: a timer that posts a command commits it through the writer") {
    withRoot { root =>
      val (w, _) = writer(root)
      val timers = Timers()
      try
        assert(w.run(activate, Source.Harness).isRight)
        val _ = timers.after(100.millis)(() => w.post(message("r-timer"), Source.Harness): Unit)
        assert(eventually()(w.state.requests(RequestId("r-timer"))))
      finally
        timers.close()
        w.close()
    }
  }
