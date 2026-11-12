package dspy4s.harness.host

import dspy4s.harness.{CellResult, Journal, PolicyReply, Support, TurnEffects}
import dspy4s.harness.Support.{answer, code}
import dspy4s.harness.guest.Mode
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import zio.blocks.schema.json.Json

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The laws of the first session host (CODE_HARNESS_DESIGN.md, 10.2 and 10.3), with scripted effects. */
class HostLaws extends munit.FunSuite:

  /** A runtime whose activations each run the script `replies`. */
  private def scripted(replies: Either[String, PolicyReply]*): ScriptRuntime =
    ScriptRuntime(_ => Support.Script(replies.toVector))

  /** The entries of the last turn of a handle, from its `TurnStarted`. */
  private def lastTurn(handle: SessionHandle): Vector[Entry] =
    val all = entries(handle)
    all.drop(all.lastIndexOf(Entry.TurnStarted))

  private def openFailed(result: Either[HostError, SessionHandle]): Boolean =
    result match
      case Left(HostError.OpenFailed(_)) => true
      case _                             => false

  /** A runtime whose activations each answer "hi". `calls` records its calls and the closes of their results in order,
    * and `openings` records the arguments of each call of `opened`. Each call of `opened` waits `slowMillis` before it
    * records itself, as the start of a walk can.
    */
  private final class OpenedRuntime(slowMillis: Long = 0L) extends SessionRuntime:
    val calls: ConcurrentLinkedQueue[String]                        = ConcurrentLinkedQueue()
    val openings: ConcurrentLinkedQueue[(SessionId, SessionConfig)] = ConcurrentLinkedQueue()

    def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
      calls.add("activate"): Unit
      val made = Support.Script(Vector(answer("hi")))
      new Activation:
        def effects: TurnEffects = made
        def stop(): Unit         = ()
        def unclean: Boolean     = false
        def close(): Unit        = calls.add("close the activation"): Unit

    override def opened(session: SessionId, config: SessionConfig): AutoCloseable =
      Thread.sleep(slowMillis)
      calls.add("opened"): Unit
      openings.add((session, config)): Unit
      () => calls.add("close the opened part"): Unit

  // ── Open, reopen and the catalog ──

  test("Host: words give each error of the host in words, and a refusal in the words of the kernel") {
    assertEquals(HostError.Rejected(Rejection.TurnOpen).words, "a turn is open")
    assertEquals(HostError.WriteFailed("FAKE disk full").words, "a write to the journal failed: FAKE disk full")
    assertEquals(HostError.Ended.words, "the session has ended")
    assertEquals(HostError.Fenced.words, "a stop ended this activation")
    assertEquals(HostError.OpenFailed("FAKE no folder").words, "FAKE no folder")
    assertEquals(HostError.Refused("FAKE not allowed").words, "FAKE not allowed")
  }

  test("Host: open of a new id makes its file and commits Activated, and a second open gives the same handle") {
    withRoot { root =>
      withHost(root, scripted(answer("hi"))) { host =>
        val first = opened(host, id(1))
        assert(Files.exists(SessionFile.pathOf(root, id(1))))
        entries(first) match
          case Vector(Entry.Activated(_, settings)) => assertEquals(settings, config())
          case other                                => fail(s"not one Activated: $other")
        assert(opened(host, id(1)) eq first)
      }
    }
  }

  test("Host: an open calls opened once with its configuration, and the end closes the result after the activation") {
    withRoot { root =>
      val runtime = OpenedRuntime()
      withHost(root, runtime) { host =>
        val first = opened(host, id(1), Some(config(steps = 5)))
        assert(opened(host, id(1), Some(config(steps = 5))) eq first, "a second open gave another session")
        assertEquals(runtime.openings.asScala.toVector, Vector((id(1), config(steps = 5))))
        assertEquals(first.send(message("m1")), Right(()))
        assert(eventually()(outcomes(first) == Vector(TurnOutcome.Replied("hi"))), entries(first).toString)
        assertEquals(first.run(Command.Close(SessionOutcome.Parked, "FAKE")).map(_.closed), Right(true))
        assert(eventually()(runtime.calls.size == 4), runtime.calls.toString)
        assertEquals(
          runtime.calls.asScala.toVector,
          Vector("opened", "activate", "close the activation", "close the opened part")
        )
        // A reopen with no configuration has the configuration of the journal.
        opened(host, id(1), None): Unit
        assertEquals(runtime.openings.asScala.toVector, Vector.fill(2)((id(1), config(steps = 5))))
      }
      assertEquals(runtime.calls.asScala.toVector.drop(4), Vector("opened", "close the opened part"))
    }
  }

  test("Host: a reopen whose journal has a waiting message calls opened before the activation of its turn") {
    withRoot { root =>
      // The message m2 came during a turn, and then the host crashed. The repair ends that turn and keeps m2 waiting,
      // so the Activate of the reopen starts a turn for m2 at once, and a worker makes its activation.
      val crashed = Support.logOf(
        1000L,
        Entry.Activated(Support.versions, config(steps = 5)),
        Entry.Input(RequestId("m1"), "x", Map.empty),
        Entry.TurnStarted,
        Entry.StepStarted(0),
        Entry.Input(RequestId("m2"), "y", Map.empty)
      )
      val file = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
      assertEquals(file.append(crashed, flush = true), Right(()))
      file.close()
      // A slow opened: with opened after the Activate, the worker would call activate first.
      val runtime = OpenedRuntime(slowMillis = 300L)
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1), None)
        assert(
          eventually()(outcomes(handle) == Vector(TurnOutcome.Crashed, TurnOutcome.Replied("hi"))),
          entries(handle).toString
        )
        assertEquals(runtime.calls.asScala.toVector, Vector("opened", "activate"))
        assertEquals(runtime.openings.asScala.toVector, Vector((id(1), config(steps = 5))))
      }
    }
  }

  test("Host: an open whose opened throws gives OpenFailed with the message, commits nothing, and frees the lock") {
    withRoot { root =>
      val failing = new SessionRuntime:
        def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
          throw IllegalStateException("FAKE-NO-ACTIVATION")
        override def opened(session: SessionId, config: SessionConfig): AutoCloseable =
          throw IllegalStateException("FAKE-NO-OPEN")
      withHost(root, failing) { host =>
        host.open(id(1), Some(config())) match
          case Left(HostError.OpenFailed(reason)) => assert(reason.contains("FAKE-NO-OPEN"), reason)
          case other                              => fail(s"not OpenFailed: $other")
        // The first host stays, so a second host gets the lock only when the failed open freed it.
        val runtime = OpenedRuntime()
        withHost(root, runtime) { second =>
          val handle = opened(second, id(1), Some(config()))
          entries(handle) match
            case Vector(Entry.Activated(_, settings)) => assertEquals(settings, config())
            case other                                => fail(s"not one Activated: $other")
          assertEquals(runtime.calls.asScala.toVector, Vector("opened"))
        }
      }
    }
  }

  test("Host: open of a new id with no configuration gives OpenFailed") {
    withRoot(root => withHost(root, scripted(answer("hi")))(host => assert(openFailed(host.open(id(1), None)))))
  }

  test("Host: a message starts a turn, a worker runs it, and the reply ends it Replied") {
    withRoot { root =>
      withHost(root, scripted(code("1 + 1"), answer("FAKE-REPLY"))) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1", "hello")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-REPLY"))), entries(handle).toString)
        assert(Support.isTurn(lastTurn(handle).drop(1)), lastTurn(handle).toString)
        assertEquals(handle.state.position, Position.NoTurn)
      }
    }
  }

  test("Host: open after a crash in a turn commits the closing entries and TurnEnded(Crashed), and runs nothing") {
    withRoot { root =>
      val crashed = Support.logOf(
        1000L,
        Entry.Activated(Support.versions, config()),
        Entry.Input(RequestId("m1"), "x", Map.empty),
        Entry.TurnStarted,
        Entry.StepStarted(0),
        Entry.Acted("", "", Some("exec(\"make\")")),
        Entry.EffectStarted(EffectId(4L, 0), "exec", Json.Null)
      )
      val file = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
      assertEquals(file.append(crashed, flush = true), Right(()))
      file.close()
      val script = Support.Script(Vector(answer("never")))
      withHost(root, ScriptRuntime(_ => script)) { host =>
        val handle = opened(host, id(1), None)
        val after  = entries(handle).drop(crashed.size)
        assertEquals(after.take(3).last, Entry.TurnEnded(TurnOutcome.Crashed))
        assert(after.contains(Entry.EffectDone(EffectId(4L, 0), EffectResult.Unknown)))
        assert(after.exists { case Entry.Activated(_, _) => true; case _ => false })
        assert(script.ran.isEmpty && script.mustAnswer.isEmpty)
      }
    }
  }

  test("Host: a new host opens the session from its journal with a new activation, and the store comes back") {
    withRoot { root =>
      val write   = CellResult("", CellOutcome.Ok, Map("k" -> Json.Number(BigDecimal(7))))
      val runtime = ScriptRuntime(_ => Support.Script(Vector(code("store(k, 7)"), answer("done")), Vector(write)))
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(outcomes(handle).nonEmpty))
      }
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1), None)
        assertEquals(handle.state.store, Map("k" -> Json.Number(BigDecimal(7))))
        // The live part of an activation comes at the first turn that needs it.
        assertEquals(handle.send(message("m2")), Right(()))
        assert(eventually()(outcomes(handle).size == 2), entries(handle).toString)
        assertEquals(runtime.activations.get, 2)
      }
    }
  }

  test("Host: a reopen with no configuration keeps the configuration of the journal, also after Close") {
    withRoot { root =>
      withHost(root, scripted(answer("hi"))) { host =>
        val first = opened(host, id(1), Some(config(steps = 5)))
        assertEquals(first.run(Command.Close(SessionOutcome.Parked, "FAKE")).map(_.closed), Right(true))
        val again = opened(host, id(1), None)
        assertEquals(again.state.config, config(steps = 5))
        assert(!again.state.closed)
        assertEquals(again.send(message("m1")), Right(()))
        assert(eventually()(outcomes(again) == Vector(TurnOutcome.Replied("hi"))), entries(again).toString)
      }
    }
  }

  test("Host: a reopen with a mode above the ceiling of the session gives OpenFailed") {
    withRoot { root =>
      withHost(root, scripted(answer("hi")))(host => opened(host, id(1)): Unit)
      withHost(root, scripted(answer("hi"))) { host =>
        assert(openFailed(host.open(id(1), Some(config().copy(mode = Mode.full, ceiling = Mode.full)))))
      }
    }
  }

  test("Host: a second host on the same root gets OpenFailed for an open session, and opens another session") {
    withRoot { root =>
      withHost(root, scripted(answer("one"))) { first =>
        opened(first, id(1)): Unit
        withHost(root, scripted(answer("two"))) { second =>
          assert(openFailed(second.open(id(1), None)))
          val other = opened(second, id(2))
          assertEquals(other.send(message("m1")), Right(()))
          assert(eventually()(outcomes(other) == Vector(TurnOutcome.Replied("two"))))
        }
      }
    }
  }

  test("Host: a file that is not a journal does not stop the start, its session gives OpenFailed, and others open") {
    withRoot { root =>
      Files.createDirectories(root.resolve("sessions")): Unit
      Files.writeString(root.resolve("sessions").resolve("fake-session-9.db"), "FAKE-NOT-SQLITE"): Unit
      withHost(root, scripted(answer("hi"))) { host =>
        assertEquals(host.failed.map(_._1.getFileName.toString), Vector("fake-session-9.db"))
        assert(openFailed(host.open(id(9), None)))
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(outcomes(handle).nonEmpty))
      }
    }
  }

  test("Host: two sessions have two files, and the end of one does not stop the other") {
    withRoot { root =>
      withHost(root, scripted(answer("hi"))) { host =>
        val one = opened(host, id(1))
        val two = opened(host, id(2))
        assertEquals(one.send(Command.Close(SessionOutcome.Parked, "FAKE")), Right(()))
        assert(eventually()(one.state.closed))
        assertEquals(one.send(message("late")), Left(HostError.Ended))
        assertEquals(two.send(message("m1")), Right(()))
        assert(eventually()(outcomes(two) == Vector(TurnOutcome.Replied("hi"))))
        assert(Files.exists(SessionFile.pathOf(root, id(2))))
      }
    }
  }

  test("Host: list gives the row of each session, after a turn ends") {
    withRoot { root =>
      withHost(root, scripted(answer("hi"))) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        // The row follows each commit, so the law waits for the end of the turn first.
        assert(eventually()(outcomes(handle).nonEmpty))
        assert(eventually()(host.list.exists(r => r.session == id(1) && r.lastSeq == handle.state.nextSeq - 1)))
        assertEquals(host.list.map(_.status), Vector(SessionStatus.Idle))
      }
    }
  }

  test("Host: run gives a refusal of decide to the client, and the session goes on") {
    withRoot { root =>
      val released = CountDownLatch(1)
      val runtime  = ScriptRuntime(_ =>
        Support.Script(
          Vector(code("wait()"), answer("FAKE-DONE")),
          during = (_, _) =>
            released.await(30, TimeUnit.SECONDS): Unit
        )
      )
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(inCell(handle)))
        assertEquals(
          handle.run(Command.Close(SessionOutcome.Parked, "FAKE")).map(_ => ()),
          Left(HostError.Rejected(Rejection.TurnOpen))
        )
        released.countDown()
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-DONE"))))
      }
    }
  }

  test("Host: two messages during a slow activation give one activation, and no two turns run at the same time") {
    withRoot { root =>
      // The activation takes half a second, as a REPL that starts.
      val runtime = ScriptRuntime { _ =>
        Thread.sleep(500)
        Support.Script(Vector(answer("FAKE-ONE"), answer("FAKE-TWO")))
      }
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assertEquals(handle.send(message("m2")), Right(()))
        assert(eventually()(handle.state.position == Position.NoTurn && outcomes(handle).nonEmpty))
        Thread.sleep(300)
        assertEquals(runtime.activations.get, 1)
        assert(Support.isTurn(lastTurn(handle).drop(1)), lastTurn(handle).toString)
      }
    }
  }

  test("Host: an activation that fails ends its turn Failed once, and the next message tries again") {
    withRoot { root =>
      val runtime = ScriptRuntime(_ => throw IllegalStateException("FAKE-NO-REPL"))
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(outcomes(handle).nonEmpty), entries(handle).toString)
        Thread.sleep(500)
        assertEquals(runtime.activations.get, 1, "the failed activation started again")
        outcomes(handle) match
          case Vector(TurnOutcome.Failed(reason)) => assert(reason.contains("FAKE-NO-REPL"), reason)
          case other                              => fail(s"not one Failed turn: $other")
        assertEquals(handle.state.position, Position.NoTurn)
        assertEquals(handle.send(message("m2")), Right(()))
        assert(eventually()(outcomes(handle).size == 2), entries(handle).toString)
        assertEquals(runtime.activations.get, 2)
      }
    }
  }

  test("Host: after a failed write the session ends, its lock goes, and an open in the same host works at once") {
    withRoot { root =>
      val runtime = scripted(answer("hi"))
      withHost(root, runtime) { host =>
        val first = opened(host, id(1))
        host.sessionOf(id(1)).foreach(_.breakWritesForLaws())
        assert(first.send(message("m1")).isRight)
        assert(eventually()(first.send(message("m2")).isLeft), "the session takes commands after a failed write")
        val start = System.nanoTime()
        val again = host.open(id(1), None)
        assert((System.nanoTime() - start) / 1_000_000L < 5_000L, "the open waited for the failed session")
        assert(again.isRight, again.toString)
      }
    }
  }

  test("Host: close of the host closes each activation, and a later commit starts no worker") {
    withRoot { root =>
      val runtime = scripted(answer("hi"))
      val host    = LocalHost.start(root, runtime, dspy4s.harness.Secrets.none).fold(e => fail(e.toString), identity)
      val handle  = opened(host, id(1))
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually()(outcomes(handle).nonEmpty))
      host.close()
      assertEquals(runtime.closes.get, runtime.activations.get)
      assertEquals(handle.send(message("m2")), Left(HostError.Ended))
      Thread.sleep(300)
      assertEquals(runtime.activations.get, 1)
    }
  }

  // ── The hard stop (10.3) ──

  test("Hard stop: Abort commits StopRequested, stops the activation, and the turn ends Aborted after the cell ends") {
    withRoot { root =>
      val released = CountDownLatch(1)
      val runtime  = ScriptRuntime(
        _ => Support.Script(Vector(code("loop()")), during = (_, _) => released.await(30, TimeUnit.SECONDS): Unit),
        onStop = () => released.countDown()
      )
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(inCell(handle)))
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
        assertEquals(runtime.stops.get, 1)
        assert(lastTurn(handle).contains(Entry.StopRequested("FAKE-STOP")))
        assert(Support.isTurn(lastTurn(handle).drop(1)), lastTurn(handle).toString)
      }
    }
  }

  test(
    "Hard stop: a cell that does not end in the grace period: the writer fences the activation and closes the turn, " +
      "a late commit of that activation does not reach the next turn, and the next turn gets a new activation"
  ) {
    withRoot { root =>
      val stuck   = CountDownLatch(1)
      val tried   = CountDownLatch(1)
      val second  = CountDownLatch(1)
      val made    = AtomicInteger(0)
      val runtime = ScriptRuntime { journal =>
        val n = made.incrementAndGet()
        Support.Script(
          Vector(code("cell()"), answer("FAKE-NEXT")),
          during = (_, _) =>
            if n == 1 then
              stuck.await(30, TimeUnit.SECONDS): Unit
              // The old activation commits through its own journal, as `HarnessEffects` does after an unclean cell.
              scala.util.Try(journal.run(Command.Abort("FAKE-LATE"), Source.Harness)): Unit
              tried.countDown()
            else second.await(30, TimeUnit.SECONDS): Unit
        )
      }
      withHost(root, runtime, HostLimits(grace = 300.millis)) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(inCell(handle)))
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
        assertEquals(handle.send(message("m2")), Right(()))
        assert(eventually()(inCell(handle) && runtime.activations.get == 2), entries(handle).toString)
        stuck.countDown()
        assert(tried.await(10, TimeUnit.SECONDS))
        second.countDown()
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted, TurnOutcome.Replied("FAKE-NEXT"))))
        assert(!lastTurn(handle).contains(Entry.StopRequested("FAKE-LATE")), lastTurn(handle).toString)
      }
    }
  }

  test("Hard stop: a stop while the activation starts gets its grace timer, and the turn ends Aborted in time") {
    withRoot { root =>
      // The activation takes 3 seconds, as a REPL that is slow to start.
      val runtime = ScriptRuntime { _ =>
        Thread.sleep(3_000)
        Support.Script(Vector(answer("never")))
      }
      withHost(root, runtime, HostLimits(grace = 300.millis)) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(runtime.activations.get == 1))
        val start = System.nanoTime()
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
        assert((System.nanoTime() - start) / 1_000_000L < 2_000L, "the turn waited for the activation")
      }
    }
  }

  test("Hard stop: the grace timer of a stop does not touch a later turn") {
    withRoot { root =>
      val released = CountDownLatch(1)
      val runtime  = ScriptRuntime(
        _ =>
          Support.Script(
            Vector(code("first"), code("second"), answer("FAKE-LATER")),
            during = (_, source) =>
              if source == "first" then released.await(30, TimeUnit.SECONDS): Unit
              else Thread.sleep(1_500)
          ),
        onStop = () => released.countDown()
      )
      withHost(root, runtime, HostLimits(grace = 500.millis)) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(inCell(handle)))
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assertEquals(handle.send(message("m2")), Right(()))
        assert(
          eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted, TurnOutcome.Replied("FAKE-LATER"))),
          entries(handle).toString
        )
      }
    }
  }
