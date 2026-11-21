package dspy4s.harness.kernel

import dspy4s.harness.guest.{Access, Mode, Scope, StrategyName}
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** A grant that a person gave, with its lifetime (5.5). */
final case class ActiveGrant(rule: GuardRule, lifetime: GrantLifetime) derives Schema, CanEqual

/** A guest API call with an intent and no outcome (4.2). */
final case class OpenCall(op: String, args: Json) derives CanEqual

/** A job is known from the intent of its `spawn`, so a `spawn` that a stop cuts is still in the fold (10.3). `turn` is
  * the `seq` of the `TurnStarted` of its turn. `end` is the end that its host recorded with `JobEnded`. It is none
  * while the job runs, and for a job that ran when its host ended.
  */
final case class JobRecord(turn: Long, stopRequested: Boolean, end: Option[JobEnd] = None) derives Schema, CanEqual

/** Where the open turn stands (4.2). */
enum Position derives CanEqual:
  case NoTurn
  case BeforeStep(step: Int)
  case InPolicy(step: Int)
  case InCell(step: Int, cell: Long, code: String)
  case Answered(text: String)
  case Finished(value: Json)

/** The live goal loop of a session (8.5). Only the fold makes it, and an `Activate` ends it.
  *
  * @param limit
  *   N: the most turns in a row that no message of the person starts while the loop is on
  * @param gave
  *   the turns that this session gave to the sessions that it started, since the start of the loop or the last message
  *   of the person
  * @param blocked
  *   the count of the checks in a row with the same blocker
  * @param blocker
  *   the blocker of the last check, or an empty text
  * @param last
  *   the last check of this loop
  */
final case class GoalLoop(
    limit  : Int,
    gave   : Int,
    blocked: Int,
    blocker: String,
    last   : Option[GoalCheck]
) derives CanEqual

object GoalLoop:

  /** A loop with the limit `limit` at its start. */
  def start(limit: Int): GoalLoop = GoalLoop(limit, 0, 0, "", None)

  /** The most checks in a row with the same blocker. At this count the loop ends with `Blocked` (8.5). */
  val BlockedChecks: Int = 3

/** The state of a session: a fold of its journal (4.2). */
final case class SessionState(
    config     : SessionConfig,
    nextSeq    : Long,
    position   : Position,
    stopping   : Boolean,
    closed     : Boolean,
    waiting    : Int, // inputs that no step has seen, since the last stop
    open       : Map[EffectId, OpenCall], // effects with an intent and no outcome
    lastCall   : Int, // the largest `n` of an `EffectStarted` in the open cell, or -1
    store      : Map[String, Json],
    inputs     : Map[String, Json], // the values of the input fields, apart from the store (5.1)
    requests   : Set[RequestId],
    grants     : Set[ActiveGrant],
    pending    : Set[GuardRule], // the pending calls, which wait for a person (5.5)
    lastTurn   : Long, // the `seq` of the last `TurnStarted`
    jobs       : Map[EffectId, JobRecord], // each `spawn` that has an intent
    releasing  : Boolean          = false, // a `Releasing` came, so the session runs its release plan (8.7)
    unattended : Int              = 0,     // the turns in a row that no message of the person started (8.7)
    personWaits: Boolean          = false, // a message or a grant of the person came since the last `TurnStarted`
    loop       : Option[GoalLoop] = None,  // the live goal loop (8.5)
    loopEnd    : Option[LoopEnd]  = None,  // the end of the last goal loop (8.5)
    afterLoop  : Boolean          = false  // a goal loop ended while a message waited, and no turn started since (8.5)
) derives CanEqual

