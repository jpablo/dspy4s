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
  private def work(model: LanguageModel, guard: Option[Guard] = None)(check: (Path, SessionHandle) => Unit): Unit =
    val place = Place()
    try
      val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none)
      val runtime = Models.withModel(model)(WorkRuntime.forWork(env, limits))
      val base    = WorkRuntime.config(place.root, Mode.local, "fake-model", limits)
      withRoot { root =>
        withHost(root, runtime) { host =>
          check(place.root, opened(host, id(1), guard.fold(base)(g => base.copy(guard = g))))
        }
      }
    finally place.close()

  /** The outputs of the cells of a handle. */
  private def outputs(handle: SessionHandle): Vector[String] =
    entries(handle).collect { case Entry.Observed(output, _, _) => output }

  private def sandboxed(): Unit = assume(Backends.detect().nonEmpty, "no OS sandbox works on this host")

  test("Work: config gives a chat turn of the user, the mode as the ceiling, the window and the default guard") {
    val scope  = Path.of("/tmp/fake-scope")
    val config = WorkRuntime.config(scope, Mode.local, "fake-model", limits)
    assertEquals(config.kind, TurnKind.Chat)
    assertEquals(config.steps, limits.steps)
    assertEquals(config.owner, Owner.User)
    assertEquals((config.mode, config.ceiling), (Mode.local, Mode.local))
    assertEquals(config.scope, Scope.Directory(scope.toString))
    assertEquals(config.guard, WorkRuntime.defaultGuard(Mode.local))
    assertEquals(config.model.model, "fake-model")
  }

  test("Work: defaultGuard asks for git push only in a mode with Network^") {
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git push origin main")), Verdict.Ask)
    assertEquals(WorkRuntime.defaultGuard(Mode.full).verdict("exec", command("git status")), Verdict.Allow)
    assertEquals(WorkRuntime.defaultGuard(Mode.local), Guard.allowAll)
  }

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

  test("Work: a job outlives its turn, Abort does not stop it, and StopJobs(OfLastTurn) stops it") {
    sandboxed()
    val model = ScriptLm(
      policy("start", Some("""val j = spawn("sleep 30")""")),
      policy("started", None),
      policy("wait", Some("println(waitJob(j, 10))")),
      policy("FAKE-WAITED", None)
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(handle.state.jobs.values.map(_.stopRequested).toVector, Vector(false))
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assertEquals(handle.send(Command.StopJobs(JobSelector.OfLastTurn, "FAKE-STOP-JOBS")), Right(()))
      assert(eventually()(handle.state.jobs.values.forall(_.stopRequested)))
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("Stopped")), outputs(handle).toString)
    }
  }

  test("Work: a job with report = true sends a message with the source Harness at its end, and it starts a turn") {
    sandboxed()
    val model = ScriptLm(
      policy("start", Some("""spawn("exit 0", report = true)""")),
      policy("started", None),
      policy("FAKE-REPORTED", None)
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assert(handle.read(-1).exists {
        case Logged(_, meta, Entry.Input(_, _, _)) => meta.source == Source.Harness
        case _                                     => false
      })
    }
  }

  test("Work: stopJob in a cell commits JobsStopRequested(One(job))") {
    sandboxed()
    val model =
      ScriptLm(policy("stop", Some("""val j = spawn("sleep 30"); stopJob(j)""")), policy("FAKE-STOPPED", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(entries(handle).exists {
        case Entry.JobsStopRequested(JobSelector.One(_), _) => true
        case _                                              => false
      })
    }
  }

  test("Work: a stop during a long exec ends the command, and the turn ends Aborted") {
    sandboxed()
    val model = ScriptLm(policy("wait", Some("""exec("sleep 30")""")), policy("never", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(handle.state.open.values.exists(_.op == "exec")), entries(handle).toString)
      val start = System.nanoTime()
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assert(eventually(20)(outcomes(handle) == Vector(TurnOutcome.Aborted)), entries(handle).toString)
      assert((System.nanoTime() - start) / 1_000_000_000L < 15)
    }
  }
