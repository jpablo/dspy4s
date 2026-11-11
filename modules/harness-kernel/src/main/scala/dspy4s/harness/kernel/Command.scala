package dspy4s.harness.kernel

import zio.blocks.schema.json.Json

/** What a client, a worker or a person asks of a session (4.5). Only `decide` turns a command into entries. */
enum Command derives CanEqual:
  case Activate(versions: Versions, config: SessionConfig)

  /** The start of a new session with its task in one commit (8.6, 8.7): `Activated`, then the `Input` of the task, then
    * `TurnStarted`. Only a journal with no entry takes it. `sourceOf` gives `Activated` the source `Harness`, and the
    * `Input` and `TurnStarted` the source of the sender, so `reports` sees the task as a message of the owner.
    */
  case Begin(versions: Versions, config: SessionConfig, request: RequestId, text: String)
  case Send(request: RequestId, text: String, values: Map[String, Json])
  case Commit(entries: Vector[Entry])
  case Abort(reason: String)
  case StopJobs(which: JobSelector, reason: String)
  case Grant(request: RequestId, rule: GuardRule, lifetime: GrantLifetime)
  case Close(outcome: SessionOutcome, reason: String)

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
