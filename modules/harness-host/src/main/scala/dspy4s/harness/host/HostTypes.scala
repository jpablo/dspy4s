package dspy4s.harness.host

import dspy4s.harness.kernel.{Command, Rejection, SessionId}

import scala.concurrent.duration.*

/** Why the host did not commit a command (CODE_HARNESS_DESIGN.md, 10.2). */
enum HostError derives CanEqual:

  /** `decide` refused the command (4.5). */
  case Rejected(rejection: Rejection)

  /** A write to the file of the session failed. The session ended, and each caller that waits gets this error. */
  case WriteFailed(message: String)

  /** The session ended, so its writer takes no message. */
  case Ended

  /** The writer fenced this activation after a hard stop (10.3, step 5). */
  case Fenced

  /** The host could not open the session: its file or folder, a file that is not a journal, a session that another host
    * has open, a new session with no configuration, a scope whose folder is not there, or a mode above the ceiling of
    * the session.
    */
  case OpenFailed(message: String)

  /** The host refuses the call, with the reason: for example a release of the controller, or a session id that is not
    * valid (8.7).
    */
  case Refused(message: String)

  /** The error in words for a person and for a model, with no Scala value. The terminal, the error of a cell and the
    * error of a call of the controller give these words:
    *
    *   - `Rejected`: the words of the refusal (`Rejection.words`), for example `a turn is open`.
    *   - `WriteFailed`: `a write to the journal failed: ` and the message.
    *   - `Ended`: `the session has ended`.
    *   - `Fenced`: `a stop ended this activation`.
    *   - `OpenFailed` and `Refused`: the message, because it is in words.
    */
  def words: String =
    this match
      case Rejected(rejection)  => rejection.words
      case WriteFailed(message) => s"a write to the journal failed: $message"
      case Ended                => "the session has ended"
      case Fenced               => "a stop ended this activation"
      case OpenFailed(message)  => message
      case Refused(message)     => message

/** The id of one activation (10.3). Each commit of the activation goes through its ticket: the commits of the worker,
  * of the effects and of the recorder. After a hard stop whose cell does not end, the writer fences the ticket, so each
  * late commit of that activation gets `HostError.Fenced`.
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

/** Points of a host where a law can stop or slow it. Only the laws set them. */
final case class HostHooks(
    /** Runs before the host sends the report `report` of `session` to its owner (8.6). The crash law of the report pass
      * stops the process here.
      */
    beforeReport: (SessionId, Command.Send) => Unit = (_, _) => ()
)

object HostHooks:
  val none: HostHooks = HostHooks()
