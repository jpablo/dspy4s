package dspy4s.harness.cli

import dspy4s.harness.guest.JobStatus
import dspy4s.harness.host.{CatalogRow, jobName, jobStatusWords}
import dspy4s.harness.kernel.{EffectId, EffectResult, Entry, GuardRule, Logged, Position, SessionId, SessionState}
import dspy4s.harness.sandbox.SandboxedShell

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/** The start of a job in the journal: the command of its `spawn`, and the outcome of the call, or none while the call
  * runs (CODE_HARNESS_DESIGN.md, 10.3).
  */
final case class JobStart(command: String, outcome: Option[EffectResult]) derives CanEqual

/** The start of each `spawn` of `log`, by the id of its job. */
def jobStarts(log: Vector[Logged]): Map[EffectId, JobStart] =
  val commands = log.collect { case Logged(_, _, Entry.EffectStarted(job, "spawn", args)) =>
    job -> commandIn(args).getOrElse(args.print)
  }.toMap
  val outcomes =
    log.collect { case Logged(_, _, Entry.EffectDone(job, result)) if commands.contains(job) => job -> result }.toMap
  commands.map((job, command) => job -> JobStart(command, outcomes.get(job)))

/** The text of `/jobs`: one line for each job in the order of `jobOrder`, with its `jobName`, its status and its
  * command in quotes. A job with no row in the job table and no process did not start when its call failed or waits for
  * an approval, starts while its call runs, and is lost with its host after its call ran.
  */
def jobsText(current: SessionState, starts: Map[EffectId, JobStart], status: Map[EffectId, JobStatus]): String =
  val jobs = jobOrder(current)
  if jobs.isEmpty then "The session has no job."
  else
    jobs.map { job =>
      val start = starts.get(job)
      s"${jobName(job)} ${statusWord(status.get(job), start.flatMap(_.outcome))}: ${commandText(start, job)}"
    }.mkString("\n")

/** The note after a turn that a stop ended (10.3: "If a job of that turn still runs, the client says so and offers to
  * stop it."). It names each job of the turn that started at the `seq` `turn`, that runs and that has no stop mark.
  * None when no such job runs. The note offers only `/stopjob`, because `/stopall` stops the jobs of the newest turn,
  * and a new turn can start after the note.
  */
def stopNotice(
    current: SessionState,
    turn   : Long,
    starts : Map[EffectId, JobStart],
    status : Map[EffectId, JobStatus]
): Option[String] =
  val live = jobOrder(current).filter { job =>
    current.jobs.get(job).exists(record => record.turn == turn && !record.stopRequested) &&
    status.get(job).contains(JobStatus.Running)
  }
  Option.when(live.nonEmpty) {
    val lines = live.map(job => s"  ${jobName(job)}: ${commandText(starts.get(job), job)}")
    val how   = live match
      case Vector(job) => s"/stopjob ${jobName(job)} stops it."
      case _           => "/stopjob ID stops the job ID."
    ("The stop ended the turn, and a job of the turn still runs:" +: lines :+ how).mkString("\n")
  }

/** Each pending call of `current` with its number: the `seq` of the last entry of `log` that asked for it, in the order
  * of these numbers (5.5). A `TurnStarted` clears the pending calls, so `log` can start at the last turn.
  */
def pendingCalls(current: SessionState, log: Vector[Logged]): Vector[(Long, GuardRule)] =
  log.collect {
    case Logged(seq, _, Entry.EffectDone(_, EffectResult.NeedsApproval(rule))) if current.pending.contains(rule) =>
      rule -> seq
  }.groupMapReduce(_._1)(_._2)(_ max _).toVector.map((rule, seq) => seq -> rule).sortBy(_._1)

/** The text of `/status`: the folder of the session, where the turn stands, the pending calls `calls` with their
  * numbers for `/allow N`, and how many jobs run.
  */
