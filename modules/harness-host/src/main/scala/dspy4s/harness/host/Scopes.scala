package dspy4s.harness.host

import dspy4s.harness.guest.Scope
import dspy4s.harness.kernel.{ReleaseItem, ReleaseResult, ScopeState}

import scala.annotation.nowarn
import scala.concurrent.duration.*

/** The git head and the state of the folder of a scope at one time (CODE_HARNESS.md, 7.9: `ScopeSnapshot`). */
final case class ScopeSnapshot(head: String, clean: Boolean) derives CanEqual

/** The handler of the host for the folders of the scopes (CODE_HARNESS.md, 7.9; CODE_HARNESS_DESIGN.md, 8.7). It makes
  * the folder of a scope for `start`, reads the state of a scope for `ScopeRecorded` and for a release plan, and runs
  * the steps of a plan. It runs git outside the sandbox, so only the host calls it, never a cell.
  *
  * A session can write a repository in its scope, so its configuration and hooks are not trusted. Each git command of
  * the handler runs with `-c core.hooksPath=/dev/null -c core.fsmonitor=false`, with only `PATH`, `HOME` and `LANG` of
  * the environment, and a read with `--no-optional-locks`, so it takes no lock of the index. A path and a ref come
  * after `--`, and a branch or a base that starts with `-` or that `git check-ref-format --branch` refuses gives an
  * error.
  */
trait Scopes:

  /** Makes the folder of `scope`, which must not exist: `git worktree add` of a new branch from its base for a
    * `Worktree`, and a new folder for a `Scratch`. Each other scope gives an error: the host makes only these folders.
    */
  def make(scope: Scope): Either[String, Unit]

  /** The git head of the folder of `scope` and whether it has no uncommitted change, or none for a folder with no git
    * or with no commit.
    */
  def snapshot(scope: Scope): Option[ScopeSnapshot]

  /** The state of `scope` for a release plan: the head, the tip of the branch of a `Worktree`, whether the scope is
    * clean with its ignored files, and whether the tip is in the base. `owned` and `shared` are false: the host knows
    * its folders and its sessions, so it sets them.
    */
  def stateOf(scope: Scope): ScopeState

  /** Runs one step of a release plan. A step that fails gives `Failed` with the error, and it never throws. Only a step
    * that the person `confirmed` can force: `git worktree remove --force` of a worktree with changes. With no
    * confirmation, a worktree that has changes since the plan stays, and its step fails.
    */
  def release(item: ReleaseItem, confirmed: Boolean): ReleaseResult

/** `Scopes` with the git of the machine (8.7). Each command runs with `git -C <folder>`, with no shell, and with the
  * time limit `timeout`. A command that does not end in time gives an error, and its process stops.
  */
@nowarn("msg=unused")
final class GitScopes(timeout: FiniteDuration = 30.seconds) extends Scopes:

  def make(scope: Scope): Either[String, Unit] = ???

  def snapshot(scope: Scope): Option[ScopeSnapshot] = ???

  def stateOf(scope: Scope): ScopeState = ???

  def release(item: ReleaseItem, confirmed: Boolean): ReleaseResult = ???
