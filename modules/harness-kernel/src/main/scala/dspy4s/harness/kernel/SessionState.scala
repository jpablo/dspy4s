package dspy4s.harness.kernel

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
    config  : SessionConfig,
    nextSeq : Long,
    position: Position,
    stopping: Boolean,
    closed  : Boolean,
    waiting : Int,                     // inputs that no step has seen, since the last stop
    open    : Map[EffectId, OpenCall], // effects with an intent and no outcome
    store   : Map[String, Json],
    inputs  : Map[String, Json],       // the values of the input fields, apart from the store (5.1)
    requests: Set[RequestId],
    grants  : Set[ActiveGrant],
    pending : Set[GuardRule],          // the pending calls, which wait for a person (5.5)
    lastTurn: Long,                    // the `seq` of the last `TurnStarted`
    jobs    : Map[EffectId, JobRecord] // each `spawn` that has an intent
) derives CanEqual

object SessionState:

  /** The state before the first entry: a closed session with no turn. */
  lazy val initial: SessionState = ???

/** The only place where state changes (4.2). Total and pure. */
def evolve(s: SessionState, logged: Logged): SessionState = ???

/** The state after a journal: `journal.foldLeft(SessionState.initial)(evolve)` (4.2). */
def state(journal: Vector[Logged]): SessionState = ???

/** The state of a journal fork at the end of a prefix of its parent (5.5). It has no grants, no pending calls and no
  * jobs, and it keeps the inputs.
  */
def forked(prefix: Vector[Logged]): SessionState = ???
