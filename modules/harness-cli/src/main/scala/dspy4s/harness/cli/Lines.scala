package dspy4s.harness.cli

import dspy4s.harness.host.jobName
import dspy4s.harness.kernel.{Command, EffectId, GrantLifetime, GuardRule, JobSelector, RequestId, SessionId}
import dspy4s.harness.kernel.{SessionOutcome, SessionState}

/** A line that the person types (CODE_HARNESS_DESIGN.md, 10.3 and 5.5). */
enum Line derives CanEqual:

  /** A message to the session. */
  case Message(text: String)

  /** `/stop`: the Stop control of 10.3. */
  case Stop

  /** `/stopall`: the Stop all control of 10.3. */
  case StopAll

  /** `/allow [N] [once|turn|session]`: the answer to the pending calls (5.5). With `call`, only the call whose number
    * is `call`: the `seq` of the entry that asked for it, which the prompt and `/status` show. With no number, each
    * pending call. With no lifetime, `once`, the default lifetime.
    */
  case Allow(lifetime: GrantLifetime, call: Option[Long])

  /** `/jobs`: the jobs of the session with their names and their status (10.3). */
  case Jobs

  /** `/stopjob ID`: the stop of the job `job`, whose name `jobName` gives, for example `12.0` (10.3). */
  case StopJob(job: EffectId)

  /** `/status`: the folder, where the turn stands, the pending calls with their numbers, and the jobs. */
  case Status

  /** `/reasoning off`, `/reasoning short` or `/reasoning full`: how much of the reasoning of a step the client shows.
    */
  case Reasoning(view: ReasoningView)

  /** `/help`: the commands, one line each. */
  case Help

  /** `/close`: the end of the session with an outcome (8.7). `/close` and `/close parked` give `Parked`,
    * `/close merged` gives `Merged`, `/close abandoned REASON` gives `Abandoned(REASON)`, and `/close replaced ID`
    * gives `Replaced(ID)`. With a desk, the client shows the release plan, and the host runs it before the end.
    */
  case Close(outcome: SessionOutcome, reason: String)

  /** `/sessions`: the cards of the sessions of the host (8.7). It needs a desk. */
  case Sessions

  /** `/open ID`: the client shows the session `ID` (8.7). It needs a desk. */
  case Open(session: SessionId)

  /** `/controller`: the client shows the controller again (8.7). It needs a desk. */
  case Controller

  /** `/reopen ID`: the session `ID`, which has ended, opens again (8.7). `/open` of an ended session only says so. It
    * needs a desk.
    */
  case Reopen(session: SessionId)

  /** `/quit`: the end of the client. The session stays open for a later client. */
  case Quit

  /** `/wait SECONDS`: the client reads the next line after `seconds`. In the script mode, the next line then also waits
    * for the open turn, so a script can wait for a job that sends a message at its end.
    */
  case Wait(seconds: Int)

  /** A line that starts with `/` and is not a command. */
  case Unknown(text: String)

/** How much of the reasoning of a step the client shows (`/reasoning`). `Short` is the default. */
enum ReasoningView derives CanEqual:
  case Off, Short, Full

/** The line of `text`. A text that does not start with `/` is a message. */
def parseLine(text: String): Line =
  command(text.trim.split("\\s+").toList).getOrElse {
    if text.trim.startsWith("/") then Line.Unknown(text.trim) else Line.Message(text)
  }

/** The command of the words of a line, or none for a line that is not a command. */
private def command(words: List[String]): Option[Line] =
  words match
    case List("/stop")            => Some(Line.Stop)
    case List("/stopall")         => Some(Line.StopAll)
    case "/allow" :: rest         => allow(rest)
    case List("/jobs")            => Some(Line.Jobs)
    case List("/stopjob", job)    => jobOf(job).map(Line.StopJob(_))
    case List("/status")          => Some(Line.Status)
    case List("/help")            => Some(Line.Help)
    case List("/reasoning", view) => ReasoningView.values.find(_.toString.equalsIgnoreCase(view)).map(Line.Reasoning(_))
    case "/close" :: rest         => close(rest)
    case List("/sessions")        => Some(Line.Sessions)
    case List("/open", id)        => Some(Line.Open(SessionId(id)))
    case List("/controller")      => Some(Line.Controller)
    case List("/reopen", id)      => Some(Line.Reopen(SessionId(id)))
    case List("/quit")            => Some(Line.Quit)
    case List("/wait", seconds)   => seconds.toIntOption.filter(_ >= 0).map(Line.Wait(_))
    case _                        => None

