package dspy4s.harness.cli

import dspy4s.harness.guest.{Scope, SessionCard}
import dspy4s.harness.host.{CatalogRow, Controller, HostError, HostSessions, LocalHost, SessionHandle}
import dspy4s.harness.kernel.{Owner, ReleaseItem, ReleaseResult, ReleaseStep, ScopeState, SessionConfig, SessionId}
import dspy4s.harness.kernel.{SessionOutcome, irreversible, state}

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
final class HostDesk(host: LocalHost, config: SessionConfig) extends Desk:

  def cards: Vector[SessionCard] = host.list.map(HostSessions.cardOf)

  def open(id: SessionId): Either[HostError, SessionHandle] =
    host.journalOf(id) match
      case Left(error)                             => Left(HostError.Refused(error))
      case Right(journal) if state(journal).closed => Left(HostError.Ended)
      case Right(_)                                => host.open(id, None)

  def reopen(id: SessionId): Either[HostError, SessionHandle] =
    host.journalOf(id).left.map(HostError.Refused(_)).flatMap(_ => host.open(id, None))

  def controller: Either[HostError, SessionHandle] = host.open(Controller.id, Some(config))

  def planOf(id: SessionId, outcome: SessionOutcome): Either[HostError, (ScopeState, Vector[ReleaseItem])] =
    host.planOf(id, outcome)

  def release(id: SessionId, outcome: SessionOutcome, reason: String, confirm: ReleaseItem => Boolean)
      : Either[HostError, Vector[ReleaseStep]] = host.release(id, outcome, reason, confirm)

/** The refusal of `--session ID` for the row `row` of the catalog (8.7): a session whose owner is a session opens only
  * in the host of its owner, so its reports reach the owner. A session with no row, or whose owner is not a session,
  * gives none.
  */
def openRefusal(row: Option[CatalogRow]): Option[String] =
  row.flatMap { r =>
    r.owner match
      case Owner.Session(owner) => Some(
          s"The session ${r.session.value} belongs to the session ${owner.value}, so it opens only with its owner. " +
            s"Start the client with --controller, then type /open ${r.session.value}."
        )
      case _ => None
  }

/** The text of a card for `/sessions`: one line with the id, the status, the goal or the scope, and the number of the
  * files that the session made.
  */
def cardText(card: SessionCard): String =
  val what = if card.goal.nonEmpty then card.goal else card.scope.fold("no scope")(scopeText)
  s"${card.session.id}  ${card.status}  $what  (${card.made.size} files made)"

/** The text of a release plan before the person confirms it: one line for each item, and the mark `(irreversible)` on
  * each item of `irreversible(plan, scopeState)`. An empty plan gives a line that says that nothing changes.
  */
def planText(scopeState: ScopeState, plan: Vector[ReleaseItem]): String =
  if plan.isEmpty then "The release plan changes nothing."
  else
    val stays = irreversible(plan, scopeState).toSet
    plan.map(item => s"- ${itemText(item)}${if stays(item) then " (irreversible)" else ""}").mkString("\n")

/** The text of one item of a release plan. */
def itemText(item: ReleaseItem): String =
  item match
    case ReleaseItem.RemoveWorktree(_, path)          => s"remove the worktree $path"
    case ReleaseItem.DeleteBranch(repo, name, commit) => s"delete the branch $name at ${commit.take(12)} of $repo"
    case ReleaseItem.KeepRef(_, name, branch, commit) => s"keep the branch $branch at ${commit.take(12)} as $name"
    case ReleaseItem.ArchiveDirectory(path, to)       => s"move $path to $to"
    case ReleaseItem.Keep(resource, reason)           => s"keep $resource: $reason"

/** The text of one step of a release. */
def stepText(step: ReleaseStep): String =
  val result = step.result match
    case ReleaseResult.Done            => "done"
    case ReleaseResult.Failed(error)   => s"failed: $error"
    case ReleaseResult.Skipped(reason) => s"skipped: $reason"
  s"${itemText(step.item)}: $result"

/** The text of a scope. */
private def scopeText(scope: Scope): String =
  scope match
    case Scope.Home(path)                   => s"home $path"
    case Scope.Directory(path)              => s"folder $path"
    case Scope.Worktree(path, branch, _, _) => s"worktree $path on $branch"
    case Scope.Scratch(path)                => s"scratch $path"
    case Scope.Fork(path, _)                => s"fork $path"
