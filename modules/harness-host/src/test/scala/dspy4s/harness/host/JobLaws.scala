package dspy4s.harness.host

import dspy4s.harness.guest.JobStatus
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The laws of the job table and of the wrapper of a job (CODE_HARNESS_DESIGN.md, 10.3). The jobs are real processes,
  * with no sandbox: the sandbox gets its tests in the laws of a work session.
  */
class JobLaws extends munit.FunSuite:

  private val job = EffectId(4L, 0)

  /** A launch of `command` under the wrapper, with a grace period of 1 second. */
  private def launch(command: String): () => Process =
    () => ProcessBuilder(JobWrapper.command(command, 1)*).redirectErrorStream(true).start()

  /** A state with the job `id`, with a stop mark or not. */
  private def withJob(id: EffectId, marked: Boolean): SessionState =
    SessionState.initial.copy(jobs = Map(id -> JobRecord(2L, stopRequested = marked)))

  test("Jobs: a job outlives the call that starts it, output reads its output, and await gives its exit code") {
    val table = JobTable(1.second)
    try
      assertEquals(table.start(job, launch("echo FAKE-OUT; sleep 1; exit 3"), None), Right(()))
      assertEquals(table.await(job, 10), Some(JobStatus.Exited(3)))
      assert(table.output(job).exists(_.contains("FAKE-OUT")), table.output(job).toString)
    finally table.close()
  }

  test("Jobs: await gives Running when the time is up first") {
    val table = JobTable(1.second)
    try
      assertEquals(table.start(job, launch("sleep 30"), None), Right(()))
      assertEquals(table.await(job, 1), Some(JobStatus.Running))
    finally table.close()
  }

  test(
    "Jobs: sync with a stop mark stops a job that runs, with KILL after the grace period for a job that ignores TERM"
  ) {
    val table = JobTable(1.second)
    try
      assertEquals(table.start(job, launch("trap '' TERM; sleep 30"), None), Right(()))
      val start = System.nanoTime()
      table.sync(withJob(job, marked = true))
      assertEquals(table.await(job, 10), Some(JobStatus.Stopped))
      assert((System.nanoTime() - start).nanos < 8.seconds)
    finally table.close()
  }

  test("Jobs: a start of a job with a stop mark does not start its process, and gives an error") {
    val table = JobTable(1.second)
    try
      val launched = AtomicBoolean(false)
      table.sync(withJob(job, marked = true))
      val result = table.start(
        job,
        () =>
          launched.set(true); launch("sleep 30")()
        ,
        None
      )
      assert(result.isLeft)
      assert(!launched.get)
    finally table.close()
  }

  test("Jobs: the status of a job of the state with no row in the table is Lost") {
    val table = JobTable(1.second)
    try assertEquals(table.status(job, withJob(job, marked = false)), JobStatus.Lost)
    finally table.close()
  }

  test("Jobs: a job with a report gives its status to the report at its end") {
    val table = JobTable(1.second)
    try
      val got = AtomicReference[JobStatus]()
      assertEquals(table.start(job, launch("exit 0"), Some(status => got.set(status))), Right(()))
      assert(eventually()(Option(got.get).collect { case JobStatus.Exited(0) => () }.nonEmpty), String.valueOf(got.get))
    finally table.close()
  }

  test("Jobs: close stops each job that runs") {
    val table = JobTable(1.second)
    assertEquals(table.start(job, launch("sleep 30"), None), Right(()))
    assertEquals(table.start(EffectId(4L, 1), launch("sleep 30"), None), Right(()))
    table.close()
    assertEquals(table.status(job, withJob(job, marked = false)), JobStatus.Stopped)
  }

  test("Jobs: the wrapper stops the process group of its job when its standard input closes") {
    val wrapper = ProcessBuilder(JobWrapper.command("sleep 30 & sleep 30; wait", 1)*).start()
    assert(eventually()(wrapper.descendants().count() >= 2), "the job did not start")
    val inside = wrapper.descendants().iterator().asScala.toVector
    wrapper.getOutputStream.close()
    assert(eventually()(inside.forall(!_.isAlive) && !wrapper.isAlive), "a process of the job stays")
  }
