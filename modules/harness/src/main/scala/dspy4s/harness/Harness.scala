package dspy4s.harness

import dspy4s.core.contracts.{ConfigurationError, DspyError, ParseError, RuntimeContext, RuntimeError}
import dspy4s.core.data.RawPrediction
import dspy4s.harness.guest.{Data, DataShape, Scope, preamble}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.harness.sandbox.{Layout, Profiles, SandboxedShell}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.contracts.{Module, ModuleLifecycle, Prediction, ProgramCall}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
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
  * `TurnEnded(Crashed)` (4.4). This path needs no writer and no thread other than the cell thread. At the end of the
  * call, `env.journals` gets the journal, and the copy and the private folders are gone.
  *
  * A proposal for the review: phase 4 has only the leaves `policy` and `finisher`, the leaves that run in a program
  * call. `subQuery` comes with `llmQuery` in phase 5, and `goal` with the goals of sessions in phase 7.
  */
final case class Harness[I, O](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],
    finisher : Predict[FinishInput, O],
    strategy : ContextStrategy,
    env      : HarnessEnv,
    limits   : HarnessLimits
)(using val inputData: Data[I], val outputData: Data[O])
    extends Module[I, O]:
  val moduleName: String = "harness"

  protected val lifecycle: ModuleLifecycle[I] = ModuleLifecycle.typed(signature.inputShape)

  protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val journal = MemoryJournal(() => System.currentTimeMillis(), env.secrets)
    val result  =
      try
        Harness.inFolder(Harness.forkWorkspace(env.workspace)) { fork =>
          Harness.inFolder(JFiles.createTempDirectory("dspy4s-journals-")) { journals =>
            val layout = Layout.forSession(env.home, journals)
            Harness.inFolder(layout.temp)(_ => run(call.input, fork, layout, journal))
          }
        }
      catch
        case NonFatal(e) =>
          // The journal of one call has no next `Activate`, so the harness repairs an open turn here (4.4). The stop
          // comes first: it holds the reason, and an input that no step saw then starts no new turn.
          val reason = s"the call stopped: $e"
          if journal.state.position != Position.NoTurn then
            journal.run(Command.Abort(reason), Source.Harness): Unit
            journal.commit(repair(journal.state)*): Unit
          Left(RuntimeError("harness", reason))
    env.journals(journal.log)
    result

  /** The work of one call in the copy `fork`. */
  private def run(input: I, fork: Path, layout: Layout, journal: MemoryJournal)(using
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
      outcome <- turn(fields, text, fork, layout, journal)
      output  <- result(outcome, fields, journal)
    yield output

  /** Starts the session, sends the input, and drives the turn. Gives the outcome of the turn. */
  private def turn(fields: Vector[(String, Json)], text: String, fork: Path, layout: Layout, journal: MemoryJournal)(
      using RuntimeContext
  ): Either[DspyError, TurnOutcome] =
    val config = SessionConfig(
      kind = TurnKind.Program,
      steps = limits.steps,
      mode = env.mode,
      ceiling = env.mode,
      strategy = strategy.name,
      scope = Scope.Fork(fork.toString, env.workspace.fold("")(_.toString)),
      owner = Owner.Optimizer,
      guard = Guard.allowAll,
      preamble = text,
      model = ModelChoice(Harness.modelName, Map.empty),
      goal = None,
      env = Set.empty
    )
    val task = Harness.task(signature, fields)
    for
      _ <- journal.run(Command.Activate(Harness.versions, config), Source.Harness).left.map(rejected)
      _ <- journal.run(Command.Send(RequestId("task"), task, fields.toMap), Source.User).left.map(rejected)
    yield
      val recorder = Recorder(journal)
      val store    = SnapshotStore()
      val profile  = Profiles.forMode(env.mode, Layout.real(fork), layout, env.hosts)
      val shell    = SandboxedShell(config, layout, env.hosts, env.backend, limits.execTimeout)
      val bindings = Bindings(
        turn = JournalTurn(journal, () => System.currentTimeMillis()),
        files = Some(LocalFiles(fork, profile, layout.secretNames)),
        shell = Some(shell),
        store = Some(store)
      )
      val repl = ScalaRepl(env.mode, recorded(bindings, recorder, Some(fork)), text, limits.cellTimeout)
      try
        drive(
          journal,
          HarnessEffects(task, Harness.apiReference, policy, strategy, journal, repl, recorder, store, limits)
        ): Unit
        journal.log.reverseIterator.collectFirst { case Logged(_, _, Entry.TurnEnded(outcome)) => outcome }
          .getOrElse(TurnOutcome.Crashed)
      finally
        repl.close()
        shell.close()

  /** The output of the call from the outcome of its turn. */
  private def result(outcome: TurnOutcome, fields: Vector[(String, Json)], journal: MemoryJournal)(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    outcome match
      case TurnOutcome.Submitted(value) => outputData.decode(value, "submit") match
          case Right(output) => Right(Prediction(output, RawPrediction(signature.outputShape.encode(output))))
          case Left(error)   => Left(ParseError("harness", s"submit gave a value that does not fit: ${error.getMessage}"))
      case TurnOutcome.Replied(_) | TurnOutcome.Exhausted =>
        finisher(FinishInput(Harness.task(signature, fields), Harness.transcript(journal.log, limits.budget)))
      case TurnOutcome.Failed(error) => Left(RuntimeError("harness", s"the turn failed: $error"))
      case TurnOutcome.Aborted       =>
        val reason = journal.log.reverseIterator.collectFirst { case Logged(_, _, Entry.StopRequested(r)) => r }
        Left(RuntimeError("harness", reason.fold("the turn stopped")(r => s"the turn stopped: $r")))
      case TurnOutcome.Crashed => Left(RuntimeError("harness", "the turn did not end"))

  private def rejected(rejection: Rejection): DspyError = RuntimeError("harness", s"the journal refused: $rejection")

object Harness extends HarnessGivens:

  /** The first instructions of the policy leaf: the work rules (9). An optimizer can change them. */
  val policyInstructions: String =
    "Solve the task with Scala 3 code in a REPL. At each step, give a short text and the code of one cell. The cell " +
      "runs, and the next step sees its output. Use the functions of the API reference. Keep a value for a later cell " +
      "in a val or in the store. When you have the result, call submit with it. A step with no code ends the work. " +
      "In that step, leave out the code field."

  /** The first instructions of the finisher leaf. */
  val finisherInstructions: String = "Give the output of the task from the steps of the work."

  /** The name of the model of the runtime, for the configuration of the session. */
  private def modelName(using context: RuntimeContext): String =
    context.lm.collect { case model: LanguageModel => model.id }.getOrElse("none")

  /** The versions of a journal of a call (4.1). */
  val versions: Versions = Versions(s"scala-${dotty.tools.dotc.config.Properties.versionNumberString}", "phase-4")

  /** The longest text of one input value in the task. */
  private val InputLimit = 2_000

  /** A harness for `signature` with the first leaves: a policy with `policyInstructions`, and a finisher that gives the
    * output fields of `signature`. The two leaves have the names `policy` and `finisher`, and the window strategy.
    */
  def forTask[I: Data, O: Data](
      signature: Signature[I, O],
      env      : HarnessEnv,
      limits   : HarnessLimits = HarnessLimits()
  ): Harness[I, O] =
    val policy   = Signature.derived[PolicyInput, PolicyStep]("policy", policyInstructions)
    val finisher =
      Signature.fromShapes("finisher", finisherInstructions, Shape.derived[FinishInput], signature.outputShape)
    Harness(
      signature,
      Predict(policy, name = Some("policy")),
      Predict(finisher, name = Some("finisher")),
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

  /** The API reference (5.1): the source text of the guest API. */
  lazy val apiReference: String = Vector("Guest.scala", "SessionTypes.scala").map { name =>
    Option(getClass.getResourceAsStream(s"/dspy4s/harness/guest/$name.txt")).fold(s"// $name is not in this build")(
      stream =>
        try String(stream.readAllBytes(), UTF_8)
        finally stream.close()
    )
  }.mkString("// The guest API. A cell is Scala 3 with capture checking and safe mode.\n\n", "\n\n", "")

  /** The task of a call: the instructions of the signature, and the input fields with their values. The preamble has a
    * val for each input field, so a cell reads the whole value.
    */
  private[harness] def task(signature: Signature[?, ?], fields: Vector[(String, Json)]): String =
    val inputs = fields.map((name, value) => s"- $name: ${Window.cut(value.print, InputLimit)}").mkString("\n")
    val head   = signature.layout.instructions.filter(_.trim.nonEmpty).getOrElse("Do the task.")
    s"$head\n\nThe input fields, each also a val of the preamble:\n$inputs\n\nGive the result with submit."

  /** The steps of a call as text for the finisher, cut to `budget` characters. */
  private[harness] def transcript(log: Vector[Logged], budget: Int): String =
    val steps = log.map(_.entry).collect {
      case Entry.Acted(_, text, code)         => (Vector(text) ++ code.map(c => s"```scala\n$c\n```")).mkString("\n")
      case Entry.Observed(output, outcome, _) => s"Output (${outcome.toString.takeWhile(_ != '(')}):\n$output"
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
