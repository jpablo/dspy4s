package dspy4s.harness.host

import dspy4s.harness.Journal
import dspy4s.harness.guest.{Files, JobId, JobStatus, Shell}
import dspy4s.harness.kernel.{EffectId, SessionState}
import dspy4s.harness.sandbox.SandboxedShell

import scala.concurrent.duration.FiniteDuration
import scala.annotation.nowarn

/** The live part of the jobs of one session (CODE_HARNESS_DESIGN.md, 10.3): the process groups, the output and the stop
  * marks. The list of the jobs is a fold of the journal (`SessionState.jobs`), so the table holds only what a restart
  * loses.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class JobTable(grace: FiniteDuration) extends AutoCloseable:

  /** Copies the stop marks of `state` to the table, and stops the process group of each marked job that runs: TERM, the
    * grace period, then KILL (10.3, step 2).
    */
  def sync(state: SessionState): Unit = ???

  /** Starts the job `id` with `launch`, unless the table has a stop mark for `id` (10.3, step 3). Then it does not
    * start the process, and it gives an error. When the job ends, `report` gets its status.
    */
  def start(id: EffectId, launch: () => Process, report: Option[JobStatus => Unit]): Either[String, Unit] = ???

  /** The output of the job `id` up to now, or none for a job that the table does not have. */
  def output(id: EffectId): Option[String] = ???

  /** Waits until the job `id` ends or `maxSeconds` pass, and gives its status. None for a job that the table does not
    * have.
    */
  def await(id: EffectId, maxSeconds: Int): Option[JobStatus] = ???

  /** The status of the job `id` (10.3). A job of `state` with no row in the table is `Lost`: its host ended. */
  def status(id: EffectId, state: SessionState): JobStatus = ???

  /** Stops each job that runs: TERM, the grace period, then KILL. */
  def close(): Unit = ???

/** The handler of `Shell^` in a session of the host (5.3, 10.3). `exec` goes to `inner`. A job starts under the wrapper
  * with `launch`, and the table holds it.
  *
  *   - `spawn` gives the `JobId` of its own `EffectStarted`: the open effect of the op `spawn` in `journal`. The calls
  *     of one cell run one at a time, so the cell has only that one open.
  *   - With `report`, the end of the job sends a message with `Source.Harness`, and the message starts a turn.
  *   - `stopJob(job)` runs `StopJobs(One(job))` (10.3).
  *
  * A proposal for the review: `launch` runs a command in the sandbox of `inner` with no wait. The sandbox gets this
  * method in phase 7.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class JobShell(inner: SandboxedShell, table: JobTable, journal: Journal, launch: Vector[String] => Process)
    extends Shell:

  def exec(command: String)(using Files): String = ???

  def spawn(command: String, report: Boolean)(using Files): JobId = ???

  def jobOutput(job: JobId): String = ???

  def waitJob(job: JobId, maxSeconds: Int): JobStatus = ???

  def stopJob(job: JobId): Unit = ???

/** The wrapper of a job (10.3). A process can outlive its host, so each job runs under a small wrapper that holds a
  * pipe to the host.
  */
object JobWrapper:

  /** The command that runs `command` in its own process group, and gives its exit code. When the standard input of the
    * wrapper closes, it stops that group with TERM, `graceSeconds`, then KILL. The host holds the other end of the
    * pipe, so a crash of the host stops the job.
    */
  def command(command: String, graceSeconds: Int): Vector[String] = ???
