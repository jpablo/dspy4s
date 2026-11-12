package dspy4s.harness

import dspy4s.core.contracts.{ConfigurationError, DspyError, ParseError, RuntimeContext, RuntimeError}
import dspy4s.core.data.RawPrediction
import dspy4s.harness.guest.{Access, Data, DataShape, Mode, Scope, preamble}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{ApiReference, Bindings, ScalaRepl}
import dspy4s.harness.sandbox.{Layout, Profiles, SandboxedShell}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.{Module, ModuleLifecycle, Prediction, ProgramCall}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.json.Json

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files as JFiles, Path, SimpleFileVisitor, StandardCopyOption}
import java.util.Comparator
import scala.util.Try
import scala.util.control.NonFatal

/** A harness for one task (9): a dspy4s `Module`, so the optimizers take it.
  *
  * `forward` copies the workspace, starts a journal in memory and a REPL with the handlers of the mode, and sends an
  * `Input` with the values of the input fields (5.1). Then it runs `drive` and reads the last `TurnEnded`. `Submitted`
  * gives the output through `Data[O]`. `Replied` and `Exhausted` run the `finisher` leaf. `Failed`, `Aborted` and
  * `Crashed` give a `DspyError`, and the error of `Aborted` gives the reason of the stop. A copy or a private folder
  * that fails also gives a `DspyError`. An exception closes an open turn with a stop, the entries of a repair and
  * `TurnEnded(Crashed)` (4.4). An interrupt of the thread of the call does the same, and the flag of the thread stays
  * set (9). This path needs no writer and no thread other than the cell thread. After a cell that confuses the
  * compiler, the effects start a new REPL with the preamble, the store and the note of lost names (6). At the end of
  * the call, the current REPL is closed, the budget of the tree is closed, `env.journals` gets the journal on each
  * path, and the copy and the private folders are gone.
  *
  * Since phase 5, a cell has `Llm^` and `Children^` (8.1). The `subQuery` leaf answers `llmQuery`, and `rlm` runs a
  * child turn inside the cell. A child has the same path as the top session of the call (`session`): its own journal, a
  * new REPL, the same `policy` leaf, the window strategy, and the same copy of the workspace with the same files and
  * shell (8.1: "The scope of the parent"). Only `submit` gives the value of a child (5.1). The policy calls of the
  * whole tree and the queries take from one `Budget` of `limits.modelCalls` calls, and a REPL at the depth
  * `limits.depth` has no `Children^` (8.2).
  *
  * A proposal for the review: the harness has the leaves `policy`, `finisher` and `subQuery`, the leaves that run in a
  * program call. `goal` comes with the goals of sessions in phase 7.
  */
