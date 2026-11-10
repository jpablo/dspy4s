package dspy4s.harness.host

import dspy4s.harness.{CellResult, Support}
import dspy4s.harness.Support.{answer, code}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import zio.blocks.schema.json.Json

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/** The laws of the first session host (CODE_HARNESS_DESIGN.md, 10.2 and 10.3), with scripted effects. */
class HostLaws extends munit.FunSuite:

  /** The entries of the last turn of a handle, from its `TurnStarted`. */
  private def lastTurn(handle: SessionHandle): Vector[Entry] =
    val all = entries(handle)
    all.drop(all.lastIndexOf(Entry.TurnStarted))

  test("Host: open of a new id makes its file and commits Activated, and a second open gives the same handle") {
    withRoot { root =>
      withHost(root, ScriptRuntime(() => Support.Script(Vector(answer("hi"))))) { host =>
        val first = opened(host, id(1))
        assert(Files.exists(SessionFile.pathOf(root, id(1))))
        entries(first) match
          case Vector(Entry.Activated(_, settings)) => assertEquals(settings, config())
          case other                                => fail(s"not one Activated: $other")
        assert(opened(host, id(1)) eq first)
      }
    }
  }

  test("Host: a message starts a turn, a worker runs it, and the reply ends it Replied") {
    withRoot { root =>
      withHost(root, ScriptRuntime(() => Support.Script(Vector(code("1 + 1"), answer("FAKE-REPLY"))))) { host =>
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
      withHost(root, ScriptRuntime(() => script)) { host =>
        val handle = opened(host, id(1))
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
      val runtime = ScriptRuntime(() => Support.Script(Vector(code("store(k, 7)"), answer("done")), Vector(write)))
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(outcomes(handle).nonEmpty))
      }
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.state.store, Map("k" -> Json.Number(BigDecimal(7))))
        assertEquals(runtime.activations.get, 2)
      }
    }
  }

  test("Host: two sessions have two files, and the end of one does not stop the other") {
    withRoot { root =>
      withHost(root, ScriptRuntime(() => Support.Script(Vector(answer("hi"))))) { host =>
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
      withHost(root, ScriptRuntime(() => Support.Script(Vector(answer("hi"))))) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually()(host.list.exists(r => r.session == id(1) && r.lastSeq == handle.state.nextSeq - 1)))
        assertEquals(host.list.map(_.status), Vector(SessionStatus.Idle))
      }
    }
  }

  test("Hard stop: Abort commits StopRequested, stops the activation, and the turn ends Aborted after the cell ends") {
    withRoot { root =>
      val released = CountDownLatch(1)
      val runtime  = ScriptRuntime(
        () => Support.Script(Vector(code("loop()")), during = (_, _) => released.await(30, TimeUnit.SECONDS): Unit),
        onStop = () => released.countDown()
      )
      withHost(root, runtime) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually() {
          handle.state.position match
            case Position.InCell(_, _, _) => true
            case _                        => false
        })
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
        assertEquals(runtime.stops.get, 1)
        assert(lastTurn(handle).contains(Entry.StopRequested("FAKE-STOP")))
        assert(Support.isTurn(lastTurn(handle).drop(1)), lastTurn(handle).toString)
      }
    }
  }

  test(
    "Hard stop: a cell that does not end in the grace period: the writer fences the worker, commits the closing entries " +
      "and TurnEnded(Aborted), refuses the late commit, and the next turn gets a new activation"
  ) {
    withRoot { root =>
      val released = CountDownLatch(1)
      val runtime  = ScriptRuntime(() =>
        Support.Script(
          Vector(code("stuck()"), answer("next")),
          during = (_, _) =>
            released.await(30, TimeUnit.SECONDS): Unit
        )
      )
      withHost(root, runtime, HostLimits(grace = 300.millis)) { host =>
        val handle = opened(host, id(1))
        assertEquals(handle.send(message("m1")), Right(()))
        assert(eventually() {
          handle.state.position match
            case Position.InCell(_, _, _) => true
            case _                        => false
        })
        assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
        assert(eventually()(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
        val size = entries(handle).size
        released.countDown()
        Thread.sleep(300)
        assertEquals(entries(handle).size, size, "the late commit of the stuck worker went in")
        assertEquals(handle.send(message("m2")), Right(()))
        assert(eventually()(outcomes(handle).size == 2))
        assertEquals(runtime.activations.get, 2)
      }
    }
  }
