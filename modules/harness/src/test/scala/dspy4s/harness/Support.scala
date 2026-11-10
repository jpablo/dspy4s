package dspy4s.harness

import dspy4s.core.contracts.DspyError
import dspy4s.harness.guest.{Mode, Scope, StrategyName}
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList, Layout}
import dspy4s.lm.contracts.LanguageModel
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

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

  /** The copies of the workspace that the journals name in their `Activated` entries. */
  def forks(journals: Vector[Vector[Logged]]): Vector[Path] =
    journals.flatten.collect { case Logged(_, _, Entry.Activated(_, config)) => config.scope }.collect {
      case Scope.Fork(path, _) => Path.of(path)
    }.distinct

  /** Removes a folder and each file in it, when the folder exists. */
  def remove(folder: Path): Unit =
    if Files.exists(folder) then
      val paths = Files.walk(folder)
      try paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p): Unit)
      finally paths.close()

/** A gate in a scripted model (9). The first call that reaches it keeps its thread, counts down `reached`, and waits
  * for `released`, at most 30 seconds. A gate that is not `interruptible` ignores each interrupt in that wait, as host
  * code that does not stop. Each later call goes through at once.
  */
final class Gate(interruptible: Boolean = true):
  val released: CountDownLatch         = CountDownLatch(1)
  @volatile var thread: Option[Thread] = None
  private val reached                  = CountDownLatch(1)
  private val entered                  = AtomicBoolean(false)

  /** The pause of a call of `RequestLm`: the wait of the first call, and then no pause. */
  def pass(): Long =
    if entered.compareAndSet(false, true) then
      thread = Some(Thread.currentThread())
      reached.countDown()
      if interruptible then released.await(30, TimeUnit.SECONDS): Unit
      else
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while released.getCount > 0 && System.nanoTime() < end do
          try released.await(50, TimeUnit.MILLISECONDS): Unit
          catch case _: InterruptedException => ()
    0L

  /** Waits until a call reaches the gate, at most 30 seconds. */
  def awaitReached(): Boolean = reached.await(30, TimeUnit.SECONDS)

  /** Waits for the end of the thread that reached the gate, at most 30 seconds. */
  def awaitThread(): Boolean =
    thread.foreach(_.join(30_000))
    thread.forall(!_.isAlive)

/** A call of a harness for the task of the tests on its own thread, so a test can interrupt it (9). It keeps the result
  * or the exception of the call, the interrupt flag of its thread after the call, and the number of journals that
  * `env.journals` had when the call returned.
  */
final class Background(model: LanguageModel, limits: HarnessLimits):
  val place: Place                                        = Place()
  val journals: ConcurrentLinkedQueue[Vector[Logged]]     = ConcurrentLinkedQueue()
  @volatile var result: Option[Either[DspyError, Answer]] = None
  @volatile var thrown: Option[Exception]                 = None
  @volatile var flagged: Boolean                          = false
  @volatile var atReturn: Int                             = -1
  lazy val thread: Thread                                 = Thread(() => call())

  /** The journals that `env.journals` got, in the order of their ends. */
  def all: Vector[Vector[Logged]] = journals.asScala.toVector

  /** Waits for the end of the call, at most 60 seconds. */
  def awaitEnd(): Boolean =
    thread.join(60_000)
    !thread.isAlive

  private def call(): Unit =
    val env = HarnessEnv(
      Some(place.root),
      place.home,
      HostList.empty,
      Backends.detect(),
      Secrets.none,
      journals = (_, log) => journals.add(log): Unit
    )
    val harness = Harness.forTask(Models.signature, env, limits)
    try result = Some(Models.withModel(model)(harness(Question("6 times 7?"))).map(_.output))
    catch case e: Exception => thrown = Some(e)
    atReturn = journals.size
    flagged = Thread.currentThread().isInterrupted

object Background:

  /** Starts a call on its own thread, and runs `check`. Then it opens each gate, waits for the end of the call, and
    * removes the place and each copy of the workspace that a journal names, because a child that outlives its call can
    * make the copy again.
    */
  def run(model: LanguageModel, limits: HarnessLimits, gates: Gate*)(check: Background => Unit): Unit =
    val call = Background(model, limits)
    try
      call.thread.setDaemon(true)
      call.thread.start()
      check(call)
    finally
      gates.foreach(_.released.countDown())
      call.awaitEnd(): Unit
      gates.foreach(_.awaitThread(): Unit)
      Support.forks(call.all).foreach(Support.remove)
      call.place.close()

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
