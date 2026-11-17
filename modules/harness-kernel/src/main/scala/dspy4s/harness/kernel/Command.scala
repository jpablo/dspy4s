package dspy4s.harness.kernel

import zio.blocks.schema.json.Json

/** What a client, a worker or a person asks of a session (4.5). Only `decide` turns a command into entries. */
enum Command derives CanEqual:
  case Activate(versions: Versions, config: SessionConfig)

  /** The start of a new session with its task in one commit (8.6, 8.7): `Activated`, then the `Input` of the task, then
    * `TurnStarted`. Only a journal with no entry takes it. `sourceOf` gives `Activated` the source `Harness`, and the
    * `Input` and `TurnStarted` the source of the sender, so `reports` sees the task as a message of the owner. A `loop`
    * above 0 adds `GoalLoopStarted(loop)` after `Activated`: the turns that the owner gives from its own loop (8.5).
    */
  case Begin(versions: Versions, config: SessionConfig, request: RequestId, text: String, loop: Int = 0)
  case Send(request: RequestId, text: String, values: Map[String, Json])
  case Commit(entries: Vector[Entry])
  case Abort(reason: String)
  case StopJobs(which: JobSelector, reason: String)
  case Grant(request: RequestId, rule: GuardRule, lifetime: GrantLifetime)
  case Close(outcome: SessionOutcome, reason: String)
  case ChangeModel(model: ModelChoice)

  /** The end of a job that the host saw (10.3). The host sends it at the end of each job, and `decide` gives one
    * `JobEnded` for it.
    */
  case RecordJobEnd(job: EffectId, end: JobEnd)

  /** `/goal set TEXT`: the new goal of the session (8.5). */
  case ChangeGoal(goal: String)

  /** `/goal on [N]`: the start of a goal loop with the limit `limit` (8.5). Only the person sends it. */
  case StartLoop(limit: Int)

  /** `/goal off`, or the end of the loop of the owner (8.5). Each sender can send it, because the end only takes
    * authority away.
    */
  case StopLoop

  /** The answer of the goal leaf, from the worker (8.5). */
  case RecordGoalCheck(check: GoalCheck)

/** Why `decide` refuses a command (4.5). */
enum Rejection derives CanEqual:

  /** The session has ended. */
  case SessionClosed

  /** `Close` needs a session with no open turn. */
  case TurnOpen

  /** An entry of a `Commit` is not valid at its place in the language of 4.1. */
  case OutOfOrder(entry: Entry)

  /** `Begin` needs a journal with no entry, so a second `start` never activates a session again (8.7). */
  case NotNew

  /** The session runs its release plan (8.7), so it takes no message and no grant. */
  case Releasing

  /** `StopJobs` of this one job, whose end the journal has (10.3). A stop mark after the end changes nothing, so the
    * person learns that the job has ended. In a live run of 2026-11-16, the client said that a job that had exited with
    * the code 0 stops.
    */
  case JobEnded(job: EffectId)

  /** `StartLoop` from a sender that is not the person (8.5). */
  case NotPerson

  /** `StartLoop` with a limit below 1 (8.5). */
  case LoopLimit(limit: Int)

  /** The refusal in words for a person and for a model, with no Scala value. The terminal, the error of a cell and the
    * error of a call of the controller give these words:
    *
    *   - `SessionClosed`: `the session has ended`.
    *   - `TurnOpen`: `a turn is open`.
    *   - `OutOfOrder`: `a StepStarted entry cannot come at this place of the journal`, with the name of the case of the
    *     entry. An entry can hold a long text, so the words give only its name.
    *   - `NotNew`: `the session has a journal already`.
    *   - `Releasing`: `the session runs its release, so it takes no message`.
    *   - `JobEnded`: `the job 12.0 has ended`, with the cell and the number of the job, as `/jobs` names it.
    *   - `NotPerson`: `only the person starts a goal loop`.
    *   - `LoopLimit`: `the limit of a goal loop must be 1 or more, not 0`, with the limit.
    */
  def words: String =
    this match
      case SessionClosed     => "the session has ended"
      case TurnOpen          => "a turn is open"
      case OutOfOrder(entry) =>
        val name    = entry.productPrefix
        val article = if "AEIOU".contains(name.head) then "an" else "a"
        s"$article $name entry cannot come at this place of the journal"
      case NotNew        => "the session has a journal already"
      case Releasing     => "the session runs its release, so it takes no message"
      case JobEnded(job) => s"the job ${job.cell}.${job.n} has ended"
      case NotPerson     => "only the person starts a goal loop"
      case LoopLimit(n)  => s"the limit of a goal loop must be 1 or more, not $n"
