package dspy4s.harness.repl

import dspy4s.harness.guest.*
import zio.blocks.schema.json.Json

import java.lang.management.{ManagementFactory, MemoryType}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*

/** The gate of the guest API calls of one REPL (5.1, 6). After `submit`, it refuses each later call of the cell. During
  * a call, it stops the clock of the cell, so the timeout counts only the time of guest code. Each call has its own
  * limit, for example the `maxSeconds` of `waitJob`.
  */
private final class CallGate(submitted: () => Boolean):
  private val depth = AtomicInteger(0)
  private val since = AtomicLong(0L)
  private val spent = AtomicLong(0L)

  /** Starts the clock of a new cell. */
  def reset(): Unit = spent.set(0L)

  def apply[A](op: String)(body: => A): A =
    if submitted() then throw IllegalStateException(s"$op: this cell has called submit, so it can make no more calls")
    // A handler can call another capability, so only the outer call counts.
    if depth.getAndIncrement() == 0 then since.set(System.nanoTime())
    try body
    finally if depth.decrementAndGet() == 0 then spent.addAndGet(System.nanoTime() - since.get()): Unit

  /** The time of the guest API calls of the current cell, with the call that is open now. */
  def callNanos: Long =
    val open = if depth.get() > 0 then System.nanoTime() - since.get() else 0L
    spent.get() + open

/** The bindings with each call through the gate. */
private def gated(b: Bindings, gate: CallGate): Bindings =
  Bindings(
    turn = GatedTurn(b.turn, gate),
    files = b.files.map(GatedFiles(_, gate)),
    shell = b.shell.map(GatedShell(_, gate)),
    network = b.network.map(GatedNetwork(_, gate)),
    llm = b.llm.map(GatedLlm(_, gate)),
    store = b.store.map(GatedStore(_, gate)),
    sessions = b.sessions.map(GatedSessions(_, gate)),
    children = b.children.map(GatedChildren(_, gate))
  )

private final class GatedTurn(inner: Turn, gate: CallGate) extends Turn:
  def now(): Long = gate("now")(inner.now())
  // An input is not an effect, because the journal already holds the value (5.1).
  def input(name: String): Option[Json] = inner.input(name)

private final class GatedFiles(inner: Files, gate: CallGate) extends Files:
  def read(path : String): String                                = gate("read")(inner.read(path))
  def write(path: String, content: String): Unit                 = gate("write")(inner.write(path, content))
  def edit(path: String, oldText: String, newText: String): Unit =
    gate("edit")(inner.edit(path, oldText, newText))

private final class GatedShell(inner: Shell, gate: CallGate) extends Shell:
  def exec(command: String)(using files: Files): String                  = gate("exec")(inner.exec(command)(using files))
  def spawn(command: String, report: Boolean)(using files: Files): JobId =
    gate("spawn")(inner.spawn(command, report)(using files))
  def jobOutput(job: JobId): String                     = gate("jobOutput")(inner.jobOutput(job))
  def waitJob(job  : JobId, maxSeconds: Int): JobStatus = gate("waitJob")(inner.waitJob(job, maxSeconds))
  def stopJob(job  : JobId): Unit                       = gate("stopJob")(inner.stopJob(job))

private final class GatedNetwork(inner: Network, gate: CallGate) extends Network:
  def get(url: String): String = gate("httpGet")(inner.get(url))

private final class GatedLlm(inner: Llm, gate: CallGate) extends Llm:
  def query(prompt        : String): String                 = gate("llmQuery")(inner.query(prompt))
  def queryBatched(prompts: Vector[String]): Vector[String] = gate("llmQueryBatched")(inner.queryBatched(prompts))

private final class GatedStore(inner: Store, gate: CallGate) extends Store:
  def keys: Vector[String]                        = gate("keys")(inner.keys)
  def load[A: Data](key : String): Option[A]      = gate("load")(inner.load[A](key))
  def store[A: Data](key: String, value: A): Unit = gate("store")(inner.store(key, value))

private final class GatedSessions(inner: Sessions, gate: CallGate) extends Sessions:
  def list(): Vector[SessionCard]                                           = gate("sessions")(inner.list())
  def tell(session: SessionRef, message: String): Unit                      = gate("tell")(inner.tell(session, message))
  def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef =
    gate("start")(inner.start(goal, scope, strategy))
  def observe(session: SessionRef, question: String)(using llm: Llm): String =
    gate("observe")(inner.observe(session, question)(using llm))
  def zoom(session: SessionRef, ref: String): String = gate("zoom")(inner.zoom(session, ref))

private final class GatedChildren(inner: Children, gate: CallGate) extends Children:
  def rlm[A: Data](task        : String, inputs: Map[String, String]): A = gate("rlm")(inner.rlm[A](task, inputs))
  def rlmBatched[A: Data](tasks: Vector[String]): Vector[A]              = gate("rlmBatched")(inner.rlmBatched[A](tasks))

/** Protection 4 of Phase 0: above a limit of the heap, the guard stops the running cell that allocated the most memory,
  * and its REPL closes, so the host starts a new one as after a crash. The other REPLs of the JVM go on.
  */
object HeapGuard:

  /** True when the heap is above the limit: a heap pool is above 85% of its maximum after its last garbage collection.
    * The use before a collection holds garbage, so it is not a measure. A test can set another check.
    */
  @volatile var pressure: () => Boolean = () =>
    ManagementFactory.getMemoryPoolMXBeans.asScala.exists { pool =>
      (pool.getType eq MemoryType.HEAP) && pool.isCollectionUsageThresholdSupported && {
        val after = pool.getCollectionUsage
        after != null && after.getMax > 0 && after.getUsed.toDouble / after.getMax > 0.85
      }
    }

  private val repls = ConcurrentHashMap.newKeySet[ScalaRepl]()

  private lazy val monitor: Thread =
    val thread = Thread(
      () =>
        while true do
          Thread.sleep(100)
          if pressure() then
            repls.asScala.flatMap(r => r.allocatedInCell.map(r -> _)).maxByOption(_._2).foreach(_._1.stopForMemory())
      ,
      "harness-heap-guard"
    )
    thread.setDaemon(true)
    thread.start()
    thread

  private[repl] def watch(repl: ScalaRepl): Unit =
    val _ = monitor
    repls.add(repl): Unit

  private[repl] def unwatch(repl: ScalaRepl): Unit = repls.remove(repl): Unit

  /** The bytes that a thread allocated, when the JVM can count them. */
  private[repl] def allocated(thread: Thread): Option[Long] =
    ManagementFactory.getThreadMXBean match
      case beans: com.sun.management.ThreadMXBean if beans.isThreadAllocatedMemorySupported =>
        Some(beans.getThreadAllocatedBytes(thread.threadId)).filter(_ >= 0)
      case _ => None
