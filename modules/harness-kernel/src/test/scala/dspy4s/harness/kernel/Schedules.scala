package dspy4s.harness.kernel

import dspy4s.harness.guest.Mode
import org.scalacheck.Gen
import zio.blocks.schema.json.Json

import scala.util.Random

/** One random run of the kernel, as in the simulation of section 12. A host with no threads sends random commands and
  * random worker commits through `decide`, commits each result, and sometimes crashes or loses power.
  *
  * @param journal
  *   the journal after the run
  * @param live
  *   the state that the host kept with `evolve` of each entry that it committed
  * @param durable
  *   the length of the journal at the last flush: after a power loss, only `journal.take(durable)` stays
  * @param changed
  *   the guest API calls whose effect ran, so that the world changed
  * @param answered
  *   the requests of the `Send` and `Grant` commands whose sender got an answer with an `Input`
  * @param marks
  *   the lengths of the journal after each commit, so `journal.take(mark)` ends between two commits
  */
final case class Run(
    journal : Vector[Logged],
    live    : SessionState,
    durable : Int,
    changed : Vector[EffectId],
    answered: Vector[RequestId],
    marks   : Vector[Int]
)

/** The generators of the laws (12). */
object Schedules:

  private val versions = Versions("3.10.2-RC1", "1")

  private val value: Json =
    Json.Object(("n", Json.Number(5L)), ("Some", Json.Object(("x", Json.Number(BigDecimal("2.50"))))))

  private val other: Json = Json.Array(Json.String("a\u0000"), Json.Number(0.1), Json.Boolean(true), Json.Null)

  /** Arguments that two rules match. The stronger rule must win. */
  private val third: Json = Json.Object(("forbidden", Json.Boolean(true)))

  private val rules = Vector(
    GuardRule("op", ArgMatch.Exact(value)),
    GuardRule("op", ArgMatch.Exact(other)),
    GuardRule("op", ArgMatch.AnyArgs),
    GuardRule("unused", ArgMatch.AnyArgs)
  )

  private val overlapping = Guard(
    Vector(
      VerdictRule(GuardRule("op", ArgMatch.Exact(value)), Verdict.Ask),
      VerdictRule(GuardRule("op", ArgMatch.Exact(third)), Verdict.Ask),
      VerdictRule(GuardRule("op", ArgMatch.Exact(third)), Verdict.Deny)
    )
  )

  private val owners = Vector(Owner.User, Owner.Session(SessionId("owner")), Owner.Cell, Owner.Optimizer)

  /** The sender of the messages of the owner. `ReportLaws` uses the same sender. */
  private val ownerSource = Source.Controller

  /** Random runs with crashes, stops, late messages, grants, jobs and power losses. The owners of the sessions are the
    * user, a session, a cell and an optimizer, and the guards have rules that overlap.
    */
  def schedules: Gen[Run] =
    Gen.zip(Gen.long, Gen.prob(0.25)).map((seed, losses) => simulate(Random(seed), losses))

  /** The states between two commits of random runs. */
  def states: Gen[SessionState] =
    for
      run <- schedules
      at  <- Gen.oneOf(run.marks)
    yield state(run.journal.take(at))

  /** Each state of a run between two commits, in order. A law about a rare case, for example a pending call, checks all
    * of them, because one random state of a run seldom has that case.
    */
  def everyState(run: Run): Vector[SessionState] =
    val after = run.journal.scanLeft(SessionState.initial)(evolve)
    run.marks.map(after)

  /** Random commands from a client, a worker and a person. */
  def commands: Gen[Command] =
    Gen.oneOf(
      Gen.choose(1, 20).map(n => Command.Send(RequestId(s"r$n"), "text", Map.empty)),
      Gen.const(Command.Abort("stop")),
      Gen.oneOf(JobSelector.All, JobSelector.OfLastTurn).map(Command.StopJobs(_, "stop")),
      Gen.zip(Gen.oneOf(rules), Gen.oneOf(GrantLifetime.values.toSeq)).map { (rule, lifetime) =>
        Command.Grant(RequestId("g1"), rule, lifetime)
      },
      Gen.const(Command.Close(SessionOutcome.Parked, "park"))
    )

  /** Random guards with rules that overlap, so that two rules can match one call. */
  def guards: Gen[Guard] =
    val rule = Gen
      .zip(Gen.oneOf(rules :+ GuardRule("op", ArgMatch.Prefix("command", "git"))), Gen.oneOf(Verdict.values.toSeq))
      .map(VerdictRule(_, _))
    Gen.choose(0, 4).flatMap(n => Gen.listOfN(n, rule)).map(list => Guard(list.toVector))

  /** Random calls of the guest API: an operation and its arguments. */
  def calls: Gen[(String, Json)] =
    Gen.zip(
      Gen.oneOf("op", "unused", "exec"),
      Gen.oneOf(value, other, third, Json.Object(("command", Json.String("git push"))))
    )

  /** The problems of a journal against the language of 4.1. It is empty for a word of the language. */
  def grammar(journal: Vector[Logged]): Vector[String] =
    var problems        = Vector.empty[String]
    var inTurn          = false
    var inPolicy        = false
    var inCell          = false
    var open            = Set.empty[EffectId]
    var stopped         = false
    var currentCell     = -1L
    var inputsAfterStop = 0
    var endedAfterStop  = false
    for logged <- journal do
      logged.entry match
        case Entry.Input(_, _, _) => inputsAfterStop += 1
        case Entry.TurnStarted    =>
          if inTurn then problems :+= "a turn inside a turn"
          if endedAfterStop && inputsAfterStop == 0 then problems :+= "a stopped turn started a turn with no new input"
          endedAfterStop = false
          inTurn = true
        case Entry.TurnEnded(_) =>
          if !inTurn then problems :+= "an end with no turn"
          if open.nonEmpty then problems :+= s"a turn ended with the open effects $open"
          if inCell then problems :+= "a turn ended inside a cell"
          endedAfterStop = stopped
          inTurn = false
          inPolicy = false
          stopped = false
        case Entry.StepStarted(_) =>
          if !inTurn || inCell || stopped then problems :+= "a step in the wrong place"
          inPolicy = true
        case Entry.Acted(_, _, code) =>
          if !inPolicy || stopped then problems :+= "a reply in the wrong place"
          inPolicy = false
          inCell = code.isDefined
          currentCell = logged.seq
        case Entry.EffectStarted(id, _, _) =>
          if !inCell || stopped then problems :+= "an intent in the wrong place"
          if id.cell != currentCell then problems :+= "an effect of an old cell entered a later cell"
          open = open + id
        case Entry.EffectDone(id, _) =>
          if !open(id) then problems :+= s"an outcome of the closed effect $id"
          open = open - id
        case Entry.Observed(_, _, _) =>
          if !inCell || open.nonEmpty then problems :+= "an observation in the wrong place"
          inCell = false
        case Entry.StopRequested(_) =>
          if !inTurn then problems :+= "a stop with no turn"
          stopped = true
          inputsAfterStop = 0
        case _ => ()
    if inCell then problems :+= "the journal ends inside a cell, with no repair"
    problems

  /** The host of one run. It sends commands through `decide`, and it commits each result. */
  private final class Host(var entries: Vector[Logged], var durable: Int, var marks: Vector[Int]):
    var live: SessionState = state(entries)

    def run(command: Command, sender: Source = Source.Harness): Either[Rejection, Vector[Entry]] =
      val decided = decide(command, live)
      for list <- decided do
        for entry <- list do
          val logged = Logged(live.nextSeq, Fixtures.meta.copy(source = sourceOf(command, sender, entry)), entry)
          entries = entries :+ logged
          live = evolve(live, logged)
        if list.exists(needsFlush) then durable = entries.size
        marks = marks :+ entries.size
      decided

    /** A command whose answer the caller does not need. */
    def send(command: Command, sender: Source = Source.Harness): Unit =
      val _ = run(command, sender)

  /** The live part of a host: the processes of the jobs, and the marks of the jobs that must not start (10.3). */
  private final class JobTable:
    var running = Set.empty[EffectId]
    var marked  = Set.empty[EffectId]

    def start(id: EffectId): Boolean =
      if marked(id) then false
      else
        running = running + id
        true

    def applyStops(s: SessionState): Unit =
      val stopped = s.jobs.collect { case (id, job) if job.stopRequested => id }.toSet
      marked = marked ++ stopped
      running = running -- stopped

  private def simulate(random: Random, losses: Boolean): Run =
    val config = Fixtures.config(
      kind = if random.nextBoolean() then TurnKind.Chat else TurnKind.Program,
      steps = 1 + random.nextInt(3),
      owner = owners(random.nextInt(owners.size)),
      guard = overlapping
    )
    var host = Host(Vector.empty, 0, Vector.empty)
    host.send(Command.Activate(versions, config))
    var table         = JobTable()
    var request       = 0
    var grantRequest  = 0
    var lastGrant     = Option.empty[Command.Grant]
    var late          = Vector.empty[Entry]
    var lmInFlight    = false
    var effectsLeft   = 0
    var effectN       = 0
    var pendingStarts = Vector.empty[EffectId]
    var changed       = Vector.empty[EffectId]
    var answered      = Vector.empty[RequestId]

    // A new process after a crash or a power loss. It reads the journal, and a new activation repairs the turn.
    def restart(kept: Vector[Logged], durable: Int): Unit =
      host = Host(kept, durable, host.marks.filter(_ <= kept.size))
      lmInFlight = false
      effectsLeft = 0
      late = Vector.empty
      pendingStarts = Vector.empty
      host.send(Command.Activate(versions, config))
      table = JobTable()
      table.applyStops(host.live)

    def startJob(id: EffectId): Unit =
      val result =
        if table.start(id) then
          changed = changed :+ id
          EffectResult.Value(value, "job")
        else EffectResult.Error("stopped")
      host.send(Command.Commit(Vector(Entry.EffectDone(id, result))))

    def call(cell: Long, spawn: Boolean): Unit =
      val id = EffectId(cell, effectN)
      effectN += 1
      effectsLeft -= 1
      if spawn then
        if host.run(Command.Commit(Vector(Entry.EffectStarted(id, "spawn", value)))).isRight then
          pendingStarts = pendingStarts :+ id
      else
        val args = random.nextInt(4) match
          case 0 | 1 => value
          case 2     => other
          case _     => third
        // In the runs with power losses, some calls only read. A read changes nothing, so it does not flush.
        val op = if losses && random.nextBoolean() then "read" else "op"
        if host.run(Command.Commit(Vector(Entry.EffectStarted(id, op, args)))).isRight then
          guardCheck(host.live, op, args) match
            case GuardResult.NeedsPerson(rule) =>
              host.send(Command.Commit(Vector(Entry.EffectDone(id, EffectResult.NeedsApproval(rule)))))
            case GuardResult.Refused(reason) =>
              host.send(Command.Commit(Vector(Entry.EffectDone(id, EffectResult.Error(reason)))))
            case GuardResult.Run =>
              if op == "op" then changed = changed :+ id
              // The calls of a cell run one at a time. A cell that an interrupt cut makes no more calls.
              val done = Entry.EffectDone(id, EffectResult.Value(value, "x"))
              if random.nextInt(4) == 0 then
                late = late :+ done
                effectsLeft = 0
              else host.send(Command.Commit(Vector(done)))

    def endCell(cell: Long): Unit =
      val done    = closing(host.live).collect { case entry @ Entry.EffectDone(_, _) => entry }
      val outcome = random.nextInt(5) match
        case 0 => CellOutcome.Submitted(value)
        case 1 => CellOutcome.Failed("boom")
        case _ => CellOutcome.Ok
      val writes: Map[String, Json] = outcome match
        case CellOutcome.Ok | CellOutcome.Submitted(_) => Map("k" -> value)
        case _                                         => Map.empty
      host.send(Command.Commit(done :+ Entry.Observed("out", outcome, writes)))
      // A late intent from the old cell must not enter a later cell.
      if random.nextInt(4) == 0 then late = late :+ Entry.EffectStarted(EffectId(cell, 99), "op", value)

    def work(): Unit =
      if lmInFlight then
        lmInFlight = false
        val code = if random.nextInt(4) == 0 then None else Some("cell")
        host.send(Command.Commit(Vector(Entry.Acted("why", "text", code))))
        effectsLeft = random.nextInt(3)
        effectN = 0
      else
        next(host.live) match
          case Next.Wait             => ()
          case Next.CallPolicy(i, _) =>
            if host.run(Command.Commit(Vector(Entry.StepStarted(i)))).isRight then lmInFlight = true
          case Next.RunCell(cell, _) =>
            if pendingStarts.nonEmpty then () // the `spawn` of this cell has not returned yet
            else if effectsLeft > 0 then call(cell, spawn = random.nextInt(4) == 0)
            else endCell(cell)
          case Next.End(outcome) => host.send(Command.Commit(closing(host.live) :+ Entry.TurnEnded(outcome)))

    def grant(): Unit =
      val before                                = host.live
      val lifetime                              = GrantLifetime.values(random.nextInt(GrantLifetime.values.length))
      def fresh(rule: GuardRule): Command.Grant =
        grantRequest += 1
        Command.Grant(RequestId(s"g$grantRequest"), rule, lifetime)
      val command = (random.nextInt(4), lastGrant) match
        case (0, Some(repeat))                     => repeat // a client that lost the answer sends again
        case (1 | 2, _) if before.pending.nonEmpty => fresh(before.pending.head)
        case _                                     => fresh(rules(random.nextInt(rules.size)))
      lastGrant = Some(command)
      for list <- host.run(command, Source.User) do
        if list.exists { case Entry.Input(_, _, _) => true; case _ => false } then
          answered = answered :+ command.request

    def step(): Unit =
      if losses && random.nextInt(30) == 0 then restart(host.entries.take(host.durable), host.durable)
      else
        random.nextInt(100) match
          case n if n < 8 =>
            request += 1
            val sent   = RequestId(s"r$request")
            val sender = random.nextInt(3) match
              case 0 => ownerSource
              case 1 => Source.User
              case _ => Source.Harness
            val values: Map[String, Json] =
              if request % 4 == 0 then Map("input-doc" -> Json.String(s"text$request"), "input-n" -> value)
              else Map.empty
            if host.run(Command.Send(sent, "text", values), sender).isRight then answered = answered :+ sent
          case n if n < 10 =>
            // A repeat holds other values. If the first send got a refusal, this send is the first input.
            val values: Map[String, Json] = if request > 0 then Map("input-doc" -> Json.String("repeat")) else Map.empty
            host.send(Command.Send(RequestId(s"r${math.max(1, request)}"), "repeat", values), ownerSource)
          case n if n < 14 => host.send(Command.Abort("stop"))
          case n if n < 16 =>
            // A crash of the process loses no commit, and it flushes nothing.
            restart(host.entries, host.durable)
          case n if n < 20 && late.nonEmpty =>
            // A message from a cell that an interrupt cut arrives late.
            host.send(Command.Commit(Vector(late.head)))
            late = late.tail
          case 89 | 90 | 91 if pendingStarts.nonEmpty =>
            val id = pendingStarts.head
            pendingStarts = pendingStarts.tail
            startJob(id)
          case 88 =>
            // "Stop all". After the stop, no new intent can commit, so no job of that turn can start later.
            host.send(Command.Abort("all"))
            host.send(Command.StopJobs(JobSelector.OfLastTurn, "all"))
            table.applyStops(host.live)
          case 87 =>
            val which = random.nextInt(3) match
              case 0 => JobSelector.All
              case 1 => JobSelector.OfLastTurn
              case _ => host.live.jobs.keys.headOption.fold(JobSelector.All)(JobSelector.One(_))
            host.send(Command.StopJobs(which, "one"))
            table.applyStops(host.live)
          case n if n >= 94 => grant()
          case 93           =>
            host.send(Command.Close(SessionOutcome.Parked, "park"))
            table.applyStops(host.live)
          case 92 => if host.live.closed then host.send(Command.Activate(versions, config))
          case _  => work()

    for _ <- 1 to 20 + random.nextInt(120) do step()

    // Drive the session to rest, with no new input.
    var rounds = 0
    while host.live.position != Position.NoTurn && rounds < 500 do
      rounds += 1
      if lmInFlight then
        lmInFlight = false
        host.send(Command.Commit(Vector(Entry.Acted("why", "text", None))))
      else
        next(host.live) match
          case Next.Wait =>
            host.send(Command.Abort("rest"))
            host.send(Command.Commit(closing(host.live) :+ Entry.TurnEnded(TurnOutcome.Aborted)))
          case Next.CallPolicy(i, _) =>
            host.send(Command.Commit(Vector(Entry.StepStarted(i))))
            lmInFlight = true
          case Next.RunCell(_, _) =>
            val done = closing(host.live).collect { case entry @ Entry.EffectDone(_, _) => entry }
            host.send(Command.Commit(done :+ Entry.Observed("o", CellOutcome.Ok, Map.empty)))
          case Next.End(outcome) => host.send(Command.Commit(closing(host.live) :+ Entry.TurnEnded(outcome)))
    pendingStarts.foreach(startJob)
    Run(host.entries, host.live, host.durable, changed, answered, host.marks)

