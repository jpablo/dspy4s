package dspy4s.harness.cli

import dspy4s.harness.kernel.{Command, GrantLifetime, RequestId, SessionState}

/** A line that the person types (CODE_HARNESS_DESIGN.md, 10.3 and 5.5). */
enum Line derives CanEqual:

  /** A message to the session. */
  case Message(text: String)

  /** `/stop`: the Stop control of 10.3. */
  case Stop

  /** `/stopall`: the Stop all control of 10.3. */
  case StopAll

  /** `/allow once`, `/allow turn` or `/allow session`: the answer to the pending calls (5.5). */
  case Allow(lifetime: GrantLifetime)

  /** `/close`: the end of the session. */
  case Close

  /** `/quit`: the end of the client. The session stays open for a later client. */
  case Quit

  /** A line that starts with `/` and is not a command. */
  case Unknown(text: String)

/** The line of `text`. A text that does not start with `/` is a message. */
def parseLine(text: String): Line = ???

/** The commands of a line (10.3, 5.5), with `request` as the request id of a message or a grant.
  *
  *   - `Message` gives one `Send`.
  *   - `Stop` gives `Abort`, and `StopAll` gives `Abort` and then `StopJobs(OfLastTurn)`.
  *   - `Allow` gives one `Grant` for each pending call of `state`, with the rule of the call and the lifetime of the
  *     line. With no pending call, it gives an error. The request id of the grant of the call k is `request` and k.
  *   - `Close` gives `Close(Parked)`.
  *   - `Quit` gives no command, and `Unknown` gives an error with the list of the commands.
  *
  * The client side of `Grant` (5.5): only a client of the user sends `Grant`. To refuse, the person sends a plain
  * message.
  */
def commandsFor(line: Line, state: SessionState, request: RequestId): Either[String, Vector[Command]] = ???
