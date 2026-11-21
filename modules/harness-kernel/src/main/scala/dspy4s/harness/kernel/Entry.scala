package dspy4s.harness.kernel

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** The sender of an entry (4.1). */
enum Source derives Schema, CanEqual:
  case User, Controller, Harness, Policy
  case Child(session: SessionId)

  /** The cell of a parent session: the sender of the task of an `rlm` child (8.2). `effect` is the `rlm` or
    * `rlmBatched` call in the journal of `session`, so the journal of a child links to the call of its parent.
    */
  case Parent(session: SessionId, effect: EffectId)

/** `External` marks a value that came from outside the session, for example a web page (5.6). */
enum Taint derives Schema, CanEqual:
  case Trusted, External

/** The data that the model never sees (4.1). `required` tells a reader what to do with an entry that it cannot decode:
  * it stops a rebuild if `required` is true, and it skips the entry if `required` is false.
  */
final case class Meta(atMillis: Long, source: Source, taint: Taint, required: Boolean) derives Schema, CanEqual

/** The outcome of a guest API call (4.1). */
enum EffectResult derives Schema, CanEqual:

  /** The value for a replay, and the text for a reader. */
  case Value(value: Json, rendered: String)

  case Error(message: String)

  /** A pending call (5.5). */
  case NeedsApproval(rule: GuardRule)

  /** A stop or a crash cut the call. */
  case Unknown

/** The outcome of a cell. The cases come from the sketch of section 12. */
enum CellOutcome derives Schema, CanEqual:
  case Ok
  case CompileError(diagnostics: Vector[String])
  case Failed(exception: String)
  case TimedOut(afterMillis: Long)
  case Submitted(value: Json)

  /** The outcome in words for a person, with no Scala value. The terminal shows these words on the first line of the
    * output of a cell:
    *
    *   - `Ok`: `ok`.
    *   - `CompileError`: `the cell did not compile`. The output of the cell holds the errors, so the words do not
    *     repeat them.
    *   - `Failed`: `the cell failed: ` and the exception.
    *   - `TimedOut`: `the cell stopped: it did not end in N s`, with N the whole seconds.
    *   - `Submitted`: `the cell submitted: ` and the value. A text value has no quotes, and another value is JSON.
    */
  def words: String =
    this match
      case Ok                            => "ok"
      case CompileError(_)               => "the cell did not compile"
      case Failed(exception)             => s"the cell failed: $exception"
      case TimedOut(afterMillis)         => s"the cell stopped: it did not end in ${afterMillis / 1000} s"
      case Submitted(Json.String(value)) => s"the cell submitted: $value"
      case Submitted(value)              => s"the cell submitted: ${value.print}"

/** The outcome of a turn (4.1, 4.3). */
enum TurnOutcome derives Schema, CanEqual:
  case Replied(text: String)
  case Submitted(value: Json)
  case Exhausted
  case Aborted
  case Crashed
  case Failed(error: String)

  /** The text of the report of a turn with this outcome (8.6). The owner reads it with a model, and the terminal shows
    * it, so it gives the outcome in words and not as a Scala value:
    *
    *   - `Submitted`: `The turn ended with submit: ` and the value. A text value has no quotes, and another value is
    *     JSON.
    *   - `Replied`: `The turn ended with a reply: ` and the reply.
    *   - `Exhausted`, `Aborted` and `Crashed`: one sentence that tells what ended the turn.
    *   - `Failed`: `The turn failed: ` and the error.
    */
  def report: String =
    this match
      case Submitted(Json.String(value)) => s"The turn ended with submit: $value"
      case Submitted(value)              => s"The turn ended with submit: ${value.print}"
      case Replied(reply)                => s"The turn ended with a reply: $reply"
      case Exhausted                     => "The turn ended: it used all its steps and gave no result."
      case Aborted                       => "The turn ended: a stop ended it."
      case Crashed                       => "The turn crashed: its host stopped during the turn."
      case Failed(message)               => s"The turn failed: $message"

