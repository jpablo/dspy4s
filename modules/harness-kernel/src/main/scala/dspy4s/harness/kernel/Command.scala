package dspy4s.harness.kernel

import zio.blocks.schema.json.Json

/** What a client, a worker or a person asks of a session (4.5). Only `decide` turns a command into entries. */
enum Command derives CanEqual:
  case Activate(versions: Versions, config: SessionConfig)
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
