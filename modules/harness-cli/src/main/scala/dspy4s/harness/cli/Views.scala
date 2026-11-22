package dspy4s.harness.cli

import dspy4s.harness.guest.{Access, JobStatus}
import dspy4s.harness.host.{CatalogRow, ContextUse, ExchangeFile, SessionFile, jobName, jobStatusWords}
import dspy4s.harness.kernel.{EffectId, EffectResult, Entry, GuardRule, Logged, Position, SessionId, SessionState}
import dspy4s.harness.kernel.{GoalLoop, TurnLimit, grouped, limitLines, modeWords, sandboxLines}
import dspy4s.harness.kernel.{unattendedLimit, unattendedUsed, waitsForPerson}
import dspy4s.harness.inspect.Inspector
import dspy4s.harness.sandbox.SandboxedShell

import java.nio.file.Path
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
  * command in quotes. After a reopen, a job has the end that the journal recorded (`JobEnded`). A job with no process
  * and no end did not start when its call failed or waits for an approval, starts while its call runs, and is lost with
  * its host after its call ran.
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

/** The notice of a message that waits for the person (8.7), or none when no message waits (`waitsForPerson`). The
  * session ran its limit of turns in a row with no message of the person, or its goal loop ended while the message
  * waited (8.5), so no turn starts until the person writes. The notice gives the count and the limit apart, because a
  * restart with a lower limit leaves a count above it. It says `1 turn` for one turn, and `N turns` for more (`count`).
  */
def heldText(s: SessionState): Option[String] =
  unattendedLimit(s) match
    case _ if s.afterLoop && waitsForPerson(s) =>
      val why = s.loopEnd.fold("The goal loop ended.")(_.report)
      Some(
        s"A message waits for you. $why The end of a goal loop does not start a turn. Type a message, and the next " +
          "turn reads it with the messages that wait, or type /goal on [N]."
      )
    case TurnLimit.AtMost(turns) if waitsForPerson(s) =>
      Some(
        s"A message waits for you: the session ran ${count(unattendedUsed(s), "turn")} in a row with no message of " +
          s"yours, and the limit is ${grouped(turns.toLong)}. Type a message, and the next turn reads it with the " +
          "messages that wait. --unattended-turns N changes the limit at the next start."
      )
    case _ => None

/** The text of `/status`: the folder of the session, where the turn stands, the turns in a row with no message of the
  * person and the notice of a message that waits (`heldText`), the reasoning effort, the context of the model
  * (`context`, with the window of `facts`), the pending calls `calls` with their numbers for `/allow N`, and how many
  * jobs run. A part with no value does not show.
  */
def statusText(
    current: SessionState,
    status : Map[EffectId, JobStatus],
    calls  : Vector[(Long, GuardRule)],
    context: Option[ContextUse] = None,
    facts  : ModelFacts         = ModelFacts.unknown
): String =
  val steps = current.config.steps
  val turn  = current.position match
    case Position.NoTurn          => "No turn is open."
    case Position.BeforeStep(i)   => s"A turn is open, before step ${i + 1} of $steps."
    case Position.InPolicy(i)     => s"Step ${i + 1} of $steps."
    case Position.InCell(i, _, _) => s"Step ${i + 1} of $steps: a cell runs."
    case Position.Answered(_)     => "The turn has its reply, and it ends."
    case Position.Finished(_)     => "The turn has its result, and it ends."
  // While a goal loop is on, the lines of the loop give the count (8.5).
  val unattended = Option.when(current.unattended > 0 && current.loop.isEmpty) {
    val count = grouped(current.unattended.toLong)
    current.config.unattendedLimit match
      case TurnLimit.AtMost(turns) => s"Turns in a row with no message of yours: $count of ${grouped(turns.toLong)}."
      case TurnLimit.NoLimit       => s"Turns in a row with no message of yours: $count (no limit)."
  }
  val stop    = Option.when(current.stopping)("A stop is open.")
  val pending = Option.when(calls.nonEmpty) {
    ("Pending calls. /allow N allows the call N, /allow allows each call, and a message refuses them:" +:
      calls.map((seq, rule) => s"  $seq. ${callText(rule)}")).mkString("\n")
  }
  val grants = Option.when(current.grants.nonEmpty)(("Active grants:" +: grantLines(current)).mkString("\n"))
  val jobs   = Option.when(current.jobs.nonEmpty) {
    val running = current.jobs.keys.count(job => status.get(job).contains(JobStatus.Running))
    s"$running of ${current.jobs.size} jobs run. /jobs lists them."
  }
  val effort = Effort.of(current.config.model).map(level => s"The effort: $level.")
  val tokens = context.map(use => s"The context: ${Screen.contextWords(use, facts.window, " tokens")}.")
  val closed = Option.when(current.closed)("The session is closed.")
  val folder = s"The folder: ${SandboxedShell.pathOf(current.config.scope)}."
  val goal   = Option.when(current.config.goal.nonEmpty || current.loop.nonEmpty || current.loopEnd.nonEmpty)(
    loopText(current)
  )
  (Vector(folder, turn) ++ unattended ++ heldText(current) ++ effort ++ tokens ++ stop ++ pending ++ grants ++ jobs ++
    goal ++ closed).mkString("\n")

