package experiment

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Prints the checks and the measurements, and counts the failed checks. */
object Report:
  private val failures = mutable.ArrayBuffer[String]()

  def section(title: String): Unit = println(s"\n==== $title ====")

  def note(text: String): Unit = println(s"  $text")

  def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    println(s"  [${if ok then "PASS" else "FAIL"}] $name${if detail.isEmpty then "" else s": $detail"}")
    if !ok then failures += name

  def show(label: String, text: String): Unit =
    println(s"  --- $label ---")
    text.linesIterator.take(25).foreach(l => println(s"  | $l"))

  def summary(): Unit =
    section("Summary")
    if failures.isEmpty then note("All checks passed.")
    else note(s"${failures.size} checks failed: ${failures.mkString("; ")}")

/** Measurements of the JVM. */
object Jvm:
  private val memory = ManagementFactory.getMemoryMXBean

  /** The heap in use after three full collections, in MiB. */
  def heapAfterGc(): Long =
    for _ <- 1 to 3 do
      System.gc()
      Thread.sleep(150)
    memory.getHeapMemoryUsage.getUsed / (1024 * 1024)

  def metaspace(): Long =
    ManagementFactory.getMemoryPoolMXBeans.asScala
      .find(_.getName == "Metaspace")
      .map(_.getUsage.getUsed / (1024 * 1024))
      .getOrElse(-1L)

  def loadedClasses(): Int = ManagementFactory.getClassLoadingMXBean.getLoadedClassCount

  /** The resident set size of this process, in MiB. */
  def rss(): Long =
    val pid  = ProcessHandle.current().pid()
    val p    = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString).start()
    val text = String(p.getInputStream.readAllBytes()).trim
    p.waitFor()
    text.toLongOption.map(_ / 1024).getOrElse(-1L)

  /** The total time of all garbage collections so far, in milliseconds. */
  def gcMillis(): Long = ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getCollectionTime).sum

  def threadCpuMillis(t: Thread): Long =
    ManagementFactory.getThreadMXBean.getThreadCpuTime(t.threadId()) / 1_000_000

/** Runs the tasks on separate threads that start at the same time. */
def inParallel[A](tasks: Seq[() => A]): Vector[A] =
  val ready   = CountDownLatch(tasks.size)
  val go      = CountDownLatch(1)
  val results = tasks.map(_ => AtomicReference[Either[Throwable, A]]())
  val threads = tasks.zip(results).zipWithIndex.map { case ((task, ref), i) =>
    val t = Thread(
      null,
      () =>
        ready.countDown()
        go.await()
        ref.set(
          try Right(task())
          catch case e: Exception => Left(e)
        )
      ,
      s"parallel-$i",
      64L << 20
    )
    t.start()
    t
  }
  ready.await()
  go.countDown()
  threads.foreach(_.join())
  results.map(_.get() match
    case Right(a) => a
    case Left(e)  => throw e).toVector

def median(xs: Seq[Long]): Long = xs.sorted.apply(xs.size / 2)

def percentile(xs: Seq[Long], p: Double): Long =
  val sorted = xs.sorted
  sorted(math.min(sorted.size - 1, (p * sorted.size).toInt))

/** Samples the states of the threads whose name starts with a prefix, every 2 ms, to find a shared lock. */
final class Contention private (prefix: String):
  private val states            = mutable.Map[String, Int]().withDefaultValue(0)
  @volatile private var running = true
  private var count             = 0
  private val thread            = Thread(() =>
    val bean = ManagementFactory.getThreadMXBean
    while running do
      for info <- bean.dumpAllThreads(false, false) if info.getThreadName.startsWith(prefix) do
        val key = info.getThreadState match
          case Thread.State.BLOCKED                              => s"BLOCKED on ${info.getLockName}"
          case Thread.State.WAITING | Thread.State.TIMED_WAITING =>
            s"${info.getThreadState} in ${info.getStackTrace.headOption.map(f => s"${f.getClassName}.${f.getMethodName}").getOrElse("?")}"
          case other => other.toString
        states.synchronized(states(key) += 1)
      count += 1
      Thread.sleep(2)
  )

  def stop(): Contention.Result =
    running = false
    thread.join()
    Contention.Result(
      count,
      states.synchronized(states.toList.sortBy(-_._2).take(6).map((k, v) => s"$k: $v").mkString("; "))
    )

object Contention:
  final case class Result(samples: Int, summary: String)

  def start(prefix: String): Contention =
    val c = Contention(prefix)
    c.thread.setDaemon(true)
    c.thread.start()
    c
