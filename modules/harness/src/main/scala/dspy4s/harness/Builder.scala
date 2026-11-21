package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.kernel.{DerivationName, Logged}

import java.util.concurrent.locks.ReentrantLock
import scala.util.control.NonFatal

/** A derivation that a builder runs in the background (CODE_HARNESS_DESIGN.md, 7.2, choice 5). `build` makes the
  * derived data of the journal with its leaf calls, and writes each round to its store with a new build number. A turn
  * reads the store up to a build number (`DerivedRead`), so it never waits for `build`.
  */
trait BuiltDerivation:

  /** The name of the derivation in a `DerivedRead`. */
  def name: DerivationName

  /** Makes the derived data of `journal`, with the leaf calls that it needs. */
  def build(journal: Vector[Logged])(using RuntimeContext): Unit

  /** The newest build number of the store. */
  def newest: Long

/** The memory tree as a derivation of a builder (7.2, choices 5 to 9). `tree` makes the leaf calls: the summaries that
  * the newest view of all the messages needs (choice 9), with the budget of each turn (choice 6). Each build starts
  * from the state of the build before it, so a stand-in of a spent budget or of a stop builds again when a later build
  * has a call. After each build, `zooms` reads the new memory, so `zoom` of a cell reads the newest lines (choice 7).
  */
final class MemoryBuilder(tree: MemoryTree, lines: LineStore, zooms: ZoomSource) extends BuiltDerivation:
  @volatile private var latest: MemoryState = tree.empty

  val name: DerivationName = DerivationName.Memory

  def newest: Long = lines.newest

  def build(journal: Vector[Logged])(using RuntimeContext): Unit =
    tree.update(journal, latest).foreach { fresh =>
      latest = fresh
      zooms.setReader(ref => tree.zoom(fresh, ref))
    }

/** The gate of the policy calls of a session (7.2, choice 4). The summary calls and the policy calls go to the same
  * endpoint, so a summary call of the builder starts only when no policy call of the session waits for its reply.
  * `during` marks a policy call, and `awaitOpen` waits until no policy call runs. A summary call that already runs goes
  * on to its end. After `close`, `awaitOpen` never waits, so the builder can end.
  */
final class PolicyGate:
  private val lock   = ReentrantLock()
  private val opened = lock.newCondition()
  private var busy   = 0
  private var closed = false

  /** Runs the policy call `call`. While it runs, `awaitOpen` waits. */
  def during[A](call: => A): A =
    locked(busy += 1)
    try call
    finally locked {
        busy -= 1
        if busy == 0 then opened.signalAll()
      }

  /** Waits until no policy call runs, or until the close. */
  def awaitOpen(): Unit =
    locked {
      while busy > 0 && !closed do opened.awaitUninterruptibly()
    }

  /** Opens the gate for ever. */
  def close(): Unit =
    locked {
      closed = true
      opened.signalAll()
    }

  private def locked[A](body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

/** The runner of the summary calls of a builder (7.2, choice 4): each call waits at `gate` first, then runs through
  * `inner`. After `close`, a call gives `PolicyRunner.Stopped` with no model call, so the builder of a closed
  * activation makes no new call.
  */
final class GatedRunner(gate: PolicyGate, inner: PolicyRunner) extends PolicyRunner:
  @volatile private var closed = false

  def run[A](call: () => Either[String, A]): Either[String, A] =
    gate.awaitOpen()
    if closed then Left(PolicyRunner.Stopped) else inner.run(call)

  /** Gives `PolicyRunner.Stopped` to each later call. */
  def close(): Unit = closed = true

/** The builder of one activation (7.2, choice 5): one virtual thread that runs `derivation` on the journal, at the
  * start and after each `wake`. The writer wakes it after each commit, so it builds between the policy calls and after
  * the turn. Many wakes during one build give one more build. A failed build keeps the lines of its rounds, and the
  * next wake tries again. `close` ends the thread: `stopCalls` cuts the leaf calls that run, and the store keeps the
  * lines of the rounds that ended. A stop of a turn does not stop the builder, because no turn waits for it.
  */
final class Builder(
    derivation: BuiltDerivation,
    journal   : () => Vector[Logged],
    context   : RuntimeContext,
    stopCalls : () => Unit,
    label     : String
) extends AutoCloseable:
  private val lock   = ReentrantLock()
  private val woken  = lock.newCondition()
  private var dirty  = true
  private var closed = false
  private val thread = Thread.ofVirtual().name(s"harness-builder-$label").start(() => loop())

  /** Asks for one more build. It returns at once. */
  def wake(): Unit =
    locked {
      dirty = true
      woken.signalAll()
    }

  /** Ends the thread: no new build starts, and the calls that run stop. It waits for the thread a short time. */
  def close(): Unit =
    locked {
      closed = true
      woken.signalAll()
    }
    stopCalls()
    thread.join(java.time.Duration.ofMillis(Builder.JoinMillis)): Unit

  private def loop(): Unit =
    while awaitBuild() do
      try RuntimeEnvironment.withContext(context)(derivation.build(journal())(using context))
      catch case NonFatal(_) => ()

  /** True when a build must run, after a wait for a wake. False after the close. A wake sets `dirty` with the lock, so
    * no wake is lost, and a wake during a build gives one more build.
    */
  private def awaitBuild(): Boolean =
    locked {
      while !dirty && !closed do woken.awaitUninterruptibly()
      if closed then false
      else
        dirty = false
        true
    }

  private def locked[A](body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

object Builder:

  /** How long `close` waits for the thread. */
  val JoinMillis = 5_000L
