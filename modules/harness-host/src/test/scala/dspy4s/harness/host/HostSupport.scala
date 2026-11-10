package dspy4s.harness.host

import dspy4s.harness.{Journal, Secrets, Support, TurnEffects}
import dspy4s.harness.kernel.*

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

/** The helpers of the tests of the host (CODE_HARNESS_DESIGN.md, 10). */
object HostSupport:

  /** A work session of the user, with chat turns of `steps` steps. */
  def config(steps: Int = 3): SessionConfig = Support.config(TurnKind.Chat, steps).copy(owner = Owner.User)

  def id(n: Int): SessionId = SessionId(s"fake-session-$n")

  /** A message of the user. */
  def message(request: String, text: String = "a fake message"): Command =
    Command.Send(RequestId(request), text, Map.empty)

  /** Runs `body` with a new folder, and removes the folder after it. */
  def withRoot[A](body: Path => A): A =
    val root = Files.createTempDirectory("dspy4s-host-test-").toRealPath()
    try body(root)
    finally Support.remove(root)

  /** Runs `body` with a host at `root`, and closes the host after it. */
  def withHost[A](root: Path, runtime: SessionRuntime, limits: HostLimits = HostLimits())(body: LocalHost => A): A =
    val host =
      LocalHost.start(root, runtime, Secrets.none, limits).fold(e => throw AssertionError(e.toString), identity)
    try body(host)
    finally host.close()

  /** Opens `session` in `host`, or fails the test. */
  def opened(host: LocalHost, session: SessionId, settings: SessionConfig = config()): SessionHandle =
    host.open(session, settings).fold(e => throw AssertionError(e.toString), identity)

  /** True when `condition` holds within `seconds`. */
  def eventually(seconds: Int = 10)(condition: => Boolean): Boolean =
    val end = System.nanoTime() + seconds * 1_000_000_000L
    var met = condition
    while !met && System.nanoTime() < end do
      Thread.sleep(20)
      met = condition
    met

  /** The entries of a handle, without their meta. */
  def entries(handle: SessionHandle): Vector[Entry] = handle.read(-1).map(_.entry)

  /** The outcomes of the turns of a handle. */
  def outcomes(handle: SessionHandle): Vector[TurnOutcome] =
    entries(handle).collect { case Entry.TurnEnded(outcome) => outcome }

  /** A runtime whose activations run the effects of `effects`. `activations` counts the activations, `stops` counts the
    * calls of `Activation.stop`, and each stop runs `onStop`.
    */
  final class ScriptRuntime(effects: () => TurnEffects, onStop: () => Unit = () => ()) extends SessionRuntime:
    val activations: AtomicInteger = AtomicInteger(0)
    val stops: AtomicInteger       = AtomicInteger(0)

    def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
      activations.incrementAndGet(): Unit
      val made = effects()
      new Activation:
        def effects: TurnEffects = made
        def stop(): Unit         =
          stops.incrementAndGet(): Unit
          onStop()
        def unclean: Boolean = false
        def close(): Unit    = ()
