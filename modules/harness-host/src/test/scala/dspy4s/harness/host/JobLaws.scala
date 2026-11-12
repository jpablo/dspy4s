package dspy4s.harness.host

import dspy4s.harness.guest.JobStatus
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.ProcessWrapper

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The laws of the job table (CODE_HARNESS_DESIGN.md, 10.3). The jobs are real processes under the wrapper, with no
  * sandbox: the sandbox gets its tests in the laws of a work session.
  */
class JobLaws extends munit.FunSuite:

  private val job = EffectId(4L, 0)

  /** A launch of `command` under the wrapper, with a grace period of 1 second. */
  private def launch(command: String): () => Process =
    () => ProcessBuilder(ProcessWrapper.command(command, 1)*).redirectErrorStream(true).start()

  /** A state with the job `id`, with a stop mark or not. */
  private def withJob(id: EffectId, marked: Boolean): SessionState =
    SessionState.initial.copy(jobs = Map(id -> JobRecord(2L, stopRequested = marked)))

  /** A table with a grace period of 1 second, whose reports go into `reports`. */
  private def table(reports: ConcurrentLinkedQueue[(EffectId, JobStatus)] = ConcurrentLinkedQueue()): JobTable =
    JobTable(1.second, (id, status) => reports.add((id, status)): Unit)

  test("Jobs: a job outlives the call that starts it, output reads its output, and await gives its exit code") {
    val jobs = table()
    try
      assertEquals(jobs.start(job, launch("echo FAKE-OUT; sleep 1; exit 3"), report = false), Right(()))
      assertEquals(jobs.await(job, 10), Some(JobStatus.Exited(3)))
      assert(jobs.output(job).exists(_.contains("FAKE-OUT")), jobs.output(job).toString)
    finally jobs.close()
  }

  test("Jobs: the output of a job keeps each character whole, at the cut of its oldest part and between two reads") {
    val jobs  = table()
    val other = EffectId(5L, 0)
    try
      // The emoji comes first, so the cut of the oldest characters falls inside it: 2 + 199_999 units are 200_001.
      val cut = "printf '\\360\\237\\230\\200'; head -c 199999 /dev/zero | tr '\\0' b"
      assertEquals(jobs.start(job, launch(cut), report = false), Right(()))
      assertEquals(jobs.await(job, 30), Some(JobStatus.Exited(0)))
      assert(eventually(10)(jobs.output(job).exists(_.length >= 199_999)), jobs.output(job).map(_.length).toString)
      assertEquals(jobs.output(job), Some("b" * 199_999))
      // The four bytes of the emoji come at bytes 8192 to 8195, so a read of 8192 bytes cuts them.
      val split = "head -c 8191 /dev/zero | tr '\\0' a; printf '\\360\\237\\230\\200'"
      assertEquals(jobs.start(other, launch(split), report = false), Right(()))
      assertEquals(jobs.await(other, 30), Some(JobStatus.Exited(0)))
      assert(eventually(10)(jobs.output(other).exists(_.length >= 8192)), jobs.output(other).map(_.length).toString)
      assertEquals(jobs.output(other), Some("a" * 8191 + "😀"))
    finally jobs.close()
  }

  test("Jobs: await gives Running when the time is up first") {
    val jobs = table()
    try
      assertEquals(jobs.start(job, launch("sleep 30"), report = false), Right(()))
      assertEquals(jobs.await(job, 1), Some(JobStatus.Running))
    finally jobs.close()
  }

  test(
    "Jobs: sync with a stop mark stops a job that runs, with KILL after the grace period for a job that ignores TERM"
  ) {
    val jobs = table()
    try
      assertEquals(jobs.start(job, launch("trap '' TERM; sleep 30"), report = false), Right(()))
      val start = System.nanoTime()
      jobs.sync(withJob(job, marked = true))
      assertEquals(jobs.await(job, 10), Some(JobStatus.Stopped))
      assert((System.nanoTime() - start).nanos < 8.seconds)
    finally jobs.close()
  }

  test("Jobs: sync returns at once, also for a job that ignores TERM") {
    val jobs = table()
    try
      assertEquals(jobs.start(job, launch("trap '' TERM; sleep 30"), report = false), Right(()))
      val start = System.nanoTime()
      jobs.sync(withJob(job, marked = true))
      assert((System.nanoTime() - start).nanos < 500.millis, "sync waited for the stop")
    finally jobs.close()
  }

  test("Jobs: a start of a job with a stop mark does not start its process, and gives an error") {
    val jobs = table()
    try
      val launched = AtomicBoolean(false)
      jobs.sync(withJob(job, marked = true))
      val result = jobs.start(
        job,
        () =>
          launched.set(true); launch("sleep 30")()
        ,
        report = false
      )
      assert(result.isLeft)
      assert(!launched.get)
    finally jobs.close()
  }

  test("Jobs: the status of a job of the state with no row in the table is Lost") {
    val jobs = table()
    try assertEquals(jobs.status(job, withJob(job, marked = false)), JobStatus.Lost)
    finally jobs.close()
  }

  test("Jobs: the end of a job with report gives its id and its status to the report of the table") {
    val reports = ConcurrentLinkedQueue[(EffectId, JobStatus)]()
    val jobs    = table(reports)
    try
      assertEquals(jobs.start(job, launch("exit 0"), report = true), Right(()))
      assertEquals(jobs.start(EffectId(4L, 1), launch("exit 0"), report = false), Right(()))
      assert(eventually()(reports.size == 1))
      Thread.sleep(300)
      assertEquals(reports.asScala.toVector, Vector((job, JobStatus.Exited(0))))
    finally jobs.close()
  }

  /** A launch of `command` with no wrapper, so a stop needs the TERM and KILL of the table. */
  private def bare(command: String): () => Process =
    () => ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()

  test("Jobs: close after the stop of sync stops a job that ignores TERM, also with no wrapper") {
    val jobs = table()
    assertEquals(jobs.start(job, bare("trap '' TERM; sleep 333"), report = false), Right(()))
    assert(eventually()(running("sleep 333").nonEmpty))
    jobs.sync(withJob(job, marked = true))
    jobs.close()
    assert(eventually(15)(running("sleep 333").isEmpty), "a process of the job stays after close")
    assertEquals(jobs.status(job, withJob(job, marked = true)), JobStatus.Stopped)
  }

  test("Jobs: the report of a job sees its whole output") {
    val outputs = java.util.concurrent.ConcurrentHashMap[EffectId, String]()
    val ref     = java.util.concurrent.atomic.AtomicReference[JobTable]()
    val jobs    = JobTable(1.second, (id, _) => outputs.put(id, ref.get.output(id).getOrElse("")): Unit)
    ref.set(jobs)
    try
      // The race of the report and the reader is short, so the law runs many jobs with a long output.
      val ids = (0 until 40).map(n => EffectId(4L, n))
      ids.foreach(id => assertEquals(jobs.start(id, bare("seq 1 100000"), report = true), Right(())))
      assert(eventually(60)(outputs.size == ids.size), s"${outputs.size} reports")
      val cut = ids.filterNot(id => outputs.get(id).trim.endsWith("100000"))
      assert(cut.isEmpty, s"${cut.size} of ${ids.size} reports lost the end of the output")
    finally jobs.close()
  }

  test("Jobs: a second close does nothing") {
    val jobs = table()
    jobs.close()
    jobs.close()
  }

  test("Jobs: close stops each job that runs") {
    val jobs = table()
    assertEquals(jobs.start(job, launch("sleep 30"), report = false), Right(()))
    assertEquals(jobs.start(EffectId(4L, 1), launch("sleep 30"), report = false), Right(()))
    jobs.close()
    assertEquals(jobs.status(job, withJob(job, marked = false)), JobStatus.Stopped)
  }

  // ── An interrupt of the caller ──

  test("Jobs: close on a thread with an interrupt waits for each stop, ends the table, and keeps the interrupt") {
    val jobs = table()
    try
      assertEquals(jobs.start(job, bare("trap '' TERM; sleep 335"), report = false), Right(()))
      assert(eventually()(running("sleep 335").nonEmpty), "the job did not start")
      val (_, done) = onThread("jobs-law-close") {
        Thread.currentThread().interrupt()
        jobs.close()
        Thread.currentThread().isInterrupted
      }
      assertEquals(done.get(30, TimeUnit.SECONDS), Right(true))
      // The job ignores TERM, so it is Stopped only when the close waited for its KILL.
      assertEquals(jobs.status(job, withJob(job, marked = false)), JobStatus.Stopped)
      // The launcher of the table ended, so a new job does not start.
      assert(jobs.start(EffectId(4L, 1), bare("exit 0"), report = false).isLeft)
    finally running("sleep 335").foreach(_.destroyForcibly(): Unit)
  }

  test("Jobs: an interrupt during the start of a job keeps the job in the table, and the caller keeps the interrupt") {
    val jobs  = table()
    val begun = CountDownLatch(1)
    val gate  = CountDownLatch(1)
    // The launch waits at the gate, so the interrupt comes while the caller waits for the launch.
    val slow: () => Process = () =>
      begun.countDown()
      gate.await(20, TimeUnit.SECONDS): Unit
      bare("sleep 336")()
    try
      val (thread, done) = onThread("jobs-law-start") {
        (jobs.start(job, slow, report = false), Thread.currentThread().isInterrupted)
      }
      assert(begun.await(20, TimeUnit.SECONDS), "the launch did not begin")
      thread.interrupt()
      // The caller gets the interrupt before the launch ends.
      Thread.sleep(200)
      gate.countDown()
      assertEquals(done.get(20, TimeUnit.SECONDS), Right((Right(()), true)))
      assert(jobs.output(job).nonEmpty, "the table has no row of the job")
      jobs.close()
      assert(eventually(15)(running("sleep 336").isEmpty), "a process of the job stays after close")
    finally
      jobs.close()
      running("sleep 336").foreach(_.destroyForcibly(): Unit)
  }