final case class Harness[I, O](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],
    finisher : Predict[FinishInput, O],
    subQuery : Predict[SubQuery, SubAnswer],
    strategy : ContextStrategy,
    env      : HarnessEnv,
    limits   : HarnessLimits
)(using val inputData: Data[I], val outputData: Data[O])
    extends Module[I, O]:
  val moduleName: String = "harness"

  protected val lifecycle: ModuleLifecycle[I] = ModuleLifecycle.typed(signature.inputShape)

  protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val id      = Harness.newId()
    val journal = MemoryJournal(() => System.currentTimeMillis(), env.secrets)
    // `Module.apply` installs the context of the call, so a query and a child on another thread get it (9).
    val host = HostThreads.ofThisThread()
    // `env.journals` gets the journal also after an error that `repaired` does not catch, for example a fatal error.
    try Harness.repaired(journal) {
        Harness.inFolder(Harness.forkWorkspace(env.workspace)) { fork =>
          Harness.inFolder(JFiles.createTempDirectory("dspy4s-journals-")) { journals =>
            val layout = Layout.forSession(env.home, journals)
            Harness.inFolder(layout.temp)(_ => run(call.input, fork, layout, journal, host, id))
          }
        }
      }
    finally env.journals(id, journal.log)

  /** The mode of a session at `depth` (5.2, 8.2): the mode of the environment at the top, and `base.meet(Mode.closed)`
    * for a child (5.2, the closed law). A REPL at the depth limit has no `Children^`, so `rlm` does not compile there.
    */
  private[harness] def modeAt(depth: Int): Mode =
    val base = if depth == 0 then env.mode else env.mode.meet(Mode.closed)
    if depth < limits.depth then base else base.copy(children = Access.None)

  /** The work of one call in the copy `fork`. */
  private def run(input: I, fork: Path, layout: Layout, journal: MemoryJournal, host: HostThreads, id: SessionId)(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    val fields = inputData.encode(input) match
      case Json.Object(pairs) => pairs.toVector
      case _                  => Vector.empty
    val inputShapes = inputData.shape match
      case DataShape.Record(_, shapes) => shapes()
      case _                           => Vector.empty
    for
      _       <- Either.cond(fields.nonEmpty || inputShapes.isEmpty, (), ConfigurationError("the input is not a record"))
      text    <- preamble(outputData.shape, inputShapes).left.map(e => ConfigurationError(s"no preamble: $e"))
      outcome <- top(fields, text, fork, layout, journal, host, id)
      output  <- result(outcome, fields, journal)
    yield output

  /** The top session of a call: the files and the shell of the copy, which each child shares (8.1), the budget of the
    * tree, and the turn. At the end of the call, the budget closes, so a child that outlives the call can make no more
    * calls (9), and the shell closes.
    */
  private def top(
      fields : Vector[(String, Json)],
      text   : String,
      fork   : Path,
      layout : Layout,
      journal: MemoryJournal,
      host   : HostThreads,
      id     : SessionId
  )(using RuntimeContext): Either[DspyError, TurnOutcome] =
    val config  = sessionConfig(0, text, Scope.Fork(fork.toString, env.workspace.fold("")(_.toString)))
    val profile = Profiles.forMode(env.mode, Layout.real(fork), layout, env.hosts, env.backend.exists(_.closesNames))
    val shell   = SandboxedShell(config, layout, env.hosts, env.backend, limits.execTimeout)
    val files   = LocalFiles(fork, profile, layout.secretNames)
    val tree    = Harness.CallTree(Budget(limits.modelCalls), host, fork, files, shell, config.scope)
    try
      val task = Harness.task(Harness.instructions(signature), fields, Some(text))
      session(config, task, fields, 0, journal, tree, id, Source.User)
    finally
      tree.budget.close()
      shell.close()

  /** One session of a call (8.1): the top session at the depth 0, or a child of `rlm`. Both have this path. It starts
    * the session, sends the task, and drives the turn with the handlers of the mode of `config` and with the files and
    * the shell of `tree`. Gives the outcome of the turn. The REPL of the last cell closes at the end, also after an
    * exception. The recorder refuses each call after the end of the program call (9), and only the top session joins
    * the trace of the host threads.
    */
  private def session(
      config : SessionConfig,
      task   : String,
      fields : Vector[(String, Json)],
      depth  : Int,
      journal: MemoryJournal,
      tree   : Harness.CallTree,
      id     : SessionId,
      source : Source
  )(using RuntimeContext): Either[DspyError, TurnOutcome] =
    for
      _ <- journal.run(Command.Activate(Harness.versions, config), Source.Harness).left.map(rejected)
      _ <- journal.run(Command.Send(RequestId("task"), task, fields.toMap), source).left.map(rejected)
    yield
      val recorder = Recorder(journal, () => tree.budget.closed, env.secrets.maskJson)
      val store    = SnapshotStore()
      val llm      = LeafLlm(subQuery, () => tree.budget, tree.host, limits.batch)
      val children = ChildTurns(childStart(depth + 1, tree, id, journal), tree.host, limits.batch)
      val zooms    = ZoomSource()
      val bindings = Bindings(
        turn = JournalTurn(journal, () => System.currentTimeMillis(), zooms),
        files = Some(tree.files),
        shell = Some(tree.shell),
        llm = Option.when(config.mode.llm == Access.Full)(llm),
        store = Some(store),
        children = Option.when(config.mode.children == Access.Full)(children)
      )
      val handlers = recorded(bindings, recorder, Some(tree.fork))
      val newRepl  = () => ScalaRepl(config.mode, handlers, config.preamble, limits.cellTimeout, limits.cellLimit)
      val effects  = HarnessEffects(
        task,
        Harness.apiReference(config.mode),
        policy,
        strategyAt(depth),
        journal,
        newRepl,
        recorder,
        store,
        limits,
        () => tree.budget,
        Option.when(depth == 0)(tree.host),
        PolicyRunner.Direct,
        zooms
      )
      try
        drive(journal, effects): Unit
        journal.log.reverseIterator.collectFirst { case Logged(_, _, Entry.TurnEnded(outcome)) => outcome }
          .getOrElse(TurnOutcome.Crashed)
      finally
        // This closes the REPL of the last cell, also after a restart (6).
        effects.close()

  /** The start of a child at `depth` in the tree of a call. */
  private def childStart(depth: Int, tree: Harness.CallTree, parent: SessionId, journal: Journal): ChildStart =
    new ChildStart:
      def child[A: Data](task: String, inputs: Map[String, String])(using RuntimeContext): A =
        runChild[A](task, inputs, depth, tree, parent, journal)

  /** A child of `rlm` at `depth` (5.1, 8.1). A spent or closed budget throws first, so the child makes no journal and
    * no REPL (8.2). It has its own journal with the secrets of the call, and `env.journals` gets the journal at its
    * end, also after an interrupt. Its preamble comes from `Data[A].shape`, with one input of the type `String` for
    * each key of `inputs`, and the `Input` entry holds the values. The task of the policy has the task text, the input
    * fields and the preamble, as the task of the top session. Only `submit` gives the value (5.1). A child that ends
    * with `Replied` or `Exhausted` throws, and the message has the last text of the child. `Failed`, `Aborted`,
    * `Crashed` and a type with no preamble also throw, so the cell of the parent fails.
    *
    * A proposal for the review: the inputs are in the order of their names. The task of a child has the source `User`,
    * as the task of the top session, because the kernel has no source for a cell.
    */
  private def runChild[A](
      task         : String,
      inputs       : Map[String, String],
      depth        : Int,
      tree         : Harness.CallTree,
      parent       : SessionId,
      parentJournal: Journal
  )(using
      data   : Data[A],
      context: RuntimeContext
  ): A =
    tree.budget.check().left.foreach(error => throw IllegalStateException(s"rlm: $error"))
    val fields = inputs.toVector.sortBy(_._1).map((name, value) => name -> Json.String(value))
    val shapes = fields.map((name, _) => name -> summon[Data[String]].shape)
    val text   = preamble(data.shape, shapes).fold(
      e => throw IllegalArgumentException(s"rlm: the type of the child has no preamble: $e"),
      identity
    )
    val id      = Harness.newId()
    val journal = MemoryJournal(() => System.currentTimeMillis(), env.secrets)
    val config  = sessionConfig(depth, text, tree.scope)
    val outcome =
      try
        val source = Source.Parent(parent, Harness.openCall(parentJournal))
        Harness.repaired(journal)(
          session(config, Harness.task(task, fields, Some(text)), fields, depth, journal, tree, id, source)
        )
      finally env.journals(id, journal.log)
    outcome.flatMap(Harness.submitted(_, journal.log, data)) match
      case Right(Some(value)) => value
      case Right(None)        => throw IllegalStateException(
          s"rlm: the child ended with no submit, so it gave no value. Its last text: ${Harness.lastText(journal.log)}"
        )
      case Left(error) => throw IllegalStateException(s"rlm: the child failed: ${error.message}")

  /** The configuration of a session at `depth` (8.1): a program turn with the mode of `modeAt`, the owner `Optimizer`
    * at the top and `Cell` for a child, and the preamble `text`.
    */
  private def sessionConfig(depth: Int, text: String, scope: Scope)(using RuntimeContext): SessionConfig =
    val mode = modeAt(depth)
    SessionConfig(
      kind = TurnKind.Program,
      steps = limits.steps,
      mode = mode,
      ceiling = mode,
      strategy = strategyAt(depth).name,
      scope = scope,
      owner = if depth == 0 then Owner.Optimizer else Owner.Cell,
      guard = Guard.allowAll,
      preamble = text,
      model = ModelChoice(Harness.modelName, Map.empty),
      goal = None,
      env = Set.empty
    )

  /** The strategy of the top session, and the window for a child (8.1). */
  private def strategyAt(depth: Int): ContextStrategy = if depth == 0 then strategy else Window

  /** The output of the call from the outcome of its turn. */
  private def result(outcome: TurnOutcome, fields: Vector[(String, Json)], journal: MemoryJournal)(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    Harness.submitted(outcome, journal.log, outputData).flatMap {
      case Some(output) => Right(Prediction(output, RawPrediction(signature.outputShape.encode(output))))
      case None         =>
        val finish = FinishInput(
          Harness.task(Harness.instructions(signature), fields),
          Harness.transcript(journal.log, limits.budget)
        )
        finisher(finish)
    }

  /** The error of a command that the journal refused, with the refusal in words (`Rejection.words`). */
  private def rejected(rejection: Rejection): DspyError =
    RuntimeError("harness", s"the journal refused: ${rejection.words}")

object Harness extends HarnessGivens:

  /** The first instructions of the policy leaf: the work rules (9). An optimizer can change them. */
  val policyInstructions: String =
    "Solve the task with Scala 3 code in a REPL. At each step, give a short text and the code of one cell. The cell " +
      "runs, and the next step sees its output. Use the functions of the API reference. Keep a value for a later cell " +
      "in a val or in the store. Prefer a Vector or a List to an Array, and convert the result of split at once, " +
      "for example text.split(\",\").toVector. When you have the result, call submit with it. A step with no code " +
      "ends the work. In that step, leave out the code field."

  /** The first instructions of the finisher leaf. */
  val finisherInstructions: String = "Give the output of the task from the steps of the work."

  /** The first instructions of the subQuery leaf, which answers `llmQuery` (9). They set no length, because the prompt
    * of the cell can ask for a list or a long extract. An optimizer can change them.
    */
  val subQueryInstructions: String = "Answer the prompt of the code."

  /** The name of the model of the runtime, for the configuration of the session. */
  private def modelName(using context: RuntimeContext): String =
    context.lm.collect { case model: LanguageModel => model.id }.getOrElse("none")

  /** The versions of a journal of a call (4.1). */
  val versions: Versions = Versions(s"scala-${dotty.tools.dotc.config.Properties.versionNumberString}", "phase-7")

  /** The id of the open `rlm` or `rlmBatched` call of a parent (8.2, phase 7). The calls of one cell run one at a time,
    * so the cell of the parent has only that one open while its child runs.
    */
  private[harness] def openCall(journal: Journal): EffectId =
    journal.state.open.collectFirst { case (id, call) if call.op == "rlm" || call.op == "rlmBatched" => id }
      .getOrElse(throw IllegalStateException("rlm: the parent has no open rlm call"))

  /** A new id for a session of a call: each session of a call has its own id (phase 7). */
  private[harness] def newId(): SessionId = SessionId(java.util.UUID.randomUUID().toString)

  /** The longest text of one input value in the task. */
  private val InputLimit = 2_000

  /** What the sessions of one call share (8.1, 8.2): the budget of model calls, the host threads, and the copy of the
    * workspace with its files, its shell and its scope. A child works in the scope of its parent.
    */
  private[harness] final case class CallTree(
      budget: Budget,
      host  : HostThreads,
      fork  : Path,
      files : LocalFiles,
      shell : SandboxedShell,
      scope : Scope
  )

  /** A harness for `signature` with the first leaves: a policy with `policyInstructions`, a finisher that gives the
    * output fields of `signature`, and a subQuery leaf with `subQueryInstructions`. The three leaves have the names
    * `policy`, `finisher` and `subQuery`, and the window strategy.
    */
  def forTask[I: Data, O: Data](
      signature: Signature[I, O],
      env      : HarnessEnv,
      limits   : HarnessLimits = HarnessLimits()
  ): Harness[I, O] =
    val policy   = Signature.derived[PolicyInput, PolicyStep]("policy", policyInstructions)
    val finisher =
      Signature.fromShapes("finisher", finisherInstructions, Shape.derived[FinishInput], signature.outputShape)
    val subQuery = Signature.derived[SubQuery, SubAnswer]("subQuery", subQueryInstructions)
    Harness(
      signature,
      Predict(policy, name = Some("policy")),
      Predict(finisher, name = Some("finisher")),
      Predict(subQuery, name = Some("subQuery")),
      Window,
      env,
      limits
    )

  /** A copy of `workspace` in a new private folder under `parent`, or an empty folder for `None` (9). The copy keeps
    * the symbolic links as links. It leaves out each file that is not a regular file, a folder or a link, such as a
    * named pipe, a socket or a device, because the open of a named pipe can wait with no limit. On an exception, the
    * part of the copy is deleted, and the exception goes on. The caller deletes the folder.
    */
  def forkWorkspace(workspace: Option[Path], parent: Path = Path.of(System.getProperty("java.io.tmpdir"))): Path =
    val created = JFiles.createTempDirectory(parent, "dspy4s-fork-")
    try
      val fork = Layout.real(created)
      workspace.foreach { source =>
        val from = Layout.real(source)
        JFiles.walkFileTree(
          from,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult =
              JFiles.createDirectories(fork.resolve(from.relativize(dir).toString)): Unit
              FileVisitResult.CONTINUE
            override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
              val target = fork.resolve(from.relativize(file).toString)
              if attributes.isSymbolicLink then JFiles.createSymbolicLink(target, JFiles.readSymbolicLink(file)): Unit
              else if !attributes.isOther then JFiles.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES): Unit
              FileVisitResult.CONTINUE
        ): Unit
      }
      fork
    catch
      case e: Exception =>
        delete(created)
        throw e

  /** The API reference (5.1) for a cell of `mode`: a header, and the parts of the source text of the guest API that
    * compile in `mode` (`ApiReference`).
    */
  def apiReference(mode: Mode): String =
    "// The guest API. A cell is Scala 3 with capture checking and safe mode.\n" +
      "// The reference shows only the calls that the mode of this session gives.\n\n" + ApiReference(mode)

  /** The instructions of a signature: the head of the task of a call. */
  private[harness] def instructions(signature: Signature[?, ?]): String =
    signature.layout.instructions.filter(_.trim.nonEmpty).getOrElse("Do the task.")

  /** The task of a session: the head, the instructions of the signature or the task of a child, and the input fields
    * with their values. The preamble has a val for each input field, so a cell reads the whole value.
    *
    * The task of the policy also shows the source of `preamble`, so the model sees the types of `submit` and of the
    * input vals (5.1). The task of the finisher has no preamble, because the finisher writes no code.
    */
  private[harness] def task(head: String, fields: Vector[(String, Json)], preamble: Option[String] = None): String =
    val inputs =
      if fields.isEmpty then "The task has no input fields."
      else
        "The input fields, each also a val of the preamble:\n" +
          fields.map((name, value) => s"- $name: ${Window.cut(value.print, InputLimit)}").mkString("\n")
    val source = preamble.fold("")(p => s"\n\nThe preamble of the REPL defines these names:\n```scala\n${p.trim}\n```")
    s"$head\n\n$inputs$source\n\nGive the result with submit."

  /** The value of a turn (5.1, 9). `Submitted` gives its value through `data`. `Replied` and `Exhausted` give no value,
    * and the caller chooses: the top session runs the finisher, and a child fails. `Failed`, `Aborted` and `Crashed`
    * give a `DspyError`, and the error of `Aborted` gives the reason of the stop.
    */
  private[harness] def submitted[A](outcome: TurnOutcome, log: Vector[Logged], data: Data[A])
      : Either[DspyError, Option[A]] =
    outcome match
      case TurnOutcome.Submitted(value) => data.decode(value, "submit") match
          case Right(output) => Right(Some(output))
          case Left(error)   => Left(ParseError("harness", s"submit gave a value that does not fit: ${error.getMessage}"))
      case TurnOutcome.Replied(_) | TurnOutcome.Exhausted => Right(None)
      case TurnOutcome.Failed(error)                      => Left(RuntimeError("harness", s"the turn failed: $error"))
      case TurnOutcome.Aborted                            =>
        val reason = log.reverseIterator.collectFirst { case Logged(_, _, Entry.StopRequested(r)) => r }
        Left(RuntimeError("harness", reason.fold("the turn stopped")(r => s"the turn stopped: $r")))
      case TurnOutcome.Crashed => Left(RuntimeError("harness", "the turn did not end"))

  /** The text of the last step of a turn, cut to `InputLimit` characters, for the error of a child with no value. */
  private[harness] def lastText(log: Vector[Logged]): String =
    log.reverseIterator.collectFirst { case Logged(_, _, Entry.Acted(_, text, _)) => Window.cut(text, InputLimit) }
      .getOrElse("none")

  /** Runs `body`. After an exception, it closes the open turn of `journal` with a stop and the entries of a repair
    * (4.4), and gives a `DspyError`. The journal of one call has no next `Activate`, so the harness repairs the turn
    * here. The stop comes first: it holds the reason, and an input that no step saw then starts no new turn.
    *
    * An `InterruptedException` gets the same repair, although `NonFatal` rejects it: the parallel executor interrupts
    * the thread of a program call at a timeout or at `maxErrors` (9). After the repair, the interrupt flag of the
    * thread is set again, so the caller sees the interrupt.
    */
  private[harness] def repaired[A](journal: Journal)(body: => Either[DspyError, A]): Either[DspyError, A] =
    try body
    catch
      case interrupt: InterruptedException =>
        val error = stopped(journal, interrupt)
        Thread.currentThread().interrupt()
        error
      case NonFatal(e) => stopped(journal, e)

  /** Closes the open turn of `journal` after `e`, and gives the error of the call. */
  private def stopped(journal: Journal, e: Throwable): Left[DspyError, Nothing] =
    val reason = s"the call stopped: $e"
    if journal.state.position != Position.NoTurn then
      journal.run(Command.Abort(reason), Source.Harness): Unit
      journal.commit(repair(journal.state)*): Unit
    Left(RuntimeError("harness", reason))

  /** The steps of a call as text for the finisher, cut to `budget` characters. A step gives its text and its code. The
    * output of a cell comes after a label with the outcome of the cell in words (`CellOutcome.words`), for example
    * `Output (the cell did not compile):`. So the finisher reads the outcome in words, and not as a Scala value.
    */
  private[harness] def transcript(log: Vector[Logged], budget: Int): String =
    val steps = log.map(_.entry).collect {
      case Entry.Acted(_, text, code)         => (Vector(text) ++ code.map(c => s"```scala\n$c\n```")).mkString("\n")
      case Entry.Observed(output, outcome, _) => s"Output (${outcome.words}):\n$output"
    }
    Window.cut(steps.mkString("\n\n"), budget)

  /** Gives the result of `body` for `folder`, and deletes the folder after it. */
  private def inFolder[A](folder: Path)(body: Path => A): A =
    try body(folder)
    finally delete(folder)

  /** Deletes a folder and each file in it, and gives nothing when a part is gone. */
  private def delete(folder: Path): Unit =
    if JFiles.exists(folder) then
      Try {
        val paths = JFiles.walk(folder)
        try paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Try(JFiles.deleteIfExists(p)): Unit)
        finally paths.close()
      }: Unit