/** The outcome of a session (8.4). The cases come from the sketch of section 12. */
enum SessionOutcome derives Schema, CanEqual:
  case Merged, Parked
  case Abandoned(reason: String)
  case Replaced(by: SessionId)

  /** The outcome in words, with no reason, for example `abandoned` or `replaced by s2`. The `Ended` entry holds the
    * reason, so a text that adds the reason shows it one time. The report to the owner and the terminal use these
    * words.
    */
  def words: String =
    this match
      case Merged       => "merged"
      case Parked       => "parked"
      case Abandoned(_) => "abandoned"
      case Replaced(by) => s"replaced by ${by.value}"

  /** The text of the end of a session with this outcome and `reason` (8.6): the report to the owner and the line of the
    * terminal. It gives the reason one time.
    */
  def report(reason: String): String = s"The session ended: $words, $reason."

/** Which jobs a stop covers (10.3). */
enum JobSelector derives Schema, CanEqual:
  case One(job: EffectId)

  /** The open turn, or the last turn if no turn is open. */
  case OfLastTurn

  case All

/** The end of a job that its host saw (10.3). `Stopped` is the end after a stop: a stop mark, or the close of the host.
  * The end holds no output, so the output of a job stays lost after a restart.
  */
enum JobEnd derives Schema, CanEqual:
  case Exited(code: Int)
  case Stopped

/** The answer of the `goal` leaf in the journal (8.5).
  *
  * @param met
  *   true when the goal is met now
  * @param reason
  *   why the goal is met or not met, in one or two sentences
  * @param blocker
  *   what stops the work now, or an empty text when nothing stops it
  * @param same
  *   true when `blocker` is the blocker of the last check
  */
final case class GoalCheck(met: Boolean, reason: String, blocker: String, same: Boolean) derives Schema, CanEqual

/** Why a goal loop ended (8.5). */
enum LoopEnd derives Schema, CanEqual:

  /** A check says that the goal is met. */
  case Met(reason: String)

  /** Three checks in a row have the same blocker. */
  case Blocked(blocker: String)

  /** The count of the unattended turns is at the limit of the loop, and the goal is not met. */
  case Limit

  /** `/goal off`, or the end of the loop of the owner. */
  case Off

  /** A stop of the person: `/stop`, `/stopall` or Ctrl-C. */
  case Stopped

  /** An `Activate`: a restart takes the permission back. */
  case Restarted

  /** One word for the card of a session and for `/goal`: `met`, `blocked`, `limit`, `off`, `stopped` or `restarted`.
    */
  def words: String =
    this match
      case Met(_)     => "met"
      case Blocked(_) => "blocked"
      case Limit      => "limit"
      case Off        => "off"
      case Stopped    => "stopped"
      case Restarted  => "restarted"

  /** The first line of the report to the owner at the end of the loop (8.5). The owner reads it with a model, so it
    * gives the end in words, and not as a Scala value.
    */
  def report: String =
    this match
      case Met(reason)      => s"The goal loop ended: the goal is met. $reason"
      case Blocked(blocker) => s"The goal loop ended: the work is blocked: $blocker"
      case Limit            => "The goal loop ended: it used all its turns, and the goal is not met."
      case Off              => "The goal loop ended: the person or the owner turned it off."
      case Stopped          => "The goal loop ended: the person stopped the session."
      case Restarted        => "The goal loop ended: the host started again."

/** The entries of a journal (4.1). The entries of one turn form a small language:
  * {{{
  * Turn ::= TurnStarted Step* TurnEnded
  * Step ::= StepStarted DerivedRead* [ Acted [ Cell ] ]
  * Cell ::= ( EffectStarted | EffectDone )* Observed
  * }}}
  * `Input`, `StopRequested`, `JobsStopRequested`, `JobEnded`, `Granted`, `ScopeRecorded`, `ModelChanged` and the
  * entries of the goal loop (`GoalChanged`, `GoalLoopStarted`, `GoalChecked` and `GoalLoopEnded`, 8.5) can occur at any
  * place. `Releasing` occurs only with no open turn, and each `Released` comes after it (8.7):
  * {{{
  * Release ::= Releasing JobsStopRequested? Released* Ended
  * }}}
  */
