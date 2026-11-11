package dspy4s.harness.host

import dspy4s.harness.{Journal, suffixStart}
import dspy4s.harness.guest.{Files, JobId, JobStatus, Shell}
import dspy4s.harness.kernel.{Command, EffectId, JobSelector, SessionState, Source}
import dspy4s.harness.sandbox.SandboxedShell

import java.io.{InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, ExecutorService, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The live part of the jobs of one session (CODE_HARNESS_DESIGN.md, 10.3): the process groups, the output and the stop
  * marks. The list of the jobs is a fold of the journal (`SessionState.jobs`), so the table holds only what a restart
  * loses. The host owns one table for each open session, so a new activation keeps the jobs.
  *
  * The table starts each process on one launcher thread that lives as long as the table. On Linux, `--die-with-parent`
  * of bubblewrap stops a process when the thread that started it ends, and a cell thread ends with its cell. A stop
  * runs on a thread of the table, so `sync` returns at once. When a job with `report` ends, the table calls `report`
  * with its id and status, and the host posts the message to the writer of the session.
  *
  * A stop closes the standard input of the job: the wrapper then stops the group and the tree of the command with TERM,
  * a grace period and KILL, and it ends after the stop (`ProcessWrapper`). When the job still runs after the grace
  * period of the table and one more second, the table sends TERM and then KILL to the process and each descendant that
  * it sees, for a process with no wrapper.
  */
final class JobTable(grace: FiniteDuration, report: (EffectId, JobStatus) => Unit) extends AutoCloseable:
  import JobTable.*

  private val rows             = ConcurrentHashMap[EffectId, Row]()
  @volatile private var closed = false
  private val marks            = ConcurrentHashMap.newKeySet[EffectId]()
  private val launcher         = Executors.newSingleThreadExecutor(threads("harness-job-launcher"))
  private val helpers          = Executors.newCachedThreadPool(threads("harness-job"))

  /** Copies the stop marks of `state` to the table, and starts the stop of each marked job that runs: TERM, the grace
    * period, then KILL (10.3, step 2). It returns at once.
    */
  def sync(state: SessionState): Unit =
    synchronized {
      state.jobs.foreach { (id, record) =>
        if record.stopRequested && marks.add(id) then
          Option(rows.get(id)).foreach(row => submit(helpers)(stop(row)))
      }
    }

  /** Starts the job `id` with `launch` on the launcher thread, unless the table has a stop mark for `id` (10.3, step
    * 3). Then it does not start the process, and it gives an error. With `report`, the end of the job calls `report`.
    */
  def start(id: EffectId, launch: () => Process, report: Boolean): Either[String, Unit] =
    synchronized {
      if marks.contains(id) then Left("the job has a stop mark, so its process did not start")
      else if rows.containsKey(id) then Left("the job runs already")
      else
        Try(launcher.submit(() => launch()).get()).toEither.left.map(e => s"the job did not start: ${causeOf(e)}")
          .map { process =>
            val row = Row(process)
            rows.put(id, row): Unit
            submit(helpers)(read(row, process.getInputStream))
            submit(helpers) {
              process.waitFor(): Unit
              // The report waits for the end of the output, so it sees the whole output. A descendant that holds the
              // pipe open makes it wait at most `ReaderWait`.
              row.drained.await(ReaderWait.toMillis, TimeUnit.MILLISECONDS): Unit
              row.ended.countDown()
              if report then Try(this.report(id, statusOf(row))): Unit
            }
          }
    }

  /** The output of the job `id` up to now, or none for a job that the table does not have. */
  def output(id: EffectId): Option[String] = Option(rows.get(id)).map(_.text)

  /** Waits until the job `id` ends or `maxSeconds` pass, and gives its status. None for a job that the table does not
    * have.
    */
  def await(id: EffectId, maxSeconds: Int): Option[JobStatus] =
    Option(rows.get(id)).map { row =>
      row.process.waitFor(maxSeconds.max(0).toLong, TimeUnit.SECONDS): Unit
      statusOf(row)
    }

  /** The status of the job `id` (10.3). A job of `state` with no row in the table is `Lost`: its host ended. A job with
    * a stop mark and no row is `Stopped`.
    */
  def status(id: EffectId, state: SessionState): JobStatus =
    Option(rows.get(id)).map(statusOf).getOrElse {
      if state.jobs.get(id).exists(_.stopRequested) then JobStatus.Stopped else JobStatus.Lost
    }

  /** Stops each job that runs: TERM, the grace period, then KILL. It waits for the stops, also for a job whose stop
    * `sync` started already, and then the threads of the table end. A second call does nothing.
    */
  def close(): Unit =
    val first = synchronized {
      val was = closed
      closed = true
      !was
    }
    if first then
      val running = rows.values().asScala.toVector.filter(_.process.isAlive)
      running.foreach(_.markStopped(): Unit)
      val stops = running.map(row => helpers.submit((() => escalate(row)): Runnable))
      stops.foreach(stop => Try(stop.get()): Unit)
      launcher.shutdown()
      helpers.shutdown()
      Try(helpers.awaitTermination(ReaderWait.toMillis, TimeUnit.MILLISECONDS)): Unit

  private def statusOf(row: Row): JobStatus =
    if row.process.isAlive then JobStatus.Running
    else if row.stopped then JobStatus.Stopped
    else JobStatus.Exited(row.process.exitValue())

  /** The stop of one job, once: the end of the pipe of its wrapper, then TERM and KILL for a process with no wrapper.
    */
  private def stop(row: Row): Unit = if row.markStopped() then escalate(row)

  /** The steps of a stop. Two stops of one job at the same time do no harm. */
  private def escalate(row: Row): Unit =
    if row.process.isAlive then
      Try(row.process.getOutputStream.close()): Unit
      if !row.ended.await(grace.toMillis + 1_000L, TimeUnit.MILLISECONDS) then
        val seen = row.process.descendants().iterator().asScala.toVector :+ row.process.toHandle
        seen.filter(_.isAlive).foreach(_.destroy(): Unit)
        if !row.ended.await(grace.toMillis, TimeUnit.MILLISECONDS) then
          (seen ++ row.process.descendants().iterator().asScala).filter(_.isAlive).foreach(_.destroyForcibly(): Unit)
          row.ended.await(grace.toMillis, TimeUnit.MILLISECONDS): Unit

  /** Keeps the output of a job, at most `MaxOutput` characters: the oldest characters go first. */
  private def read(row: Row, in: InputStream): Unit =
    try
      // A reader of characters keeps a character whole when its bytes come in two reads, and it never gives one half
      // of a surrogate pair.
      val chars  = InputStreamReader(in, UTF_8)
      val buffer = new Array[Char](8192)
      var n      = chars.read(buffer)
      while n >= 0 do
        row.append(String(buffer, 0, n))
        n = chars.read(buffer)
    finally row.drained.countDown()

object JobTable:

  /** The most characters of the output of a job that the table keeps. */
  private val MaxOutput = 200_000

  /** The longest wait for the end of the output of a job that ended, and for the threads of a table that closes. */
  private val ReaderWait = scala.concurrent.duration.FiniteDuration(2, TimeUnit.SECONDS)

  /** The row of one job: its process, its output, and its stop. */
  private final class Row(val process: Process):
    val ended: CountDownLatch      = CountDownLatch(1)
    val drained: CountDownLatch    = CountDownLatch(1)
    private val output             = StringBuilder()
    @volatile private var stopFlag = false

    def stopped: Boolean = stopFlag

    /** True for the first stop only. */
    def markStopped(): Boolean =
      synchronized {
        val first = !stopFlag
        stopFlag = true
        first
      }

    def append(text: String): Unit =
      synchronized {
        output.append(text): Unit
        // The oldest characters go first, with no surrogate pair cut in two, because `jobOutput` puts the text into
        // the journal.
        if output.length > MaxOutput then output.delete(0, suffixStart(output, MaxOutput)): Unit
      }

    def text: String = synchronized(output.toString)

  private def threads(name: String): ThreadFactory =
    body =>
      val thread = Thread(body, name)
      thread.setDaemon(true)
      thread

  private def submit(pool: ExecutorService)(body: => Unit): Unit = pool.execute(() => body)

  private def causeOf(e: Throwable): String =
    val cause = Option(e.getCause).getOrElse(e)
    Option(cause.getMessage).getOrElse(cause.toString)

/** The handler of `Shell^` in a session of the host (5.3, 10.3). `exec` goes to `inner`. A job starts with
  * `inner.launch`, and the table holds it.
  *
  *   - `spawn` gives the `JobId` of its own `EffectStarted`: the open effect of the op `spawn` in `journal`. The calls
  *     of one cell run one at a time, so the cell has only that one open.
  *   - With `report`, the end of the job sends a message with `Source.Harness`. The message reaches a step of the open
  *     turn, or it starts a turn when no turn is open.
  *   - `stopJob(job)` runs `StopJobs(One(job))` (10.3).
  */
final class JobShell(inner: SandboxedShell, table: JobTable, journal: Journal) extends Shell:

  def exec(command: String)(using Files): String = inner.exec(command)

  def spawn(command: String, report: Boolean)(using Files): JobId =
    val id = journal.state.open.collectFirst { case (effect, call) if call.op == "spawn" => effect }
      .getOrElse(throw IllegalStateException("spawn: the journal has no open spawn call"))
    table.start(id, () => inner.launch(command), report) match
      case Right(())   => JobId(id.cell, id.n)
      case Left(error) => throw IllegalStateException(s"spawn: $error")

  def jobOutput(job: JobId): String =
    val id = known(job, "jobOutput")
    // A job of the journal with no row lost its output with its host (10.3).
    table.output(id).getOrElse("")

  def waitJob(job: JobId, maxSeconds: Int): JobStatus =
    val id = known(job, "waitJob")
    table.await(id, maxSeconds).getOrElse(table.status(id, journal.state))

  def stopJob(job: JobId): Unit =
    val id = known(job, "stopJob")
    journal.run(Command.StopJobs(JobSelector.One(id), "the cell stopped the job"), Source.Policy).left.foreach {
      rejection => throw IllegalStateException(s"stopJob: the session refused the stop ($rejection)")
    }

  /** The effect of `job`, when the journal knows the job. */
  private def known(job: JobId, op: String): EffectId =
    val id = EffectId(job.cell, job.n)
    if journal.state.jobs.contains(id) then id
    else throw IllegalArgumentException(s"$op: this session has no job $job")
