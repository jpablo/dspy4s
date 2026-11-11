package dspy4s.harness.cli

import dspy4s.harness.guest.SessionCard
import dspy4s.harness.host.{CatalogRow, HostError, LocalHost, SessionHandle}
import dspy4s.harness.kernel.{ReleaseItem, ReleaseStep, ScopeState, SessionConfig, SessionId, SessionOutcome}

import scala.annotation.nowarn

/** What a client reaches through its host (CODE_HARNESS_DESIGN.md, 8.7): the cards of the sessions, another session,
  * the controller, and the release of a session. A client with no desk shows one session, as in phase 7.
  */
trait Desk:

  /** The cards of the sessions of the host. */
  def cards: Vector[SessionCard]

  /** Opens the session `id` of the host, or gives it when it is open. A session that has ended gives `Ended`, and it
    * does not open again (8.7).
    */
  def open(id: SessionId): Either[HostError, SessionHandle]

  /** Opens again the session `id`, which has ended (8.7). */
  def reopen(id: SessionId): Either[HostError, SessionHandle]

  /** Opens the controller of the host, or gives it when it is open. */
  def controller: Either[HostError, SessionHandle]

  /** The release plan of the session `id` for `outcome`, with the state of its scope, and with no effect (7.9). */
  def planOf(id: SessionId, outcome: SessionOutcome): Either[HostError, (ScopeState, Vector[ReleaseItem])]

  /** Ends the session `id` with `outcome` and its release plan (`LocalHost.release`). */
  def release(id: SessionId, outcome: SessionOutcome, reason: String, confirm: ReleaseItem => Boolean)
      : Either[HostError, Vector[ReleaseStep]]

/** The desk of a `LocalHost`. `controller` opens `Controller.id` with `config`. */
@nowarn("msg=unused")
final class HostDesk(host: LocalHost, config: SessionConfig) extends Desk:

  def cards: Vector[SessionCard] = ???

  def open(id: SessionId): Either[HostError, SessionHandle] = ???

  def reopen(id: SessionId): Either[HostError, SessionHandle] = ???

  def controller: Either[HostError, SessionHandle] = ???

  def planOf(id: SessionId, outcome: SessionOutcome): Either[HostError, (ScopeState, Vector[ReleaseItem])] = ???

  def release(id: SessionId, outcome: SessionOutcome, reason: String, confirm: ReleaseItem => Boolean)
      : Either[HostError, Vector[ReleaseStep]] = ???

/** The refusal of `--session ID` for the row `row` of the catalog (8.7): a session whose owner is a session opens only
  * in the host of its owner, so its reports reach the owner. A session with no row, or whose owner is not a session,
  * gives none.
  */
def openRefusal(row: Option[CatalogRow]): Option[String] = ???

/** The text of a card for `/sessions`: one line with the id, the status, the goal or the scope, and the number of the
  * files that the session made.
  */
def cardText(card: SessionCard): String = ???

/** The text of a release plan before the person confirms it: one line for each item, and the mark `(irreversible)` on
  * each item of `irreversible(plan, state)`. An empty plan gives a line that says that nothing changes.
  */
def planText(state: ScopeState, plan: Vector[ReleaseItem]): String = ???
