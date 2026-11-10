package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, Models, Place, ScriptLm, Secrets}
import dspy4s.harness.Models.policy
import dspy4s.harness.guest.{Mode, Scope}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}

/** The laws of a work session (CODE_HARNESS_DESIGN.md, 8.1, 5.5 and 10.3): a host with a `WorkRuntime`, a real REPL,
  * the sandbox of this machine and a scripted model.
  */
class WorkLaws extends munit.FunSuite:

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
    try
      val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none)
      val runtime = Models.withModel(model)(WorkRuntime.forWork(env, settings))
      val base    = WorkRuntime.config(place.root, "fake-model", settings)
      withRoot { root =>
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

  test("Work: config gives a chat turn of the user in workMode, the window and the default guard") {
    val scope  = Path.of("/tmp/fake-scope")
    val config = WorkRuntime.config(scope, "fake-model", limits)
    assertEquals(config.kind, TurnKind.Chat)
    assertEquals(config.steps, limits.steps)
    assertEquals(config.owner, Owner.User)
    assertEquals((config.mode, config.ceiling), (WorkRuntime.workMode, WorkRuntime.workMode))
    assertEquals(config.scope, Scope.Directory(scope.toString))
    assertEquals(config.guard, WorkRuntime.defaultGuard(WorkRuntime.workMode))
    assertEquals(config.model.model, "fake-model")
  }

  test("Work: workMode is local mode with no Sessions^ and no Children^") {
    assertEquals(WorkRuntime.workMode.copy(sessions = Mode.local.sessions, children = Mode.local.children), Mode.local)
    assertEquals(WorkRuntime.workMode.sessions, dspy4s.harness.guest.Access.None)
    assertEquals(WorkRuntime.workMode.children, dspy4s.harness.guest.Access.None)
  }

  test("Work: defaultGuard asks for git push only in a mode with Network^") {
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git push origin main")), Verdict.Ask)
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git status")), Verdict.Allow)
    assertEquals(WorkRuntime.defaultGuard(Mode.local), Guard.allowAll)
  }

  // ── Turns ──

  test("Work: a message runs a cell in the scope of the session, and the reply ends the turn Replied") {
    val model = ScriptLm(policy("read", Some("""println(read("a.txt"))""")), policy("FAKE-DONE", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-DONE"))), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("FAKE-A")), outputs(handle).toString)
    }
  }

  test("Work: a val of one turn is there in the next turn") {
    val model = ScriptLm(
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
    val model = ScriptLm(policy("delegate", Some("""rlm[String]("FAKE-CHILD")""")), policy("FAKE-NO-RLM", None))
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
    val model = ScriptLm(
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
    val model = ScriptLm(
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
    val model = ScriptLm(
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
    val model = ScriptLm(
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
    val model = ScriptLm(
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
      ScriptLm(policy("stop", Some("""val j = spawn("sleep 332"); stopJob(j)""")), policy("FAKE-STOPPED", None))
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

  test("Work: a stop during a long exec ends the command and its process, and the turn ends Aborted") {
    sandboxed()
    val model = ScriptLm(policy("wait", Some("""exec("sleep 319")""")), policy("never", None))
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