/** The words after `/close`: no word or `parked` gives `Parked`, `merged` gives `Merged`, `abandoned REASON` gives
  * `Abandoned(REASON)`, and `replaced ID` gives `Replaced(ID)` (8.7).
  */
private def close(words: List[String]): Option[Line] =
  words match
    case Nil | List("parked")                     => Some(Line.Close(SessionOutcome.Parked, closed))
    case List("merged")                           => Some(Line.Close(SessionOutcome.Merged, closed))
    case "abandoned" :: reason if reason.nonEmpty =>
      Some(Line.Close(SessionOutcome.Abandoned(reason.mkString(" ")), reason.mkString(" ")))
    case List("replaced", id) => Some(Line.Close(SessionOutcome.Replaced(SessionId(id)), s"$closed: replaced by $id"))
    case _                    => None

/** The reason of a `Close` that the person typed. */
private val closed = "the person closed the session"

/** The words after `/allow`: an optional number of a call, then an optional lifetime. */
private def allow(words: List[String]): Option[Line] =
  val (call, rest) = words match
    case first :: more if first.toLongOption.exists(_ >= 1) => (first.toLongOption, more)
    case more                                               => (None, more)
  val lifetime = rest match
    case Nil             => Some(GrantLifetime.Once)
    case List("once")    => Some(GrantLifetime.Once)
    case List("turn")    => Some(GrantLifetime.Turn)
    case List("session") => Some(GrantLifetime.Session)
    case _               => None
  lifetime.map(Line.Allow(_, call))

/** The job of a name as `jobName` gives it: the cell and the number of the call, for example `12.0`. */
private def jobOf(word: String): Option[EffectId] =
  word.split('.') match
    case Array(cell, n) =>
      for
        c <- cell.toLongOption.filter(_ >= 0)
        k <- n.toIntOption.filter(_ >= 0)
      yield EffectId(c, k)
    case _ => None

/** Each command of the client with what it does: the one list that `/help` shows. A law checks that each command of
  * this list parses.
  */
val commandHelp: Vector[(String, String)] = Vector(
  "/stop"                          -> "stop the turn (Ctrl-C on an empty line does the same during a turn)",
  "/stopall"                       -> "stop the turn and each job of the turn",
  "/allow [N] [once|turn|session]" -> "allow each pending call, or only the call N of the prompt and of /status",
  "/jobs"                          -> "list the jobs of the session",
  "/stopjob ID"                    -> "stop the job ID of /jobs, for example 12.0",
  "/status"                        -> "show the folder, the turn, the pending calls and the jobs",
  "/reasoning off|short|full"      -> "set how much of the reasoning of a step the client shows",
  "/wait SECONDS"                  -> "wait before the next line, for a script",
  "/help"                          -> "show this text",
  "/close"                         -> "end the session as parked; with --controller, show and run its release plan",
  "/close merged"                  -> "end the session as merged",
  "/close abandoned REASON"        -> "end the session as abandoned, with the reason",
  "/close replaced ID"             -> "end the session as replaced by the session ID",
  "/sessions"                      -> "list the sessions of the host (with --controller)",
  "/open ID"                       -> "show the session ID (with --controller)",
  "/reopen ID"                     -> "open again the session ID, which has ended (with --controller)",
  "/controller"                    -> "show the controller again (with --controller)",
  "/quit"                          -> "end the client. The session stays open for --session"
)

