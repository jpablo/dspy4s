package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import Fixtures.*
import Schedules.*

/** The laws Grant, Pending call and Guard of 4.7 (5.5). */
class ApprovalLaws extends ScalaCheckSuite:

  private val exec  = GuardRule("exec", ArgMatch.AnyArgs)
  private val asks  = config(guard = Guard(Vector(VerdictRule(exec, Verdict.Ask))))
  private val asked = GuardResult.NeedsPerson(GuardRule("exec", ArgMatch.Exact(args)))

  private val verdicts: Gen[Verdict] = Gen.oneOf(Verdict.values.toSeq)

  /** Run < NeedsPerson < Refused: a stronger result lets less through. */
  private def strength(result: GuardResult): Int =
    result match
      case GuardResult.Run            => 0
      case GuardResult.NeedsPerson(_) => 1
      case GuardResult.Refused(_)     => 2

  private def granted(lifetime: GrantLifetime): Vector[Entry] =
    cellEntries(asks) :+ Entry.Granted(RequestId("g1"), exec, lifetime)

  // ── Grant ──

  test("Grant: a Once grant covers one call") {
    val first  = EffectId(cell, 0)
    val during = state(journal((granted(GrantLifetime.Once) :+ Entry.EffectStarted(first, "exec", args))*))
    assertEquals(guardCheck(during, "exec", args), GuardResult.Run)
    val after = commit(during, Vector(Entry.EffectDone(first, EffectResult.Error("failed"))))
    assertEquals(guardCheck(after, "exec", args), asked)
  }

  test("Grant: a Turn grant ends at the next TurnEnded") {
    val inTheTurn = state(journal(granted(GrantLifetime.Turn)*))
    assertEquals(guardCheck(inTheTurn, "exec", args), GuardResult.Run)
    val closed = commit(
      inTheTurn,
      Vector(Entry.Observed("", CellOutcome.Ok, Map.empty), Entry.TurnEnded(TurnOutcome.Replied("done")))
    )
    assertEquals(guardCheck(closed, "exec", args), asked)
  }

  property("Grant: no grant stays after Ended") {
    forAll(states)(s => assert(commit(s, Vector(Entry.Ended(SessionOutcome.Parked, "done"))).grants.isEmpty))
  }

  property("Grant: a journal fork starts with no grants") {
    forAll(schedules)(run => assert(forked(run.journal).grants.isEmpty))
  }

  property("Grant: a repeat of a Grant commits nothing") {
    forAll(states) { s =>
      val grant = Command.Grant(RequestId("law-grant"), exec, GrantLifetime.Once)
      decide(grant, s) match
        case Right(entries) => assertEquals(decide(grant, commit(s, entries)), Right(Vector.empty))
        case Left(_)        => ()
    }
  }

  // ── Pending call ──

  property("Pending call: a pending call has no active grant that covers it") {
    forAll(states) { s =>
      for call <- s.pending do assert(!s.grants.exists(_.rule.covers(call)), s"a grant covers the pending call $call")
    }
  }

  property("Pending call: TurnStarted and Ended empty pending") {
    forAll(states) { s =>
      assert(commit(s, Vector(Entry.TurnStarted)).pending.isEmpty)
      assert(commit(s, Vector(Entry.Ended(SessionOutcome.Parked, "done"))).pending.isEmpty)
    }
  }

  property("Pending call: a journal fork starts with no pending calls") {
    forAll(schedules)(run => assert(forked(run.journal).pending.isEmpty))
  }

  property("Pending call: a session with no person never gets a pending call") {
    forAll(schedules, calls) { (run, call) =>
      val s = state(run.journal)
      if !s.config.owner.hasPerson then
        assert(s.pending.isEmpty)
        assert(strength(guardCheck(s, call._1, call._2)) != 1, "NeedsPerson in a session with no person")
    }
  }

  property("Pending call: the goal check waits while a pending call waits") {
    forAll(states)(s => if s.pending.nonEmpty then assert(!goalCheckDue(s)))
  }

  // ── Guard ──

  property("Guard: verdict is a homomorphism from Guard to Verdict") {
    forAll(guards, guards, calls) { (a, b, call) =>
      val (op, callArgs) = call
      assertEquals(a.and(b).verdict(op, callArgs), a.verdict(op, callArgs).join(b.verdict(op, callArgs)))
    }
  }

  property("Guard: allowAll is the identity of and, and its verdict is Allow") {
    forAll(guards, calls) { (g, call) =>
      assertEquals(g.and(Guard.allowAll), g)
      assertEquals(Guard.allowAll.and(g), g)
      assertEquals(Guard.allowAll.verdict(call._1, call._2), Verdict.Allow)
    }
  }

  property("Guard: join is associative, commutative and idempotent, and Allow is its identity") {
    forAll(verdicts, verdicts, verdicts) { (a, b, c) =>
      assertEquals(a.join(b).join(c), a.join(b.join(c)))
      assertEquals(a.join(b), b.join(a))
      assertEquals(a.join(a), a)
      assertEquals(a.join(Verdict.Allow), a)
    }
  }

  property("Guard: a grant changes only Ask, and more grants never make a result stronger") {
    forAll(states, calls) { (s, call) =>
      val (op, callArgs) = call
      val wider          = s.copy(grants = s.grants + ActiveGrant(GuardRule(op, ArgMatch.AnyArgs), GrantLifetime.Session))
      val before         = guardCheck(s, op, callArgs)
      val after          = guardCheck(wider, op, callArgs)
      assert(strength(after) <= strength(before), s"$after is stronger than $before")
      s.config.guard.verdict(op, callArgs) match
        case Verdict.Ask => assertEquals(after, GuardResult.Run)
        case _           => assertEquals(after, before)
    }
  }
