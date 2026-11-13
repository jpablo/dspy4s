package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.*
import dspy4s.harness.guest.{Access, Data, Mode, Scope, StrategyName, preamble}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.harness.sandbox.{Backend, HostList, Layout, Profile, Profiles, SandboxedShell}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.json.Json

import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.{CancellationException, ConcurrentHashMap, ExecutionException, Executors, Future}
import scala.util.Try

/** What a work session needs from the machine (CODE_HARNESS_DESIGN.md, 8.1): the home folder for the secret paths
  * (5.4), the host list of a mode with `Network^`, the backend of the sandbox, the secrets to mask (5.6), and the root
  * of the host, which holds the journals. A command can neither read nor write under the journals (5.4).
  * `summaryOptions` is the option bag of the provider for each summary request of the memory tree (7.1). With
  * `exchanges`, each activation writes the HTTP exchanges of its model calls to the exchange file of its session
  * (docs/code-harness/OBSERVABILITY.md, 4).
  */
final case class WorkEnv(
    home          : Path,
    hosts         : HostList,
    backend       : Option[Backend],
    secrets       : Secrets,
    journals      : Path,
    summaryOptions: DynamicValue.Record = DynamicValue.Record.empty,
    exchanges     : Boolean             = true
)

object WorkEnv:

  /** The name of the variable of the summary options: a JSON object, the option bag of the provider. */
  val SummaryOptions = "DSPY_SUMMARY_OPTIONS"

  /** The summary options of `env`: the JSON object of `DSPY_SUMMARY_OPTIONS`, an empty bag with no such variable, or an
    * error for a text that is not a JSON object. For a model of vLLM that reasons, the value
    * `{"chat_template_kwargs": {"enable_thinking": false}}` makes a summary about 50 times faster (7.1).
    */
  def summaryOptions(env: Map[String, String]): Either[String, DynamicValue.Record] =
    env.get(SummaryOptions).filter(_.trim.nonEmpty) match
      case None       => Right(DynamicValue.Record.empty)
      case Some(text) => Json.parse(text) match
          case Right(json: Json.Object) =>
            Right(DynamicValue.Record(json.fields.map((key, value) => key -> value.toDynamicValue)))
          case Right(_)    => Left(s"$SummaryOptions must be a JSON object")
          case Left(error) => Left(s"$SummaryOptions is not JSON: $error")

/** The runtime of a work session (8.1): the user owns the session, and each message starts a chat turn.
  *
  *   - A turn runs with the effects of a harness (`HarnessEffects`): the `policy` leaf, the strategy of the
  *     configuration (`strategyOf`), and a REPL with the mode and the preamble of the configuration. The task of the
  *     policy is `WorkRuntime.chatTask`: the instructions and the scope. A new session has the memory tree (7.1): its
  *     view holds the earlier turns, a cell reads a part of it with `zoom`, and the table `memory` of the session file
  *     keeps its lines. A session of phase 7 keeps the window, which gives the messages and the replies of the earlier
  *     turns (7).
  *   - The handlers are the handlers of the mode through `recorded`, and the recorder calls `guarded` for each call
  *     (5.5). `exec` and the jobs run in the sandbox, in the scope of the session, through `JobShell`.
  *   - Opening a session and starting a command do not scan the scope for secret files. Commands use the fixed sandbox
  *     profile of the session, so the first command does not wait for a directory walk.
  *   - `llmQuery` goes to the `subQuery` leaf. A work session of phase 7 has no `Children^`, so no `rlm`.
  *   - The REPL lives as long as the activation, so a value of a turn stays for the next turns. A new activation has a
  *     new REPL, and the store comes back from the journal (11, scenario 3). The window gives the first step after a
  *     new activation the note of the names that the new REPL lost (6, `NewReplNote.afterActivation`).
  *   - Each turn gets a new budget of `limits.modelCalls` model calls (8.2), because a work session has no end of a
  *     call. The effects and the `subQuery` leaf get the budget of the current turn through a function.
  *   - Each activation has a meter of its memory (`MemoryMeter`) for a client (10.6). The memory tree reports its
  *     summaries to it, and the effects report the size of each projection. `activity` and `memoryUse` read the meter
  *     of the activation of the session with no lock, so a client can poll them while a turn runs. A new activation has
  *     a new meter, so `memoryUse` is none until its first call to the policy.
  *   - Each activation has its `Drafts`, and the effects write the draft of each call to the policy there while the
  *     model writes it (CLI_SCREEN.md, 13). `draft` reads it with no wait for the call.
  *   - A mode with `Sessions` gets the handler `HostSessions` with `peers` and a `HarnessProbe` with the leaves of the
  *     runtime (8.7). The task of a turn comes from `taskOf`: the controller, a session with a session as its owner, or
  *     a session of the person.
  *
  * The policy calls run on the thread of the worker, so the activation installs the context of the runtime there. The
  * context gives the LM, so the key never goes into the journal (5.6).
  */
