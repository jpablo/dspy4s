package dspy4s.harness.host

import dspy4s.harness.{HarnessLimits, Journal, MemoryTree, Models, RequestLm, Secrets, Support, TurnEffects}
import dspy4s.harness.kernel.*

import java.nio.file.{Files, Path}
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The helpers of the tests of the host (CODE_HARNESS_DESIGN.md, 10). */
object HostSupport:

  /** True for a request of the leaf `compress` or `merge` of the memory tree (7.1). */
  def isSummary(text: String): Boolean =
    text.contains(MemoryTree.compressInstructions) || text.contains(MemoryTree.mergeInstructions)

  /** The reply of a summary leaf: a line that is a fixed function of the request. */
  def summaryReply(text: String): String =
    s"[[ ## line ## ]]\nS${(text.hashCode & 0x7fffffff) % 100000}\n\n[[ ## completed ## ]]"

  /** A model for a work session: a summary request gets `summaryReply`, and the policy gets the replies of `steps` in
    * order. A work session has the memory tree, so its summary requests come between the policy calls.
    */
  def sessionModel(steps: String*): RequestLm =
    val next = AtomicInteger(0)
    RequestLm { text =>
      if isSummary(text) then Right(summaryReply(text))
      else steps.lift(next.getAndIncrement()).toRight("the script has no more policy replies")
    }

  /** A model for the controller and the sessions that it starts (8.7): a summary request gets `summaryReply`, and a
    * policy request gets the next reply of the first route whose key is in the text of the request. The task of the
    * controller holds `Controller.instructions`, and the task of a started session holds its goal, so a route of each
    * session has a key that only its requests hold. A route with no reply left gives an error.
    */
  def routedModel(routes: (String, Seq[String])*): RequestLm =
    val next = routes.map((key, _) => key -> AtomicInteger(0)).toMap
    RequestLm { text =>
      if isSummary(text) then Right(summaryReply(text))
      else
        routes.find((key, _) => text.contains(key)) match
          case Some((key, replies)) =>
            replies.lift(next(key).getAndIncrement()).toRight(s"the route $key has no more policy replies")
          case None => Left("no route takes this request")
    }

  /** Runs `body` with a host at `root` whose runtime is `WorkRuntime.forHost` with `model`, `env` and `limits`, so a
    * session with `Sessions` reaches the other sessions (8.7). `scopes` is the handler of the scopes.
    */
  def withPeers[A](
      root  : Path,
      env   : WorkEnv,
      model : dspy4s.lm.contracts.LanguageModel,
      limits: HarnessLimits,
      scopes: Scopes       = GitScopes(),
      hooks : HostHooks    = HostHooks.none,
      work  : Option[Path] = None
  )(body: LocalHost => A): A =
    val host = LocalHost
      .startWith(
        root,
        peers => Models.withModel(model)(WorkRuntime.forHost(env, limits)(peers)),
        Secrets.none,
        scopes = scopes,
        hooks = hooks,
        work = work
      )
      .fold(e => throw AssertionError(e.toString), identity)
    try body(host)
    finally host.close()

  /** The texts of the summary requests of `lm`. */
  def summaryRequests(lm: RequestLm): Vector[String] =
    lm.requests.asScala.toVector.map(_.messages.flatMap(_.text).mkString).filter(isSummary)

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
  def opened(host: LocalHost, session: SessionId, settings: Option[SessionConfig] = Some(config())): SessionHandle =
    host.open(session, settings).fold(e => throw AssertionError(e.toString), identity)

  /** True when `handle` is in a cell. */
  def inCell(handle: SessionHandle): Boolean =
    handle.state.position match
      case Position.InCell(_, _, _) => true
      case _                        => false

  /** True when `condition` holds within `seconds`. */
  def eventually(seconds: Int = 10)(condition: => Boolean): Boolean =
    val end = System.nanoTime() + seconds * 1_000_000_000L
    var met = condition
    while !met && System.nanoTime() < end do
      Thread.sleep(20)
      met = condition
    met

  /** The processes of this machine whose command line holds `text`. A law gives each of its commands a unique text, for
    * example `sleep 331`, so it finds only its own processes.
    */
  def running(text: String): Vector[ProcessHandle] =
    ProcessHandle.allProcesses().iterator().asScala.filter(_.info().commandLine().toScala.exists(_.contains(text)))
      .toVector

  /** Starts `body` on a new thread with the name `name`, and gives the thread and the result of `body`. A throw of
    * `body` comes to the result. With no handler, the thread prints it on the standard error. A law about an interrupt
    * uses it: an `InterruptedException` on the thread of munit makes munit skip the other laws of the suite.
    */
  def onThread[A](name: String)(body: => A): (Thread, CompletableFuture[Either[Throwable, A]]) =
    val done   = CompletableFuture[Either[Throwable, A]]()
    val thread = Thread.ofPlatform().name(name).unstarted(() => done.complete(Right(body)): Unit)
    thread.setUncaughtExceptionHandler((_, e) => done.complete(Left(e)): Unit)
    thread.start()
    (thread, done)

  /** The entries of a handle, without their meta. */
  def entries(handle: SessionHandle): Vector[Entry] = handle.read(-1).map(_.entry)

  /** The outcomes of the turns of a handle. */
  def outcomes(handle: SessionHandle): Vector[TurnOutcome] =
    entries(handle).collect { case Entry.TurnEnded(outcome) => outcome }

  /** A runtime whose activations run the effects that `effects` makes from the journal of the activation, so a script
    * can commit through that journal. `activations` counts the activations, `stops` counts the calls of
    * `Activation.stop`, and each stop runs `onStop`.
    */
  final class ScriptRuntime(effects: Journal => TurnEffects, onStop: () => Unit = () => ()) extends SessionRuntime:
    val activations: AtomicInteger = AtomicInteger(0)
    val stops: AtomicInteger       = AtomicInteger(0)
    val closes: AtomicInteger      = AtomicInteger(0)

    def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation =
      activations.incrementAndGet(): Unit
      val made = effects(journal)
      new Activation:
        def effects: TurnEffects = made
        def stop(): Unit         =
          stops.incrementAndGet(): Unit
          onStop()
        def unclean: Boolean = false
        def close(): Unit    = closes.incrementAndGet(): Unit
