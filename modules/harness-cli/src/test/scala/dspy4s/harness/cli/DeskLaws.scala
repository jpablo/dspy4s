package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.guest.{Scope, SessionCard, SessionRef}
import dspy4s.harness.host.{Behind, Feed, HostError, SessionHandle}
import dspy4s.harness.kernel.*

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The laws of the client with a desk (CODE_HARNESS_DESIGN.md, 8.7): the lines of the controller and of the release,
  * and the switch between the sessions of a host.
  */
class DeskLaws extends munit.FunSuite:

  private val home = Path.of("/fake/home")
  private val cwd  = Path.of("/fake/work")

  private val worktree = Scope.Worktree("/fake/wt", "feature", "/fake/repo", "main")
  private val state    =
    ScopeState(worktree, Some("a1b2c3"), Some("a1b2c3"), clean = false, merged = false, owned = true, shared = false)
  private val remove = ReleaseItem.RemoveWorktree("/fake/repo", "/fake/wt")
  private val keep   = ReleaseItem.KeepRef("/fake/repo", "refs/archive/s2", "feature", "a1b2c3")
  private val delete = ReleaseItem.DeleteBranch("/fake/repo", "feature", "a1b2c3")

  private def card(n: Int): SessionCard =
    SessionCard(
      SessionRef(s"s$n"),
      s"goal $n",
      Some(worktree),
      "User",
      None,
      "Idle",
      4L,
      1,
      Vector(s"/fake/wt/f$n"),
      2,
      None,
      None
    )

  /** A desk with two sessions: `s2`, which the client shows first, and the controller. */
  private final class FakeDesk extends Desk:
    val controllerHandle: NamedHandle                                = NamedHandle("controller")
    val other: NamedHandle                                           = NamedHandle("s3")
    val released: ConcurrentLinkedQueue[(SessionId, SessionOutcome)] = ConcurrentLinkedQueue()
    val confirmed: ConcurrentLinkedQueue[ReleaseItem]                = ConcurrentLinkedQueue()
    def cards: Vector[SessionCard]                                   = Vector(card(2), card(3))
    // The session s4 has ended: open says so, and reopen opens it.
    val ended: NamedHandle                                    = NamedHandle("s4")
    def open(id: SessionId): Either[HostError, SessionHandle] =
      if id == SessionId("s3") then Right(other)
      else if id == SessionId("s4") then Left(HostError.Ended)
      else Left(HostError.OpenFailed(s"no session ${id.value}"))
    def reopen(id: SessionId): Either[HostError, SessionHandle] =
      if id == SessionId("s4") then Right(ended) else Left(HostError.OpenFailed(s"no session ${id.value}"))
    def controller: Either[HostError, SessionHandle]   = Right(controllerHandle)
    def planOf(id: SessionId, outcome: SessionOutcome) = Right((state, Vector(remove, keep, delete)))
    // The scope has changes, so only the removal of the worktree is irreversible: the ref keeps the branch.
    def release(id: SessionId, outcome: SessionOutcome, reason: String, confirm: ReleaseItem => Boolean) =
      released.add((id, outcome)): Unit
      if id.value == "controller" then Left(HostError.Refused("the controller never ends"))
      else
        Right(Vector(remove, keep, delete).map { item =>
          if item != remove then ReleaseStep(item, ReleaseResult.Done)
          else
            confirmed.add(item): Unit
            ReleaseStep(item, if confirm(item) then ReleaseResult.Done else ReleaseResult.Skipped("no confirmation"))
        })

  /** Runs the loop of a client of `NamedHandle("s2")` with `desk` and the lines `lines`, and gives its output. */
  private def run(lines: String, desk: Option[Desk]): String =
    val out = ByteArrayOutputStream()
    Cli.loop(NamedHandle("s2"), BufferedReader(StringReader(lines)), PrintStream(out, true, UTF_8), desk = desk): Unit
    String(out.toByteArray, UTF_8)

  test("Desk: parseLine reads /close with an outcome, /sessions, /open ID and /controller") {
    assertEquals(parseLine("/close merged"), Line.Close(SessionOutcome.Merged, "the person closed the session"))
    assertEquals(parseLine("/close parked"), Line.Close(SessionOutcome.Parked, "the person closed the session"))
    assertEquals(
      parseLine("/close abandoned the plan changed"),
      Line.Close(SessionOutcome.Abandoned("the plan changed"), "the plan changed")
    )
    parseLine("/close replaced s9") match
      case Line.Close(SessionOutcome.Replaced(SessionId("s9")), _) => ()
      case other                                                   => fail(other.toString)
    assertEquals(parseLine("/close abandoned"), Line.Unknown("/close abandoned"))
    assertEquals(parseLine("/sessions"), Line.Sessions)
    assertEquals(parseLine("/open s3"), Line.Open(SessionId("s3")))
    assertEquals(parseLine("/controller"), Line.Controller)
    assert(helpText.contains("/open ID") && helpText.contains("/controller"), helpText)
  }

  test("Desk: --controller gives the controller flag, and --controller with --session gives an error") {
    assertEquals(CliArgs.parse(Vector("--controller"), home, cwd).map(_.controller), Right(true))
    assert(CliArgs.parse(Vector("--controller", "--session", "s1"), home, cwd).isLeft)
    assert(CliArgs.usage.contains("--controller"))
  }

  test("Desk: cardText names the id, the status, the goal and the files, and planText marks each irreversible item") {
    val text = cardText(card(2))
    Vector("s2", "Idle", "goal 2", "1").foreach(part => assert(text.contains(part), text))
    val plan = planText(state, Vector(remove, keep, delete)).linesIterator.toVector
    assertEquals(plan.size, 3)
    assert(plan(0).contains("(irreversible)"), plan(0))
    assert(!plan(1).contains("(irreversible)") && !plan(2).contains("(irreversible)"), plan.toString)
    assert(planText(state, Vector.empty).nonEmpty)
  }

  test("Desk: with no desk, /sessions and /open print that the client shows one session, and send no command") {
    val output = run("/sessions\n/open s3\n/quit\n", None)
    assert(output.contains("one session"), output)
  }

  test("Desk: with a desk, /sessions prints the cards, /open shows the other session, and /controller the controller") {
    val desk   = FakeDesk()
    val output = run("/sessions\n/open s3\nfor s3\n/controller\nfor the controller\n/quit\n", Some(desk))
    assert(output.contains("goal 2") && output.contains("goal 3"), output)
    assert(output.contains("FAKE-s3") && output.contains("FAKE-controller"), output)
    assertEquals(desk.other.texts, Vector("for s3"))
    assertEquals(desk.controllerHandle.texts, Vector("for the controller"))
  }

  test("Desk: with a desk, /close OUTCOME prints the plan, asks for each irreversible item, and runs the release") {
    val desk   = FakeDesk()
    val output = run("/close abandoned not needed\nno\n/quit\n", Some(desk))
    assert(output.contains("(irreversible)"), output)
    assertEquals(desk.released.asScala.toVector, Vector((SessionId("s2"), SessionOutcome.Abandoned("not needed"))))
    assertEquals(desk.confirmed.asScala.toVector, Vector(remove))
    assert(output.contains("no confirmation"), output)
  }

  test("Desk: /reopen ID reads, and an id of /open, /reopen or /close replaced that is not valid is not a command") {
    assertEquals(parseLine("/reopen s4"), Line.Reopen(SessionId("s4")))
    Vector("/open ../x", "/reopen /tmp/x", "/close replaced -x").foreach { line =>
      parseLine(line) match
        case Line.Unknown(_) => ()
        case other           => fail(s"$line gives $other")
    }
    assert(helpText.contains("/reopen ID"), helpText)
  }

  test("Desk: --session with an id that is not valid gives an error with the usage") {
    Vector("../x", "/tmp/x", "a b").foreach { bad =>
      assert(CliArgs.parse(Vector("--session", bad), home, cwd).isLeft, bad)
    }
  }

  test("Desk: openRefusal refuses --session of a session whose owner is a session, and no other") {
    def row(owner: Owner) =
      dspy4s.harness.host.CatalogRow(
        SessionId("s2"),
        owner,
        None,
        dspy4s.harness.host.SessionStatus.Idle,
        3L,
        "/w/s2",
        0L,
        ""
      )
    assert(openRefusal(Some(row(Owner.Session(SessionId("controller"))))).nonEmpty)
    assertEquals(openRefusal(Some(row(Owner.User))), None)
    assertEquals(openRefusal(None), None)
  }

  test("Desk: /open of a session that has ended says so and names /reopen, and /reopen shows it") {
    val desk   = FakeDesk()
    val output = run("/open s4\n/reopen s4\nfor s4\n/quit\n", Some(desk))
    assert(output.contains("/reopen"), output)
    assert(output.contains("FAKE-s4"), output)
    assertEquals(desk.ended.texts, Vector("for s4"))
  }

  test("Desk: /close while the client shows the controller prints that the controller never ends") {
    val desk   = FakeDesk()
    val output = run("/controller\n/close merged\n/quit\n", Some(desk))
    assert(output.contains("never ends"), output)
  }

