package dspy4s.harness.kernel

import dspy4s.harness.guest.Scope

/** The default release plan of a session (CODE_HARNESS.md, 7.9). It is pure, and it names only the resources of
  * `state.scope`. `ref` is the name of the ref that keeps a branch, for example `refs/archive/<session>`, and `archive`
  * is the folder that gets a scratch directory.
  *
  * The table applies only to a folder that the host made and that no other open session uses (`owned` and not
  * `shared`). Each other folder, and its branch, gets `Keep`.
  *
  * | Outcome   | Worktree                    | Branch                      | Scratch directory |
  * |:----------|:----------------------------|:----------------------------|:------------------|
  * | Merged    | Remove if it has no changes | Delete if merged, else keep | Keep              |
  * | Parked    | Keep                        | Keep                        | Keep              |
  * | Abandoned | Remove if it has no changes | Keep a ref, then delete     | Archive           |
  * | Replaced  | Remove if it has no changes | Keep                        | Keep              |
  *
  * A `Home`, a `Directory` and a `Fork` scope are folders of the user or of an optimizer, so each outcome keeps them. A
  * `DeleteBranch` comes only after the `RemoveWorktree` of the scope, because git refuses to delete the branch of a
  * worktree, and it names `tip`. A `KeepRef` names the branch and `tip`. With no `tip`, the branch stays.
  */
def releasePlan(state: ScopeState, outcome: SessionOutcome, ref: String, archive: String): Vector[ReleaseItem] =
  val parked = outcome match
    case SessionOutcome.Parked => true
    case _                     => false
  // Why a folder of the host stays, or none when the table applies to it.
  val stays =
    if !state.owned then Some("the host did not make this folder")
    else if state.shared then Some("another session uses this folder")
    else if parked then Some("a parked session keeps its folder")
    else None
  state.scope match
    case Scope.Worktree(path, branch, repo, _) =>
      def keepBranch(why: String) = ReleaseItem.Keep(s"branch $branch", why)
      stays match
        case Some(why)            => Vector(ReleaseItem.Keep(path, why), keepBranch(why))
        case None if !state.clean =>
          Vector(
            ReleaseItem.Keep(path, "the worktree has changes that git does not keep"),
            keepBranch("git does not delete the branch of a worktree")
          )
        case None =>
          val branchItems = (outcome, state.tip) match
            case (_, None)                                          => Vector(keepBranch("the branch has no tip"))
            case (SessionOutcome.Merged, Some(tip)) if state.merged =>
              Vector(ReleaseItem.DeleteBranch(repo, branch, tip))
            case (SessionOutcome.Merged, Some(_))         => Vector(keepBranch("the branch is not merged"))
            case (SessionOutcome.Abandoned(_), Some(tip)) =>
              Vector(ReleaseItem.KeepRef(repo, ref, branch, tip), ReleaseItem.DeleteBranch(repo, branch, tip))
            case (_, Some(_)) => Vector(keepBranch("a session that another one replaces keeps its branch"))
          ReleaseItem.RemoveWorktree(repo, path) +: branchItems
    case Scope.Scratch(path) => (stays, outcome) match
        case (None, SessionOutcome.Abandoned(_)) => Vector(ReleaseItem.ArchiveDirectory(path, archive))
        case _                                   => Vector(ReleaseItem.Keep(path, stays.getOrElse("the outcome keeps a scratch folder")))
    case Scope.Home(path)      => Vector(ReleaseItem.Keep(path, "the home of the person stays"))
    case Scope.Directory(path) => Vector(ReleaseItem.Keep(path, "a folder of the person stays"))
    case Scope.Fork(path, _)   => Vector(ReleaseItem.Keep(path, "a fork stays for its optimizer"))

/** The release plans of a host (CODE_HARNESS.md, 7.9: "The plans are values, so a user or a project can change them"):
  * the plan for the state of a scope, an outcome, the name of the ref that keeps a branch, and the archive folder.
  * `releasePlan` is the default.
  */
type ReleasePlans = (ScopeState, SessionOutcome, String, String) => Vector[ReleaseItem]

/** The items of `plan` that stay irreversible for a scope in `state` (CODE_HARNESS.md, 7.9): a `RemoveWorktree` of a
  * scope that is not clean, an `ArchiveDirectory` or a `RemoveWorktree` of a folder that is not owned or that is
  * shared, and a `DeleteBranch` of a branch that is not merged with no `KeepRef` of the same branch and the same commit
  * before it. A step of the host runs such an item only after the person confirms it.
  *
  * The host asks this again before each step, with the items whose steps are `Done` and then the item. So a `KeepRef`
  * that failed makes its `DeleteBranch` irreversible, and with no confirmation it gets `Skipped`.
  */
def irreversible(plan: Vector[ReleaseItem], state: ScopeState): Vector[ReleaseItem] =
  val ours                                                              = state.owned && !state.shared
  def kept(i: Int, repo: String, name: String, commit: String): Boolean =
    plan.take(i).exists {
      case ReleaseItem.KeepRef(r, _, branch, at) => r == repo && branch == name && at == commit
      case _                                     => false
    }
  plan.zipWithIndex.collect {
    case (item @ ReleaseItem.RemoveWorktree(_, _), _) if !state.clean || !ours                                     => item
    case (item @ ReleaseItem.ArchiveDirectory(_, _), _) if !ours                                                   => item
    case (item @ ReleaseItem.DeleteBranch(repo, name, commit), i) if !state.merged && !kept(i, repo, name, commit) =>
      item
  }
