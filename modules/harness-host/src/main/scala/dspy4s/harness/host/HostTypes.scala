package dspy4s.harness.host

import dspy4s.harness.kernel.Rejection

import scala.concurrent.duration.*

/** Why the host did not commit a command (CODE_HARNESS_DESIGN.md, 10.2). */
enum HostError derives CanEqual:

  /** `decide` refused the command (4.5). */
  case Rejected(rejection: Rejection)

  /** A write to the file of the session failed. The session ended, and each caller that waits gets this error. */
  case WriteFailed(message: String)

  /** The session ended, so its writer takes no message. */
  case Ended

  /** The writer fenced this run of a worker after a hard stop (10.3, step 5). */
  case Fenced

  /** The host could not open the file or the folder of a session. */
  case OpenFailed(message: String)

/** The id of one run of a worker (10.3). After a hard stop, the writer fences the ticket of a worker whose cell did not
  * end, so a late commit of that worker gets `HostError.Fenced`.
  */
final case class WorkerTicket(n: Long) derives CanEqual

/** The limits of a host (10.2, 10.3).
  *
  * @param mailbox
  *   the most messages in the mailbox of one session. A put into a full mailbox waits.
  * @param grace
  *   the grace period of a hard stop: the wait for a cell after its interrupt, and the time from TERM to KILL for a
  *   process group
  *
  * A proposal for the review: the design gives no numbers. The grace period is 5 seconds, as the wait of the REPL for a
  * cell that it stops (6).
  */
final case class HostLimits(mailbox: Int = 256, grace: FiniteDuration = 5.seconds)
