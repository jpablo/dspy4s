package dspy4s.harness.host

import dspy4s.harness.{PolicyReply, Secrets, Support}
import dspy4s.harness.Support.answer
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.util.concurrent.{CountDownLatch, TimeUnit}

/** The laws of the report pass of a host (CODE_HARNESS_DESIGN.md, 8.6 and 8.7), with scripted effects. The kernel laws
  * `Report:` cover `reports` and `missing`. These laws cover the sends of a host: the live report, the pass at the open
  * of an owner, and the repair at the start of a host.
  */
class ReportPassLaws extends munit.FunSuite:

  /** A runtime whose turns each end with a reply. */
  private def replying: ScriptRuntime = ScriptRuntime(_ => Support.Script(Vector.fill(8)(answer("done"))))

  /** A work session whose owner is the session `owner`. */
  private def ownedBy(owner: SessionId): SessionConfig = config().copy(owner = Owner.Session(owner))

  /** A message of the owner `owner`, which opens the debt of a report (8.6). */
  private def fromOwner(host: LocalHost, session: SessionId, owner: SessionId, request: String): Unit =
    host.runAs(session, Command.Send(RequestId(request), "fake work", Map.empty), Controller.messageSource(owner))
      .left.foreach(e => fail(e.toString))

  /** The reports in the journal of an owner: each `Input` from a child. */
  private def reportsOf(owner: SessionHandle): Vector[Logged] =
    owner.read(-1).filter {
      case Logged(_, meta, Entry.Input(_, _, _)) => meta.source match
          case Source.Child(_) => true
          case _               => false
      case _ => false
    }

  test(
    "Report pass: after a turn of a session whose owner is open, the owner commits the report, and it starts a turn"
  ) {
    withRoot { root =>
      withHost(root, replying) { host =>
        val owner = opened(host, id(1))
        val child = opened(host, id(2), Some(ownedBy(id(1))))
        fromOwner(host, id(2), id(1), "m1")
        assert(eventually()(outcomes(child).size == 1), entries(child).toString)
        assert(eventually()(reportsOf(owner).size == 1), entries(owner).toString)
        val report = reportsOf(owner).head
        assertEquals(report.meta.source, Source.Child(id(2)))
        report.entry match
          case Entry.Input(request, text, _) =>
            assert(request.value.startsWith(s"report:${id(2).value}:"), request.value)
            assert(text.contains("done"), text)
          case other => fail(other.toString)
        assert(eventually()(outcomes(owner).size == 1), entries(owner).toString)
      }
    }
  }

  test("Report pass: a turn that only the person started gives no report") {
    withRoot { root =>
      withHost(root, replying) { host =>
        val owner = opened(host, id(1))
        val child = opened(host, id(2), Some(ownedBy(id(1))))
        child.send(message("m1")).left.foreach(e => fail(e.toString))
        assert(eventually()(outcomes(child).size == 1))
        Thread.sleep(300)
        assertEquals(reportsOf(owner), Vector.empty)
      }
    }
  }

  test("Report pass: an owner that was not open gets the missing reports when it opens, once") {
    withRoot { root =>
      withHost(root, replying) { host =>
        val child = opened(host, id(2), Some(ownedBy(id(1))))
        fromOwner(host, id(2), id(1), "m1")
        assert(eventually()(outcomes(child).size == 1))
        // The owner is new, so it has no journal yet: its open makes it and runs the pass.
        val owner = opened(host, id(1))
        assert(eventually()(reportsOf(owner).size == 1), entries(owner).toString)
        assertEquals(host.reportTo(id(1)), Right(0))
      }
      withHost(root, replying) { host =>
        val owner = opened(host, id(1), None)
        Thread.sleep(300)
        assertEquals(reportsOf(owner).size, 1)
      }
    }
  }

  test("Report pass: at the start of a host, a session with a session owner and an open turn gets TurnEnded(Crashed)") {
    withRoot { root =>
      val inCell = CountDownLatch(1)
      val hold   = CountDownLatch(1)
      val stuck  = ScriptRuntime(_ =>
        Support.Script(
          Vector(Right(PolicyReply("", "", Some("work()")))),
          during = (_, _) =>
            inCell.countDown()
            hold.await(30, TimeUnit.SECONDS): Unit
        )
      )
      withHost(root, stuck) { host =>
        opened(host, id(2), Some(ownedBy(id(1)))): Unit
        fromOwner(host, id(2), id(1), "m1")
        assert(inCell.await(10, TimeUnit.SECONDS), "the cell did not start")
      }
      hold.countDown()
      withHost(root, replying) { host =>
        // The start of the host repaired the open turn, before any open of a client.
        val child = host.journalOf(id(2)).fold(e => fail(e), identity).map(_.entry)
        assert(child.contains(Entry.TurnEnded(TurnOutcome.Crashed)), child.toString)
        val owner = opened(host, id(1))
        assert(eventually()(reportsOf(owner).exists(_.entry.toString.contains("Crashed"))), entries(owner).toString)
      }
    }
  }

  test("Report pass: a session whose owner has ended keeps its report until the owner opens again") {
    withRoot { root =>
      withHost(root, replying) { host =>
        val owner = opened(host, id(1))
        owner.run(Command.Close(SessionOutcome.Parked, "fake end")).left.foreach(e => fail(e.toString))
        val child = opened(host, id(2), Some(ownedBy(id(1))))
        fromOwner(host, id(2), id(1), "m1")
        assert(eventually()(outcomes(child).size == 1))
        assertEquals(host.reportTo(id(1)), Right(0))
        val again = opened(host, id(1), None)
        assert(eventually()(reportsOf(again).size == 1), entries(again).toString)
      }
    }
  }

  test("Report pass: a report that waits stops neither the writer of its session nor the close of the host (8.7)") {
    withRoot { root =>
      val inHook = CountDownLatch(1)
      val hold   = CountDownLatch(1)
      val hooks  = HostHooks(beforeReport = (_, _) =>
        inHook.countDown()
        hold.await(30, TimeUnit.SECONDS): Unit)
      val host =
        LocalHost.startWith(root, _ => replying, Secrets.none, hooks = hooks).fold(e => fail(e.toString), identity)
      try
        opened(host, id(1)): Unit
        val child = opened(host, id(2), Some(ownedBy(id(1))))
        fromOwner(host, id(2), id(1), "m1")
        assert(inHook.await(10, TimeUnit.SECONDS), "no report started")
        // The writer of the session still commits while its report waits.
        assert(child.run(Command.Abort("fake stop")).isRight)
        val closer = Thread.ofPlatform().start(() => host.close())
        closer.join(10_000)
        assert(!closer.isAlive, "the close of the host waits for a report")
      finally
        hold.countDown()
        host.close()
    }
  }
