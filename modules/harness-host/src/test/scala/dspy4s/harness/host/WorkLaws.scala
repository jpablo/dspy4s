package dspy4s.harness.host

import dspy4s.core.contracts.{DynamicValues, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, MemoryTree, Models, Place, PolicyRunner, RequestLm, Secrets, StreamLm}
import dspy4s.harness.Models.policy
import dspy4s.harness.guest.{Access, JobStatus, Mode, Scope, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
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

  /** The options of a model with the reasoning effort `high` (CLI_SCREEN.md, 14.4). */
  private val high: Map[String, Json] = Map("reasoning_effort" -> Json.String("high"))

  /** The text of the option `key` of `request`, or none. */
  private def optionText(request: dspy4s.lm.contracts.LmRequest, key: String): Option[String] =
    dspy4s.core.contracts.DynamicValues.recordGet(
      request.options,
      key
    ).flatMap(dspy4s.core.contracts.DynamicValues.asString)

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

  // In the live session of 2026-11-15, the controller did not know /allow, so it told the person to use a "host UI".
  // A fix gave the task of the controller the answer of an approval. The task of a work session still had no such
  // text, and only its instructions said "end the turn and ask". An optimizer can change the instructions.
  // The task of a work session has the text only when a rule of its guard can ask. The default guard of a work session
  // asks for no call, and the text costs tokens at each turn.
  test("Work: a task says that the person types /allow when the guard of the session can ask, as the controller says") {
    val rule = "When a call needs an approval, end the turn and tell the person to type /allow, with no number. " +
      "A message of the person instead of /allow refuses the call."
    def count(task: String): Int = task.split(java.util.regex.Pattern.quote(rule), -1).length - 1

    val scope = Path.of("/tmp/fake-scope")
    val mine  = WorkRuntime.config(scope, "fake-model", limits)
    val asks  = mine.copy(guard = WorkRuntime.defaultGuard(Mode.local.copy(network = Access.Full)))
    val owned = asks.copy(owner = Owner.Session(Controller.id), goal = Some("Fix the fake build"))
    val once  = Vector(
      WorkRuntime.taskOf(id(1), asks, scope, "FAKE-SHELL"),
      WorkRuntime.taskOf(id(1), asks.copy(goal = Some("Fix the fake build")), scope, "FAKE-SHELL", looping = true),
      WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", owes = true),
      WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", owes = false),
      WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", looping = true),
      Controller.task(Path.of("/tmp/fake-home"))
    )
    once.foreach(task => assertEquals(count(task), 1, task))
    val never = Vector(
      WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL"),
      WorkRuntime.taskOf(
        id(2),
        mine.copy(owner = Owner.Session(Controller.id), goal = Some("Fix the fake build")),
        scope,
        "FAKE-SHELL",
        owes = true
      )
    )
    never.foreach(task => assertEquals(count(task), 0, task))
  }

  test("Work: the task of an owned session asks for the call submit(...) in a cell at the end of the work") {
    val owned = WorkRuntime.config(Path.of("/tmp/fake-scope"), "fake-model", limits)
      .copy(owner = Owner.Session(Controller.id), goal = Some("fake goal"))
    val task = WorkRuntime.taskOf(SessionId("fake-owned"), owned, Path.of("/tmp/fake-scope"), "FAKE-SHELL")
    assert(task.contains("fake goal"), task)
    assert(task.contains("submit("), task)
    assert(!task.contains("in one text"), task)
  }

  test("Work: the task of an owned session that owes no report asks for a reply, and not for submit (8.6)") {
    // Live session 8b418720 (2026-11-16): after a stop closed the debt, the task still asked for submit, and the model
    // ended two typed turns with a value that nobody saw.
    val scope = Path.of("/tmp/fake-scope")
    val owned = WorkRuntime.config(scope, "fake-model", limits)
      .copy(owner = Owner.Session(Controller.id), goal = Some("Fix the fake build"))
    val open   = WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", owes = true)
    val closed = WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL", owes = false)
    assert(open.contains("submit(") && open.contains("gets a report at the end of each turn"), open)
    assert(closed.contains("Fix the fake build") && closed.contains("FAKE-SHELL"), closed)
    assert(!closed.contains("submit"), closed)
    assert(closed.contains("gives it no report") && closed.contains("the reply to the person"), closed)
    // A session of the person has no text about an owner, whatever the debt.
    val mine = WorkRuntime.config(scope, "fake-model", limits)
    assertEquals(
      WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL", owes = false),
      WorkRuntime.chatTask(scope, "FAKE-SHELL")
    )
  }

  test("Work: each call to the policy of an owned session gets the task of the debt after the journal so far (8.6)") {
    val model = sessionModel(
      policy("I'll give the result.", Some("submit(\"FAKE-RESULT\")")),
      policy("FAKE-REPLY", None),
      policy("FAKE-AGAIN", None)
    )
    val place = Place()
    try withRoot { root =>
        val env    = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val parent = Controller.config(place.home, "fake-model", limits)
        val owned  = WorkRuntime.ownedConfig(
          "FAKE-GOAL",
          Scope.Directory(place.root.toString),
          StrategyName.MemoryTree,
          Controller.id,
          parent,
          limits
        )
        withHost(root, Models.withModel(model)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(owned))
          // The owner gives the work, and the turn ends with submit. That closes the debt.
          assert(host.runAs(id(1), message("m1", "FAKE-WORK"), Source.Controller).isRight)
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          // The person types, and the debt stays closed.
          assertEquals(handle.send(message("m2", "FAKE-QUESTION")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
          // A new message of the owner opens the debt again.
          assert(host.runAs(id(1), message("m3", "FAKE-MORE"), Source.Controller).isRight)
          assert(eventually(60)(outcomes(handle).size == 3), entries(handle).toString)
          assertEquals(
            outcomes(handle),
            Vector(
              TurnOutcome.Submitted(Json.String("FAKE-RESULT")),
              TurnOutcome.Replied("FAKE-REPLY"),
              TurnOutcome.Replied("FAKE-AGAIN")
            )
          )
        }
        val tasks = model.requests.asScala.toVector.map(_.messages.flatMap(_.text).mkString("\n"))
          .filterNot(isSummary)
        assertEquals(tasks.size, 3, tasks.mkString("\n=====\n"))
        val asks = tasks.map(_.contains("write a cell that calls submit"))
        assertEquals(asks, Vector(true, false, true), tasks.mkString("\n=====\n"))
      }
    finally place.close()
  }

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

  test("Work: taskOf gives the task of the controller, the goal and submit for an owned session, else chatTask (8.7)") {
    val scope = Path.of("/tmp/fake-scope")
    val home  = Path.of("/tmp/fake-home")
    val mine  = WorkRuntime.config(scope, "fake-model", limits)
    assertEquals(WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL"), WorkRuntime.chatTask(scope, "FAKE-SHELL"))
    assertEquals(
      WorkRuntime.taskOf(Controller.id, Controller.config(home, "fake-model", limits), home, "FAKE-SHELL"),
      Controller.task(home)
    )
    val owned = mine.copy(owner = Owner.Session(Controller.id), goal = Some("Fix the fake build"))
    val task  = WorkRuntime.taskOf(id(2), owned, scope, "FAKE-SHELL")
    assert(task.contains("Fix the fake build") && task.contains("submit") && task.contains(scope.toString), task)
    assert(task.contains("FAKE-SHELL"), task)
  }

  test("Work: ownedConfig gives the owner, the goal, the strategy, workMode met with the ceiling and the model (8.7)") {
    val parent = Controller.config(Path.of("/tmp/fake-home"), "fake-parent-model", limits)
    val scope  = Scope.Directory("/tmp/fake-scope")
    val c      = WorkRuntime.ownedConfig("Fix it", scope, StrategyName.Window, Controller.id, parent, limits)
    assertEquals(
      (c.kind, c.steps, c.owner, c.goal),
      (TurnKind.Chat, limits.steps, Owner.Session(Controller.id), Some("Fix it"))
    )
    assertEquals((c.strategy, c.scope, c.model), (StrategyName.Window, scope, parent.model))
    assertEquals((c.mode, c.ceiling), (WorkRuntime.workMode, WorkRuntime.workMode))
    assertEquals(c.guard, WorkRuntime.defaultGuard(WorkRuntime.workMode))
    val narrow = parent.copy(ceiling = Mode.readOnly)
    val low    = WorkRuntime.ownedConfig("Fix it", scope, StrategyName.Window, Controller.id, narrow, limits)
    assertEquals(low.mode, WorkRuntime.workMode.meet(Mode.readOnly))
  }

  test(
    "Work: after each turn in a git folder, the journal has ScopeRecorded with the head, and no git gives none (8.7)"
  ) {
    def git(dir: Path, args: String*): String =
      val process = ProcessBuilder(("git" +: "-C" +: dir.toString +: args)*).redirectErrorStream(true).start()
      val output  = String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
      assert(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, output)
      output
    assume(scala.util.Try(ProcessBuilder("git", "--version").start().waitFor() == 0).getOrElse(false), "no git")
    val model = sessionModel(policy("one", None), policy("two", None))
    work(model) { (folder, handle) =>
      git(folder, "init", "-q", "-b", "main"): Unit
      git(
        folder,
        "-c",
        "user.email=fake@example.test",
        "-c",
        "user.name=Fake",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "x"
      ): Unit
      val head = git(folder, "rev-parse", "HEAD")
      assertEquals(handle.send(message("m1")), Right(()))
      assert(
        eventually(60)(entries(handle).exists {
          case Entry.ScopeRecorded(`head`, _) => true
          case _                              => false
        }),
        entries(handle).toString
      )
    }
    work(sessionModel(policy("one", None))) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1))
      Thread.sleep(500)
      assert(
        !entries(handle).exists {
          case Entry.ScopeRecorded(_, _) => true
          case _                         => false
        },
        entries(handle).toString
      )
    }
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

  // The live session of 2026-11-11 came through this path. A second model call for a step would take the next reply,
  // and the turn would not end Replied.
  test("Work: a step with one fence and no field markers runs its cell, and a step with only a text is the reply") {
    val model = sessionModel("Let me read it.\n\n```scala\nprintln(read(\"a.txt\"))\n```", "FAKE-DONE")
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

  test("Work: in a new host, waitJob of a job that ended in the earlier host gives its exit code, not Lost") {
    // In the live scenario store-reopen (2026-11-10), a job ended with the code 0, and after a reopen in a new host
    // waitJob gave Lost, because the journal kept no end of a job (10.3). Lost is for a job that ran when its host
    // ended. The job ends during the turn, so its end must be in the journal before the first host ends.
    sandboxed()
    val first = sessionModel(
      policy("start", Some("""val j = spawn("exit 3"); store("job", j); println("FAKE-FIRST " + waitJob(j, 30))""")),
      policy("FAKE-STARTED", None)
    )
    val again = sessionModel(
      policy("look", Some("""load[JobId]("job").foreach(j => println("FAKE-AFTER " + waitJob(j, 1)))""")),
      policy("FAKE-LOOKED", None)
    )
    val place = Place()
    try withRoot { root =>
        val env    = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val config = WorkRuntime.config(place.root, "fake-model", limits)
        withHost(root, Models.withModel(first)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(config))
          assertEquals(handle.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          assert(outputs(handle).exists(_.contains("FAKE-FIRST Exited(3)")), outputs(handle).toString)
        }
        withHost(root, Models.withModel(again)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), None)
          assertEquals(handle.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
          assert(outputs(handle).exists(_.contains("FAKE-AFTER Exited(3)")), outputs(handle).toString)
        }
      }
    finally place.close()
  }

  test("Work: each activation records the limits and the sandbox of its runtime, also after a reopen") {
    val model                                   = sessionModel(policy("FAKE-DONE", None))
    val place                                   = Place()
    def facts(handle: SessionHandle): HostFacts = handle.state.config.host.getOrElse(fail("the config has no facts"))
    try withRoot { root =>
        val env    = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val config = WorkRuntime.config(place.root, "fake-model", limits)
        withHost(root, Models.withModel(model)(WorkRuntime.forWork(env, limits))) { host =>
          val first = facts(opened(host, id(1), Some(config)))
          assertEquals(first.limits.cellMillis, limits.cellTimeout.toMillis)
          assertEquals(first.limits.cellWallMillis, limits.cellWallTime.map(_.toMillis))
          assertEquals(first.limits.callsPerCell, limits.callsPerCell)
          assertEquals(first.limits.summaryCalls, limits.summaryCalls)
          assertEquals(first.limits.memoryChars, limits.budget)
          assertEquals(first.limits.unattendedTurns, Some(limits.unattendedTurns))
          assertEquals(first.sandbox.backend, Backends.detect().map(_.name))
          assertEquals(first.sandbox.secretNames, Vector(".env"))
          assert(first.sandbox.secretPaths.exists(_.endsWith("/.ssh")), first.sandbox.secretPaths.toString)
          assert(first.sandbox.env.contains("PATH"), first.sandbox.env.toString)
        }
        // The reopen takes the configuration of the journal, and the facts of the new runtime.
        val fewer = limits.copy(summaryCalls = 0, callsPerCell = 7)
        withHost(root, Models.withModel(model)(WorkRuntime.forWork(env, fewer))) { host =>
          val again = facts(opened(host, id(1), None))
          assertEquals((again.limits.summaryCalls, again.limits.callsPerCell), (0, 7))
        }
        // A restart with another limit of the unattended turns records it, and the kernel uses it (8.7).
        withHost(
          root,
          Models.withModel(model)(WorkRuntime.forWork(env, limits.copy(unattendedTurns = TurnLimit.NoLimit)))
        ) {
          host =>
            val again = opened(host, id(1), None)
            assertEquals(facts(again).limits.unattendedTurns, Some(TurnLimit.NoLimit))
            assertEquals(again.state.config.unattendedLimit, TurnLimit.NoLimit)
        }
      }
    finally place.close()
  }

  test("Work: the wall limit of a cell comes from the limits of the host, and it stops a command that runs (D1)") {
    sandboxed()
    val model = sessionModel(
      policy("sleep", Some("""println(exec("sleep 30; echo FAKE-LATE"))""")),
      policy("FAKE-DONE", None)
    )
    work(model, settings = limits.copy(cellWallTime = Some(2.seconds))) { (_, handle) =>
      val started = System.nanoTime()
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val seconds  = (System.nanoTime() - started) / 1e9
      val observed = entries(handle).collect { case Entry.Observed(output, outcome, _) => (outcome, output) }
      assert(observed.exists(_._1 == CellOutcome.TimedOut(2000L)), observed.toString)
      assert(!outputs(handle).exists(_.contains("FAKE-LATE")), outputs(handle).toString)
      assert(seconds < 20, s"the turn took $seconds s")
    }
  }

  test("Work: the limit of the guest API calls of a cell comes from the limits of the host") {
    val model = sessionModel(
      policy("loop", Some("""(1 to 5).foreach(_ => now()); println("FAKE-END")""")),
      policy("FAKE-DONE", None)
    )
    work(model, settings = limits.copy(callsPerCell = 3)) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assert(outputs(handle).exists(_.contains("more than 3 guest API calls")), outputs(handle).toString)
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
      // The model reads the message, so it gives the status of the job in words.
      val text = handle.read(-1).collectFirst {
        case Logged(_, meta, Entry.Input(_, text, _)) if meta.source == Source.Harness => text
      }.getOrElse(fail("no message of the job"))
      assert(text.contains("exited with the code 0") && !text.contains("Exited("), text)
    }
  }

  test("Work: a call of a job that the session does not have names the job as /jobs does, with no Scala value") {
    sandboxed()
    val model = sessionModel(policy("ask", Some("jobOutput(JobId(99, 0))")), policy("FAKE-DONE", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val failures = entries(handle).collect { case Entry.Observed(_, CellOutcome.Failed(exception), _) => exception }
      assert(failures.exists(_.contains("this session has no job 99.0")), failures.toString)
      assert(!failures.exists(_.contains("JobId(")), failures.toString)
    }
  }

  test("Work: the handle gives the draft of the policy while the model writes it, and none after the turn") {
    sandboxed()
    val reply   = policy("The file has 42 lines.", None)
    val reached = CountDownLatch(1)
    val hold    = CountDownLatch(1)
    // Before the chunk 16 of 4 characters, the reply is at "The file " of its text field (CLI_SCREEN.md, 13).
    val model = StreamLm(
      text => Right(if isSummary(text) then summaryReply(text) else reply),
      size = 4,
      gate = i =>
        if i == 16 then
          reached.countDown()
          hold.await(60, TimeUnit.SECONDS): Unit
    )
    work(model) { (_, handle) =>
      assertEquals(handle.draft, None)
      assertEquals(handle.send(message("m1")), Right(()))
      val draft =
        try
          assert(reached.await(60, TimeUnit.SECONDS), entries(handle).toString)
          handle.draft
        finally hold.countDown()
      assertEquals(draft.map(d => (d.thought, d.text)), Some(("a fake thought", "The file")))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(outcomes(handle), Vector(TurnOutcome.Replied("The file has 42 lines.")))
      assertEquals(handle.draft, None)
      assertEquals(model.streams.get, 1)
    }
  }

  test("Work: a stop during a call that streams ends the turn, and leaves no draft") {
    sandboxed()
    val reached = CountDownLatch(1)
    val model   = StreamLm(
      text => Right(if isSummary(text) then summaryReply(text) else policy("FAKE-LATE", None)),
      size = 4,
      gate = i =>
        if i == 16 then
          reached.countDown()
          // The thread of the call ignores the stop for a while, and then writes the rest of its draft.
          try Thread.sleep(2_000)
          catch case _: InterruptedException => Thread.sleep(2_000)
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(reached.await(60, TimeUnit.SECONDS), entries(handle).toString)
      assert(handle.draft.nonEmpty)
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(outcomes(handle), Vector(TurnOutcome.Aborted))
      assertEquals(handle.draft, None)
      Thread.sleep(3_000)
      assertEquals(handle.draft, None)
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

  // The kernel now refuses a stop of a job that has ended (live run of 2026-11-16). A cell that stops a job that has
  // just ended must not fail for that: the job is already where the cell wants it.
  test("Work: stopJob in a cell of a job that has ended does nothing, and the cell goes on") {
    sandboxed()
    val model = sessionModel(
      policy("start", Some("""val j = spawn("exit 0")""")),
      policy("FAKE-STARTED", None),
      policy("stop", Some("""stopJob(j); println("AFTER-STOP")""")),
      policy("FAKE-STOPPED", None)
    )
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      def ended =
        entries(handle).exists {
          case Entry.JobEnded(_, _) => true
          case _                    => false
        }
      assert(eventually(30)(ended), entries(handle).toString)
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      val observed = entries(handle).collect { case Entry.Observed(output, outcome, _) => (output, outcome) }
      assert(
        observed.lastOption.exists((output, outcome) => outcome == CellOutcome.Ok && output.contains("AFTER-STOP")),
        observed.toString
      )
    }
  }

  test("Work: jobStatus gives Running for a job that runs after its turn, and Stopped after StopJobs") {
    sandboxed()
    val model = sessionModel(policy("start", Some("""spawn("sleep 333")""")), policy("FAKE-STARTED", None))
    work(model) { (_, handle) =>
      assertEquals(handle.jobStatus, Map.empty)
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(handle.jobStatus.values.toVector, Vector(JobStatus.Running))
      assertEquals(handle.jobStatus.keySet, handle.state.jobs.keySet)
      assertEquals(handle.send(Command.StopJobs(JobSelector.All, "FAKE-STOP-JOBS")), Right(()))
      def stopped =
        handle.jobStatus.values.toVector match
          case Vector(JobStatus.Stopped) => true
          case _                         => false
      assert(eventually(15)(stopped), handle.jobStatus.toString)
      assert(eventually(15)(running("sleep 333").isEmpty), "the job stays after StopJobs")
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
        // The strategy updates at the first step of a turn (7.2, choice 2), so the first host made the line of the cell
        // of turn 1 in turn 2. The cell of turn 2 gets its line in the next turn, in the second host.
        assert(!summaryRequests(again).exists(_.contains("FAKE-OUT-ONE")), summaryRequests(again).toString)
        assert(summaryRequests(again).exists(_.contains("FAKE-OUT-TWO")), summaryRequests(again).toString)
      }
    finally place.close()
  }

  test("Work: each turn has one DerivedRead before its first Acted, with the newest build number of the store") {
    val long  = "y" * 700
    val model = sessionModel(
      policy("one", Some(s"""println("FAKE-OUT-ONE $long")""")),
      policy("FAKE-ONE", None),
      policy("FAKE-TWO", None)
    )
    val place = Place()
    try withRoot { root =>
        val env = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        withHost(root, Models.withModel(model)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(WorkRuntime.config(place.root, "fake-model", limits)))
          checkReads(root, model, handle)
        }
      }
    finally place.close()
  }

  /** The checks of the law of `DerivedRead` for the session of `handle`, whose journals are in `root`. */
  private def checkReads(root: Path, model: RequestLm, handle: SessionHandle): Unit =
    assertEquals(handle.send(message("m1")), Right(()))
    assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
    // One update for each turn (7.2, choice 2): the second step of turn 1 made no compress call for the cell output.
    assert(!summaryRequests(model).exists(_.contains("FAKE-OUT-ONE")), summaryRequests(model).toString)
    assertEquals(handle.send(message("m2")), Right(()))
    assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
    assert(summaryRequests(model).exists(_.contains("FAKE-OUT-ONE")), "the next turn made no compress call")
    val log    = entries(handle)
    val starts = log.indices.filter(i => log(i) == Entry.TurnStarted) :+ log.size
    val builds = starts.zip(starts.drop(1)).map { (start, end) =>
      val turn  = log.slice(start, end)
      val acted = turn.indexWhere {
        case Entry.Acted(_, _, _) => true
        case _                    => false
      }
      val reads = turn.zipWithIndex.collect { case (Entry.DerivedRead(derivation, build), at) =>
        (derivation, build, at)
      }
      assertEquals(reads.size, 1, turn.toString)
      assertEquals(reads.head._1, DerivationName.Memory)
      assert(reads.head._3 < acted, s"the DerivedRead comes after the first Acted: $turn")
      reads.head._2
    }
    // Turn 1 wrote no line, and turn 2 wrote the line of the cell of turn 1. The store has no later write.
    assertEquals(builds, Vector(0L, 1L))
    val newest = FileLines.readOnly(SessionFile.pathOf(root, id(1)), id(1)).map { lines =>
      try lines.newest
      finally lines.close()
    }
    assertEquals(newest, Right(1L))

  test("Work: with summaryCalls 0, a session makes no compress or merge call, and the memory shows each message cut") {
    // The CLI flag --no-memory-calls gives these limits, so a person can compare a session with no memory calls.
    val long  = "FAKE-HEAD " + "y" * 700 + " FAKE-TAIL"
    val model = sessionModel(policy("FAKE-ONE", None), policy("FAKE-TWO", None))
    work(model, settings = limits.copy(summaryCalls = 0)) { (_, handle) =>
      assertEquals(handle.send(message("m1", long)), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      assertEquals(summaryRequests(model), Vector.empty)
      // The memory of turn 2 holds the first 512 bytes of the long message of turn 1 (MemoryLine.of).
      val second = promptOf(model, 1)
      assert(second.contains("FAKE-HEAD"), second)
      assert(!second.contains("FAKE-TAIL"), second)
    }
  }

  // ── The model of the session (CLI_SCREEN.md, 14.4) ──

  test("Work: config and the config of the controller put the options of the model in the configuration") {
    assertEquals(WorkRuntime.config(Path.of("/tmp/fake-scope"), "fake-model", limits, high).model.options, high)
    assertEquals(Controller.config(Path.of("/tmp/fake-home"), "fake-model", limits, high).model.options, high)
    assertEquals(WorkRuntime.config(Path.of("/tmp/fake-scope"), "fake-model", limits).model.options, Map.empty)
  }

  test("Work: contextUse gives the tokens of the last call to the policy, and only that call gets the effort") {
    // The footer of a client shows the context of the model (CLI_SCREEN.md, 14.4). A long message makes a summary.
    val long  = "FAKE-LONG-MESSAGE " + "y" * 700
    val model = RequestLm(
      text => if isSummary(text) then Right(summaryReply(text)) else Right(policy("FAKE-DONE", None)),
      usage = text =>
        if isSummary(text) then
          Some(dspy4s.core.contracts.LmUsage(totalTokens = 100, promptTokens = 99, completionTokens = 1))
        else Some(dspy4s.core.contracts.LmUsage(totalTokens = 4_342, promptTokens = 4_321, completionTokens = 21))
    )
    val place = Place()
    try withRoot { root =>
        val env = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        withHost(root, Models.withModel(model)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(WorkRuntime.config(place.root, "fake-model", limits, high)))
          assertEquals(handle.contextUse, None)
          assertEquals(handle.send(message("m1", long)), Right(()))
          assert(eventually(60)(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-DONE"))), entries(handle).toString)
          assertEquals(handle.contextUse, Some(ContextUse(4_321L, 21L)))
        }
        val requests  = model.requests.asScala.toVector
        val summaries = requests.filter(r => isSummary(r.messages.flatMap(_.text).mkString))
        assert(summaries.nonEmpty, "the long message made no summary")
        assert(summaries.forall(optionText(_, "reasoning_effort").isEmpty), summaries.map(_.options).toString)
        val policies = requests.filterNot(summaries.contains)
        assertEquals(policies.map(optionText(_, "reasoning_effort")), Vector(Some("high")))
      }
    finally place.close()
  }

  test("Work: a reopen keeps the effort of a ChangeModel, and its next call to the policy sends it") {
    val first = sessionModel(policy("FAKE-ONE", None))
    val again = sessionModel(policy("FAKE-TWO", None))
    val low   = Map("reasoning_effort" -> Json.String("low"))
    val place = Place()
    try withRoot { root =>
        val env = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        withHost(root, Models.withModel(first)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(WorkRuntime.config(place.root, "fake-model", limits, low)))
          assertEquals(handle.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          assert(handle.run(Command.ChangeModel(ModelChoice("fake-model", high))).isRight)
        }
        withHost(root, Models.withModel(again)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), None)
          assertEquals(handle.state.config.model.options, high)
          assertEquals(handle.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
        }
        val sent = again.requests.asScala.toVector.filterNot(r => isSummary(r.messages.flatMap(_.text).mkString))
        assertEquals(sent.map(optionText(_, "reasoning_effort")), Vector(Some("high")))
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

  test("Work: with native reasoning and no DSPY_SUMMARY_OPTIONS, the summary options turn the reasoning off") {
    // In session 7c377cf6 (2026-11-15), a summary reasoned up to 23,455 tokens for one line. A probe of the same
    // requests with reasoning_effort none gave 0 reasoning tokens in 0.7 s. The level low gave 1,453 and 4,588.
    def keys(env: Map[String, String]): Either[String, Vector[String]] =
      WorkEnv.summaryOptions(env).map(_.fields.toVector.map(_._1))
    def effort(env: Map[String, String]): Option[String] =
      WorkEnv.summaryOptions(env).toOption.flatMap(DynamicValues.recordGet(_, "reasoning_effort"))
        .flatMap(DynamicValues.asString)
    val native = Map("DSPY_NATIVE_REASONING" -> "true")
    assertEquals(keys(native), Right(Vector("reasoning_effort")))
    assertEquals(effort(native), Some("none"))
    // A blank variable is no variable.
    assertEquals(effort(native + (WorkEnv.SummaryOptions -> " ")), Some("none"))
    // The variable wins: an empty object sends no option, and another object gets no effort.
    assertEquals(keys(native + (WorkEnv.SummaryOptions -> "{}")), Right(Vector.empty))
    assertEquals(
      keys(native + (WorkEnv.SummaryOptions -> """{"chat_template_kwargs": {"thinking": false}}""")),
      Right(Vector("chat_template_kwargs"))
    )
    // With no native reasoning, the endpoint can refuse the key, and a failed summary keeps the cut message.
    assertEquals(keys(Map("DSPY_NATIVE_REASONING" -> "false")), Right(Vector.empty))
  }

  test("Work: the handle gives the summaries of the memory while they run, and memoryUse after the first policy call") {
    // A live session waited 63 s at one step for the summaries of the memory, and the screen gave no reason
    // (2026-11-11). The footer of a client also shows the size of the last view and its budget (10.6).
    val long  = "FAKE-LONG-MESSAGE " + "y" * 700
    val model = RequestLm(
      text => if isSummary(text) then Right(summaryReply(text)) else Right(policy("FAKE-DONE", None)),
      text => if isSummary(text) then 1_500L else 0L
    )
    work(model) { (_, handle) =>
      assertEquals(handle.memoryUse, None)
      assertEquals(handle.send(message("m1", long)), Right(()))
      // The first policy call compresses the long message: one summary.
      assert(eventually(30)(handle.activity == Some(Activity.MemorySummaries(0, 1))), handle.activity.toString)
      assert(eventually(60)(outcomes(handle) == Vector(TurnOutcome.Replied("FAKE-DONE"))), entries(handle).toString)
      assert(eventually()(handle.activity.isEmpty), handle.activity.toString)
      // The budget of a memory session is the budget of the open turn and the room of the view (7.2, choice 10).
      val most = limits.budget + MemoryTree.room(limits.viewChars)
      handle.memoryUse match
        case Some(MemoryUse(chars, budget)) =>
          assert(chars > long.length && chars <= most, s"$chars")
          assertEquals(budget, most)
        case None => fail("no memory use after the policy call")
    }
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
      assert(!text.contains("The files of the project"), "a folder with no AGENTS.md and no README has no section")
    }
  }

  // ── The prompt (10.6) ──

  /** The number of times that `part` comes in `text`. */
  private def times(text: String, part: String): Int = text.split(java.util.regex.Pattern.quote(part), -1).length - 1

  test("Work: the instructions of a work session come one time in a request, in the system message") {
    // A measured request (2026-11-12) had the same 434 characters in the system message and in the task.
    val model = sessionModel(policy("FAKE-HELLO", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val request = model.requests.asScala.toVector.filterNot(r => isSummary(r.messages.flatMap(_.text).mkString)).head
      assertEquals(times(request.messages.flatMap(_.text).mkString("\n"), WorkRuntime.instructions), 1)
      assert(request.messages.head.text.exists(_.contains(WorkRuntime.instructions)), "not in the system message")
    }
  }

  // ── The files of the project (10.6) ──

  test(
    "Work: taskOf puts the files of the project after the shell rules and before the goal, and not for the controller"
  ) {
    val scope = Path.of("/tmp/fake-scope")
    val home  = Path.of("/tmp/fake-home")
    val mine  = WorkRuntime.config(scope, "fake-model", limits)
    val task  = WorkRuntime.taskOf(id(1), mine, scope, "FAKE-SHELL", "FAKE-PROJECT")
    assertEquals(task, WorkRuntime.chatTask(scope, "FAKE-SHELL", "FAKE-PROJECT"))
    assert(task.indexOf("FAKE-PROJECT") > task.indexOf("FAKE-SHELL"), task)
    val owned   = mine.copy(owner = Owner.Session(Controller.id), goal = Some("fake goal"))
    val ownTask = WorkRuntime.taskOf(SessionId("fake-owned"), owned, scope, "FAKE-SHELL", "FAKE-PROJECT")
    assert(ownTask.indexOf("FAKE-PROJECT") > ownTask.indexOf("FAKE-SHELL"), ownTask)
    assert(ownTask.indexOf("FAKE-PROJECT") < ownTask.indexOf("The goal of the session"), ownTask)
    val control = Controller.config(home, "fake-model", limits)
    assert(!WorkRuntime.taskOf(Controller.id, control, home, "FAKE-SHELL", "FAKE-PROJECT").contains("FAKE-PROJECT"))
    assertEquals(WorkRuntime.chatTask(scope, "FAKE-SHELL", ""), WorkRuntime.chatTask(scope, "FAKE-SHELL"))
  }

  test("Work: the task holds AGENTS.md and the README of the folder from the open, and a reopen reads them again") {
    // In a live session the model used one more step to read the README with a cell (2026-11-12).
    val first = sessionModel(policy("FAKE-ONE", None), policy("FAKE-TWO", None))
    val again = sessionModel(policy("FAKE-THREE", None))
    val place = Place()
    try withRoot { root =>
        val readme = place.root.resolve("README.md")
        Files.writeString(place.root.resolve("AGENTS.md"), "FAKE-AGENTS-RULE"): Unit
        Files.writeString(readme, "FAKE-README-OLD"): Unit
        val env = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        withHost(root, Models.withModel(first)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), Some(WorkRuntime.config(place.root, "fake-model", limits)))
          assertEquals(handle.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
          Files.writeString(readme, "FAKE-README-NEW"): Unit
          assertEquals(handle.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
        }
        Vector(promptOf(first, 0), promptOf(first, 1)).foreach { text =>
          assert(text.contains("FAKE-AGENTS-RULE") && text.contains("FAKE-README-OLD"), text)
          assert(!text.contains("FAKE-README-NEW"), "a change in the activation changed the task")
        }
        withHost(root, Models.withModel(again)(WorkRuntime.forWork(env, limits))) { host =>
          val handle = opened(host, id(1), None)
          assertEquals(handle.send(message("m3")), Right(()))
          assert(eventually(60)(outcomes(handle).size == 3), entries(handle).toString)
        }
        assert(promptOf(again, 0).contains("FAKE-README-NEW"), promptOf(again, 0))
      }
    finally place.close()
  }

  test("Work: the task names what a command can write, the control paths, the secret files and the network") {
    // In the live session 00429ec8 (2026-11-11), each git worktree add failed with "Operation not permitted", and the
    // model used three steps to find the cause. The task did not say that .git is read-only, that a command can write
    // only in the folder of the session. A file call refuses a .env file, and a command too when the sandbox closes
    // the name, or when the walk at the open of the session found the file (5.4). Since choice 19, the task names the
    // control paths and the git commands that fail.
    val model = sessionModel(policy("FAKE-HELLO", None))
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      val text   = promptOf(model, 0)
      val closes = Backends.detect().exists(_.closesNames)
      // A backend that closes names lists each control path. Another backend lists the ones that exist: here .git.
      val git =
        if closes then Vector(".git/config", "print an error about .git/config", ".vscode/settings.json")
        else Vector("all of .git stays read-only")
      (Vector("TMPDIR", ".env.local", "no network", "Operation not permitted") ++ git)
        .foreach(words => assert(text.contains(words), s"the task does not name $words:\n$text"))
      val alone = "A file call can neither read nor write a file or a folder whose name is .env"
      assertEquals(text.contains(alone), !closes, text)
      // A backend that cannot close a name covers the files of the walk at the open of the session (5.4).
      val walked = "A command can neither read nor write such a file or folder when it was there at the open"
      assertEquals(text.contains(walked), Backends.detect().exists(!_.closesNames), text)
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

  /** The input field `name` of the last message of the policy call `n` of `model`, or an empty text. A summary request
    * of the memory tree is not a policy call.
    */
  private def fieldOf(model: RequestLm, n: Int, name: String): String =
    val policyCalls =
      model.requests.asScala.toVector.filterNot(r => isSummary(r.messages.flatMap(_.text).mkString("\n")))
    val text   = policyCalls.lift(n).getOrElse(fail(s"no policy call $n")).messages.lastOption.flatMap(_.text)
    val marker = s"[[ ## $name ## ]]"
    text.filter(_.contains(marker)).fold("") { t =>
      val body = t.substring(t.indexOf(marker) + marker.length)
      body.indexOf("[[ ##") match
        case -1  => body
        case end => body.substring(0, end)
    }

  /** The model of the laws of a reopen: turn 1 defines `fakeKept`, and turn 2 answers. */
  private def keeper(): RequestLm =
    sessionModel(policy("define", Some("val fakeKept = 41")), policy("FAKE-FIRST", None), policy("FAKE-SECOND", None))

  test("Work: after a reopen, the first step of the next turn names the vals that the new REPL lost") {
    // In a live session of 2026-11-10, a reopen started a new REPL with no note, and the model only guessed that its
    // val was gone. Spec 6 gives this note after a restart, and a new activation is a restart.
    val model = keeper()
    val place = Place()
    try withRoot { root =>
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root)
        val runtime = Models.withModel(model)(WorkRuntime.forWork(env, limits))
        withHost(root, runtime) { host =>
          val first = opened(host, id(1), Some(WorkRuntime.config(place.root, "fake-model", limits)))
          assertEquals(first.send(message("m1")), Right(()))
          assert(eventually(60)(outcomes(first).size == 1), entries(first).toString)
          assert(first.run(Command.Close(SessionOutcome.Parked, "FAKE-CLOSE")).isRight)
          val again = opened(host, id(1), None)
          assertEquals(again.send(message("m2")), Right(()))
          assert(eventually(60)(outcomes(again).size == 2), entries(again).toString)
          val seen = fieldOf(model, 2, "observation") + fieldOf(model, 2, "status")
          assert(seen.contains("fakeKept"), seen)
        }
      }
    finally place.close()
  }

  test("Work: a turn in the same activation gets no note of lost names, because its REPL keeps them") {
    val model = keeper()
    work(model) { (_, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 1), entries(handle).toString)
      assertEquals(handle.send(message("m2")), Right(()))
      assert(eventually(60)(outcomes(handle).size == 2), entries(handle).toString)
      val seen = fieldOf(model, 2, "observation") + fieldOf(model, 2, "status")
      assert(!seen.contains("fakeKept"), seen)
    }
  }
