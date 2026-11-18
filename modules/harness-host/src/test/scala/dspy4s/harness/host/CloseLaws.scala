package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{CellResult, Journal, PolicyReply, Support, TurnEffects}
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** The laws of the close of a session by the host (CODE_HARNESS_DESIGN.md, 10.2): the close is a crash for the open
  * turn. The worker of the session commits nothing after the close starts, also when the close makes the start of its
  * activation fail. The next `Activate` repairs the turn with `TurnEnded(Crashed)`.
  */
class CloseLaws extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def isTurnEnd(entry: Entry): Boolean =
    entry match
      case Entry.TurnEnded(_) => true
      case _                  => false

  /** A runtime whose activation starts slowly: `activate` waits for the close of the opened part of the session, so the
    * host closes while the activation starts. Then `activate` gives effects whose call fails, or with `fails`, it
    * throws. The close of the opened part waits at most 3 s for a commit of the worker, so a worker that commits after
    * the close starts does it in this time.
    */
  private final class SlowStart(fails: Boolean) extends SessionRuntime:
    private val closing                            = CountDownLatch(1)
    val starting: CountDownLatch                   = CountDownLatch(1)
    val calls: AtomicInteger                       = AtomicInteger(0)
    @volatile private var journal: Option[Journal] = None

    override def opened(session: SessionId, config: SessionConfig): AutoCloseable =
      val _ = (session, config)
      () =>
        closing.countDown()
        eventually(3)(journal.exists(_.log.exists(l => l.entry == Entry.StepStarted(0) || isTurnEnd(l.entry)))): Unit

    def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
      val _ = (session, config, jobs)
      this.journal = Some(journal)
      starting.countDown()
      closing.await(60, TimeUnit.SECONDS): Unit
      if fails then throw IllegalStateException("FAKE-START-FAILED")
      new Activation:
        val effects: TurnEffects = new TurnEffects:
          def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply] =
            val _ = mustAnswer
            calls.incrementAndGet(): Unit
            Left("FAKE-CLOSED")
          def runCell(cell: Long, code: String): CellResult =
            val _ = (cell, code)
            Support.done
        def stop(): Unit     = ()
        def unclean: Boolean = false
        def close(): Unit    = ()

  /** Closes the host while the activation of the turn of `m1` starts with `runtime`, and gives the journal after a
    * reopen in a new host.
    */
  private def closedDuringStart(runtime: SlowStart): Vector[Entry] =
    withRoot { root =>
      withHost(root, runtime) { host =>
        val session = opened(host, id(1))
        session.send(message("m1")).left.foreach(e => fail(e.toString))
        assert(runtime.starting.await(30, TimeUnit.SECONDS), entries(session).toString)
      }
      withHost(root, ScriptRuntime(_ => Support.Script(Vector.empty))) { host =>
        entries(opened(host, id(1), None))
      }
    }

  test("Host close: a close while the activation starts runs no step on that activation, and the turn is Crashed") {
    val runtime = SlowStart(fails = false)
    val log     = closedDuringStart(runtime)
    assertEquals(runtime.calls.get(), 0, log.toString)
    assert(!log.contains(Entry.StepStarted(0)), log.toString)
    assertEquals(log.collect { case Entry.TurnEnded(outcome) => outcome }, Vector(TurnOutcome.Crashed), log.toString)
  }

  test("Host close: a start of the activation that fails after the close starts does not end the turn Failed") {
    val log = closedDuringStart(SlowStart(fails = true))
    assertEquals(log.collect { case Entry.TurnEnded(outcome) => outcome }, Vector(TurnOutcome.Crashed), log.toString)
  }