object SessionState:

  /** The default limit of the turns in a row that no message of the person starts (8.7), and the limit of an older
    * host, whose facts have no limit (`SessionConfig.unattendedLimit`). At the limit, a message from another session,
    * the harness or a job waits in the journal, and no turn starts until the person writes. So two sessions cannot send
    * messages to each other with no end, and the start of a host does not go on with them.
    */
  val UnattendedTurns: Int = TurnLimit.DefaultTurns

  /** The limit of a goal loop that `/goal on` gives with no number (8.5). */
  val LoopTurns: Int = 50

  /** The state before the first entry: a closed session with no turn. The first entry of a journal is an `Activated`
    * with the real configuration, so nothing reads this one. It has the least authority.
    */
  lazy val initial: SessionState =
    val none  = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)
    val unset = SessionConfig(
      TurnKind.Chat,
      0,
      none,
      none,
      StrategyName.Window,
      Scope.Scratch(""),
      Owner.Cell,
      Guard.allowAll,
      "",
      ModelChoice("", Map.empty),
      None,
      Set.empty
    )
    SessionState(
      unset,
      0L,
      Position.NoTurn,
      stopping = false,
      closed = true,
      0,
      Map.empty,
      -1,
      Map.empty,
      Map.empty,
      Set.empty,
      Set.empty,
      Set.empty,
      -1L,
      Map.empty
    )

/** The only place where state changes (4.2). Total and pure. */
def evolve(s: SessionState, logged: Logged): SessionState =
  val base = s.copy(nextSeq = logged.seq + 1)
  logged.entry match
    case Entry.Activated(_, config) =>
      // A new activation ends the `Session` grants (5.5): a person gives them again after a quit or a restart.
      // It ends the goal loop too (8.5): a person gives the permission again after a restart.
      base.copy(
        config = config,
        closed = false,
        grants = s.grants.filterNot(_.lifetime == GrantLifetime.Session),
        loop = None
      )
    case Entry.Input(request, _, values) => base.copy(
        requests = s.requests + request,
        waiting = s.waiting + 1,
        inputs = s.inputs ++ values,
        personWaits = s.personWaits || logged.meta.source == Source.User
      )
    case Entry.TurnStarted =>
      // A new turn closes the old questions: the model asks again if it still needs the call. A turn that a message
      // or a grant of the person starts sets the count of the unattended turns to 0, and each other turn adds 1 (8.7).
      // Such a turn also fills the pool of the loop again: the turns that the session gave go to 0 (8.5).
      base.copy(
        position = Position.BeforeStep(0),
        lastTurn = logged.seq,
        pending = Set.empty,
        unattended = if s.personWaits then 0 else s.unattended + 1,
        personWaits = false,
        loop = if s.personWaits then s.loop.map(_.copy(gave = 0)) else s.loop,
        afterLoop = false
      )
    case Entry.StepStarted(step)    => base.copy(position = Position.InPolicy(step), waiting = 0)
    case Entry.Acted(_, text, code) =>
      val step = s.position match
        case Position.InPolicy(i) => i
        case _                    => 0
      // Each cell counts its own calls, so `lastCall` starts again.
      code match
        case Some(source) => base.copy(position = Position.InCell(step, logged.seq, source), lastCall = -1)
        case None         => base.copy(position = Position.Answered(text), lastCall = -1)
    case Entry.EffectStarted(id, op, args) =>
      val jobs = if op == spawnOp then s.jobs + (id -> JobRecord(s.lastTurn, stopRequested = false)) else s.jobs
      base.copy(open = s.open + (id -> OpenCall(op, args)), lastCall = s.lastCall.max(id.n), jobs = jobs)
    case Entry.EffectDone(id, result) =>
      // `guarded` reads the grants after the commit of `EffectStarted`, so a `Once` grant stays until the outcome.
      val grants  = s.open.get(id).fold(s.grants)(call => s.grants.filterNot(usedUp(call.op, call.args)))
      val pending = result match
        case EffectResult.NeedsApproval(rule) if !s.grants.exists(_.rule.covers(rule)) => s.pending + rule
        case _                                                                         => s.pending
      // A `start` with a value gave the turns of its `loop` from the pool of the loop (8.5).
      val loop = (s.open.get(id), result) match
        case (Some(call), EffectResult.Value(_, _)) if call.op == startOp =>
          s.loop.map(l => l.copy(gave = l.gave + loopOf(call.args)))
        case _ => s.loop
      base.copy(open = s.open - id, grants = grants, pending = pending, loop = loop)
    case Entry.Observed(_, outcome, writes) =>
      val position = (s.position, outcome) match
        case (_, CellOutcome.Submitted(value)) => Position.Finished(value)
        case (Position.InCell(i, _, _), _)     => Position.BeforeStep(i + 1)
        case (other, _)                        => other
      base.copy(position = position, store = s.store ++ writes)
    case Entry.StopRequested(_)                 => base.copy(stopping = true, waiting = 0)
    case Entry.JobsStopRequested(which, _)      => base.copy(jobs = stopJobs(s, which))
    case Entry.Granted(request, rule, lifetime) => base.copy(
        requests = s.requests + request,
        grants = s.grants + ActiveGrant(rule, lifetime),
        pending = s.pending.filterNot(rule.covers),
        personWaits = true // only a client of the person sends `Grant` (5.5)
      )
    case Entry.ScopeRecorded(_, _) => base
    // The read of the derived data changes no part of the state (7.2, choice 3).
    case Entry.DerivedRead(_, _) => base
    case Entry.Releasing(_)      => base.copy(releasing = true)
    case Entry.Released(_)       => base
    case Entry.TurnEnded(_)      => base.copy(
        position = Position.NoTurn,
        stopping = false,
        grants = s.grants.filterNot(_.lifetime == GrantLifetime.Turn)
      )
    case Entry.Ended(_, _) =>
      // The end closes a release too, so a session that opens again takes messages.
      base.copy(
        closed = true,
        grants = Set.empty,
        pending = Set.empty,
        jobs = stopJobs(s, JobSelector.All),
        releasing = false,
        loop = None
      )
    case Entry.ModelChanged(model) => base.copy(config = s.config.copy(model = model))
    case Entry.JobEnded(job, end)  =>
      // The end of a job that the state does not have changes no job. A stop mark stays with the end.
      base.copy(jobs = s.jobs.updatedWith(job)(_.map(_.copy(end = Some(end)))))
    case Entry.GoalChanged(goal)      => base.copy(config = s.config.copy(goal = Some(goal)))
    case Entry.GoalLoopStarted(limit) =>
      base.copy(loop = Some(GoalLoop.start(limit)), unattended = 0, afterLoop = false)
    case Entry.GoalChecked(check) => base.copy(loop = s.loop.map(afterCheck(_, check)))
    case Entry.GoalLoopEnded(end) =>
      // The end of a loop starts no turn by itself: a message that waits then waits for the person (8.5).
      base.copy(loop = None, loopEnd = Some(end), afterLoop = s.waiting > 0)

