package dspy4s.harness.host

import dspy4s.harness.guest.Scope
import dspy4s.harness.kernel.{ReleaseItem, ReleaseResult, ScopeState}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.util.Try

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

  /** The git head of the folder of `scope` and whether it has no uncommitted change, or none for a folder with no git,
    * with no commit, or with a path that is not absolute.
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
  *
  * A smudge filter in the configuration of a repository still runs when `git worktree add` checks out the files: git
  * has no option to ignore the configuration of a repository. So the host refuses a repository inside the scope of an
  * open session (`LocalHost.make`).
  */
final class GitScopes(timeout: FiniteDuration = 30.seconds) extends Scopes:

  def make(scope: Scope): Either[String, Unit] =
    scope match
      case Scope.Scratch(path) =>
        for
          folder <- newFolder(path)
          _      <- Try(JFiles.createDirectories(folder)).toEither.left.map(e => s"the folder $path did not open: $e")
        yield ()
      case Scope.Worktree(path, branch, repo, base) =>
        for
          _ <- newFolder(path)
          _ <- branchName(branch)
          _ <- branchName(base)
          _ <- git(Path.of(repo), "worktree", "add", "-b", branch, "--", path, base)
        yield ()
      case other => Left(s"the host makes only a Scratch or a Worktree, not ${other.productPrefix}")

  def snapshot(scope: Scope): Option[ScopeSnapshot] =
    val folder = folderOf(scope)
    for
      // A relative path would name a folder of the working folder of the host.
      _      <- Option.when(folder.isAbsolute)(())
      head   <- git(folder, "rev-parse", "--verify", "-q", "HEAD").toOption
      status <- git(folder, "status", "--porcelain", "--ignored", "--untracked-files=all").toOption
    yield ScopeSnapshot(head, status.isEmpty)

  def stateOf(scope: Scope): ScopeState =
    val now           = snapshot(scope)
    val (tip, merged) = scope match
      case Scope.Worktree(_, branch, repo, base) if branchName(branch).isRight && branchName(base).isRight =>
        val folder = Path.of(repo)
        val tip    = git(folder, "rev-parse", "--verify", "-q", s"refs/heads/$branch").toOption
        (tip, tip.exists(t => git(folder, "merge-base", "--is-ancestor", t, s"refs/heads/$base").isRight))
      case _ => (None, false)
    ScopeState(scope, now.map(_.head), tip, now.forall(_.clean), merged, owned = false, shared = false)

  def release(item: ReleaseItem, confirmed: Boolean): ReleaseResult =
    val done = Try {
      item match
        case ReleaseItem.RemoveWorktree(repo, path) =>
          val force = if confirmed then Vector("--force") else Vector.empty
          git(Path.of(repo), (Vector("worktree", "remove") ++ force ++ Vector("--", path))*).map(_ => ())
        case ReleaseItem.DeleteBranch(repo, name, commit) => branchName(name).flatMap(_ =>
            git(Path.of(repo), "update-ref", "-d", s"refs/heads/$name", commit)
          ).map(_ => ())
        case ReleaseItem.KeepRef(repo, name, _, commit) =>
          refName(name).flatMap(_ => git(Path.of(repo), "update-ref", name, commit)).map(_ => ())
        case ReleaseItem.ArchiveDirectory(path, to) =>
          val target = Path.of(to)
          if JFiles.exists(target) then Left(s"the archive $to exists")
          else
            Option(target.getParent).foreach(JFiles.createDirectories(_): Unit)
            JFiles.move(Path.of(path), target): Unit
            Right(())
        case ReleaseItem.Keep(_, _) => Right(())
    }.toEither.left.map(e => s"the step failed: $e").flatten
    done.fold(ReleaseResult.Failed(_), _ => ReleaseResult.Done)

  /** The folder of a scope. */
  private def folderOf(scope: Scope): Path =
    scope match
      case Scope.Home(path)              => Path.of(path)
      case Scope.Directory(path)         => Path.of(path)
      case Scope.Worktree(path, _, _, _) => Path.of(path)
      case Scope.Scratch(path)           => Path.of(path)
      case Scope.Fork(path, _)           => Path.of(path)

  /** The absolute path `path` of a folder that does not exist yet. */
  private def newFolder(path: String): Either[String, Path] =
    Try(Path.of(path)).toOption.filter(_.isAbsolute) match
      case None                                  => Left(s"the folder $path is not an absolute path")
      case Some(folder) if JFiles.exists(folder) => Left(s"the folder $path exists")
      case Some(folder)                          => Right(folder)

  /** A name that git takes as a branch. A name that starts with `-` would be an option of git. */
  private def branchName(name: String): Either[String, Unit] =
    if name.isEmpty || name.startsWith("-") then Left(s"$name is not the name of a branch")
    else
      git(Path.of("."), "check-ref-format", "--branch", name).left.map(_ => s"$name is not the name of a branch").map(
        _ => ()
      )

  /** A full name of a ref, for example `refs/archive/<session>`. */
  private def refName(name: String): Either[String, Unit] =
    if !name.startsWith("refs/") then Left(s"$name is not the full name of a ref")
    else git(Path.of("."), "check-ref-format", name).left.map(_ => s"$name is not the name of a ref").map(_ => ())

  /** The output of `git args` in `folder`, or an error with it. The command runs with no hooks, no fsmonitor, no
    * optional lock, only `PATH`, `HOME` and `LANG` of the environment, and the time limit.
    */
  private def git(folder: Path, args: String*): Either[String, String] =
    Try {
      val command = Vector(
        "git",
        "--no-optional-locks",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "core.fsmonitor=false",
        "-C",
        folder.toString
      ) ++ args
      val builder = ProcessBuilder(command*).redirectErrorStream(true)
      val env     = builder.environment()
      val kept    = GitScopes.Kept.flatMap(name => Option(env.get(name)).map(name -> _))
      env.clear()
      kept.foreach((name, value) => env.put(name, value): Unit)
      env.put("GIT_TERMINAL_PROMPT", "0"): Unit
      val process = builder.start()
      process.getOutputStream.close()
      val output = CompletableFuture.supplyAsync(() => String(process.getInputStream.readAllBytes(), UTF_8))
      if !process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
        process.destroyForcibly(): Unit
        Left(s"git ${args.headOption.getOrElse("")} did not end in $timeout")
      else
        val text = output.get(5, TimeUnit.SECONDS).trim
        if process.exitValue() == 0 then Right(text) else Left(s"git ${args.headOption.getOrElse("")}: $text")
    }.toEither.left.map(e => s"git did not run: $e").flatten

object GitScopes:

  /** The variables of the environment that a git command of the host gets. */
  private val Kept = Vector("PATH", "HOME", "LANG")