final class WorkRuntime(
    env     : WorkEnv,
    limits  : HarnessLimits,
    policy  : Predict[PolicyInput, PolicyStep],
    subQuery: Predict[SubQuery, SubAnswer],
    peers   : Peers = Peers.none
)(using RuntimeContext)
    extends SessionRuntime:

  private val context = summon[RuntimeContext]

  /** The meter of the memory of the activation of each open session (`activate`). */
  private val meters = ConcurrentHashMap[SessionId, MemoryMeter]()

  /** The drafts of the calls to the policy of the activation of each open session (`activate`, CLI_SCREEN.md, 13). */
  private val drafts = ConcurrentHashMap[SessionId, Drafts]()

  /** The summaries of the memory tree of `session` while they run (`MemoryMeter.building`), else none. */
  override def activity(session: SessionId): Option[Activity] =
    Option(meters.get(session)).flatMap(_.building)

  /** The size of the last projection of the activation of `session` and its budget (`MemoryMeter.use`), or none. */
  override def memoryUse(session: SessionId): Option[MemoryUse] = Option(meters.get(session)).flatMap(_.use)

  /** The tokens of the last call to the policy of the activation of `session` (`MemoryMeter.context`), or none. */
  override def contextUse(session: SessionId): Option[ContextUse] = Option(meters.get(session)).flatMap(_.context)

  /** The draft of the call to the policy of the activation of `session` that runs now (`Drafts.current`), or none. */
  override def draft(session: SessionId): Option[Draft] = Option(drafts.get(session)).flatMap(_.current)

  def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
    // The context of the activation: the context of the runtime with the exchange file of the session
    // (docs/code-harness/OBSERVABILITY.md, 4.2). The host threads capture it here, so `llmQuery` has it too.
    val inSession = WorkRuntime.withExchanges(context, env, session, journal)
    RuntimeEnvironment.withContext(inSession) {
      var layout  = Option.empty[Layout]
      var shell   = Option.empty[SandboxedShell]
      var lines   = Option.empty[FileLines]
      var started = false
      try
        val scope = Layout.real(Path.of(SandboxedShell.pathOf(config.scope)))
        val made  = Layout.forSession(env.home, env.journals)
        layout = Some(made)
        // The profile of the shell, so the words of the task give the rules of the sandbox of this host.
        val profile  = Profiles.forMode(config.mode, scope, made, env.hosts, env.backend.exists(_.closesNames))
        val commands = SandboxedShell(config, made, env.hosts, env.backend, limits.execTimeout)
        shell = Some(commands)
        val turns    = TurnBudget(journal, limits.modelCalls)
        val recorder = Recorder(journal, () => turns.closed, env.secrets.maskJson)
        val store    = SnapshotStore()
        val host     = HostThreads.ofThisThread()
        val calls    = StoppablePolicy(inSession, () => journal.state.stopping)
        val zooms    = ZoomSource()
        val meter    = MemoryMeter()
        val written  = Drafts()
        val summary  = TurnBudget(journal, limits.summaryCalls)
        val file     = () =>
          FileLines.open(SessionFile.pathOf(env.journals, session), session).map { opened =>
            lines = Some(opened)
            opened
          }
        val strategy =
          WorkRuntime.strategyOf(config, file, calls, () => summary.current, limits.batch, env.summaryOptions, meter)
            .fold(error => throw IllegalStateException(error), identity)
        val probe = HarnessProbe(env.journals, env.home, env.secrets, limits, policy, subQuery)
        // The files of the cells. The task gets AGENTS.md and the README of the folder through them, once for this
        // activation (`ProjectFiles`). The controller works in the home folder, so its task gets no files.
        val reader  = Option.when(config.mode.files != Access.None)(LocalFiles(scope, profile, made.secretNames))
        val project =
          if session == Controller.id then ""
          else reader.fold("")(files => ProjectFiles.section(ProjectFiles.of(files)))
        val bindings = Bindings(
          turn = JournalTurn(journal, () => System.currentTimeMillis(), zooms),
          files = reader,
          shell = Option.when(config.mode.shell == Access.Full)(JobShell(commands, jobs, journal)),
          llm = Option.when(config.mode.llm == Access.Full)(LeafLlm(subQuery, () => turns.current, host, limits.batch)),
          store = Option.when(config.mode.store != Access.None)(store),
          sessions = Option.when(config.mode.sessions != Access.None)(
            HostSessions(
              peers,
              session,
              journal,
              probe,
              () => turns.current,
              (goal, scope, strategy) =>
                WorkRuntime.ownedConfig(goal, scope, strategy, session, config, limits)
            )
          )
        )
        val handlers = recorded(bindings, recorder, Some(scope))
        val newRepl  = () =>
          ScalaRepl(
            config.mode,
            handlers,
            config.preamble,
            limits.cellTimeout,
            limits.cellLimit,
            stops = () => journal.state.stopping
          )
        val effects = HarnessEffects(
          WorkRuntime.taskOf(session, config, scope, WorkRuntime.shellWords(profile, scope, made), project),
          Harness.apiReference(config.mode),
          WorkRuntime.policyOf(session, policy),
          strategy,
          journal,
          newRepl,
          recorder,
          store,
          limits,
          () => turns.current,
          Some(host),
          calls,
          zooms,
          meter,
          Some(written)
        )(using inSession)
        val forget = () =>
          meters.remove(session, meter): Unit
          drafts.remove(session, written): Unit
        val activation =
          WorkActivation(effects, commands, calls, Vector(turns, summary), lines, made, inSession, forget)
        // The last step of the start, so a failed start keeps no meter. A later activation of the session puts its own.
        meters.put(session, meter): Unit
        drafts.put(session, written): Unit
        started = true
        activation
      finally
        // Each throwable of a start leaves no shell proxy and no temporary folder.
        if !started then
          shell.foreach(s => Try(s.close()): Unit)
          lines.foreach(l => Try(l.close()): Unit)
          layout.foreach(l => WorkRuntime.remove(l.temp))
    }

