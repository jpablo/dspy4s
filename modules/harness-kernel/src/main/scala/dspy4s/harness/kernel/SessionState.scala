package dspy4s.harness.kernel

import dspy4s.harness.guest.{Access, Mode, Scope, StrategyName}
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** A grant that a person gave, with its lifetime (5.5). */
final case class ActiveGrant(rule: GuardRule, lifetime: GrantLifetime) derives Schema, CanEqual

/** A guest API call with an intent and no outcome (4.2). */
final case class OpenCall(op: String, args: Json) derives CanEqual

/** A job is known from the intent of its `spawn`, so a `spawn` that a stop cuts is still in the fold (10.3). `turn` is
  * the `seq` of the `TurnStarted` of its turn.
  */
final case class JobRecord(turn: Long, stopRequested: Boolean) derives Schema, CanEqual

/** Where the open turn stands (4.2). */
enum Position derives CanEqual:
  case NoTurn
  case BeforeStep(step: Int)
  case InPolicy(step: Int)
  case InCell(step: Int, cell: Long, code: String)
  case Answered(text: String)
  case Finished(value: Json)

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
    releasing  : Boolean = false, // a `Releasing` came, so the session runs its release plan (8.7)
    unattended : Int     = 0,     // the turns in a row that no message of the person started (8.7)
    personWaits: Boolean = false  // a message or a grant of the person came since the last `TurnStarted`
) derives CanEqual

object SessionState:

  /** The most turns in a row that no message of the person starts (8.7). At the limit, a message from another session,
    * the harness or a job waits in the journal, and no turn starts until the person writes. So two sessions cannot send
    * messages to each other with no end, and the start of a host does not go on with them.
    */
  val UnattendedTurns: Int = 8

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
    case Entry.Activated(_, config)      => base.copy(config = config, closed = false)
    case Entry.Input(request, _, values) => base.copy(
        requests = s.requests + request,
        waiting = s.waiting + 1,
        inputs = s.inputs ++ values,
        personWaits = s.personWaits || logged.meta.source == Source.User
      )
    case Entry.TurnStarted =>
      // A new turn closes the old questions: the model asks again if it still needs the call. A turn that a message
      // or a grant of the person starts sets the count of the unattended turns to 0, and each other turn adds 1 (8.7).
      base.copy(
        position = Position.BeforeStep(0),
        lastTurn = logged.seq,
        pending = Set.empty,
        unattended = if s.personWaits then 0 else s.unattended + 1,
        personWaits = false
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
      base.copy(open = s.open - id, grants = grants, pending = pending)
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
    case Entry.Releasing(_)        => base.copy(releasing = true)
    case Entry.Released(_)         => base
    case Entry.TurnEnded(_)        => base.copy(
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
        releasing = false
      )

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

/** The state of a journal fork at the end of a prefix of its parent (5.5). It has no grants, no pending calls and no
  * jobs, and it keeps the inputs.
  */
def forked(prefix: Vector[Logged]): SessionState =
  state(prefix).copy(grants = Set.empty, pending = Set.empty, jobs = Map.empty)
