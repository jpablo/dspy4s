package dspy4s.harness.cli

import dspy4s.harness.kernel.{Command, GrantLifetime, JobSelector, RequestId, SessionOutcome, SessionState}

/** A line that the person types (CODE_HARNESS_DESIGN.md, 10.3 and 5.5). */
enum Line derives CanEqual:

  /** A message to the session. */
  case Message(text: String)

  /** `/stop`: the Stop control of 10.3. */
  case Stop

  /** `/stopall`: the Stop all control of 10.3. */
  case StopAll

  /** `/allow once`, `/allow turn` or `/allow session`: the answer to the pending calls (5.5). `/allow` alone is
    * `/allow once`, the default lifetime.
    */
  case Allow(lifetime: GrantLifetime)

  /** `/close`: the end of the session. */
  case Close

  /** `/quit`: the end of the client. The session stays open for a later client. */
  case Quit

  /** `/wait SECONDS`: the client reads the next line after `seconds`. In the script mode, the next line then also waits
    * for the open turn, so a script can wait for a job that sends a message at its end.
    */
  case Wait(seconds: Int)

  /** A line that starts with `/` and is not a command. */
  case Unknown(text: String)

/** The line of `text`. A text that does not start with `/` is a message. */
def parseLine(text: String): Line =
  text.trim.split("\\s+").toList match
    case List("/stop")                                                => Line.Stop
    case List("/stopall")                                             => Line.StopAll
    case List("/allow")                                               => Line.Allow(GrantLifetime.Once)
    case List("/allow", "once")                                       => Line.Allow(GrantLifetime.Once)
    case List("/allow", "turn")                                       => Line.Allow(GrantLifetime.Turn)
    case List("/allow", "session")                                    => Line.Allow(GrantLifetime.Session)
    case List("/close")                                               => Line.Close
    case List("/quit")                                                => Line.Quit
    case List("/wait", seconds) if seconds.toIntOption.exists(_ >= 0) => Line.Wait(seconds.toInt)
    case _ if text.trim.startsWith("/")                               => Line.Unknown(text.trim)
    case _                                                            => Line.Message(text)

/** The commands that a client knows, for the text of an unknown command. */
val commandList: String = "/stop, /stopall, /allow once, /allow turn, /allow session, /wait SECONDS, /close and /quit"

/** The commands of a line (10.3, 5.5), with `request` as the request id of a message or a grant.
  *
  *   - `Message` gives one `Send`, and an empty or blank message gives no command.
  *   - `Stop` gives `Abort`, and `StopAll` gives `Abort` and then `StopJobs(OfLastTurn)`.
  *   - `Allow` gives one `Grant` for each pending call of `state`, with the rule of the call and the lifetime of the
  *     line. With no pending call, it gives an error. The request id of the grant of the call k is `request` and k.
  *   - `Close` gives `Close(Parked)`.
  *   - `Quit` and `Wait` give no command, and `Unknown` gives an error with the list of the commands.
  *
  * The client side of `Grant` (5.5): only a client of the user sends `Grant`. To refuse, the person sends a plain
  * message.
  */
def commandsFor(line: Line, state: SessionState, request: RequestId): Either[String, Vector[Command]] =
  line match
    case Line.Message(text) =>
      Right(if text.isBlank then Vector.empty else Vector(Command.Send(request, text, Map.empty)))
    case Line.Stop    => Right(Vector(Command.Abort("the person stopped the turn")))
    case Line.StopAll => Right(Vector(
        Command.Abort("the person stopped the turn and its jobs"),
        Command.StopJobs(JobSelector.OfLastTurn, "the person stopped the turn and its jobs")
      ))
    case Line.Allow(lifetime) =>
      if state.pending.isEmpty then Left("No call waits for an approval.")
      else
        Right(state.pending.toVector.sortBy(_.toString).zipWithIndex.map { (rule, k) =>
          Command.Grant(RequestId(s"${request.value}-$k"), rule, lifetime)
        })
    case Line.Close               => Right(Vector(Command.Close(SessionOutcome.Parked, "the person closed the session")))
    case Line.Quit | Line.Wait(_) => Right(Vector.empty)
    case Line.Unknown(text)       => Left(s"$text is not a command. The commands are $commandList.")
