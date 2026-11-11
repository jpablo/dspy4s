package dspy4s.harness.host

import dspy4s.harness.{PolicyReply, Secrets, Support}
import dspy4s.harness.Support.answer
import dspy4s.harness.guest.Scope
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

/** The laws of the handler of the scopes and of the release of a session (CODE_HARNESS.md, 7.9; CODE_HARNESS_DESIGN.md,
  * 8.7). They run the git of this machine in a new folder.
  */
class ScopesLaws extends munit.FunSuite:

  private val scopes = GitScopes()

  /** The output of `git args` in `dir`, or an error with the output. */
  private def git(dir: Path, args: String*): Either[String, String] =
    val process = ProcessBuilder(("git" +: "-C" +: dir.toString +: args)*).redirectErrorStream(true).start()
    val output  = String(process.getInputStream.readAllBytes(), UTF_8).trim
    if process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 then Right(output) else Left(output)

  private def gitOk(dir: Path, args: String*): String =
    git(dir, args*).fold(e => fail(s"git ${args.mkString(" ")}: $e"), identity)

  private def gitWorks: Boolean = Try(ProcessBuilder("git", "--version").start().waitFor() == 0).getOrElse(false)

  /** A new repository at `root/repo` with one commit on `main`. */
  private def repo(root: Path): Path =
    assume(gitWorks, "git does not run on this host")
    val dir = Files.createDirectories(root.resolve("repo"))
    gitOk(dir, "init", "-q", "-b", "main"): Unit
    gitOk(dir, "config", "user.email", "fake@example.test"): Unit
    gitOk(dir, "config", "user.name", "Fake Person"): Unit
    Files.writeString(dir.resolve("a.txt"), "a\n")
    gitOk(dir, "add", "a.txt"): Unit
    gitOk(dir, "commit", "-q", "-m", "first"): Unit
    dir

  private def worktree(root: Path, repo: Path, branch: String = "feature"): Scope.Worktree =
    Scope.Worktree(root.resolve("wt").toString, branch, repo.toString, "main")

  /** A commit of the file `name` in the folder `dir`. */
  private def commitIn(dir: Path, name: String): String =
    Files.writeString(dir.resolve(name), s"$name\n")
    gitOk(dir, "add", name): Unit
    gitOk(dir, "-c", "user.email=fake@example.test", "-c", "user.name=Fake", "commit", "-q", "-m", name): Unit
    gitOk(dir, "rev-parse", "HEAD")

  // ── The handler of the scopes ──

  test("Scopes: make of a Worktree adds it with a new branch from the base, and a folder that exists gives an error") {
    withRoot { root =>
      val r     = repo(root)
      val scope = worktree(root, r)
      assertEquals(scopes.make(scope), Right(()))
      assertEquals(gitOk(Path.of(scope.path), "branch", "--show-current"), "feature")
      assertEquals(gitOk(Path.of(scope.path), "rev-parse", "HEAD"), gitOk(r, "rev-parse", "main"))
      assert(scopes.make(scope).isLeft)
    }
  }

  test("Scopes: make of a Scratch makes a new folder, and each other scope or a folder that exists gives an error") {
    withRoot { root =>
      assertEquals(scopes.make(Scope.Scratch(root.resolve("scratch/s1").toString)), Right(()))
      assert(Files.isDirectory(root.resolve("scratch/s1")))
      assert(scopes.make(Scope.Scratch(root.resolve("scratch/s1").toString)).isLeft)
      assert(scopes.make(Scope.Directory(root.toString)).isLeft)
      assert(scopes.make(Scope.Home(root.toString)).isLeft)
      assert(scopes.make(Scope.Fork(root.resolve("fork").toString, root.toString)).isLeft)
    }
  }

  test("Scopes: a branch or a base that starts with - or is not a ref of git gives an error, and runs nothing") {
    withRoot { root =>
      val r = repo(root)
      Vector(
        worktree(root, r, branch = "-x"),
        worktree(root, r, branch = "a..b"),
        Scope.Worktree(root.resolve("wt").toString, "feature", r.toString, "--upload-pack=touch /tmp/fake-pwned")
      ).foreach(scope => assert(scopes.make(scope).isLeft, scope.toString))
      assert(!Files.exists(root.resolve("wt")))
    }
  }

  test("Scopes: a hook or an fsmonitor that a session planted in a repository runs neither on make nor on snapshot") {
    withRoot { root =>
      val r      = repo(root)
      val marker = root.resolve("pwned")
      val script = Files.writeString(root.resolve("hook.sh"), s"#!/bin/sh\ntouch $marker\n")
      Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x")): Unit
      Files.copy(script, r.resolve(".git/hooks/post-checkout")): Unit
      gitOk(r, "config", "core.fsmonitor", script.toString): Unit
      scopes.make(worktree(root, r)).left.foreach(fail(_))
      scopes.snapshot(Scope.Directory(r.toString)): Unit
      scopes.stateOf(worktree(root, r)): Unit
      assert(!Files.exists(marker), "a planted script ran")
    }
  }

  test(
    "Scopes: snapshot gives the head and the clean flag, an ignored file counts as a change, and no git gives none"
  ) {
    withRoot { root =>
      val r = repo(root)
      assertEquals(
        scopes.snapshot(Scope.Directory(r.toString)),
        Some(ScopeSnapshot(gitOk(r, "rev-parse", "HEAD"), true))
      )
      Files.writeString(r.resolve(".gitignore"), "*.env\n")
      commitIn(r, ".gitignore"): Unit
      Files.writeString(r.resolve("secret.env"), "FAKE=1\n")
      assertEquals(scopes.snapshot(Scope.Directory(r.toString)).map(_.clean), Some(false))
      val plain = Files.createDirectories(root.resolve("plain"))
      assertEquals(scopes.snapshot(Scope.Directory(plain.toString)), None)
    }
  }

  test("Scopes: stateOf of a Worktree gives the head, the tip of its branch, and whether the tip is in the base") {
    withRoot { root =>
      val r     = repo(root)
      val scope = worktree(root, r)
      scopes.make(scope).left.foreach(fail(_))
      assertEquals(scopes.stateOf(scope).merged, true)
      val wt   = Path.of(scope.path)
      val head = commitIn(wt, "b.txt")
      val s    = scopes.stateOf(scope)
      assertEquals((s.merged, s.clean, s.head, s.tip), (false, true, Some(head), Some(head)))
      assertEquals((s.owned, s.shared), (false, false))
      gitOk(wt, "checkout", "-q", "--detach", "HEAD~1"): Unit
      assertEquals(scopes.stateOf(scope).tip, Some(head))
    }
  }

  test(
    "Scopes: release of RemoveWorktree, KeepRef and DeleteBranch changes the repository, and the ref keeps the tip"
  ) {
    withRoot { root =>
      val r     = repo(root)
      val scope = worktree(root, r)
      scopes.make(scope).left.foreach(fail(_))
      val tip = commitIn(Path.of(scope.path), "b.txt")
      assertEquals(
        scopes.release(ReleaseItem.RemoveWorktree(r.toString, scope.path), confirmed = false),
        ReleaseResult.Done
      )
      assert(!Files.exists(Path.of(scope.path)))
      assertEquals(
        scopes.release(ReleaseItem.KeepRef(r.toString, "refs/archive/s1", "feature", tip), confirmed = false),
        ReleaseResult.Done
      )
      assertEquals(
        scopes.release(ReleaseItem.DeleteBranch(r.toString, "feature", tip), confirmed = false),
        ReleaseResult.Done
      )
      assert(git(r, "rev-parse", "--verify", "refs/heads/feature").isLeft)
      assertEquals(gitOk(r, "rev-parse", "refs/archive/s1"), tip)
    }
  }

  test("Scopes: only a confirmed step removes a worktree with changes, and a branch that moved stays") {
    withRoot { root =>
      val r     = repo(root)
      val scope = worktree(root, r)
      scopes.make(scope).left.foreach(fail(_))
      val wt  = Path.of(scope.path)
      val old = gitOk(wt, "rev-parse", "HEAD")
      Files.writeString(wt.resolve("draft.txt"), "a change\n")
      val remove = ReleaseItem.RemoveWorktree(r.toString, scope.path)
      scopes.release(remove, confirmed = false) match
        case ReleaseResult.Failed(_) => ()
        case other                   => fail(other.toString)
      assert(Files.exists(wt.resolve("draft.txt")))
      // The branch moves after the plan: a delete with the old commit leaves it.
      val moved = commitIn(wt, "c.txt")
      assertEquals(scopes.release(remove, confirmed = true), ReleaseResult.Done)
      scopes.release(ReleaseItem.DeleteBranch(r.toString, "feature", old), confirmed = true) match
        case ReleaseResult.Failed(_) => ()
        case other                   => fail(other.toString)
      assertEquals(gitOk(r, "rev-parse", "refs/heads/feature"), moved)
    }
  }

  test("Scopes: release of ArchiveDirectory moves the folder, and a step that fails gives Failed and does not throw") {
    withRoot { root =>
      val from = Files.createDirectories(root.resolve("scratch/s1"))
      Files.writeString(from.resolve("note.txt"), "kept")
      val to = root.resolve("archive/s1")
      assertEquals(
        scopes.release(ReleaseItem.ArchiveDirectory(from.toString, to.toString), confirmed = false),
        ReleaseResult.Done
      )
      assert(!Files.exists(from) && Files.readString(to.resolve("note.txt")) == "kept")
      scopes.release(
        ReleaseItem.DeleteBranch(root.resolve("no-repo").toString, "feature", "a1"),
        confirmed = true
      ) match
        case ReleaseResult.Failed(_) => ()
        case other                   => fail(other.toString)
      assertEquals(scopes.release(ReleaseItem.Keep("x", "a reason"), confirmed = false), ReleaseResult.Done)
    }
  }

  // ── The scopes of a host ──

  /** A host at `root/host` whose folder for work is `root/work`, with sessions that reply `done`. */
  private def withScriptHost[A](root: Path)(body: (LocalHost, Path) => A): A =
    val work    = root.resolve("work")
    val runtime = ScriptRuntime(_ => Support.Script(Vector.fill(4)(answer("done"))))
    val host    = LocalHost.startWith(root.resolve("host"), _ => runtime, Secrets.none, work = Some(work))
      .fold(e => fail(e.toString), identity)
    try body(host, work)
    finally host.close()

  test("Host scopes: make of an empty Scratch or Worktree makes <work>/<id>, and the rules of start refuse the rest") {
    withRoot { root =>
      val r = repo(root)
      withScriptHost(root) { (host, work) =>
        assertEquals(host.make(Scope.Scratch(""), id(5)), Right(Scope.Scratch(work.resolve(id(5).value).toString)))
        assert(Files.isDirectory(work.resolve(id(5).value)))
        val made = host.make(Scope.Worktree("", "feature", r.toString, "main"), id(6))
        assertEquals(made, Right(Scope.Worktree(work.resolve(id(6).value).toString, "feature", r.toString, "main")))
        assertEquals(gitOk(work.resolve(id(6).value), "branch", "--show-current"), "feature")
        assertEquals(host.make(Scope.Directory(root.toString), id(7)), Right(Scope.Directory(root.toString)))
        Vector(
          Scope.Scratch(root.resolve("mine").toString),
          Scope.Worktree(root.resolve("mine").toString, "f2", r.toString, "main"),
          Scope.Worktree("", "main", r.toString, "main"),
          Scope.Home(root.toString),
          Scope.Fork(root.resolve("fork").toString, root.toString),
          Scope.Directory("relative/folder"),
          Scope.Directory(root.resolve("missing").toString)
        ).foreach(scope => assert(host.make(scope, id(8)).isLeft, scope.toString))
      }
    }
  }

  test("Host scopes: make refuses a worktree of a repository inside the scope of a session that has not ended") {
    withRoot { root =>
      val r = repo(root)
      withScriptHost(root) { (host, _) =>
        opened(host, id(1), Some(config().copy(scope = Scope.Directory(root.toString)))): Unit
        assert(host.make(Scope.Worktree("", "feature", r.toString, "main"), id(2)).isLeft)
      }
    }
  }

  test("Host scopes: after a turn in a git folder, the journal has ScopeRecorded and the row of list has the head") {
    withRoot { root =>
      val r = repo(root)
      withScriptHost(root) { (host, _) =>
        val handle = opened(host, id(1), Some(config().copy(scope = Scope.Directory(r.toString))))
        handle.send(message("m1")).left.foreach(e => fail(e.toString))
        val head = gitOk(r, "rev-parse", "HEAD")
        assert(eventually()(entries(handle).contains(Entry.ScopeRecorded(head, true))), entries(handle).toString)
        assert(
          eventually()(host.list.find(_.session == id(1)).exists(_.ledger.head.contains(head))),
          host.list.toString
        )
      }
    }
  }

  // ── The release of a session in a host ──

  /** A host with a session in a worktree that the host made, the scope, and the repository. */
  private def withWorktreeSession(check: (LocalHost, SessionHandle, Scope.Worktree, Path) => Unit): Unit =
    withRoot { root =>
      val r = repo(root)
      withScriptHost(root) { (host, _) =>
        val scope = host.make(Scope.Worktree("", "feature", r.toString, "main"), id(1)) match
          case Right(made: Scope.Worktree) => made
          case other                       => fail(other.toString)
        check(host, opened(host, id(1), Some(config().copy(scope = scope))), scope, r)
      }
    }

  private def isReleasing(entry: Entry): Boolean =
    entry match
      case Entry.Releasing(_) => true
      case _                  => false

  test("Host release: Releasing first, then one Released for each step and Ended, and the row holds the steps") {
    withWorktreeSession { (host, handle, scope, _) =>
      val (state, plan) = host.planOf(id(1), SessionOutcome.Merged).fold(e => fail(e.toString), identity)
      assertEquals((state.merged, state.owned, state.shared), (true, true, false))
      assertEquals(
        plan,
        Vector(
          ReleaseItem.RemoveWorktree(scope.repo, scope.path),
          ReleaseItem.DeleteBranch(scope.repo, scope.branch, state.tip.getOrElse(fail("no tip")))
        )
      )
      val steps =
        host.release(id(1), SessionOutcome.Merged, "fake merge", _ => false).fold(e => fail(e.toString), identity)
      assertEquals(steps.map(_.item), plan)
      assert(steps.forall(_.result == ReleaseResult.Done), steps.toString)
      val tail = entries(handle).dropWhile(e => !isReleasing(e))
      assertEquals(tail.headOption, Some(Entry.Releasing(plan)))
      assertEquals(tail.collect { case Entry.Released(step) => step }, steps)
      assertEquals(tail.lastOption, Some(Entry.Ended(SessionOutcome.Merged, "fake merge")))
      assert(!Files.exists(Path.of(scope.path)))
      assert(eventually()(host.list.find(_.session == id(1)).exists(_.ledger.released == steps)), host.list.toString)
    }
  }

  test("Host release: a folder that another open session uses is shared, so it stays") {
    withWorktreeSession { (host, _, scope, _) =>
      opened(host, id(2), Some(config().copy(scope = scope))): Unit
      val (state, plan) = host.planOf(id(1), SessionOutcome.Merged).fold(e => fail(e.toString), identity)
      assert(state.shared)
      assert(
        plan.forall {
          case ReleaseItem.Keep(_, _) => true
          case _                      => false
        },
        plan.toString
      )
    }
  }

  test("Host release: an irreversible item runs only when confirm gives true, else it gets Skipped") {
    withWorktreeSession { (host, _, scope, _) =>
      val delete              = ReleaseItem.DeleteBranch(scope.repo, scope.branch, "0000000")
      val remove              = ReleaseItem.RemoveWorktree(scope.repo, scope.path)
      val plans: ReleasePlans = (_, _, _, _) => Vector(remove, delete)
      Files.writeString(Path.of(scope.path).resolve("c.txt"), "a change\n")
      val asked = scala.collection.mutable.ArrayBuffer.empty[ReleaseItem]
      val steps = host
        .release(
          id(1),
          SessionOutcome.Abandoned("fake"),
          "fake",
          item =>
            asked += item; false
          ,
          plans
        )
        .fold(e => fail(e.toString), identity)
      // The worktree has a change, so its removal is irreversible. The branch is merged, so its delete is not.
      assertEquals(asked.toVector, Vector(remove))
      assert(
        steps.headOption.exists(_.result match
          case ReleaseResult.Skipped(_) => true;
          case _                        => false),
        steps.toString
      )
      assert(Files.exists(Path.of(scope.path).resolve("c.txt")))
    }
  }

  test("Host release: after a KeepRef that failed, the DeleteBranch of its branch is Skipped with no confirmation") {
    withWorktreeSession { (host, _, scope, _) =>
      val tip                 = commitIn(Path.of(scope.path), "b.txt")
      val remove              = ReleaseItem.RemoveWorktree(scope.repo, scope.path)
      val keep                = ReleaseItem.KeepRef(scope.repo, "refs/archive/not a ref", scope.branch, tip)
      val delete              = ReleaseItem.DeleteBranch(scope.repo, scope.branch, tip)
      val plans: ReleasePlans = (_, _, _, _) => Vector(remove, keep, delete)
      val steps               = host.release(id(1), SessionOutcome.Abandoned("fake"), "fake", _ => false, plans)
        .fold(e => fail(e.toString), identity)
      steps.map(_.result) match
        case Vector(ReleaseResult.Done, ReleaseResult.Failed(_), ReleaseResult.Skipped(_)) => ()
        case other                                                                         => fail(other.toString)
      assertEquals(gitOk(Path.of(scope.repo), "rev-parse", s"refs/heads/${scope.branch}"), tip)
    }
  }

  test("Host release: a release that a crash cut goes on with the rest of the plan of its Releasing") {
    withWorktreeSession { (host, handle, _, _) =>
      val (_, plan) = host.planOf(id(1), SessionOutcome.Merged).fold(e => fail(e.toString), identity)
      val first     = ReleaseStep(ReleaseItem.Keep("fake", "done before the crash"), ReleaseResult.Done)
      val cut       = ReleaseItem.Keep("fake", "done before the crash") +: plan
      host.runAs(id(1), Command.Commit(Vector(Entry.Releasing(cut), Entry.Released(first))), Source.Harness)
        .left.foreach(e => fail(e.toString))
      val steps = host.release(id(1), SessionOutcome.Merged, "after the crash", _ => false)
        .fold(e => fail(e.toString), identity)
      assertEquals(steps.map(_.item), plan)
      assertEquals(entries(handle).count(isReleasing), 1)
    }
  }

  test("Host release: the controller gives Refused, and a release during a turn gives TurnOpen and runs no step") {
    withRoot { root =>
      val r = repo(root)
      withScriptHost(root) { (host, _) =>
        opened(host, Controller.id, Some(config().copy(scope = Scope.Home(root.toString)))): Unit
        host.release(Controller.id, SessionOutcome.Parked, "fake", _ => true) match
          case Left(HostError.Refused(_)) => ()
          case other                      => fail(other.toString)
      }
      val hold    = CountDownLatch(1)
      val runtime = ScriptRuntime(_ =>
        Support.Script(
          Vector(Right(PolicyReply("", "", Some("work()"))), answer("done")),
          during = (_, _) => hold.await(30, TimeUnit.SECONDS): Unit
        )
      )
      withHost(root.resolve("host2"), runtime) { host =>
        val scope = worktree(root, r)
        scopes.make(scope).left.foreach(fail(_))
        val handle = opened(host, id(1), Some(config().copy(scope = scope)))
        handle.send(message("m1")).left.foreach(e => fail(e.toString))
        assert(eventually()(inCell(handle)))
        assertEquals(
          host.release(id(1), SessionOutcome.Merged, "fake", _ => true),
          Left(HostError.Rejected(Rejection.TurnOpen))
        )
        hold.countDown()
        assert(Files.exists(Path.of(scope.path)))
        assert(!entries(handle).exists(isReleasing))
      }
    }
  }
