package dspy4s.harness.host

import dspy4s.harness.guest.Scope
import dspy4s.harness.kernel.{ReleaseItem, ReleaseResult, ScopeState}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

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

  /** Makes the folder of `scope`, which must not exist: for a `Worktree`, a local clone of its base with a new branch,
    * and the same branch in the repository (choice 19, 5.4); for a `Scratch`, a new folder. Each other scope gives an
    * error: the host makes only these folders.
    */
  def make(scope: Scope): Either[String, Unit]

  /** Puts the tip of the branch of a `Worktree` clone into its repository, with a compare-and-swap on the last tip that
    * the host put there (choice 19, 5.4). It gives true when the branch of the repository moved, and false when the tip
    * did not move or the scope is not a clone of the host. It gives an error when the person moved the branch, when the
    * branch is checked out in the repository, or when git fails. The branch of the repository then stays.
    */
  def publish(scope: Scope): Either[String, Boolean]

  /** The git head of the folder of `scope` and whether it has no uncommitted change, or none for a folder with no git,
    * with no commit, or with a path that is not absolute.
    */
  def snapshot(scope: Scope): Option[ScopeSnapshot]

  /** The state of `scope` for a release plan: the head, the tip of the branch of a `Worktree` in its repository after a
    * `publish`, whether the scope is clean with its ignored files, and whether the tip is in the base. `owned` and
    * `shared` are false: the host knows its folders and its sessions, so it sets them.
    */
  def stateOf(scope: Scope): ScopeState

  /** Runs one step of a release plan. A step that fails gives `Failed` with the error, and it never throws. Only a step
    * that the person `confirmed` can force: the removal of a clone with uncommitted changes. With no confirmation, a
    * clone that has changes since the plan stays, and its step fails. A clone whose commits are not in its repository
    * stays also with a confirmation, because the removal would lose them.
    */
  def release(item: ReleaseItem, confirmed: Boolean): ReleaseResult