/** The live part of one activation of a work session. Each call of the effects runs in the context of the runtime. The
  * close removes the meter of the memory of the activation from the runtime (`forget`).
  */
private final class WorkActivation(
    inner                  : HarnessEffects,
    private[host] val shell: SandboxedShell,
    calls                  : StoppablePolicy,
    turns                  : Vector[TurnBudget],
    lines                  : Option[FileLines],
    layout                 : Layout,
    context                : RuntimeContext,
    forget                 : () => Unit
) extends Activation:

  val effects: TurnEffects = new TurnEffects:
    def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply] =
      RuntimeEnvironment.withContext(context)(inner.callPolicy(mustAnswer))
    def runCell(cell: Long, code: String): CellResult =
      RuntimeEnvironment.withContext(context)(inner.runCell(cell, code))

  /** Steps 2 and 3 of a hard stop: the LM call of the policy, the cell and the commands stop. It returns at once. */
  def stop(): Unit =
    calls.stop()
    Try(inner.stopCell()): Unit
    Try(shell.stopAll()): Unit

  def unclean: Boolean = inner.unclean

  def close(): Unit =
    forget()
    turns.foreach(_.close())
    calls.close()
    Try(inner.close()): Unit
    lines.foreach(l => Try(l.close()): Unit)
    Try(shell.close()): Unit
    WorkRuntime.remove(layout.temp)

/** The runner of the LM calls of the policy of a work session (10.3): each call runs on a thread of its own, so that
  * `stop` cuts it with no interrupt of the worker. A call that ignores the interrupt ends later on its thread, and the
  * next call does not wait for it. A cut call gives an error, and the stop ends the turn `Aborted` (`drive`).
  */
