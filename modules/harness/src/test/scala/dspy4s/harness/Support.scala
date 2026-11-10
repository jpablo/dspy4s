package dspy4s.harness

import dspy4s.harness.guest.{Mode, Scope, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.Layout
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.collection.mutable.ArrayBuffer

/** The helpers of the tests of the harness. */
object Support:
  val versions: Versions = Versions("test", "test")

  /** A rollout of phase 4: a program call of an optimizer, in the task mode. */
  def config(kind: TurnKind = TurnKind.Program, steps: Int = 3, mode: Mode = HarnessEnv.taskMode): SessionConfig =
    SessionConfig(
      kind = kind,
      steps = steps,
      mode = mode,
      ceiling = mode,
      strategy = StrategyName.Window,
      scope = Scope.Scratch("/tmp/harness-test"),
      owner = Owner.Optimizer,
      guard = Guard.allowAll,
      preamble = "",
      model = ModelChoice("fake-model", Map.empty),
      goal = None,
      env = Set.empty
    )

  /** A journal in memory with the clock at 1000. */
  def journal(secrets: Secrets = Secrets.none): MemoryJournal = MemoryJournal(() => 1000L, secrets)

  /** Activates the session and sends a task, so a turn is open. */
  def open(
      journal: Journal,
      config : SessionConfig     = config(),
      text   : String            = "the task",
      values : Map[String, Json] = Map.empty
  ): Unit =
    journal.run(Command.Activate(versions, config), Source.Harness): Unit
    journal.run(Command.Send(RequestId("task"), text, values), Source.User): Unit

  /** Opens a turn and starts a cell: `StepStarted(0)` and an `Acted` with code. Gives the `seq` of the `Acted`. */
  def inCell(journal: Journal): Long =
    open(journal)
    journal.commit(Entry.StepStarted(0)): Unit
    journal.run(Command.Commit(Vector(Entry.Acted("", "", Some("code")))), Source.Policy): Unit
    journal.log.last.seq

  /** The entries after the last `TurnStarted`. */
  def turn(journal: Journal): Vector[Entry] =
    journal.log.map(_.entry).reverse.takeWhile(_ != Entry.TurnStarted).reverse

  /** True when `entries`, after a `TurnStarted`, are one turn of the language of 4.1:
    * {{{
    * Turn ::= TurnStarted Step* TurnEnded
    * Step ::= StepStarted [ Acted [ Cell ] ]
    * Cell ::= ( EffectStarted | EffectDone )* Observed
    * }}}
    * `Input`, `StopRequested`, `JobsStopRequested`, `Granted` and `ScopeRecorded` can come at any place.
    */
  def isTurn(entries: Vector[Entry]): Boolean =
    val letters = entries.flatMap {
      case Entry.StepStarted(_)         => "S"
      case Entry.Acted(_, _, _)         => "A"
      case Entry.EffectStarted(_, _, _) => "E"
      case Entry.EffectDone(_, _)       => "D"
      case Entry.Observed(_, _, _)      => "O"
      case Entry.TurnEnded(_)           => "Z"
      case Entry.TurnStarted            => "T"
      case _                            => ""
    }.mkString
    ("T" + letters).matches("T(S(A([ED]*O)?)?)*Z")

  def code(source: String): Either[String, PolicyReply] = Right(PolicyReply("", "", Some(source)))

  def answer(text: String): Either[String, PolicyReply] = Right(PolicyReply("", text, None))

  val done: CellResult = CellResult("ok\n", CellOutcome.Ok, Map.empty)

  /** The effects of a script. Each policy call takes the next reply, and each cell takes the next result. `during` runs
    * in each cell before its result, so a test can commit an effect or a stop there, or throw as in a crash.
    */
  final class Script(
      replies: Vector[Either[String, PolicyReply]],
      cells  : Vector[CellResult]     = Vector.empty,
      during : (Long, String) => Unit = (_, _) => ()
  ) extends TurnEffects:
    val mustAnswer: ArrayBuffer[Boolean] = ArrayBuffer.empty
    val ran: ArrayBuffer[(Long, String)] = ArrayBuffer.empty

    def callPolicy(last: Boolean): Either[String, PolicyReply] =
      mustAnswer += last: Unit
      replies.lift(mustAnswer.size - 1).getOrElse(Left("the script has no more replies"))

    def runCell(cell: Long, source: String): CellResult =
      ran += ((cell, source)): Unit
      during(cell, source)
      cells.lift(ran.size - 1).getOrElse(done)

  /** A log from entries, with `seq` from 0 and the time `at`. */
  def logOf(at: Long, entries: Entry*): Vector[Logged] =
    entries.toVector.zipWithIndex.map((entry, i) =>
      Logged(i.toLong, Meta(at, Source.Harness, Taint.Trusted, true), entry)
    )

/** The places of one test, under a new real temporary folder: a root with `.git/config`, `.env` and `a.txt`, a folder
  * outside the root, a secret folder of the home folder, the journals and a private temporary folder. Each file holds a
  * fake value.
  */
final class Place extends AutoCloseable:
  val base: Path     = Files.createTempDirectory("dspy4s-harness-test-").toRealPath()
  val root: Path     = Files.createDirectories(base.resolve("root"))
  val outside: Path  = Files.createDirectories(base.resolve("outside"))
  val home: Path     = Files.createDirectories(base.resolve("home"))
  val secrets: Path  = Files.createDirectories(home.resolve(".ssh"))
  val journals: Path = Files.createDirectories(base.resolve("journals"))
  val temp: Path     = Files.createDirectories(base.resolve("tmp"))
  Files.createDirectories(root.resolve(".git")): Unit
  Files.writeString(root.resolve(".git/config"), "FAKE-GIT"): Unit
  Files.writeString(root.resolve(".env"), "FAKE-ENV"): Unit
  Files.writeString(root.resolve("a.txt"), "FAKE-A"): Unit
  Files.writeString(outside.resolve("o.txt"), "FAKE-OUTSIDE"): Unit
  Files.writeString(secrets.resolve("id_fake"), "FAKE-KEY"): Unit

  val layout: Layout = Layout(temp, journals, Vector(secrets), Layout.secretNames)

  def close(): Unit =
    val paths = Files.walk(base)
    try paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p): Unit)
    finally paths.close()