/** The text of `/goal`, and the goal part of `/status` (8.5): the goal, the loop on or off, the turns used of N, the
  * last check, and the count of the same blocker of `GoalLoop.BlockedChecks`. With no loop, it gives how the last loop
  * ended.
  */
def loopText(current: SessionState): String =
  val goal = current.config.goal.fold("The session has no goal. /goal set TEXT gives it one.")(g => s"The goal: $g")
  val loop = current.loop match
    case Some(on) =>
      val used    = unattendedUsed(current)
      val checked = on.last.fold("No goal check yet.") { c =>
        s"The last goal check: ${if c.met then "met" else "not met"}. ${c.reason}"
      }
      val blocker =
        Option.when(on.blocked > 0)(s"The blocker, ${on.blocked} of ${GoalLoop.BlockedChecks} checks: ${on.blocker}")
      Vector(s"The goal loop is on: $used of ${on.limit} turns with no person. /goal off ends it.", checked) ++ blocker
    case None =>
      val last = current.loopEnd.fold("")(why => s" ${why.report}")
      Vector(s"The goal loop is off.$last /goal on [N] turns it on.")
  (goal +: loop).mkString("\n")

/** One line for each active grant of `current`, with its lifetime in words and its call, for example
  * `  for this session: exec "git push"`. The grants with a shorter lifetime come first (5.5).
  */
private def grantLines(current: SessionState): Vector[String] =
  current.grants.toVector.map(grant => (grant.lifetime.ordinal, s"  ${grant.lifetime.words}: ${callText(grant.rule)}"))
    .sorted.map(_._2)

/** What the client knows apart from the journal, for `/config` and `/inspect`: the root of the host, the base URL of
  * the endpoint of the model, whether the host writes the exchange file of each session (`DSPY_EXCHANGES`), and the
  * port that `/inspect` tries first (`--port`). An empty root or URL is not known.
  */
final case class ClientFacts(
    root       : Path,
    endpoint   : String,
    exchanges  : Boolean,
    inspectPort: Int = Inspector.DefaultPort
) derives CanEqual

object ClientFacts:
  val unknown: ClientFacts = ClientFacts(Path.of(""), "", exchanges = false)

/** The text of `/config`: each setting that acts on the session `id` and on its turns, in eight parts. The session, the
  * model, the mode, the guard, the limits and the sandbox come from the configuration of the last `Activated`
  * (`current.config`), which holds the facts of the host of this activation (`HostFacts`). The grants come from
  * `current`, the context window from `facts`, and the endpoint and the files from `client`. A path in the home folder
  * `home` starts with `~`. An activation of an older host has no limits and no sandbox, and the text says so. Its
  * limits give the steps of a turn and the limit of the turns with no person of that host (8.7).
  */