def statusText(current: SessionState, status: Map[EffectId, JobStatus], calls: Vector[(Long, GuardRule)]): String =
  val steps = current.config.steps
  val turn  = current.position match
    case Position.NoTurn          => "No turn is open."
    case Position.BeforeStep(i)   => s"A turn is open, before step ${i + 1} of $steps."
    case Position.InPolicy(i)     => s"Step ${i + 1} of $steps."
    case Position.InCell(i, _, _) => s"Step ${i + 1} of $steps: a cell runs."
    case Position.Answered(_)     => "The turn has its reply, and it ends."
    case Position.Finished(_)     => "The turn has its result, and it ends."
  val stop    = Option.when(current.stopping)("A stop is open.")
  val pending = Option.when(calls.nonEmpty) {
    ("Pending calls. /allow N allows the call N, /allow allows each call, and a message refuses them:" +:
      calls.map((seq, rule) => s"  $seq. ${callText(rule)}")).mkString("\n")
  }
  val jobs = Option.when(current.jobs.nonEmpty) {
    val running = current.jobs.keys.count(job => status.get(job).contains(JobStatus.Running))
    s"$running of ${current.jobs.size} jobs run. /jobs lists them."
  }
  val closed = Option.when(current.closed)("The session is closed.")
  val folder = s"The folder: ${SandboxedShell.pathOf(current.config.scope)}."
  (Vector(folder, turn) ++ stop ++ pending ++ jobs ++ closed).mkString("\n")

/** The text of `--list` (10.2): the sessions of `rows`, the newest first. Each session has one line with its id, its
  * status in words (`SessionStatus.words`, as the card of the controller), the time of its last entry in `zone` and its
  * folder. A second line has its first message.
  */
def sessionsText(rows: Vector[CatalogRow], zone: ZoneId): String =
  if rows.isEmpty then "The root has no session."
  else
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    rows.sortBy(row => (-row.lastAt, row.session.value)).map { row =>
      // A file name, a folder and a message can hold a new line, so each shows on one line with its escapes.
      val at   = time.format(Instant.ofEpochMilli(row.lastAt))
      val head = s"${visible(row.session.value)}  ${row.status.words}  $at  ${visible(row.folder)}"
      if row.firstMessage.isEmpty then head else s"$head\n    ${visible(row.firstMessage)}"
    }.mkString("\n")

/** The session of `--session` (10.2) among the sessions `ids` of the journal files: the session whose id is `wanted`,
  * or else the one session whose id starts with `wanted`. An error for an empty `wanted`, and when no session or more
  * than one session starts with it.
  */
def resolveSession(ids: Vector[SessionId], wanted: String): Either[String, SessionId] =
  if wanted.isBlank then Left("Give the id of a session, or the start of it. --list lists them.")
  else
    ids.find(_.value == wanted) match
      case Some(exact) => Right(exact)
      case None        => ids.filter(_.value.startsWith(wanted)) match
          case Vector(one) => Right(one)
          case Vector()    => Left(s"No session of the root has an id that starts with $wanted. --list lists them.")
          case more        =>
            Left(s"${more.size} sessions have an id that starts with $wanted. Give more of the id. --list lists them.")

/** The status of a job in words. A job with a process has the status of the job table in the words of the host
  * (`jobStatusWords`), the same words as the message at the end of a job. A job with no process (no status, or `Lost`)
  * has the outcome of its `spawn` call (10.3).
  */
private def statusWord(status: Option[JobStatus], outcome: Option[EffectResult]): String =
  status match
    case None | Some(JobStatus.Lost) => outcome match
        case None                                                              => "starts"
        case Some(EffectResult.Error(_)) | Some(EffectResult.NeedsApproval(_)) => "did not start"
        case Some(EffectResult.Unknown)                                        => "lost: a stop cut its start"
        case Some(EffectResult.Value(_, _))                                    => "lost with its host"
    case Some(known) => jobStatusWords(known)

/** The command of a job in quotes, or its name when the journal has no command for it. */
private def commandText(start: Option[JobStart], job: EffectId): String =
  start.fold(s"the job ${jobName(job)}")(s => inQuotes(s.command))
