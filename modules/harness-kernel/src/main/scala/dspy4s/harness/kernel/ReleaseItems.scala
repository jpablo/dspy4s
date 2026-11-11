package dspy4s.harness.kernel

import dspy4s.harness.guest.Scope
import dspy4s.harness.guest.SessionSchemas.given
import zio.blocks.schema.Schema

/** The state of the scope of a session at its end (CODE_HARNESS.md, 7.9): what `releasePlan` needs. The handler of the
  * host reads it from the folder of the scope.
  *
  * @param head
  *   the git head of the scope, or none for a folder with no git
  * @param tip
  *   the commit of `refs/heads/<branch>` of a `Worktree` scope, or none. It differs from `head` on a detached head.
  * @param clean
  *   true when the scope has no change that git does not keep: no uncommitted change, no file that git does not track,
  *   and no ignored file, because `git worktree remove` deletes ignored files too. A folder with no git is clean.
  * @param merged
  *   true when `tip` is in the base branch of a `Worktree` scope. It is false for each other scope.
  * @param owned
  *   true when the host made the folder for `start` (8.7), under its folder of work. Only an owned folder can go.
  * @param shared
  *   true when another session that has not ended has the same folder. A shared folder stays.
  */
final case class ScopeState(
    scope : Scope,
    head  : Option[String],
    tip   : Option[String],
    clean : Boolean,
    merged: Boolean,
    owned : Boolean,
    shared: Boolean
) derives Schema,
      CanEqual

/** One step of a release plan (CODE_HARNESS.md, 7.9). `repo` is the repository of a `Worktree` scope.
  *
  * A proposal for the review: 7.9 gives `RemoveWorktree(path)`, `DeleteBranch(name)` and `KeepRef(name, commit)`. A git
  * command needs the repository too, so these items hold it.
  */
enum ReleaseItem derives Schema, CanEqual:

  /** `git worktree remove`: the folder of the worktree goes, and its branch stays. */
  case RemoveWorktree(repo: String, path: String)

  /** The delete of the branch `name` when it is still at `commit`: `git update-ref -d refs/heads/<name> <commit>`. A
    * branch that moved after the plan stays.
    */
  case DeleteBranch(repo: String, name: String, commit: String)

  /** A ref `name` on `commit`, the tip of `branch`, so a later delete of that branch is reversible. */
  case KeepRef(repo: String, name: String, branch: String, commit: String)

  /** Moves the folder `path` to `to`. The move is reversible, so the plan removes a scratch directory only this way. */
  case ArchiveDirectory(path: String, to: String)

  /** No step: the resource stays, with the reason. */
  case Keep(resource: String, reason: String)

/** What a step of a release did. */
enum ReleaseResult derives Schema, CanEqual:
  case Done

  /** The step ran and failed. The resource can be in its old state or in a part of the new one. */
  case Failed(error: String)

  /** The step did not run. An irreversible item with no confirmation of the person gets it. */
  case Skipped(reason: String)

/** One step of a release with its result, as the journal and the ledger keep it. */
final case class ReleaseStep(item: ReleaseItem, result: ReleaseResult) derives Schema, CanEqual