def configText(id: SessionId, current: SessionState, client: ClientFacts, facts: ModelFacts, home: String): String =
  val config                      = current.config
  def short(path: String): String =
    if home.nonEmpty && (path == home || path.startsWith(home + "/")) then "~" + path.drop(home.length) else path
  def lines(pairs: Vector[(String, String)]): Vector[String] = pairs.map((name, value) => s"  $name: ${visible(value)}")
  def known(text : String): String                           = if text.isEmpty then "not known" else text
  val noFacts                                                = "  This activation recorded no limits and no sandbox."
  val session                                                = lines(Vector(
    "id"           -> id.value,
    "owner"        -> config.owner.words,
    "goal"         -> config.goal.getOrElse("none"),
    "scope"        -> s"${config.scope.productPrefix} ${short(SandboxedShell.pathOf(config.scope))}",
    "strategy"     -> config.strategy.toString,
    "kind of turn" -> config.kind.toString
  ))
  val model = lines(
    Vector("name" -> config.model.model, "endpoint" -> known(client.endpoint)) ++
      config.model.options.toVector.sortBy(_._1).map((name, value) => s"option $name" -> value.print) :+
      ("context window" -> facts.window.fold("not known")(window => s"${grouped(window)} tokens"))
  )
  val mode = lines(
    Vector("this session" -> modeWords(config.mode)) ++
      Option.when(config.ceiling != config.mode)("the most for a session that it starts" -> modeWords(config.ceiling))
  )
  val guard =
    if config.guard.rules.isEmpty then Vector("  rules: none, so each call runs")
    else config.guard.rules.map(rule => s"  ${rule.verdict}: ${callText(rule.rule)}")
  val grants = if current.grants.isEmpty then Vector("  none") else grantLines(current)
  // The facts give the limit of the turns with no person. With no facts, the limit is the limit of an older host.
  val loop   = current.loop.fold("off")(on => s"on, at most ${grouped(on.limit.toLong)} turns with no person")
  val limits = lines(Vector("steps of a turn" -> grouped(config.steps.toLong), "goal loop" -> loop)) ++
    config.host.fold(lines(Vector("turns with no person" -> config.unattendedLimit.words)) :+ noFacts)(host =>
      lines(limitLines(host.limits))
    )
  val network = "network" -> (if config.mode.network == Access.Full then "on, for the hosts of the list" else "off")
  val sandbox = config.host.fold(lines(Vector(network)))(host =>
    lines(network +: sandboxLines(host.sandbox, home))
  )
  val root  = client.root.toString
  val files = lines(
    if root.isEmpty then Vector("root" -> "not known")
    else
      Vector(
        "root"          -> short(root),
        "journal"       -> short(SessionFile.folderOf(client.root).resolve(s"${id.value}.db").toString),
        "exchange file" ->
          (if client.exchanges then short(ExchangeFile.pathOf(client.root, id).toString)
           else "off (DSPY_EXCHANGES=off)")
      )
  )
  Vector(
    "Session" -> session,
    "Model"   -> model,
    "Mode"    -> mode,
    "Guard"   -> guard,
    "Grants"  -> grants,
    "Limits"  -> limits,
    "Sandbox" -> sandbox,
    "Files"   -> files
  ).flatMap((head, body) => head +: body).mkString("\n")

/** The text of `--list` (10.2): the sessions of `rows`, the newest first (`CatalogRow.newestFirst`). Each session has
  * one line with its id, its status in words (`SessionStatus.words`, as the card of the controller), the time of its
  * last entry in `zone` and its folder. A second line has its first message.
  */
def sessionsText(rows: Vector[CatalogRow], zone: ZoneId): String =
  if rows.isEmpty then "The root has no session."
  else
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    rows.sorted(using CatalogRow.newestFirst).map { row =>
      // A file name, a folder and a message can hold a new line, so each shows on one line with its escapes.
      val at   = time.format(Instant.ofEpochMilli(row.lastAt))
      val head = s"${visible(row.session.value)}  ${row.status.words}  $at  ${visible(row.folder)}"
      if row.firstMessage.isEmpty then head else s"$head\n    ${visible(row.firstMessage)}"
    }.mkString("\n")

/** The session of `--session` (10.2) among the sessions `ids` of the journal files, and the session of `/open` and
  * `/reopen` (8.7) among the ids of the cards: the session whose id is `wanted`, or else the one session whose id
  * starts with `wanted`. An error for an empty `wanted`, and when no session or more than one session starts with it.
  * Each error ends with `hint`, which names the command that lists the sessions: `--list` for `--session`, and
  * `/sessions` for `/open` and `/reopen`.
  */
def resolveSession(
    ids   : Vector[SessionId],
    wanted: String,
    hint  : String = "--list lists them."
): Either[String, SessionId] =
  if wanted.isBlank then Left(s"Give the id of a session, or the start of it. $hint")
  else
    ids.find(_.value == wanted) match
      case Some(exact) => Right(exact)
      case None        => ids.filter(_.value.startsWith(wanted)) match
          case Vector(one) => Right(one)
          case Vector()    => Left(s"No session of the root has an id that starts with $wanted. $hint")
          case more        =>
            val many = s"${more.size} sessions have an id that starts with $wanted."
            Left(s"$many Give more of the id. $hint")

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
