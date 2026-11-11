package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{Budget, Harness, HarnessLimits, Journal, Place, Recorder, RequestLm, Secrets, Support, recorded}
import dspy4s.harness.{JournalTurn, LocalFiles}
import dspy4s.harness.Models.policy
import dspy4s.harness.Support.answer
import dspy4s.harness.guest.{Llm, Scope, SessionCard, SessionRef, Sessions, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.{HostList, Layout, Profiles}
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The laws of the controller and of the handler of `Sessions` (CODE_HARNESS_DESIGN.md, 8.2, 8.6 and 8.7). The laws of
  * the handler use fake peers, so they see each call of the handler to its host. The laws at the end run a real host.
  */
class ControllerLaws extends munit.FunSuite:

  // A law at the end starts real REPLs.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3, "min")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val limits = HarnessLimits(steps = 3)
  private val home   = Path.of("/tmp/fake-home")

  // ── The controller ──

  test(
    "Controller: config gives a chat turn of the user in Controller.mode, with its ceiling, the memory tree and Home"
  ) {
    val c = Controller.config(home, "fake-model", limits)
    assertEquals((c.kind, c.steps, c.owner), (TurnKind.Chat, limits.steps, Owner.User))
    assertEquals((c.mode, c.ceiling), (Controller.mode, Controller.ceiling))
    assertEquals((c.strategy, c.scope), (StrategyName.MemoryTree, Scope.Home(home.toString)))
    assertEquals((c.model.model, c.goal, c.guard), ("fake-model", None, Controller.guard))
    // The ceiling holds the mode of the controller and the mode of a work session (8.1).
    assertEquals(Controller.mode.meet(Controller.ceiling), Controller.mode)
    assertEquals(WorkRuntime.workMode.meet(Controller.ceiling), WorkRuntime.workMode)
  }

  test("Controller: the task holds the instructions and the home, and messageSource gives Controller or Child") {
    val task = Controller.task(home)
    assert(task.contains(Controller.instructions) && task.contains(home.toString), task)
    assertEquals(Controller.messageSource(Controller.id), Source.Controller)
    assertEquals(Controller.messageSource(id(2)), Source.Child(id(2)))
  }

  test("Controller: the guard asks for a start in a folder of the person, and allows a start in a new folder") {
    def start(kind: String) = Json.Object(("goal", Json.String("g")), ("kind", Json.String(kind)))
    assertEquals(Controller.guard.verdict("start", start("Directory")), Verdict.Ask)
    assertEquals(Controller.guard.verdict("start", start("Scratch")), Verdict.Allow)
    assertEquals(Controller.guard.verdict("start", start("Worktree")), Verdict.Allow)
  }

  test("Controller: the API reference of its mode names the functions of Sessions, and not write, edit, exec or rlm") {
    val reference = Harness.apiReference(Controller.mode)
    Vector("def sessions", "def tell", "def start", "def observe", "def zoom(session", "def read")
      .foreach(name => assert(reference.contains(name), s"the reference does not name $name"))
    Vector("def write", "def edit", "def exec", "def spawn", "def rlm")
      .foreach(name => assert(!reference.contains(name), s"the reference names $name"))
  }

  test("Controller: its mode reads a file of its home, and not a file under the root of the host (8.7)") {
    val place = Place()
    try
      Files.writeString(place.home.resolve("notes.txt"), "FAKE-NOTES")
      val root   = Files.createDirectories(place.base.resolve("host-root"))
      val hidden = Files.writeString(root.resolve("catalog.txt"), "FAKE-HIDDEN")
      val layout = Layout.forSession(place.home, root)
      val files  = LocalFiles(
        place.home,
        Profiles.forMode(Controller.mode, place.home, layout, HostList.empty),
        layout.secretNames
      )
      assertEquals(files.read("notes.txt"), "FAKE-NOTES")
      assert(Try(files.read(hidden.toString)).isFailure)
    finally place.close()
  }

  // ── The handler of Sessions, with fake peers ──

  /** Peers that keep each call. A `refused` scope does not make, and a Scratch or a Worktree with an empty path gets
    * `/work/<session>`.
    */
  private final class FakePeers(val rows: Vector[CatalogRow], refused: Set[Scope] = Set.empty) extends Peers:
    val calls: ConcurrentLinkedQueue[String]                                                 = ConcurrentLinkedQueue()
    val runs: ConcurrentLinkedQueue[(SessionId, Command, Source)]                            = ConcurrentLinkedQueue()
    val opens: ConcurrentLinkedQueue[(SessionId, SessionConfig, Command.Send, Source)]       = ConcurrentLinkedQueue()
    def open(id: SessionId, config: Option[SessionConfig]): Either[HostError, SessionHandle] =
      calls.add(s"open ${id.value}"): Unit
      Left(HostError.OpenFailed("the fake peers open no handle"))
    def openWith(id: SessionId, config: SessionConfig, task: Command.Send, source: Source) =
      calls.add(s"openWith ${id.value}"): Unit
      opens.add((id, config, task, source)): Unit
      Right(SessionState.initial)
    def runAs(id: SessionId, command: Command, source: Source) =
      calls.add(s"runAs ${id.value}"): Unit
      runs.add((id, command, source)): Unit
      Right(SessionState.initial)
    def journalOf(id: SessionId): Either[String, Vector[Logged]] =
      calls.add(s"journalOf ${id.value}"): Unit
      Right(Vector(Logged(0L, Meta(0L, Source.User, Taint.Trusted, required = true), Entry.TurnStarted)))
    def zoomInto(id: SessionId, ref: String): Either[String, String] =
      calls.add(s"zoomInto ${id.value}"): Unit
      if ref == "9.9" then Left("the node 9.9 is not in the memory") else Right(s"line $ref of ${id.value}")
    def make(scope: Scope, session: SessionId): Either[String, Scope] =
      calls.add(s"make $scope"): Unit
      if refused(scope) then Left(s"the folder of $scope does not exist")
      else
        Right(scope match
          case Scope.Scratch("")                   => Scope.Scratch(s"/work/${session.value}")
          case Scope.Worktree("", branch, r, base) => Scope.Worktree(s"/work/${session.value}", branch, r, base)
          case other                               => other)

  /** A probe that keeps its calls and gives a fixed answer. */
  private final class FakeProbe extends Probe:
    val asked: ConcurrentLinkedQueue[(SessionId, String, ProbeLink, Budget)] = ConcurrentLinkedQueue()
    def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
        RuntimeContext
    ): Either[String, String] =
      asked.add((session, question, link, budget)): Unit
      Right("the answer of the probe")

  private def llm: Llm =
    new Llm:
      def query(prompt        : String): String                 = ""
      def queryBatched(prompts: Vector[String]): Vector[String] = prompts

  private def row(n: Int, status: SessionStatus = SessionStatus.Idle): CatalogRow =
    CatalogRow(
      id(n),
      Owner.Session(Controller.id),
      None,
      status,
      5L,
      s"/w/$n",
      0L,
      "",
      Some(s"goal $n"),
      Some(Scope.Directory(s"/w/$n"))
    )

  /** The handler of the controller with `peers`, recorded in a journal with an open cell, and the journal. */
  private def handler(peers: Peers, probe: Probe = Probe.none, budget: Budget = Budget(7)): (Sessions, Journal) =
    val journal  = Support.journal()
    val cell     = Support.inCell(journal)
    val recorder = Recorder(journal)
    recorder.startCell(cell)
    val config = Controller.config(home, "fake-model", limits)
    val inner  = HostSessions(
      peers,
      Controller.id,
      config,
      journal,
      probe,
      () => budget,
      limits,
      (goal, scope, strategy) =>
        WorkRuntime.ownedConfig(goal, scope, strategy, Controller.id, config, limits)
    )(using RuntimeContext())
    val bound = recorded(Bindings(turn = JournalTurn(journal, () => 5L), sessions = Some(inner)), recorder)
    (bound.sessions.getOrElse(fail("no sessions")), journal)

  /** The id of the last effect of `journal`. */
  private def lastEffect(journal: Journal): EffectId =
    journal.log.reverseIterator.collectFirst { case Logged(_, _, Entry.EffectStarted(id, _, _)) => id }
      .getOrElse(fail("no effect"))

  private def failure(body: => Any): Option[String] = Try(body).failed.toOption.map(_.getMessage)

  test("Sessions: list gives the card of each row of the catalog apart from the caller") {
    val controllerRow = CatalogRow(Controller.id, Owner.User, None, SessionStatus.InTurn, 9L, "/home/p", 0L, "")
    val peers         = FakePeers(Vector(controllerRow, row(1), row(2)))
    val (sessions, _) = handler(peers)
    assertEquals(sessions.list(), Vector(row(1), row(2)).map(HostSessions.cardOf))
  }

  test("Sessions: cardOf gives the goal, the scope, the owner, the status and the part of the ledger of a row") {
    val ledger = Ledger(Vector("/w/1/a", "/w/1/c"), Vector("/w/1/b"), 3, Some("a1b2"), Some(true), Vector.empty)
    val card   = HostSessions.cardOf(row(1).copy(ledger = ledger, status = SessionStatus.Ended(SessionOutcome.Merged)))
    assertEquals(
      card,
      SessionCard(
        SessionRef(id(1).value),
        "goal 1",
        Some(Scope.Directory("/w/1")),
        s"Session(${Controller.id.value})",
        None,
        "Ended(Merged)",
        5L,
        2,
        Vector("/w/1/b"),
        3,
        Some("a1b2"),
        Some(true)
      )
    )
    val bare = HostSessions.cardOf(row(2).copy(goal = None, scope = None))
    assertEquals((bare.goal, bare.scope), ("", None))
  }

  test("Sessions: tell runs a Send with the request id of its effect and the source Controller through runAs") {
    val peers               = FakePeers(Vector(row(1)))
    val (sessions, journal) = handler(peers)
    sessions.tell(SessionRef(id(1).value), "please go on")
    val effect = lastEffect(journal)
    assertEquals(
      peers.runs.asScala.toVector,
      Vector((
        id(1),
        Command.Send(HostSessions.requestOf("tell", Controller.id, effect), "please go on", Map.empty),
        Source.Controller
      ))
    )
  }

  test(
    "Sessions: tell to a session whose row has ended or that the catalog does not have fails, and it sends nothing"
  ) {
    val peers         = FakePeers(Vector(row(1, SessionStatus.Ended(SessionOutcome.Parked))))
    val (sessions, _) = handler(peers)
    val ended         = failure(sessions.tell(SessionRef(id(1).value), "go on"))
    val unknown       = failure(sessions.tell(SessionRef("fake-unknown"), "go on"))
    assert(ended.exists(_.contains("ended")), ended.toString)
    assert(unknown.exists(_.contains("fake-unknown")), unknown.toString)
    assert(peers.runs.isEmpty && peers.opens.isEmpty, peers.calls.toString)
  }

  test("Sessions: an id that is not valid fails each call before it reaches the host, and so does an id with no row") {
    val peers         = FakePeers(Vector(row(1)))
    val (sessions, _) = handler(peers, FakeProbe())
    Vector("../../../tmp/x", "/Users/p/notes", "a b", "-x").foreach { bad =>
      val ref = SessionRef(bad)
      assert(failure(sessions.tell(ref, "m")).nonEmpty, bad)
      assert(failure(sessions.zoom(ref, "1.0")).nonEmpty, bad)
      assert(failure(sessions.observe(ref, "why?")(using llm)).nonEmpty, bad)
    }
    assert(failure(sessions.zoom(SessionRef("fake-no-row"), "1.0")).nonEmpty)
    assert(failure(sessions.observe(SessionRef("fake-no-row"), "why?")(using llm)).nonEmpty)
    assertEquals(peers.calls.asScala.toVector, Vector.empty)
  }

  test("Sessions: start makes the scope of a new id, then opens the session with ownedConfig and the goal, once") {
    val peers               = FakePeers(Vector.empty)
    val (sessions, journal) = handler(peers)
    val scope               = Scope.Worktree("", "feature", "/w/repo", "main")
    val ref                 = sessions.start("Fix the fake build", scope, StrategyName.Window)
    val effect              = lastEffect(journal)
    assert(SessionId.valid(ref.id), ref.id)
    assertEquals(peers.calls.asScala.toVector, Vector(s"make $scope", s"openWith ${ref.id}"))
    val (opened, config, task, source) = peers.opens.asScala.head
    val made                           = Scope.Worktree(s"/work/${ref.id}", "feature", "/w/repo", "main")
    assertEquals(opened.value, ref.id)
    assertEquals(
      config,
      WorkRuntime.ownedConfig(
        "Fix the fake build",
        made,
        StrategyName.Window,
        Controller.id,
        Controller.config(home, "fake-model", limits),
        limits
      )
    )
    assertEquals(
      task,
      Command.Send(HostSessions.requestOf("start", Controller.id, effect), "Fix the fake build", Map.empty)
    )
    assertEquals(source, Source.Controller)
  }

  test("Sessions: start with a scope that make refuses fails with its error, and it opens nothing") {
    val scope         = Scope.Directory("/fake/missing")
    val peers         = FakePeers(Vector.empty, refused = Set(scope))
    val (sessions, _) = handler(peers)
    val error         = failure(sessions.start("goal", scope, StrategyName.Window))
    assert(error.exists(_.contains("does not exist")), error.toString)
    assert(peers.opens.isEmpty)
  }

  test("Sessions: zoom gives the text of zoomInto, and an error of zoomInto makes the call fail with its text") {
    val (sessions, _) = handler(FakePeers(Vector(row(1))))
    assertEquals(sessions.zoom(SessionRef(id(1).value), "1.0"), s"line 1.0 of ${id(1).value}")
    val error = failure(sessions.zoom(SessionRef(id(1).value), "9.9"))
    assert(error.exists(_.contains("9.9")), error.toString)
  }

  test("Sessions: observe gives the answer of the probe about that session, with the link and the budget of the turn") {
    val probe               = FakeProbe()
    val budget              = Budget(7)
    val peers               = FakePeers(Vector(row(1)))
    val (sessions, journal) = handler(peers, probe, budget)
    assertEquals(sessions.observe(SessionRef(id(1).value), "what did it decide?")(using llm), "the answer of the probe")
    val (session, question, link, used) = probe.asked.asScala.head
    assertEquals((session, question), (id(1), "what did it decide?"))
    assertEquals(link, ProbeLink(Controller.id, lastEffect(journal)))
    assert(used eq budget)
    // A probe changes nothing in that session (8.2).
    assert(peers.runs.isEmpty && peers.opens.isEmpty)
  }

  test("Sessions: requestOf gives op:caller:cell.n, so two effects or two operations give two ids") {
    assertEquals(HostSessions.requestOf("tell", Controller.id, EffectId(3L, 1)), RequestId("tell:controller:3.1"))
    assertNotEquals(
      HostSessions.requestOf("tell", Controller.id, EffectId(3L, 1)),
      HostSessions.requestOf("tell", Controller.id, EffectId(3L, 2))
    )
    assertNotEquals(
      HostSessions.requestOf("tell", Controller.id, EffectId(3L, 1)),
      HostSessions.requestOf("start", Controller.id, EffectId(3L, 1))
    )
  }

  // ── A real host ──

  test("Sessions: runAs to a session that has ended gives Ended, and it does not open the session again") {
    withRoot { root =>
      withHost(root, ScriptRuntime(_ => Support.Script(Vector(answer("done"))))) { host =>
        val handle = opened(host, id(2))
        handle.run(Command.Close(SessionOutcome.Parked, "fake end")).left.foreach(e => fail(e.toString))
        // The row of the catalog can still lag here: the check reads the journal.
        assertEquals(
          host.runAs(id(2), Command.Send(RequestId("t1"), "go on", Map.empty), Source.Controller),
          Left(HostError.Ended)
        )
        val after = host.journalOf(id(2)).fold(e => fail(e), identity).map(_.entry)
        assert(
          after.lastOption.exists {
            case Entry.Ended(_, _) => true
            case _                 => false
          },
          after.toString
        )
      }
    }
  }

  test("Sessions: journalOf and zoomInto of an id that is not valid or has no row give an error, and no file appears") {
    withRoot { root =>
      withHost(root, ScriptRuntime(_ => Support.Script(Vector(answer("done"))))) { host =>
        val before = Files.walk(root.getParent).count()
        Vector("../../x", "fake-no-row").foreach { bad =>
          assert(host.journalOf(SessionId(bad)).isLeft, bad)
          assert(host.zoomInto(SessionId(bad), "0.0").isLeft, bad)
        }
        assertEquals(Files.walk(root.getParent).count(), before)
      }
    }
  }

  /** Runs `check` with a host at a new root, the controller open in it, a folder for work, the folder of the sessions
    * of `start`, and the model that `modelOf` makes for the folder for work.
    */
  private def withController(modelOf: Path => RequestLm)(
      check: (LocalHost, SessionHandle, Path, Path, RequestLm) => Unit
  ): Unit =
    val place = Place()
    try withRoot { root =>
        val env   = WorkEnv(place.home, HostList.empty, None, Secrets.none, root)
        val model = modelOf(place.root)
        val work  = place.base.resolve("work")
        withPeers(root, env, model, limits, work = Some(work)) { host =>
          val controller = opened(host, Controller.id, Some(Controller.config(place.home, "fake-model", limits)))
          check(host, controller, place.root, work, model)
        }
      }
    finally place.close()

  test("Controller: a cell of the controller starts a session in a new folder, which can write there (8.7)") {
    val goal  = "Write the fake report"
    val model = (_: Path) =>
      routedModel(
        Controller.instructions -> Seq(
          policy("start", Some(s"""println(start("$goal", Scope.Scratch(""), StrategyName.Window).id)""")),
          policy("Started.", None),
          policy("Noted.", None)
        ),
        goal -> Seq(policy("write", Some("""write("report.txt", "FAKE-REPORT")""")), policy("Done.", None))
      )
    withController(model) { (host, controller, _, work, _) =>
      controller.send(message("m1", "start the work")).left.foreach(e => fail(e.toString))
      assert(eventually(30)(host.rows.exists(_.owner == Owner.Session(Controller.id))), host.rows.toString)
      val started = host.rows.find(_.owner == Owner.Session(Controller.id)).getOrElse(fail("no session")).session
      val journal = host.journalOf(started).fold(e => fail(e), identity)
      journal.take(3).map(_.entry) match
        case Vector(Entry.Activated(_, config), Entry.Input(request, text, _), Entry.TurnStarted) =>
          assertEquals(
            (config.owner, config.goal, config.mode),
            (Owner.Session(Controller.id), Some(goal), WorkRuntime.workMode)
          )
          assertEquals(config.scope, Scope.Scratch(work.resolve(started.value).toString))
          assertEquals(config.model.model, "fake-model")
          assert(request.value.startsWith("start:controller:"), request.value)
          assertEquals(text, goal)
        case other => fail(s"the first entries are $other")
      assertEquals(journal.take(3).map(_.meta.source), Vector(Source.Harness, Source.Controller, Source.Controller))
      val report = work.resolve(started.value).resolve("report.txt")
      assert(eventually(30)(Files.exists(report)), host.journalOf(started).toString)
      assertEquals(Files.readString(report), "FAKE-REPORT")
    }
  }

  test("Controller: zoomInto reads the memory of a session of the host from its file, with no LM call") {
    val model = (_: Path) =>
      routedModel(Controller.instructions -> Seq.empty, "" -> Seq(policy("one", None), policy("two", None)))
    withController(model) { (host, _, folder, _, model) =>
      val work = opened(host, id(1), Some(WorkRuntime.config(folder, "fake-model", limits)))
      work.send(message("m1", "the first fake message")).left.foreach(e => fail(e.toString))
      assert(eventually(30)(outcomes(work).size == 1))
      val before = model.requests.size
      assertEquals(host.zoomInto(id(1), "0.0"), Right("the first fake message"))
      assert(host.zoomInto(id(1), "40.0").isLeft)
      assertEquals(model.requests.size, before)
    }
  }