/** Small values for the tests of single cases. */
object Fixtures:
  val versions: Versions = Versions("3.10.2-RC1", "1")
  val meta: Meta         = Meta(0L, Source.User, Taint.Trusted, required = true)
  val session: SessionId = SessionId("s1")
  val args: Json         = Json.Object(("command", Json.String("git push origin main")))

  def config(
      kind : TurnKind = TurnKind.Chat,
      steps: Int      = 3,
      owner: Owner    = Owner.User,
      guard: Guard    = Guard.allowAll
  ): SessionConfig =
    SessionConfig(
      kind,
      steps,
      Mode.full,
      Mode.full,
      StrategyName.Window,
      Scope.Scratch("/tmp/session"),
      owner,
      guard,
      "",
      ModelChoice("model", Map.empty),
      None,
      Set.empty
    )

  def activated(c: SessionConfig = config()): Entry = Entry.Activated(versions, c)

  def input(request: String, source: Source = Source.User): (Source, Entry) =
    source -> Entry.Input(RequestId(request), s"message $request", Map.empty)

  /** A journal with these entries in this order, each from its sender. */
  def journalFrom(entries: (Source, Entry)*): Vector[Logged] =
    entries.toVector.zipWithIndex.map { case ((source, entry), i) =>
      Logged(i.toLong, meta.copy(source = source), entry)
    }

  /** A journal with these entries in this order, all from the user. */
  def journal(entries: Entry*): Vector[Logged] = journalFrom(entries.map(Source.User -> _)*)

  /** The journal `start` with these entries at its end. */
  def append(start: Vector[Logged], entries: Vector[Entry]): Vector[Logged] =
    start ++ entries.zipWithIndex.map((entry, i) => Logged((start.size + i).toLong, meta, entry))

  /** The state after the commit of these entries. */
  def commit(s: SessionState, entries: Vector[Entry]): SessionState =
    entries.foldLeft(s)((now, entry) => evolve(now, Logged(now.nextSeq, meta, entry)))

  /** The state after a command that `decide` accepts. */
  def run(s: SessionState, command: Command): SessionState =
    decide(command, s) match
      case Right(entries)  => commit(s, entries)
      case Left(rejection) => throw AssertionError(s"decide refused $command: $rejection")

  /** The entries of an active session with an open turn before its first step. One input waits. */
  def turnEntries(c: SessionConfig = config()): Vector[Entry] =
    Vector(activated(c), Entry.Input(RequestId("r1"), "hi", Map.empty), Entry.TurnStarted)

  /** The entries of a session in a cell. The `Acted` entry has the `seq` 4, so the cell is 4. */
  def cellEntries(c: SessionConfig = config()): Vector[Entry] =
    turnEntries(c) ++ Vector(Entry.StepStarted(0), Entry.Acted("", "", Some("1 + 1")))

  val cell: Long = 4L

  def idle: SessionState   = state(journal(activated()))
  def inTurn: SessionState = state(journal(turnEntries()*))
  def inCell: SessionState = state(journal(cellEntries()*))
  def ended: SessionState  = state(journal(activated(), Entry.Ended(SessionOutcome.Parked, "done")))
