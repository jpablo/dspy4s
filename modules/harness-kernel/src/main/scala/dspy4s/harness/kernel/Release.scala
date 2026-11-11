package dspy4s.harness.kernel

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
def releasePlan(state: ScopeState, outcome: SessionOutcome, ref: String, archive: String): Vector[ReleaseItem] = ???

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
def irreversible(plan: Vector[ReleaseItem], state: ScopeState): Vector[ReleaseItem] = ???
