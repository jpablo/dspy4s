package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, Models, Place, PolicyRunner, RequestLm, Secrets}
import dspy4s.harness.Models.policy
import dspy4s.harness.guest.{Mode, Scope, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

/** The laws of a work session (CODE_HARNESS_DESIGN.md, 8.1, 5.5 and 10.3): a host with a `WorkRuntime`, a real REPL,
  * the sandbox of this machine and a scripted model.
  */
class WorkLaws extends munit.FunSuite:

  // A work session starts a real REPL, and a turn compiles cells.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3, "min")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val limits = HarnessLimits(steps = 3)

  /** An `Ask` rule for each call of `op`. */
  private def ask(op: String): Guard = Guard(Vector(VerdictRule(GuardRule(op, ArgMatch.AnyArgs), Verdict.Ask)))

  private def command(text: String): Json = Json.Object(Chunk(("command", Json.String(text))))

  /** Runs a work session in a new place with `model`, and gives the folder of the scope and the handle to `check`. */
  private def work(model: LanguageModel, guard: Option[Guard] = None, settings: HarnessLimits = limits)(
      check: (Path, SessionHandle) => Unit
  ): Unit =
    val place = Place()
    try withRoot { root =>
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val runtime = Models.withModel(model)(WorkRuntime.forWork(env, settings))
        val base    = WorkRuntime.config(place.root, "fake-model", settings)
        withHost(root, runtime) { host =>
          check(place.root, opened(host, id(1), Some(guard.fold(base)(g => base.copy(guard = g)))))
        }
      }
    finally place.close()

  /** The outputs of the cells of a handle. */
  private def outputs(handle: SessionHandle): Vector[String] =
    entries(handle).collect { case Entry.Observed(output, _, _) => output }

  private def sandboxed(): Unit = assume(Backends.detect().nonEmpty, "no OS sandbox works on this host")

  // ── The configuration ──

  test("Work: config gives a chat turn of the user in workMode, the memory tree and the default guard") {
    val scope  = Path.of("/tmp/fake-scope")
    val config = WorkRuntime.config(scope, "fake-model", limits)
    assertEquals(config.kind, TurnKind.Chat)
    assertEquals(config.steps, limits.steps)
    assertEquals(config.owner, Owner.User)
    assertEquals((config.mode, config.ceiling), (WorkRuntime.workMode, WorkRuntime.workMode))
    assertEquals(config.scope, Scope.Directory(scope.toString))
    assertEquals(config.guard, WorkRuntime.defaultGuard(WorkRuntime.workMode))
    assertEquals(config.model.model, "fake-model")
    assertEquals(config.strategy, StrategyName.MemoryTree)
  }

  test("Work: workMode is local mode with no Sessions^ and no Children^") {
    assertEquals(WorkRuntime.workMode.copy(sessions = Mode.local.sessions, children = Mode.local.children), Mode.local)
    assertEquals(WorkRuntime.workMode.sessions, dspy4s.harness.guest.Access.None)
    assertEquals(WorkRuntime.workMode.children, dspy4s.harness.guest.Access.None)
  }

  test("Work: defaultGuard asks for git push only in a mode with Network^") {
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git push origin main")), Verdict.Ask)
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git status")), Verdict.Allow)
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("spawn", command("git push origin main")), Verdict.Ask)
    assertEquals(WorkRuntime.defaultGuard(Mode.local), Guard.allowAll)
  }

  // ── Turns ──

  test("Work: a message runs a cell in the scope of the session, and the reply ends the turn Replied") {
    val model = sessionModel(policy("read", Some("""println(read("a.txt"))""")), policy("FAKE-DONE", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-DONE"))), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("FAKE-A")), outputs(handle).toString)
    }
  }

  test("Work: a val of one turn is there in the next turn") {
    val model = sessionModel(
      policy("define", Some("val x = 41")),
      policy("ok", None),
      policy("use", Some("println(x + 1)")),
      policy("FAKE-SECOND", None)
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1))
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("42")), outputs(handle).toString)
    }
  }

  test("Work: the REPL of a work session has no rlm, so a cell that calls it does not compile") {
    val model = sessionModel(policy("delegate", Some("""rlm[String]("FAKE-CHILD")""")), policy("FAKE-NO-RLM", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(entries(handle).exists {
        case Entry.Observed(_, CellOutcome.CompileError(_), _) => true
        case _                                                 => false
      })
    }
  }

  test("Work: each turn gets a new budget of model calls") {
    val model = sessionModel(
      policy("one", Some("val a = 1")),
      policy("FAKE-FIRST", None),
      policy("two", Some("val b = 2")),
      policy("FAKE-SECOND", None)
    )
    work(model, settings = HarnessLimits(steps = 3, modelCalls = 2)) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1))
      assertEquals(handle.send(message("m2")), Right(()))
      assert(
        eventually(60)(outcomes(handle) ==
          Vector(TurnOutcome.Replied("FAKE-FIRST"), TurnOutcome.Replied("FAKE-SECOND"))),
        entries(handle).toString
      )
    }
  }

  // ── Approvals (5.5) ──

  test("Work: an Ask call with no grant does not run, and pending holds its rule; after a Grant the new turn runs it") {
    val model = sessionModel(
      policy("write", Some("""write("b.txt", "FAKE-B")""")),
      policy("I need an approval to write b.txt.", None),
      policy("write", Some("""write("b.txt", "FAKE-B")""")),
      policy("FAKE-WRITTEN", None)
    )
    work(model, Some(ask("write"))) { (scope, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(!Files.exists(scope.resolve("b.txt")))
      val rule = handle.state.pending.headOption.getOrElse(fail("no pending call"))
      assert(entries(handle).exists {
        case Entry.EffectDone(_, EffectResult.NeedsApproval(r)) => r == rule
        case _                                                  => false
      })
      assertEquals(handle.send(Command.Grant(RequestId("g1"), rule, GrantLifetime.Once)), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assertEquals(Files.readString(scope.resolve("b.txt")), "FAKE-B")
    }
  }

  test("Work: a catch of NeedsApproval in the cell does not hide the pending call") {
    val model = sessionModel(
      policy("try", Some("""try write("b.txt", "x") catch case e: NeedsApproval => println("caught")""")),
      policy("FAKE-TRIED", None)
    )
    work(model, Some(ask("write"))) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("caught")), outputs(handle).toString)
      assertEquals(handle.state.pending.size, 1)
    }
  }

  // ── Jobs and the hard stop (10.3) ──

  test(
    "Work: under the sandbox a job runs on after its cell, Abort during a cell stops no job, and " +
      "StopJobs(OfLastTurn) stops the job of that turn"
  ) {
    sandboxed()
    val model = sessionModel(
      policy("start", Some("""val j = spawn("sleep 331")""")),
      policy("look", Some("println(waitJob(j, 1))")),
      policy("wait", Some("waitJob(j, 60)")),
      policy("again", Some("println(waitJob(j, 10))")),
      policy("FAKE-WAITED", None)
    )
    work(model, settings = HarnessLimits(steps = 4)) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(
        eventually(60)(handle.state.position match
          case Position.InCell(2, _, _) => true
          case _                        => false),
        entries(handle).toString
      )
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assert(eventually(20)(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
      assert(running("sleep 331").nonEmpty, "Abort stopped the job")
      assertEquals(handle.send(Command.StopJobs(JobSelector.OfLastTurn, "FAKE-STOP-JOBS")), Right(()))
      assert(eventually(15)(running("sleep 331").isEmpty), "the job stays after StopJobs")
      assert(handle.state.jobs.values.forall(_.stopRequested))
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("Running")), outputs(handle).toString)
      assert(outputs(handle).exists(_.contains("Stopped")), outputs(handle).toString)
    }
  }

  test("Work: a job with report = true sends a message with the source Harness at its end, and it starts a turn") {
    sandboxed()
    val model = sessionModel(
      policy("start", Some("""spawn("while [ ! -f go ]; do sleep 0.1; done", report = true)""")),
      policy("started", None),
      policy("FAKE-REPORTED", None)
    )
    work(model) { (scope, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle) == Vector(TurnOutcome.Replied("started"))), entries(handle).toString)
      Files.writeString(scope.resolve("go"), ""): Unit
      assert(
        eventually(60)(outcomes(handle) ==
          Vector(TurnOutcome.Replied("started"), TurnOutcome.Replied("FAKE-REPORTED"))),
        entries(handle).toString
      )
      assert(handle.read(-1).exists {
        case Logged(_, meta, Entry.Input(_, _, _)) => meta.source == Source.Harness
        case _                                     => false
      })
    }
  }

  test("Work: stopJob in a cell commits JobsStopRequested(One(job))") {
    sandboxed()
    val model =
      sessionModel(policy("stop", Some("""val j = spawn("sleep 332"); stopJob(j)""")), policy("FAKE-STOPPED", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(entries(handle).exists {
        case Entry.JobsStopRequested(JobSelector.One(_), _) => true
        case _                                              => false
      })
      assert(eventually(15)(running("sleep 332").isEmpty), "the job stays after stopJob")
    }
  }

  // ── The memory tree (7.1) ──

  test("Work: a work session has the memory tree, and a cell reads a part of its memory with zoom") {
    val model = sessionModel(policy("look", Some("""println(zoom("0.0"))""")), policy("FAKE-DONE", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1", "FAKE-FIRST-MESSAGE")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("FAKE-FIRST-MESSAGE")), outputs(handle).toString)
      assert(entries(handle).exists {
        case Entry.EffectStarted(_, "zoom", _) => true
        case _                                 => false
      })
    }
  }

  test("Work: a reopen reads the built lines, so it makes no compress call for a message of an earlier host") {
    val long  = "y" * 700
    val first = sessionModel(
      policy("one", Some(s"""println("FAKE-OUT-ONE $long")""")),
      policy("FAKE-ONE", None),
      policy("two", Some(s"""println("FAKE-OUT-TWO $long")""")),
      policy("FAKE-TWO", None)
    )
    val again = sessionModel(policy("FAKE-THREE", None))
    val place = Place()
    try withRoot { root =>
        val env    = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val config = WorkRuntime.config(place.root, "fake-model", limits)
        withHost(root, Models.withModel(first)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(config))
          assertEquals(handle.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          assertEquals(handle.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
        }
        assert(summaryRequests(first).exists(_.contains("FAKE-OUT-ONE")), "the first host made no compress call")
        withHost(root, Models.withModel(again)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), None)
          assertEquals(handle.send(message("m3")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 3), entries(handle).toString)
        }
        assert(!summaryRequests(again).exists(r => r.contains("FAKE-OUT-ONE") || r.contains("FAKE-OUT-TWO")))
      }
    finally place.close()
  }

  test("Work: summaryOptions reads the JSON object of DSPY_SUMMARY_OPTIONS, and refuses another text") {
    assert(WorkEnv.summaryOptions(Map.empty).exists(_.fields.isEmpty))
    val options =
      WorkEnv.summaryOptions(Map(WorkEnv.SummaryOptions -> """{"chat_template_kwargs": {"enable_thinking": false}}"""))
    assert(options.exists(_.fields.map(_._1) == Vector("chat_template_kwargs")), options.toString)
    assert(WorkEnv.summaryOptions(Map(WorkEnv.SummaryOptions -> "[1, 2]")).isLeft)
    assert(WorkEnv.summaryOptions(Map(WorkEnv.SummaryOptions -> "{not json")).isLeft)
  }

  test("Work: a stop cuts each call of the runner that runs at the same time") {
    val context = Models.withModel(sessionModel())(summon[RuntimeContext])
    val runner  = StoppablePolicy(context, () => false)
    val started = CountDownLatch(3)
    val results = ConcurrentLinkedQueue[Either[String, Int]]()
    val threads = (1 to 3).map { _ =>
      Thread.ofPlatform().start { () =>
        val result = runner.run { () =>
          started.countDown()
          Thread.sleep(30_000)
          Right(1)
        }
        results.add(result): Unit
      }
    }
    try
      assert(started.await(10, TimeUnit.SECONDS), "the calls did not start")
      runner.stop()
      threads.foreach(_.join(5_000))
      assert(threads.forall(!_.isAlive), "a call did not end after the stop")
      assertEquals(results.asScala.toVector, Vector.fill(3)(Left(PolicyRunner.Stopped)))
    finally runner.close()
  }

  test("Work: a stop during a long policy call ends the call, the turn ends Aborted in time, and the REPL stays") {
    // The calls of the model: 1 and 2 define x, 3 is the slow call of the stopped turn, 4 and 5 use x.
    val calls = java.util.concurrent.atomic.AtomicInteger(0)
    // The summary requests of the memory tree do not count, and they get a fixed line.
    val model = dspy4s.harness.RequestLm(
      text =>
        if isSummary(text) then Right(summaryReply(text))
        else
          Right(calls.get match
            case 1 => policy("define", Some("val x = 41"))
            case 2 => policy("defined", None)
            case 4 => policy("use", Some("println(x + 1)"))
            case _ => policy("FAKE-USED", None))
      ,
      text => if !isSummary(text) && calls.incrementAndGet() == 3 then 30_000L else 0L
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(handle.send(message("m2", "FAKE-SLOW")), Right(()))
      assert(eventually(30)(calls.get == 3), entries(handle).toString)
      val start = System.nanoTime()
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assert(eventually(20)(outcomes(handle).size == 2), entries(handle).toString)
      // Before the grace period of 5 s, so no fence: the activation and its REPL stay.
      assert((System.nanoTime() - start) / 1_000_000L < 4_000L, "the stop waited for the policy call")
      assertEquals(outcomes(handle).last, TurnOutcome.Aborted)
      assertEquals(handle.send(message("m3")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 3), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("42")), outputs(handle).toString)
    }
  }

  test("Work: a stop during a long exec ends the command and its process, and the turn ends Aborted") {
    sandboxed()
    val model = sessionModel(policy("wait", Some("""exec("sleep 319")""")), policy("never", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(handle.state.open.values.exists(_.op == "exec")), entries(handle).toString)
      val start = System.nanoTime()
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assert(eventually(20)(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
      assert((System.nanoTime() - start) / 1_000_000_000L < 15)
      assert(eventually(15)(running("sleep 319").isEmpty), "the process of the exec stays after the stop")
    }
  }

  // ── The prompt and the folder (the live sessions of 2026-11-10) ──

  /** The text of the policy call `n` of `model`. A summary request of the memory tree is not a policy call. */
  private def promptOf(model: RequestLm, n: Int): String =
    model.requests.asScala.toVector.map(_.messages.flatMap(_.text).mkString("\n")).filterNot(isSummary).lift(n)
      .getOrElse(fail(s"no policy call $n"))

  /** True when `text` has a definition with the name `name`. */
  private def defines(text: String, name: String): Boolean =
    raw"\bdef\s+${java.util.regex.Pattern.quote(name)}\b".r.findFirstIn(text).nonEmpty

  test("Work: the prompt of a work session names the calls of workMode and no call that workMode refuses") {
    // The model of a live session said that it could use the network, other sessions and rlm.
    val model = sessionModel(policy("FAKE-HELLO", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val text = promptOf(model, 0)
      // zoom(ref) reads the memory of the session with no capability (7.1). zoom(session, ref) needs Sessions.
      val missing =
        Vector("read", "write", "edit", "exec", "spawn", "llmQuery", "store", "zoom").filterNot(defines(text, _))
      val refused = Vector("httpGet", "rlm", "rlmBatched", "tell", "start", "sessions", "observe")
        .filter(defines(text, _))
      assert(missing.isEmpty && refused.isEmpty, s"missing: $missing; refused: $refused")
      assert(!text.contains("def zoom(session"), "the prompt names zoom(session, ref)")
    }
  }

  test("Work: the task of a chat turn names the folder, asks for no submit, and names no input fields") {
    // A chat ends a turn with a step with no code, so the text of a program call is wrong for it.
    val model = sessionModel(policy("FAKE-HELLO", None))
    work(model) { (scope, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val text = promptOf(model, 0)
      assert(text.contains(scope.toString), text)
      assert(!text.contains("Give the result with submit"), text)
      assert(!text.contains("The task has no input fields"), text)
    }
  }

  test(
    "Work: open refuses a new session whose folder does not exist or is a file, names the path, and makes no journal"
  ) {
    // A live session opened in a folder that did not exist, and its first exec gave an error that misled the model.
    val place = Place()
    try withRoot { root =>
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val runtime = Models.withModel(sessionModel())(WorkRuntime.forWork(env, limits))
        val missing = place.root.resolve("fake-missing")
        val file    = Files.writeString(place.root.resolve("fake-file.txt"), "FAKE")
        withHost(root, runtime) { host =>
          Vector(missing, file).zipWithIndex.foreach { (folder, n) =>
            host.open(id(n + 1), Some(WorkRuntime.config(folder, "fake-model", limits))) match
              case Left(HostError.OpenFailed(reason)) => assert(reason.contains(folder.toString), reason)
              case other                              => fail(s"$folder: $other")
            assert(!Files.exists(SessionFile.pathOf(root, id(n + 1))), s"the open made a journal for $folder")
          }
        }
      }
    finally place.close()
  }

  test("Work: after the folder of a session is gone, an exec names the folder and says that it does not exist") {
    sandboxed()
    val model = sessionModel(
      policy("FAKE-FIRST", None),
      policy("run", Some("""println(exec("pwd"))""")),
      policy("FAKE-SECOND", None)
    )
    val place = Place()
    try withRoot { root =>
        val folder  = Files.createDirectory(place.root.resolve("fake-folder"))
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val runtime = Models.withModel(model)(WorkRuntime.forWork(env, limits))
        withHost(root, runtime) { host =>
          val handle = opened(host, id(1), Some(WorkRuntime.config(folder, "fake-model", limits)))
          assertEquals(handle.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          WorkRuntime.remove(folder)
          assertEquals(handle.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
          // The model or the person reads the reason: in the output of the cell, or in the end of the turn.
          val seen = entries(handle).map(_.toString)
          assert(seen.exists(e => e.contains(folder.toString) && e.contains("does not exist")), seen.mkString("\n"))
          assert(!seen.exists(_.contains("Cannot run program")), seen.mkString("\n"))
        }
      }
    finally place.close()
  }
