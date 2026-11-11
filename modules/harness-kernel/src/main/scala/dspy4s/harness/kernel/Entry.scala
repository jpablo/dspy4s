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

/** The outcome of a turn (4.1, 4.3). */
enum TurnOutcome derives Schema, CanEqual:
  case Replied(text: String)
  case Submitted(value: Json)
  case Exhausted
  case Aborted
  case Crashed
  case Failed(error: String)

/** The outcome of a session (8.4). The cases come from the sketch of section 12. */
enum SessionOutcome derives Schema, CanEqual:
  case Merged, Parked
  case Abandoned(reason: String)
  case Replaced(by: SessionId)

/** Which jobs a stop covers (10.3). */
enum JobSelector derives Schema, CanEqual:
  case One(job: EffectId)

  /** The open turn, or the last turn if no turn is open. */
  case OfLastTurn

  case All

/** The entries of a journal (4.1). The entries of one turn form a small language:
  * {{{
  * Turn ::= TurnStarted Step* TurnEnded
  * Step ::= StepStarted [ Acted [ Cell ] ]
  * Cell ::= ( EffectStarted | EffectDone )* Observed
  * }}}
  * `Input`, `StopRequested`, `JobsStopRequested`, `Granted` and `ScopeRecorded` can occur at any place. `Releasing`
  * occurs only with no open turn, and each `Released` comes after it (8.7):
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
