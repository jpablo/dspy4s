package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{CellResult, GoalLeaf, HarnessLimits, Journal, Place, PolicyReply, RequestLm, Secrets, Support}
import dspy4s.harness.TurnEffects
import dspy4s.harness.Models.policy
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.HostList

import java.nio.file.Path
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** The host laws of the goal loop (CODE_HARNESS_DESIGN.md, 8.5): a loop of a work session that goes on with no message
  * of the person, the end of a loop at a restart, the pool of `start`, the end of the loops that a session gave, the
  * card, and the task.
  */
class GoalHostLaws extends munit.FunSuite:

  // The laws with a real host start real REPLs.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3, "min")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val limits = HarnessLimits(steps = 3)

  /** A reply of the goal leaf in the format of the chat adapter. */
  private def goalReply(met: Boolean, reason: String, blocker: String = ""): String =
    s"[[ ## met ## ]]\n$met\n\n[[ ## reason ## ]]\n$reason\n\n[[ ## blocker ## ]]\n$blocker\n\n" +
      "[[ ## same ## ]]\nfalse\n\n[[ ## completed ## ]]"

  private def isInput(entry: Entry): Boolean =
    entry match
      case Entry.Input(_, _, _) => true
      case _                    => false

  /** Runs `check` with a host whose runtime is `WorkRuntime.forHost` with `model`, at a new root, with a folder. */
  private def withWork(model: RequestLm)(check: (LocalHost, Path) => Unit): Unit =
    val place = Place()
    try withRoot { root =>
        val env  = WorkEnv(place.home, HostList.empty, None, Secrets.none, root)
        val work = place.base.resolve("work")
        withPeers(root, env, model, limits, work = Some(work))(host => check(host, place.root))
      }
    finally place.close()

  test("Goal host: a session with a loop goes on for more than 8 turns with no message of the person, until met") {
    val checks = AtomicInteger(0)
    val model  = RequestLm { text =>
      if isSummary(text) then Right(summaryReply(text))
      else if text.contains(GoalLeaf.instructions) then
        val n = checks.incrementAndGet()
        Right(goalReply(met = n >= 11, s"FAKE-CHECK-$n"))
      else Right(policy("FAKE-WORK", None))
    }
    withWork(model) { (host, folder) =>
      val config  = WorkRuntime.config(folder, "fake-model", limits).copy(goal = Some("FAKE-GOAL"))
      val session = opened(host, id(1), Some(config))
      session.run(Command.StartLoop(50)).left.foreach(e => fail(e.toString))
      assert(eventually(120)(session.state.loopEnd.isDefined), session.state.toString)
      assertEquals(session.state.loopEnd, Some(LoopEnd.Met("FAKE-CHECK-11")))
      val log = session.read(-1)
      assertEquals(log.count(_.entry == Entry.TurnStarted), 10)
      assertEquals(log.count(l => l.meta.source == Source.User && isInput(l.entry)), 0)
      // The task of each step gives the goal and the rule of the loop.
      val asked = model.requests.asScala.toVector.map(_.messages.flatMap(_.text).mkString("\n"))
        .filter(_.contains(WorkRuntime.instructions))
        .filterNot(text => isSummary(text) || text.contains(GoalLeaf.instructions))
      assert(asked.nonEmpty)
      assert(asked.forall(t => t.contains("The goal of the session: FAKE-GOAL") && t.contains(WorkRuntime.LoopRule)))
      assert(eventually()(host.rows.find(_.session == id(1)).exists(_.loop == "met")), host.rows.toString)
    }
  }

  test("Goal host: a restart of the host ends the loop with Restarted, and the card says so") {
    withRoot { root =>
      withHost(root, ScriptRuntime(_ => Support.Script(Vector.empty))) { host =>
        val session = opened(host, id(1))
        session.run(Command.StartLoop(5)).left.foreach(e => fail(e.toString))
        assertEquals(session.state.loop.map(_.limit), Some(5))
        assert(eventually()(host.rows.find(_.session == id(1)).exists(_.loop == "on 0/5")), host.rows.toString)
      }
      withHost(root, ScriptRuntime(_ => Support.Script(Vector.empty))) { host =>
        val session = opened(host, id(1), None)
        assertEquals((session.state.loop, session.state.loopEnd), (None, Some(LoopEnd.Restarted)))
        assert(eventually()(host.rows.find(_.session == id(1)).exists(_.loop == "restarted")), host.rows.toString)
        // The person gives the permission again.
        session.run(Command.StartLoop(7)).left.foreach(e => fail(e.toString))
        assertEquals(session.state.loop.map(_.limit), Some(7))
      }
    }
  }

  test("Goal host: start refuses a loop above the turns left, and /goal off of the controller ends the loops it gave") {
    val release = CountDownLatch(1)
    val turns   = AtomicInteger(0)
    val tried   = """println(start("FAKE-CHILD-GOAL", Scope.Scratch(""), StrategyName.Window, 9).id)"""
    val gives   = """println(start("FAKE-CHILD-GOAL", Scope.Scratch(""), StrategyName.Window, 3).id)"""
    val steps   = Vector(policy("try", Some(tried)), policy("again", Some(gives)), policy("Started.", None))
    val model   = RequestLm { text =>
      if isSummary(text) then Right(summaryReply(text))
      else if text.contains(GoalLeaf.instructions) then
        // The check of the child waits, so its loop is on when the person ends the loop of the controller.
        release.await(60, TimeUnit.SECONDS): Unit
        Right(goalReply(met = false, "FAKE-NOT-YET"))
      else if text.contains(Controller.instructions) then
        Right(steps.lift(turns.getAndIncrement()).getOrElse(policy("Noted.", None)))
      else if text.contains("FAKE-CHILD-GOAL") then Right(policy("FAKE-CHILD-WORK", None))
      else Left("no route takes this request")
    }
    val place = Place()
    try
      withRoot { root =>
        val env  = WorkEnv(place.home, HostList.empty, None, Secrets.none, root)
        val work = place.base.resolve("work")
        withPeers(root, env, model, limits, work = Some(work)) { host =>
          val controller = opened(host, Controller.id, Some(Controller.config(place.home, "fake-model", limits)))
          controller.run(Command.StartLoop(5)).left.foreach(e => fail(e.toString))
          controller.send(message("m1", "start the work")).left.foreach(e => fail(e.toString))
          assert(eventually(120)(host.rows.exists(_.owner == Owner.Session(Controller.id))), host.rows.toString)
          val child = host.rows.find(_.owner == Owner.Session(Controller.id)).getOrElse(fail("no child")).session
          // The first start asked for 9 turns, and the loop of the controller had 5.
          val failed = controller.read(-1).collect { case Logged(_, _, Entry.Observed(_, CellOutcome.Failed(e), _)) =>
            e
          }
          assert(failed.exists(_.contains("more than the 5 turns")), failed.toString)
          val begun = host.journalOf(child).fold(e => fail(e), identity).take(4).map(_.entry)
          assertEquals(begun(1), Entry.GoalLoopStarted(3))
          assert(eventually(60)(controller.state.loop.exists(_.gave == 3)), controller.state.loop.toString)
          assert(eventually(60)(host.sessionOf(child).exists(_.state.loop.isDefined)))
          // The person ends the loop of the controller, and the host ends the loop of the child.
          controller.run(Command.StopLoop).left.foreach(e => fail(e.toString))
          assert(
            eventually(60)(host.sessionOf(child).exists(_.state.loopEnd.contains(LoopEnd.Off))),
            host.journalOf(child).toString
          )
          release.countDown()
          val ended = host.journalOf(child).fold(e => fail(e), identity).collect {
            case Logged(_, meta, Entry.GoalLoopEnded(why)) => (meta.source, why)
          }
          assertEquals(ended, Vector(Source.Controller -> LoopEnd.Off))
          assert(eventually()(host.rows.find(_.session == child).exists(_.loop == "off")), host.rows.toString)
        }
      }
    finally
      release.countDown()
      place.close()
  }

  test("Goal host: a close of the host during a loop turn commits no goal check and no goal message") {
    // The close of a REPL takes time, and it makes the call of the policy fail. During that time, the worker of the
    // closing activation must commit nothing. Else the failed call ends the turn, the check fails at once, and a goal
    // message comes before the writer ends. The reopen then repairs the turn of that message.
    def isCheck(entry: Entry): Boolean =
      entry match
        case Entry.GoalChecked(_) => true
        case _                    => false
    def isGoalMessage(entry: Entry): Boolean =
      entry match
        case Entry.Input(request, _, _) => request.value.startsWith("goal:")
        case _                          => false
    def slowClose(closing: CountDownLatch): SessionRuntime =
      new SessionRuntime:
        def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
          val _ = (session, config, jobs)
          new Activation:
            val effects: TurnEffects = new TurnEffects:
              def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply] =
                val _ = mustAnswer
                closing.await(60, TimeUnit.SECONDS): Unit
                Left("FAKE-CLOSED")
              def runCell(cell: Long, code: String): CellResult =
                val _ = (cell, code)
                Support.done
            def stop(): Unit     = ()
            def unclean: Boolean = false
            def close(): Unit    =
              closing.countDown()
              // A worker that commits during the close commits a second check in this time.
              eventually(3)(journal.log.count(l => isCheck(l.entry)) > 1): Unit
    withRoot { root =>
      val goal = config().copy(goal = Some("FAKE-GOAL"))
      withHost(root, slowClose(CountDownLatch(1))) { host =>
        val session = opened(host, id(1), Some(goal))
        session.run(Command.StartLoop(5)).left.foreach(e => fail(e.toString))
        // The first check fails (this runtime has no goal leaf), so a goal message starts a turn, and its call waits.
        assert(eventually()(entries(session).contains(Entry.StepStarted(0))), entries(session).toString)
      }
      val open = CountDownLatch(0)
      withHost(root, slowClose(open)) { host =>
        val session = opened(host, id(1), None)
        val log     = entries(session)
        assertEquals(log.count(isCheck), 1, log.toString)
        assertEquals(log.count(isGoalMessage), 1, log.toString)
        // The close of the host is a crash for the turn: the reopen ends it, and no turn starts.
        assertEquals(outcomes(session), Vector(TurnOutcome.Crashed), log.toString)
        assertEquals(session.state.loopEnd, Some(LoopEnd.Restarted))
      }
    }
  }

  test("Goal host: loopFault refuses a negative loop and a loop above the turns left") {
    assert(HostSessions.loopFault(-1, 5).nonEmpty)
    assert(HostSessions.loopFault(6, 5).exists(_.contains("more than the 5 turns")))
    assertEquals(HostSessions.loopFault(5, 5), None)
    assertEquals(HostSessions.loopFault(0, 0), None)
  }

  test("Goal host: the card gives the loop of its row, and the task gives the goal and the rule of the loop") {
    val row =
      CatalogRow(id(1), Owner.User, None, SessionStatus.Idle, 3L, "/w", 0L, "", Some("g"), None, loop = "on 3/9")
    assertEquals(HostSessions.cardOf(row).loop, "on 3/9")
    val scope = Path.of("/tmp/fake-scope")
    val mine  = config().copy(goal = Some("FAKE-GOAL"))
    val plain = WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL")
    assert(plain.contains("The goal of the session: FAKE-GOAL"), plain)
    assert(!plain.contains(WorkRuntime.LoopRule), plain)
    assert(WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL", looping = true).contains(WorkRuntime.LoopRule))
    val owned = mine.copy(owner = Owner.Session(Controller.id))
    val loop  = WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", looping = true)
    assert(loop.contains("a report when the goal loop of this session ends") && loop.contains("submit("), loop)
  }