/** `Scopes` with the git of the machine (8.7). Each command runs with `git -C <folder>`, with no shell, and with the
  * time limit `timeout`. A command that does not end in time gives an error, and its process stops. An interrupt of the
  * thread during a command also stops the process and gives an error, and the interrupt stays set.
  *
  * A `Worktree` scope is a local clone (choice 19, 5.4): `git clone --shared --single-branch`, so its `.git` is in the
  * scope and the rules of the sandbox apply to it. A linked worktree kept its index, its objects and its branch in the
  * repository of the person, outside the scope, so a session could not commit. The clone reads the objects of the
  * repository through `objects/info/alternates`, so it copies no object. The clone holds two values of the host in its
  * configuration, which is a control path, so a session cannot change them: `dspy4s.branch`, the branch of the scope,
  * and `dspy4s.published`, the last tip that the host put into the repository.
  *
  * A new clone does not copy the configuration of the repository, so a smudge filter of that configuration does not run
  * when the clone checks out the files. The host still refuses a repository inside the scope of an open session
  * (`LocalHost.make`).
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
          folder <- newFolder(path)
          _      <- branchName(branch)
          _      <- branchName(base)
          _      <- git(Path.of(repo), "rev-parse", "--verify", "-q", s"refs/heads/$branch").swap
                 .left.map(_ => s"the branch $branch exists in $repo")
          _ <- git(
                 Path.of(repo),
                 "clone",
                 "-q",
                 "--shared",
                 "--single-branch",
                 "--no-tags",
                 "--branch",
                 base,
                 "--",
                 repo,
                 path
               )
          // A step that fails after the clone removes it, so a second make can start again.
          _ <- branchOfClone(folder, repo, branch).left.map { error =>
                 removeTree(folder)
                 error
               }
        yield ()
      case other => Left(s"the host makes only a Scratch or a Worktree, not ${other.productPrefix}")

  def publish(scope: Scope): Either[String, Boolean] =
    scope match
      case Scope.Worktree(path, branch, repo, _) => publishClone(Path.of(path), repo, branch)
      case _                                     => Right(false)

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
      case Scope.Worktree(path, branch, repo, base) if branchName(branch).isRight && branchName(base).isRight =>
        // The plan reads the branch of the repository, so the commits of the clone go there first.
        publishClone(Path.of(path), repo, branch): Unit
        val folder = Path.of(repo)
        val tip    = git(folder, "rev-parse", "--verify", "-q", s"refs/heads/$branch").toOption
        (tip, tip.exists(t => git(folder, "merge-base", "--is-ancestor", t, s"refs/heads/$base").isRight))
      case _ => (None, false)
    ScopeState(scope, now.map(_.head), tip, now.forall(_.clean), merged, owned = false, shared = false)

  def release(item: ReleaseItem, confirmed: Boolean): ReleaseResult =
    val done = Try {
      item match
        case ReleaseItem.RemoveWorktree(repo, path) =>
          if JFiles.isRegularFile(Path.of(path).resolve(".git")) then
            // A linked worktree of a host before choice 19: its commits are in the repository.
            val force = if confirmed then Vector("--force") else Vector.empty
            git(Path.of(repo), (Vector("worktree", "remove") ++ force ++ Vector("--", path))*).map(_ => ())
          else removeClone(repo, Path.of(path), confirmed)
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

  /** The key of the branch of a clone, and the key of the last tip that the host put into the repository. */
  private val BranchKey    = "dspy4s.branch"
  private val PublishedKey = "dspy4s.published"

  /** Makes the branch `branch` in the new clone `folder` and in its repository `repo`, at the base that the clone
    * checked out, and keeps the branch and the tip in the configuration of the clone.
    */
  private def branchOfClone(folder: Path, repo: String, branch: String): Either[String, Unit] =
    for
      _   <- git(folder, "switch", "-q", "-c", branch)
      tip <- git(folder, "rev-parse", "--verify", "-q", "HEAD")
      _   <- git(folder, "config", BranchKey, branch)
      _   <- push(folder, repo, branch, tip, expected = "")
      _   <- git(folder, "config", PublishedKey, tip)
    yield ()

  /** The `publish` of the clone `folder` of `repo` with the branch `branch`. A folder with no `.git` folder, or with no
    * tip of the host, is not a clone of the host: a linked worktree of a host before choice 19 has its branch in the
    * repository. So it gives false.
    */
  private def publishClone(folder: Path, repo: String, branch: String): Either[String, Boolean] =
    val last = Option.when(JFiles.isDirectory(folder.resolve(".git")))(()).flatMap(_ =>
      git(folder, "config", "--get", PublishedKey).toOption
    )
    last match
      case None       => Right(false)
      case Some(last) =>
        for
          _   <- branchName(branch)
          tip <- git(folder, "rev-parse", "--verify", "-q", s"refs/heads/$branch")
                   .left.map(_ => s"the clone $folder has no branch $branch")
          moved <-
            if tip == last then Right(false)
            else
              push(folder, repo, branch, tip, expected = last).flatMap(_ => git(folder, "config", PublishedKey, tip))
                .map(_ => true)
        yield moved

  /** Puts `tip` into `refs/heads/<branch>` of `repo` from the clone `folder`, only when that ref is `expected` now (an
    * empty `expected` means that the ref must not exist): `git push --force-with-lease`, one compare-and-swap. git
    * refuses to move a branch that is checked out in the repository, because that would change the files of the person.
    *
    * A push runs no hook of the clone (`--no-verify`). git clears the `-c` values for the receiving side, so a plain
    * push runs `pre-receive`, `update`, `reference-transaction`, `post-receive` and `post-update` of the repository (a
    * probe on 2026-11-14). So the push names its own receive-pack command, with the same `-c` values.
    */
  private def push(folder: Path, repo: String, branch: String, tip: String, expected: String): Either[String, Unit] =
    val ref     = s"refs/heads/$branch"
    val receive = "--receive-pack=git -c core.hooksPath=/dev/null -c core.fsmonitor=false receive-pack"
    git(folder, "push", "-q", "--no-verify", receive, s"--force-with-lease=$ref:$expected", "--", repo, s"$tip:$ref")
      .left.map(error => s"git did not move $ref of $repo to $tip: $error").map(_ => ())

  /** Removes the clone `folder` of `repo` after a `publish`. It stays when a branch or the head of the clone is in no
    * ref of the repository, because the removal would lose those commits, also with a confirmation. With no
    * confirmation, it also stays when it has a change, with its ignored files, as a worktree stays with
    * `git worktree remove`.
    */
  private def removeClone(repo: String, folder: Path, confirmed: Boolean): Either[String, Unit] =
    for
      branch <- git(folder, "config", "--get", BranchKey).left.map(_ => s"$folder is not a clone of the host")
      // The publish can fail, for example after the person merged the branch and removed it. The check of the lost
      // commits decides, so such a clone does not stay.
      _    <- Right(publishClone(folder, repo, branch): Unit)
      tips <- git(folder, "for-each-ref", "--format=%(objectname)", "refs/heads").map(_.linesIterator.toVector)
      head <- git(folder, "rev-parse", "--verify", "-q", "HEAD")
      lost  = (tips :+ head).distinct.filterNot(tip =>
        git(Path.of(repo), "for-each-ref", "--count=1", "--contains", tip).exists(_.nonEmpty)
      )
      _      <- Either.cond(lost.isEmpty, (), s"the repository $repo has no ref with the commits ${lost.mkString(", ")}")
      status <- git(folder, "status", "--porcelain", "--ignored", "--untracked-files=all")
      _      <- Either.cond(confirmed || status.isEmpty, (), s"the clone $folder has changes: $status")
    yield removeTree(folder)

  /** Removes the folder `path` and each file in it. A symbolic link goes, and not its target. */
  private def removeTree(path: Path): Unit =
    if JFiles.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
      val paths = JFiles.walk(path)
      try paths.iterator.asScala.toVector.reverse.foreach(JFiles.deleteIfExists(_): Unit)
      finally paths.close()

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
    *
    * An interrupt of the thread asks it to stop. A thread with an interrupt starts no command. An interrupt during the
    * command stops the command (`GitScopes.stop`) and gives an error. The interrupt stays set for the caller. `Try`
    * does not catch an `InterruptedException`, so this function catches it. Else the command stays alive, and the
    * exception ends the thread with a stack trace on the standard error.
    */
  private def git(folder: Path, args: String*): Either[String, String] =
    val name = s"git ${args.headOption.getOrElse("")}"
    if Thread.currentThread().isInterrupted then Left(s"$name did not start, because the thread has an interrupt")
    else
      Try(GitScopes.start(folder, args)).toEither.left.map(e => s"git did not run: $e").flatMap { process =>
        val (result, interrupted) =
          try (outputOf(process, name), false)
          catch
            case _: InterruptedException => (Left(s"$name stopped, because the thread got an interrupt"), true)
            case NonFatal(e)             => (Left(s"git did not run: $e"), false)
        // A command that did not end stops here: after the time limit, after an interrupt, and after an error.
        val again = process.isAlive && GitScopes.stop(process)
        if interrupted || again then Thread.currentThread().interrupt()
        result
      }

  /** The output of the git command `process` with the name `name`, or an error. It waits at most `timeout` for the end
    * of the command, and it does not stop a command that does not end.
    */
  private def outputOf(process: Process, name: String): Either[String, String] =
    process.getOutputStream.close()
    val output = CompletableFuture.supplyAsync(() => String(process.getInputStream.readAllBytes(), UTF_8))
    if !process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then Left(s"$name did not end in $timeout")
    else
      val text = output.get(5, TimeUnit.SECONDS).trim
      if process.exitValue() == 0 then Right(text) else Left(s"$name: $text")