private[host] final class StoppablePolicy(context: RuntimeContext, stopping: () => Boolean) extends PolicyRunner:
  private val calls = Executors.newCachedThreadPool { body =>
    val thread = Thread(body, "harness-policy-call")
    thread.setDaemon(true)
    thread
  }
  // The summaries of a round of the memory tree run at the same time, so the runner keeps each call that runs.
  private val running = ConcurrentHashMap.newKeySet[Future[?]]()

  def run[A](call: () => Either[String, A]): Either[String, A] =
    val future = calls.submit(() => RuntimeEnvironment.withContext(context)(call()))
    running.add(future): Unit
    // A stop before the line above saw no call, so the call reads the stop itself.
    if stopping() then future.cancel(true): Unit
    try future.get()
    catch
      case _: CancellationException => Left(PolicyRunner.Stopped)
      case e: ExecutionException    => throw Option(e.getCause).getOrElse(e)
    finally running.remove(future): Unit

  /** Cuts each call that runs. It returns at once. */
  def stop(): Unit = running.forEach(future => future.cancel(true): Unit)

  def close(): Unit =
    stop()
    calls.shutdownNow(): Unit

/** The budget of the current turn of a work session (8.2): a new `Budget` of `limit` calls at each new `TurnStarted`.
  * The last budget closes at the end of the activation, so a late call fails.
  */
private final class TurnBudget(journal: Journal, limit: Int):
  private var turn            = Long.MinValue
  private var budget          = Budget(limit)
  @volatile private var ended = false

  def current: Budget =
    synchronized {
      val now = journal.state.lastTurn
      if now != turn && !ended then
        budget.close()
        budget = Budget(limit)
        turn = now
      budget
    }

  /** True after the end of the activation. */
  def closed: Boolean = ended

  def close(): Unit =
    synchronized {
      ended = true
      budget.close()
    }

/** The values of the memory of one activation for a client (10.6). The memory tree and the effects of the activation
  * write them as a `MemoryProgress`, and a client reads them with no lock, also while a turn runs.
  */
private final class MemoryMeter extends MemoryProgress:
  @volatile private var running: Option[Activity]  = None
  @volatile private var last: Option[MemoryUse]    = None
  @volatile private var tokens: Option[ContextUse] = None

  /** The summaries of the memory tree while an update builds them, or none. */
  def building: Option[Activity] = running

  /** The size of the last projection and its budget, or none before the first call to the policy. */
  def use: Option[MemoryUse] = last

  /** The tokens of the last call to the policy, or none before the first call with a usage (CLI_SCREEN.md, 14.4). */
  def context: Option[ContextUse] = tokens

  override def summaries(done: Int, total: Int): Unit = running = Some(Activity.MemorySummaries(done, total))

  override def summariesEnded(): Unit = running = None

  override def projected(chars: Int, budget: Int): Unit = last = Some(MemoryUse(chars, budget))

  override def used(prompt: Long, completion: Long): Unit = tokens = Some(ContextUse(prompt, completion))

