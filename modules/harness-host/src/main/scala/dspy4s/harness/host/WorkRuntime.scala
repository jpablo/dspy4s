package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.*
import dspy4s.harness.guest.{Access, Data, Mode, Scope, StrategyName, preamble}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.harness.sandbox.{Backend, HostList, Layout, Profiles, SandboxedShell}
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
  * `summaryOptions` is the option bag of the provider for each summary request of the memory tree (7.1).
  */
final case class WorkEnv(
    home          : Path,
    hosts         : HostList,
    backend       : Option[Backend],
    secrets       : Secrets,
    journals      : Path,
    summaryOptions: DynamicValue.Record = DynamicValue.Record.empty
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
  *   - `llmQuery` goes to the `subQuery` leaf. A work session of phase 7 has no `Children^`, so no `rlm`.
  *   - The REPL lives as long as the activation, so a value of a turn stays for the next turns. A new activation has a
  *     new REPL, and the store comes back from the journal (11, scenario 3). The window gives the first step after a
  *     new activation the note of the names that the new REPL lost (6, `NewReplNote.afterActivation`).
  *   - Each turn gets a new budget of `limits.modelCalls` model calls (8.2), because a work session has no end of a
  *     call. The effects and the `subQuery` leaf get the budget of the current turn through a function.
  *
  * The policy calls run on the thread of the worker, so the activation installs the context of the runtime there. The
  * context gives the LM, so the key never goes into the journal (5.6).
  */
final class WorkRuntime(
    env     : WorkEnv,
    limits  : HarnessLimits,
    policy  : Predict[PolicyInput, PolicyStep],
    subQuery: Predict[SubQuery, SubAnswer]
)(using RuntimeContext)
    extends SessionRuntime:

  private val context = summon[RuntimeContext]

  def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
    RuntimeEnvironment.withContext(context) {
      var layout  = Option.empty[Layout]
      var shell   = Option.empty[SandboxedShell]
      var lines   = Option.empty[FileLines]
      var started = false
      try
        val scope = Layout.real(Path.of(SandboxedShell.pathOf(config.scope)))
        val made  = Layout.forSession(env.home, env.journals)
        layout = Some(made)
        val profile  = Profiles.forMode(config.mode, scope, made, env.hosts)
        val commands = SandboxedShell(config, made, env.hosts, env.backend, limits.execTimeout)
        shell = Some(commands)
        val turns    = TurnBudget(journal, limits.modelCalls)
        val recorder = Recorder(journal, () => turns.closed, env.secrets.maskJson)
        val store    = SnapshotStore()
        val host     = HostThreads.ofThisThread()
        val calls    = StoppablePolicy(context, () => journal.state.stopping)
        val zooms    = ZoomSource()
        val summary  = TurnBudget(journal, limits.summaryCalls)
        val file     = () =>
          FileLines.open(SessionFile.pathOf(env.journals, session), session).map { opened =>
            lines = Some(opened)
            opened
          }
        val strategy =
          WorkRuntime.strategyOf(config, file, calls, () => summary.current, limits.batch, env.summaryOptions)
            .fold(error => throw IllegalStateException(error), identity)
        val bindings = Bindings(
          turn = JournalTurn(journal, () => System.currentTimeMillis(), zooms),
          files = Option.when(config.mode.files != Access.None)(LocalFiles(scope, profile, made.secretNames)),
          shell = Option.when(config.mode.shell == Access.Full)(JobShell(commands, jobs, journal)),
          llm = Option.when(config.mode.llm == Access.Full)(LeafLlm(subQuery, () => turns.current, host, limits.batch)),
          store = Option.when(config.mode.store != Access.None)(store)
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
          WorkRuntime.chatTask(scope),
          Harness.apiReference(config.mode),
          policy,
          strategy,
          journal,
          newRepl,
          recorder,
          store,
          limits,
          () => turns.current,
          Some(host),
          calls,
          zooms
        )(using context)
        val activation = WorkActivation(effects, commands, calls, Vector(turns, summary), lines, made, context)
        started = true
        activation
      finally
        // Each throwable of a start leaves no shell proxy and no temporary folder.
        if !started then
          shell.foreach(s => Try(s.close()): Unit)
          lines.foreach(l => Try(l.close()): Unit)
          layout.foreach(l => WorkRuntime.remove(l.temp))
    }

/** The live part of one activation of a work session. Each call of the effects runs in the context of the runtime. */
private final class WorkActivation(
    inner  : HarnessEffects,
    shell  : SandboxedShell,
    calls  : StoppablePolicy,
    turns  : Vector[TurnBudget],
    lines  : Option[FileLines],
    layout : Layout,
    context: RuntimeContext
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

  /** The task of each chat turn of a work session in the folder `scope`: `instructions` and the folder. A chat turn
    * ends with a step with no code, and its text is the reply. So the task has no text about input fields and no text
    * about `submit`. The task of a program call (`Harness.task`) has both.
    */
  def chatTask(scope: Path): String = s"$instructions\n\nThe folder of the session: $scope."

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
    * session with no input fields, and the model `model`.
    */
  def config(scope: Path, model: String, limits: HarnessLimits): SessionConfig =
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
      model = ModelChoice(model, Map.empty),
      goal = None,
      env = Set.empty
    )

  /** The strategy of an activation from `config.strategy` (7.1): the window, or the memory tree with `lines`, at most
    * `width` summaries at a time with the option bag `options`, the runner of the activation and the budget of the
    * summaries of the turn. `lines` is the table `memory` of the session file, and the activation opens it only for the
    * memory tree, and closes it in its `close`. `PrefixSummary` has no code, so it gives an error, and the activation
    * fails with it.
    */
  def strategyOf(
      config    : SessionConfig,
      lines     : () => Either[String, LineStore],
      leafRunner: PolicyRunner,
      summaries : () => Budget,
      width     : Int,
      options   : DynamicValue.Record
  ): Either[String, ContextStrategy] =
    config.strategy match
      case StrategyName.Window     => Right(Window)
      case StrategyName.MemoryTree => lines().map(store =>
          MemoryTree.withLeaves(store, MemoryTree.DefaultHigh, leafRunner, summaries, width, options)
        )
      case StrategyName.PrefixSummary =>
        Left("the strategy PrefixSummary has no code yet, so a session with it cannot start")

  /** A runtime with the first leaves: the policy with `instructions`, and the `subQuery` leaf of a harness. */
  def forWork(env: WorkEnv, limits: HarnessLimits = HarnessLimits())(using RuntimeContext): WorkRuntime =
    val policy   = Signature.derived[PolicyInput, PolicyStep]("policy", instructions)
    val subQuery = Signature.derived[SubQuery, SubAnswer]("subQuery", Harness.subQueryInstructions)
    WorkRuntime(env, limits, Predict(policy, name = Some("policy")), Predict(subQuery, name = Some("subQuery")))

  /** Removes a folder and each file in it. */
  private[host] def remove(folder: Path): Unit =
    if JFiles.exists(folder) then
      val paths = JFiles.walk(folder)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => JFiles.deleteIfExists(p): Unit)
      finally paths.close()