/** A session for the laws of the desk: its feed gives one `Acted` with the text `FAKE-<name>`, and `send` and `run`
  * keep the text of each `Send`.
  */
private final class NamedHandle(name: String) extends SessionHandle:
  private val sent  = ConcurrentLinkedQueue[String]()
  private val reply = Support.logOf(1000L, Entry.Acted("", s"FAKE-$name", None)).head

  def texts: Vector[String] = sent.asScala.toVector

  def id: SessionId                                          = SessionId(name)
  def send(command: Command): Either[HostError, Unit]        = run(command).map(_ => ())
  def run(command: Command): Either[HostError, SessionState] =
    command match
      case Command.Send(_, text, _) => sent.add(text): Unit
      case _                        => ()
    Right(state)
  def state: SessionState                                                            = SessionState.initial.copy(closed = false)
  def jobStatus: Map[dspy4s.harness.kernel.EffectId, dspy4s.harness.guest.JobStatus] = Map.empty
  def read(after: Long): Vector[Logged]                                              = Vector(reply).filter(_.seq > after)
  def subscribe(capacity: Int): Feed                                                 =
    new Feed:
      private val queue                                          = ConcurrentLinkedQueue[Either[Behind, Logged]](java.util.List.of(Right(reply)))
      def poll(waitMillis: Long): Option[Either[Behind, Logged]] =
        Option(queue.poll()).orElse { Thread.sleep(waitMillis.min(50)); None }
      def ended: Boolean = false
      def close(): Unit  = ()