object WorkRuntime:

  /** The mode of a work session of phase 7: each capability of local mode, apart from `Sessions^` (phase 9) and
    * `Children^`. Local mode has no network, and the network of a work session comes with a recorded handler of
    * `Network^`.
    */
  val workMode: Mode = Mode.local.copy(sessions = Access.None, children = Access.None)

  /** The first instructions of the policy of a work session. An optimizer can change them. */
  val instructions: String =
    "Work with the person in the folder of the session, with Scala 3 code in a REPL. At each step, give a short text " +
      "and the code of one cell, or only a text. The cell runs, and the next step sees its output. Use the functions " +
      "of the API reference. Keep a value for a later cell or turn in a val or in the store. A step with no code " +
      "ends the turn, and its text is the reply to the person. If a call needs an approval, end the turn and ask."

  /** The policy leaf of `session`: `policy`, and for the controller (`Controller.id`) `policy` with the instructions of
    * the controller. So the system message of each session has the instructions of its own work, one time.
    */
  def policyOf(session: SessionId, policy: Predict[PolicyInput, PolicyStep]): Predict[PolicyInput, PolicyStep] =
    if session == Controller.id then policy.copy(signature = policy.signature.withInstructions(Controller.instructions))
    else policy

  /** The task of each chat turn of a work session in the folder `scope`: the folder, the words of its shell `shell`
    * (`shellWords`), and the section `project` of the files of the project (`ProjectFiles.section`), when it is not
    * empty. The instructions are the instructions of the policy signature, so they come one time, in the system
    * message. A chat turn ends with a step with no code, and its text is the reply. So the task has no text about input
    * fields and no text about `submit`. The task of a program call (`Harness.task`) has both.
    */
  def chatTask(scope: Path, shell: String, project: String = ""): String =
    val files = if project.isEmpty then "" else s"\n\n$project"
    s"The folder of the session: $scope.\n\n$shell$files"

  /** What the model does when the shell refuses a write. In the live session 00429ec8, each `git worktree add` failed
    * with "Operation not permitted", and the model used three steps to find the cause. It never told the person.
    */
  val Refused: String =
    "A write in another place fails, for example with \"Operation not permitted\". When a task needs such a write, " +
      "stop, tell the person that this session cannot do it, and give the command that the person can run."

  /** The words of the shell of a session with `profile` in `scope` with `layout`: `Profiles.words`, and `Refused` when
    * a command can run. The text comes from the profile of the activation, so the task and the sandbox give the same
    * rules.
    */
  def shellWords(profile: Profile, scope: Path, layout: Layout): String =
    val words = Profiles.words(profile, scope, layout)
    if profile.canRun then s"$words $Refused" else words

  /** The task of each turn of the session `session` with `config` in the folder `scope` (8.6, 8.7): `Controller.task`
    * for the controller (`Controller.id`), the goal of a session whose owner is a session with the rule to end the work
    * with a cell that calls `submit(...)`, and `chatTask` for a session of the person. `shell` is the text of the shell
    * of the session (`shellWords`), and `project` the section of its files (`ProjectFiles.section`), for each task
    * apart from the task of the controller. The files come after the shell rules and before the goal.
    *
    * The preamble of such a session has `submit(value: String)`, and the task does not show the preamble. So the rule
    * names the String and gives an example call that compiles. In two live runs, a rule with no call made the model
    * write `submit: 42` as the text of its reply. Then each turn ended in `Replied`, and the work never ended.
    */
  def taskOf(session: SessionId, config: SessionConfig, scope: Path, shell: String, project: String = ""): String =
    if session == Controller.id then Controller.task(scope)
    else
      (config.owner, config.goal) match
        case (Owner.Session(_), Some(goal)) =>
          s"${chatTask(scope, shell, project)}\n\nThe goal of the session: $goal\n\nAnother session started this session, and it " +
            "gets a report at the end of each turn. When you complete the work, write a cell that calls submit with " +
            "the result as one String, for example submit(\"The file has 42 lines.\"). A reply with no cell does " +
            "not give the result to the other session."
        case _ => chatTask(scope, shell, project)

  /** The configuration of a session that `caller`, with the configuration `parent`, starts with `goal` in `scope` (8.2,
    * 8.7): a chat turn with `limits.steps` steps, the owner `Owner.Session(caller)`, the goal, `strategy`, the mode
    * `workMode` met with the ceiling of `parent` as the mode and the ceiling, the guard of that mode, the preamble of a
    * session with no input fields, and the model of `parent`.
    */
  def ownedConfig(
      goal    : String,
      scope   : Scope,
      strategy: StrategyName,
      caller  : SessionId,
      parent  : SessionConfig,
      limits  : HarnessLimits
  ): SessionConfig =
    val mode = workMode.meet(parent.ceiling)
    SessionConfig(
      kind = TurnKind.Chat,
      steps = limits.steps,
      mode = mode,
      ceiling = mode,
      strategy = strategy,
      scope = scope,
      owner = Owner.Session(caller),
      guard = defaultGuard(mode),
      preamble = preamble(summon[Data[String]].shape).getOrElse(""),
      model = parent.model,
      goal = Some(goal),
      env = Set.empty
    )

  /** The first rules of 5.5: in a mode with `Network^`, `exec` and `spawn` with the prefix `git push` get `Ask`. In
    * another mode the sandbox already stops a push, so the guard has no rule.
    */
  def defaultGuard(mode: Mode): Guard =
    if mode.network != Access.Full then Guard.allowAll
    else
      // `spawn` runs a command as `exec` does, so it gets the same rule.
      Guard(Vector("exec", "spawn").map(op =>
        VerdictRule(GuardRule(op, ArgMatch.Prefix("command", "git push")), Verdict.Ask)
      ))

  /** The configuration of a work session in the folder `scope` (8.1): a chat turn with `limits.steps` steps, the owner
    * `User`, `workMode` as the mode and the ceiling, the memory tree, `defaultGuard(workMode)`, the preamble of a
    * session with no input fields, and the model `model` with the option bag `options` of its provider, for example the
    * reasoning effort (CLI_SCREEN.md, 14.4).
    */
  def config(
      scope  : Path,
      model  : String,
      limits : HarnessLimits,
      options: Map[String, Json] = Map.empty
  ): SessionConfig =
    SessionConfig(
      kind = TurnKind.Chat,
      steps = limits.steps,
      mode = workMode,
      ceiling = workMode,
      strategy = StrategyName.MemoryTree,
      scope = Scope.Directory(scope.toString),
      owner = Owner.User,
      guard = defaultGuard(workMode),
      preamble = preamble(summon[Data[String]].shape).getOrElse(""),
      model = ModelChoice(model, options),
      goal = None,
      env = Set.empty
    )

  /** The strategy of an activation from `config.strategy` (7.1): the window, or the memory tree with `lines`, at most
    * `width` summaries at a time with the option bag `options`, the runner of the activation, the budget of the
    * summaries of the turn, and the progress of the activation for a client. `lines` is the table `memory` of the
    * session file, and the activation opens it only for the memory tree, and closes it in its `close`. `PrefixSummary`
    * has no code, so it gives an error, and the activation fails with it.
    */
  def strategyOf(
      config    : SessionConfig,
      lines     : () => Either[String, LineStore],
      leafRunner: PolicyRunner,
      summaries : () => Budget,
      width     : Int,
      options   : DynamicValue.Record,
      progress  : MemoryProgress = MemoryProgress.none
  ): Either[String, ContextStrategy] =
    config.strategy match
      case StrategyName.Window     => Right(Window)
      case StrategyName.MemoryTree => lines().map(store =>
          MemoryTree.withLeaves(store, MemoryTree.DefaultHigh, leafRunner, summaries, width, options, progress)
        )
      case StrategyName.PrefixSummary =>
        Left("the strategy PrefixSummary has no code yet, so a session with it cannot start")

  /** `context` with the exchange file of `session` as its exchange sink (docs/code-harness/OBSERVABILITY.md, 4.2), or
    * `context` itself when `env.exchanges` is off. The file gives each exchange the last `seq` of `journal` at its
    * start.
    */
  def withExchanges(context: RuntimeContext, env: WorkEnv, session: SessionId, journal: Journal): RuntimeContext =
    if !env.exchanges then context
    else
      val file = ExchangeFile(env.journals, session, () => journal.state.nextSeq - 1, env.secrets)
      context.withServices(context.services.copy(exchangeSink = Some(file)))

  /** A runtime with the first leaves: the policy with `instructions`, and the `subQuery` leaf of a harness. */
  def forWork(env: WorkEnv, limits: HarnessLimits = HarnessLimits())(using RuntimeContext): WorkRuntime =
    forHost(env, limits)(Peers.none)

  /** `forWork` with the host `peers`, for `LocalHost.startWith` (8.7): a session with `Sessions` reaches the other
    * sessions of the host.
    */
  def forHost(env: WorkEnv, limits: HarnessLimits = HarnessLimits())(peers: Peers)(using RuntimeContext): WorkRuntime =
    val policy   = Harness.policySignature(instructions)
    val subQuery = Signature.derived[SubQuery, SubAnswer]("subQuery", Harness.subQueryInstructions)
    WorkRuntime(env, limits, Predict(policy, name = Some("policy")), Predict(subQuery, name = Some("subQuery")), peers)

  /** Removes a folder and each file in it. */
  private[host] def remove(folder: Path): Unit =
    if JFiles.exists(folder) then
      val paths = JFiles.walk(folder)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => JFiles.deleteIfExists(p): Unit)
      finally paths.close()