/** The text of `/help`: each command of `commandHelp` with what it does. */
val helpText: String =
  val width = commandHelp.map(_._1.length).max + 3
  ("Type a message to the session, or a command:" +:
    commandHelp.map((syntax, text) => s"  ${syntax.padTo(width, ' ')}$text") :+
    "A line that ends with \\ goes on to the next line.").mkString("\n")

/** The jobs of `state` in the order of their start, for `/jobs` (10.3). */
def jobOrder(state: SessionState): Vector[EffectId] = state.jobs.keys.toVector.sortBy(job => (job.cell, job.n))

/** The commands of a line (10.3, 5.5), with `request` as the request id of a message or a grant. `asked` gives the rule
  * of the entry with a `seq` that asked for an approval, if the journal has such an entry.
  *
  *   - `Message` gives one `Send`, and an empty or blank message gives no command.
  *   - `Stop` gives `Abort`, and `StopAll` gives `Abort` and then `StopJobs(OfLastTurn)`.
  *   - `Allow` with no number gives one `Grant` for each pending call of `current`, with the rule of the call and the
  *     lifetime of the line. With no pending call, it gives an error. The request id of the grant of the call k is
  *     `request` and k.
  *   - `Allow` with the number N gives one `Grant` for the rule that the entry N asked for, only while that rule is
  *     pending. A number is the `seq` of an entry, so it does not change when other calls change.
  *   - `StopJob` gives `StopJobs(One(job))` for a job of `current`, or an error.
  *   - `Close` gives `Close` with its outcome and its reason. A client with a desk runs the release instead (8.7).
  *   - `Jobs`, `Status`, `Reasoning`, `Help`, `Quit`, `Wait`, `Sessions`, `Open`, `Reopen` and `Controller` give no
  *     command, because they change only the client. The client runs `Sessions`, `Open`, `Reopen` and `Controller`
  *     through its desk. `Unknown` gives an error that points to `/help`.
  *
  * The client side of `Grant` (5.5): only a client of the user sends `Grant`. To refuse, the person sends a plain
  * message.
  */
def commandsFor(
    line   : Line,
    current: SessionState,
    request: RequestId,
    asked  : Long => Option[GuardRule] = _ => None
): Either[String, Vector[Command]] =
  line match
    case Line.Message(text) =>
      Right(if text.isBlank then Vector.empty else Vector(Command.Send(request, text, Map.empty)))
    case Line.Stop    => Right(Vector(Command.Abort("the person stopped the turn")))
    case Line.StopAll => Right(Vector(
        Command.Abort("the person stopped the turn and its jobs"),
        Command.StopJobs(JobSelector.OfLastTurn, "the person stopped the turn and its jobs")
      ))
    case Line.Allow(lifetime, None) =>
      if current.pending.isEmpty then Left("No call waits for an approval.")
      else
        Right(current.pending.toVector.sortBy(_.toString).zipWithIndex.map { (rule, k) =>
          Command.Grant(RequestId(s"${request.value}-$k"), rule, lifetime)
        })
    case Line.Allow(lifetime, Some(call)) =>
      val calls = "/status lists the pending calls."
      asked(call) match
        case Some(rule) if current.pending.contains(rule) => Right(Vector(Command.Grant(request, rule, lifetime)))
        case Some(_)                                      => Left(s"The call $call does not wait any more. $calls")
        case None                                         => Left(s"No call has the number $call. $calls")
    case Line.StopJob(job) =>
      if current.jobs.contains(job) then
        Right(Vector(Command.StopJobs(JobSelector.One(job), s"the person stopped the job ${jobName(job)}")))
      else Left(s"The session has no job ${jobName(job)}. /jobs lists the jobs.")
    case Line.Close(outcome, reason) => Right(Vector(Command.Close(outcome, reason)))
    case Line.Unknown(text)          => Left(s"$text is not a command. /help lists the commands.")
    // These lines change only the client.
    case Line.Jobs | Line.Status | Line.Reasoning(_) | Line.Help | Line.Quit | Line.Wait(_) | Line.Sessions |
        Line.Open(_) | Line.Controller | Line.Reopen(_) => Right(Vector.empty)
