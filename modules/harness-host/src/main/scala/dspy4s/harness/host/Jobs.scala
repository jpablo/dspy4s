package dspy4s.harness.host

import dspy4s.harness.Journal
import dspy4s.harness.guest.{Files, JobId, JobStatus, Shell}
import dspy4s.harness.kernel.{EffectId, SessionState}
import dspy4s.harness.sandbox.SandboxedShell

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/** The live part of the jobs of one session (CODE_HARNESS_DESIGN.md, 10.3): the process groups, the output and the stop
  * marks. The list of the jobs is a fold of the journal (`SessionState.jobs`), so the table holds only what a restart
  * loses. The host owns one table for each open session, so a new activation keeps the jobs.
  *
  * The table starts each process on one launcher thread that lives as long as the table. On Linux, `--die-with-parent`
  * of bubblewrap stops a process when the thread that started it ends, and a cell thread ends with its cell. A stop
  * runs on a thread of the table, so `sync` returns at once. When a job with `report` ends, the table calls `report`
  * with its id and status, and the host posts the message to the writer of the session.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class JobTable(grace: FiniteDuration, report: (EffectId, JobStatus) => Unit) extends AutoCloseable:

  /** Copies the stop marks of `state` to the table, and starts the stop of each marked job that runs: TERM, the grace
    * period, then KILL (10.3, step 2). It returns at once.
    */
  def sync(state: SessionState): Unit = ???

  /** Starts the job `id` with `launch` on the launcher thread, unless the table has a stop mark for `id` (10.3, step
    * 3). Then it does not start the process, and it gives an error. With `report`, the end of the job calls `report`.
    */
  def start(id: EffectId, launch: () => Process, report: Boolean): Either[String, Unit] = ???

  /** The output of the job `id` up to now, or none for a job that the table does not have. */
  def output(id: EffectId): Option[String] = ???

  /** Waits until the job `id` ends or `maxSeconds` pass, and gives its status. None for a job that the table does not
    * have.
    */
  def await(id: EffectId, maxSeconds: Int): Option[JobStatus] = ???

  /** The status of the job `id` (10.3). A job of `state` with no row in the table is `Lost`: its host ended. */
  def status(id: EffectId, state: SessionState): JobStatus = ???

  /** Stops each job that runs: TERM, the grace period, then KILL. It waits for the stops. */
  def close(): Unit = ???

/** The handler of `Shell^` in a session of the host (5.3, 10.3). `exec` goes to `inner`. A job starts with
  * `inner.launch`, and the table holds it.
  *
  *   - `spawn` gives the `JobId` of its own `EffectStarted`: the open effect of the op `spawn` in `journal`. The calls
  *     of one cell run one at a time, so the cell has only that one open.
  *   - With `report`, the end of the job sends a message with `Source.Harness`. The message reaches a step of the open
  *     turn, or it starts a turn when no turn is open.
  *   - `stopJob(job)` runs `StopJobs(One(job))` (10.3).
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class JobShell(inner: SandboxedShell, table: JobTable, journal: Journal) extends Shell:

  def exec(command: String)(using Files): String = ???

  def spawn(command: String, report: Boolean)(using Files): JobId = ???

  def jobOutput(job: JobId): String = ???

  def waitJob(job: JobId, maxSeconds: Int): JobStatus = ???

  def stopJob(job: JobId): Unit = ???