enum Entry derives Schema, CanEqual:

  /** A start, a reopen, or the first entry after a crash. */
  case Activated(versions: Versions, config: SessionConfig)

  /** A message. `meta.source` is the sender. */
  case Input(request: RequestId, text: String, values: Map[String, Json])

  case TurnStarted

  /** The intent of the LM call. */
  case StepStarted(step: Int)

  /** The outcome of the LM call, and the intent of the cell. */
  case Acted(reasoning: String, text: String, code: Option[String])

  /** The intent of a guest API call. */
  case EffectStarted(id: EffectId, op: String, args: Json)

  /** The outcome of the guest API call. */
  case EffectDone(id: EffectId, result: EffectResult)

  /** The outcome of the cell. */
  case Observed(output: String, outcome: CellOutcome, writes: Map[String, Json])

  case StopRequested(reason: String)

  /** A stop of jobs, apart from the turn (10.3). */
  case JobsStopRequested(which: JobSelector, reason: String)

  /** A person widened the guard (5.5). */
  case Granted(request: RequestId, rule: GuardRule, lifetime: GrantLifetime)

  /** The git head of the scope, at the end of each turn. */
  case ScopeRecorded(head: String, clean: Boolean)

  /** The start of the release of the session with its plan (CODE_HARNESS.md, 7.9; 8.7). After it the session takes no
    * message and no grant, and no turn starts, so a turn never runs in a folder that the release removes. After a
    * crash, the host goes on from the first item with no `Released`.
    */
  case Releasing(plan: Vector[ReleaseItem])

  /** One step of the release plan of the session, after its `Releasing` and before its `Ended` (CODE_HARNESS.md, 7.9;
    * 8.7).
    */
  case Released(step: ReleaseStep)

  case TurnEnded(outcome: TurnOutcome)

  case Ended(outcome: SessionOutcome, reason: String)

  /** A person changed the model of the session, for example its reasoning effort (CLI_SCREEN.md, 14.4). The fold puts
    * it in the configuration, so the next call to the policy and a reopen use it.
    */
  case ModelChanged(model: ModelChoice)

  /** The end of a job (10.3). The host commits it at the end of each job, also with no `report`, so a later host gives
    * the status of the job and not `Lost`. The fold puts the end in the record of the job.
    */
  case JobEnded(job: EffectId, end: JobEnd)

  /** The new goal of the session (8.5). The fold puts it in the configuration, as `ModelChanged` does with the model,
    * so a reopen keeps it.
    */
  case GoalChanged(goal: String)

  /** The start of a goal loop with the limit `limit` (8.5). Only the person starts it, or the owner in `Begin`. */
  case GoalLoopStarted(limit: Int)

  /** The answer of the goal leaf after a turn (8.5). */
  case GoalChecked(check: GoalCheck)

  /** The end of the goal loop (8.5). */
  case GoalLoopEnded(end: LoopEnd)

  /** The derived data that the first step of a turn read (CODE_HARNESS_DESIGN.md, 7.2, choice 3): the derivation and
    * the newest build number of its store. A later read up to the same number gives the same lines, so a replay can
    * make the same prompt again. It comes after the `StepStarted` and before the `Acted` of the step, and the fold
    * ignores it. This case is the last one, so the earlier cases keep their places.
    */
  case DerivedRead(derivation: DerivationName, build: Long)

/** A derivation of the journal (CODE_HARNESS_DESIGN.md, 7.2): a fold that makes derived data, with a store of its own.
  * The memory tree is the first one.
  */
enum DerivationName derives Schema, CanEqual:

  /** The built lines of the memory tree (7.1). */
  case Memory

/** One line of the journal: an entry with its `seq` and its meta (4.1). */
final case class Logged(seq: Long, meta: Meta, entry: Entry) derives Schema, CanEqual

/** The operations that change nothing outside the session (4.1). Each other operation flushes, so a new one is safe. */
val readOnlyOps: Set[String] = Set(
  "read",
  "keys",
  "load",
  "sessions",
  "jobOutput",
  "waitJob",
  "now",
  "zoom",
  "observe",
  "llmQuery",
  "llmQueryBatched"
)

/** True for an entry that reaches the disk before `commit` returns (4.1): an `Input`, because a sender has an answer,
  * an `Activated`, because a parent has a child, an `EffectStarted` of an operation that is not read-only, a
  * `Releasing`, because the plan must survive a crash in it, and a `Released`, because its step changed the scope
  * (8.7).
  */
def needsFlush(entry: Entry): Boolean =
  entry match
    case Entry.EffectStarted(_, op, _)                => !readOnlyOps(op)
    case Entry.Input(_, _, _) | Entry.Activated(_, _) => true
    case Entry.Releasing(_) | Entry.Released(_)       => true
    case _                                            => false