/** The operation that starts a job (10.3). */
private val spawnOp = "spawn"

/** The jobs with a mark on each job that `which` covers. `OfLastTurn` covers the jobs of the last `TurnStarted`. */
private def stopJobs(s: SessionState, which: JobSelector): Map[EffectId, JobRecord] =
  s.jobs.map { (id, job) =>
    val covered = which match
      case JobSelector.One(target) => id == target
      case JobSelector.OfLastTurn  => job.turn == s.lastTurn
      case JobSelector.All         => true
    id -> (if covered then job.copy(stopRequested = true) else job)
  }

/** True for a `Once` grant that the call uses up. */
private def usedUp(op: String, args: Json)(grant: ActiveGrant): Boolean =
  grant.lifetime == GrantLifetime.Once && grant.rule.matches(op, args)

/** The state after a journal: `journal.foldLeft(SessionState.initial)(evolve)` (4.2). */
def state(journal: Vector[Logged]): SessionState = journal.foldLeft(SessionState.initial)(evolve)

/** The state of a journal fork at the end of a prefix of its parent (5.5). It has no grants, no pending calls, no jobs
  * and no goal loop (8.5), and it keeps the inputs.
  */
def forked(prefix: Vector[Logged]): SessionState =
  state(prefix).copy(grants = Set.empty, pending = Set.empty, jobs = Map.empty, loop = None)
