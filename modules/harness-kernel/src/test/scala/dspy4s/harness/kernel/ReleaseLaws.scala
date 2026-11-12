package dspy4s.harness.kernel

import dspy4s.harness.guest.Scope
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

/** The laws of the release plans (CODE_HARNESS.md, 7.9; CODE_HARNESS_DESIGN.md, 8.7). A plan is pure, so the laws are
  * properties over each kind of scope, each state of it, and each outcome.
  */
class ReleaseLaws extends ScalaCheckSuite:

  private val ref      = "refs/archive/s1"
  private val archive  = "/root/archive/s1"
  private val worktree = Scope.Worktree("/work/s1", "feature", "/home/p/repo", "main")

  private val scopes: Gen[Scope] = Gen.oneOf(
    Gen.const(Scope.Home("/home/p")),
    Gen.const(Scope.Directory("/home/p/work")),
    Gen.const(worktree),
    Gen.const(Scope.Scratch("/work/s1")),
    Gen.const(Scope.Fork("/root/forks/f1", "/home/p/work"))
  )

  private def isWorktree(scope: Scope): Boolean =
    scope match
      case Scope.Worktree(_, _, _, _) => true
      case _                          => false

  private val states: Gen[ScopeState] =
    for
      scope  <- scopes
      head   <- Gen.option(Gen.oneOf("a1b2c3", "d4e5f6"))
      tip    <- Gen.option(Gen.oneOf("a1b2c3", "0f0f0f"))
      clean  <- Gen.oneOf(true, false)
      merged <- Gen.oneOf(true, false)
      owned  <- Gen.oneOf(true, false)
      shared <- Gen.oneOf(true, false)
    yield
      val git = isWorktree(scope)
      ScopeState(scope, head, tip.filter(_ => git), clean, merged && git, owned, shared)

  private val outcomes: Gen[SessionOutcome] = Gen.oneOf(
    Gen.const(SessionOutcome.Merged),
    Gen.const(SessionOutcome.Parked),
    Gen.const(SessionOutcome.Abandoned("no longer needed")),
    Gen.const(SessionOutcome.Replaced(SessionId("s2")))
  )

  private def plan(state: ScopeState, outcome: SessionOutcome): Vector[ReleaseItem] =
    releasePlan(state, outcome, ref, archive)

  private def isKeep(item: ReleaseItem): Boolean =
    item match
      case ReleaseItem.Keep(_, _) => true
      case _                      => false

  property("Release: a worktree that is not clean never gets RemoveWorktree") {
    forAll(states, outcomes) { (state, outcome) =>
      state.clean || !plan(state, outcome).exists {
        case ReleaseItem.RemoveWorktree(_, _) => true
        case _                                => false
      }
    }
  }

  property("Release: only a folder that the host made and that no other session uses changes (8.7)") {
    forAll(states, outcomes) { (state, outcome) =>
      (state.owned && !state.shared) || plan(state, outcome).forall(isKeep)
    }
  }

  property("Release: a DeleteBranch names the tip, and on a branch that is not merged it has a KeepRef of both first") {
    forAll(states, outcomes) { (state, outcome) =>
      val items = plan(state, outcome)
      items.zipWithIndex.forall {
        case (ReleaseItem.DeleteBranch(repo, name, commit), i) => state.tip.contains(commit) &&
          (state.merged || items.take(i).exists {
            case ReleaseItem.KeepRef(r, _, branch, kept) => r == repo && branch == name && kept == commit
            case _                                       => false
          })
        case _ => true
      }
    }
  }

  property("Release: a DeleteBranch comes only after the RemoveWorktree of the scope (8.7)") {
    forAll(states, outcomes) { (state, outcome) =>
      val items = plan(state, outcome)
      items.zipWithIndex.forall {
        case (ReleaseItem.DeleteBranch(_, _, _), i) => items.take(i).exists {
            case ReleaseItem.RemoveWorktree(_, _) => true
            case _                                => false
          }
        case _ => true
      }
    }
  }

  property("Release: Parked keeps each resource, and a Home, a Directory or a Fork scope keeps its folder") {
    forAll(states, outcomes) { (state, outcome) =>
      val kept = state.scope match
        case Scope.Home(_) | Scope.Directory(_) | Scope.Fork(_, _) => true
        case _                                                     => outcome == SessionOutcome.Parked
      Prop(!kept || plan(state, outcome).forall(isKeep))
    }
  }

  property("Release: each item of a default plan is reversible") {
    forAll(states, outcomes)((state, outcome) => irreversible(plan(state, outcome), state).isEmpty)
  }

  property(
    "Release: a plan names only the folder, the repository and the branch of its scope, the ref and the archive"
  ) {
    forAll(states, outcomes) { (state, outcome) =>
      val (folder, repo, branch) = state.scope match
        case Scope.Worktree(path, name, r, _) => (path, Some(r), Some(name))
        case Scope.Home(p)                    => (p, None, None)
        case Scope.Directory(p)               => (p, None, None)
        case Scope.Scratch(p)                 => (p, None, None)
        case Scope.Fork(p, _)                 => (p, None, None)
      plan(state, outcome).forall {
        case ReleaseItem.RemoveWorktree(r, path)    => repo.contains(r) && path == folder
        case ReleaseItem.DeleteBranch(r, name, _)   => repo.contains(r) && branch.contains(name)
        case ReleaseItem.KeepRef(r, name, b, _)     => repo.contains(r) && name == ref && branch.contains(b)
        case ReleaseItem.ArchiveDirectory(path, to) => path == folder && to == archive
        case ReleaseItem.Keep(_, _)                 => true
      }
    }
  }

  test("Release: the rows of the table of 7.9 for an owned worktree and an owned scratch directory") {
    val clean =
      ScopeState(worktree, Some("a1b2c3"), Some("a1b2c3"), clean = true, merged = true, owned = true, shared = false)
    assertEquals(
      plan(clean, SessionOutcome.Merged),
      Vector(
        ReleaseItem.RemoveWorktree("/home/p/repo", "/work/s1"),
        ReleaseItem.DeleteBranch("/home/p/repo", "feature", "a1b2c3")
      )
    )
    val open = clean.copy(merged = false)
    assertEquals(
      plan(open, SessionOutcome.Abandoned("no longer needed")),
      Vector(
        ReleaseItem.RemoveWorktree("/home/p/repo", "/work/s1"),
        ReleaseItem.KeepRef("/home/p/repo", ref, "feature", "a1b2c3"),
        ReleaseItem.DeleteBranch("/home/p/repo", "feature", "a1b2c3")
      )
    )
    val replaced = plan(open, SessionOutcome.Replaced(SessionId("s2")))
    assertEquals(replaced.headOption, Some(ReleaseItem.RemoveWorktree("/home/p/repo", "/work/s1")))
    assert(replaced.drop(1).forall(isKeep), replaced.toString)
    val scratch =
      ScopeState(Scope.Scratch("/work/s1"), None, None, clean = true, merged = false, owned = true, shared = false)
    assertEquals(
      plan(scratch, SessionOutcome.Abandoned("no longer needed")),
      Vector(ReleaseItem.ArchiveDirectory("/work/s1", archive))
    )
    // A worktree with changes keeps its branch too, because git refuses to delete the branch of a worktree.
    assert(plan(clean.copy(clean = false), SessionOutcome.Merged).forall(isKeep))
  }

  test("Release: irreversible needs a KeepRef of the same branch and commit, and finds a removal it cannot undo") {
    val dirty =
      ScopeState(worktree, Some("a1b2c3"), Some("a1b2c3"), clean = false, merged = false, owned = true, shared = false)
    val remove = ReleaseItem.RemoveWorktree("/home/p/repo", "/work/s1")
    val delete = ReleaseItem.DeleteBranch("/home/p/repo", "feature", "a1b2c3")
    val keep   = ReleaseItem.KeepRef("/home/p/repo", ref, "feature", "a1b2c3")
    assertEquals(irreversible(Vector(remove, delete), dirty), Vector(remove, delete))
    assertEquals(irreversible(Vector(keep, delete), dirty), Vector.empty)
    assertEquals(
      irreversible(Vector(ReleaseItem.KeepRef("/home/p/repo", ref, "other", "a1b2c3"), delete), dirty),
      Vector(delete)
    )
    assertEquals(
      irreversible(Vector(ReleaseItem.KeepRef("/home/p/repo", ref, "feature", "0f0f0f"), delete), dirty),
      Vector(delete)
    )
    assertEquals(irreversible(Vector(delete), dirty.copy(merged = true)), Vector.empty)
    val clean = dirty.copy(clean = true)
    assertEquals(irreversible(Vector(remove), clean), Vector.empty)
    assertEquals(irreversible(Vector(remove), clean.copy(owned = false)), Vector(remove))
    assertEquals(irreversible(Vector(remove), clean.copy(shared = true)), Vector(remove))
  }
