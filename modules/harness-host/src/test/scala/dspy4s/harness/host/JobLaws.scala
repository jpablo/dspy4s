package dspy4s.harness.host

import dspy4s.harness.guest.JobStatus
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.ProcessWrapper

import java.util.concurrent.ConcurrentLinkedQueue
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

  test("Jobs: close stops each job that runs") {
    val jobs = table()
    assertEquals(jobs.start(job, launch("sleep 30"), report = false), Right(()))
    assertEquals(jobs.start(EffectId(4L, 1), launch("sleep 30"), report = false), Right(()))
    jobs.close()
    assertEquals(jobs.status(job, withJob(job, marked = false)), JobStatus.Stopped)
  }