object GitScopes:

  /** The variables of the environment that a git command of the host gets. */
  private val Kept = Vector("PATH", "HOME", "LANG")

  /** The time between TERM and KILL when a git command stops. */
  private val Grace = 1.second

  /** Starts `git args` in `folder` with no hooks, no fsmonitor, no optional lock, and only the variables `Kept` of the
    * environment.
    */
  private def start(folder: Path, args: Seq[String]): Process =
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
    val kept    = Kept.flatMap(name => Option(env.get(name)).map(name -> _))
    env.clear()
    kept.foreach((name, value) => env.put(name, value): Unit)
    env.put("GIT_TERMINAL_PROMPT", "0"): Unit
    builder.start()

  /** Stops the git command `process` and each process that it started, for example a filter: TERM, at most `Grace` for
    * the end of the command, then KILL for each process that still runs. git removes its lock files on TERM, so the
    * repository stays usable. It gives true when an interrupt came during the wait: the wait then ends, and the caller
    * sets the interrupt again.
    */
  private def stop(process: Process): Boolean =
    // A child of a command that ended is not a descendant of it any more, so the list comes before the signals.
    val seen = process.descendants().iterator().asScala.toVector :+ process.toHandle
    seen.foreach(_.destroy(): Unit)
    val interrupted =
      try
        process.waitFor(Grace.toMillis, TimeUnit.MILLISECONDS): Unit
        false
      catch case _: InterruptedException => true
    (seen ++ process.descendants().iterator().asScala).filter(_.isAlive).foreach(_.destroyForcibly(): Unit)
    interrupted
