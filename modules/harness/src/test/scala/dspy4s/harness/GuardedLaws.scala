package dspy4s.harness

import dspy4s.harness.guest.NeedsApproval
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

/** The laws of the handler `guarded` (CODE_HARNESS_DESIGN.md, 5.3 and 5.5). */
class GuardedLaws extends munit.FunSuite:

  private val push = Json.Object(Chunk(("command", Json.String("git push origin main"))))

  private def rule(verdict: Verdict): Guard =
    Guard(Vector(VerdictRule(GuardRule("exec", ArgMatch.Prefix("command", "git push")), verdict)))

  /** A state with `guard`, the owner `owner` and `grants`. */
  private def stateWith(guard: Guard, owner: Owner = Owner.User, grants: Set[ActiveGrant] = Set.empty): SessionState =
    SessionState.initial.copy(config = Support.config().copy(guard = guard, owner = owner), grants = grants)

  test("Guarded: an Allow call runs, and a Deny call stops with EffectResult.Error and an error with the reason") {
    assertEquals(guarded(stateWith(rule(Verdict.Allow)), "exec", push), Right(()))
    assertEquals(guarded(stateWith(Guard.allowAll), "exec", push), Right(()))
    guarded(stateWith(rule(Verdict.Deny)), "exec", push) match
      case Left(GuardStop(EffectResult.Error(reason), error)) => assertEquals(error.getMessage, reason)
      case other                                              => fail(s"no Deny: $other")
  }

  test("Guarded: an Ask call with no grant stops with NeedsApproval of the Exact rule, and a NeedsApproval error") {
    guarded(stateWith(rule(Verdict.Ask)), "exec", push) match
      case Left(GuardStop(EffectResult.NeedsApproval(r), error: NeedsApproval)) =>
        assertEquals(r, GuardRule("exec", ArgMatch.Exact(push)))
        assertEquals(error.op, "exec")
        assert(error.argument.contains("git push origin main"), error.argument)
      case other => fail(s"no NeedsApproval: $other")
  }

  test("Guarded: an Ask call with a grant that matches runs") {
    val grant = ActiveGrant(GuardRule("exec", ArgMatch.Prefix("command", "git push")), GrantLifetime.Turn)
    assertEquals(guarded(stateWith(rule(Verdict.Ask), grants = Set(grant)), "exec", push), Right(()))
  }

  test("Guarded: in a session with no person, an Ask call stops with a plain error, not NeedsApproval") {
    Vector(Owner.Cell, Owner.Optimizer).foreach { owner =>
      guarded(stateWith(rule(Verdict.Ask), owner), "exec", push) match
        case Left(GuardStop(EffectResult.Error(_), _: NeedsApproval)) => fail(s"$owner: a NeedsApproval error")
        case Left(GuardStop(EffectResult.Error(_), _))                => ()
        case other                                                    => fail(s"$owner: $other")
    }
  }

  test("Guarded: the recorder calls guarded after the commit of EffectStarted, so a Deny call does not run") {
    val journal = Support.journal()
    Support.open(journal, Support.config().copy(guard = rule(Verdict.Deny)))
    journal.commit(Entry.StepStarted(0)): Unit
    journal.run(Command.Commit(Vector(Entry.Acted("", "", Some("code")))), Source.Policy): Unit
    val recorder = Recorder(journal)
    recorder.startCell(journal.log.last.seq)
    var ran = false
    intercept[RuntimeException](recorder.call("exec", push) { ran = true; "out" }): Unit
    assert(!ran)
    val log = journal.log.map(_.entry)
    assert(log.exists { case Entry.EffectStarted(_, "exec", _) => true; case _ => false })
    assert(log.exists { case Entry.EffectDone(_, EffectResult.Error(_)) => true; case _ => false })
  }

  test("Guarded: an Ask call through the recorder commits EffectDone(NeedsApproval), and pending holds the rule") {
    val journal = Support.journal()
    Support.open(journal, Support.config().copy(guard = rule(Verdict.Ask), owner = Owner.User))
    journal.commit(Entry.StepStarted(0)): Unit
    journal.run(Command.Commit(Vector(Entry.Acted("", "", Some("code")))), Source.Policy): Unit
    val recorder = Recorder(journal)
    recorder.startCell(journal.log.last.seq)
    intercept[NeedsApproval](recorder.call("exec", push)("out")): Unit
    assertEquals(journal.state.pending, Set(GuardRule("exec", ArgMatch.Exact(push))))
  }

  test("Guarded: a grant of a call whose arguments hold a known secret lets the next call with those arguments run") {
    val secret  = "fake-secret-value-0123456789"
    val secrets = Secrets(Map("FAKE_KEY" -> secret))
    val call    = Json.Object(Chunk(("command", Json.String(s"deploy --token $secret"))))
    val journal = Support.journal(secrets)
    val anyExec = Guard(Vector(VerdictRule(GuardRule("exec", ArgMatch.AnyArgs), Verdict.Ask)))
    Support.open(journal, Support.config().copy(guard = anyExec, owner = Owner.User))
    journal.commit(Entry.StepStarted(0)): Unit
    journal.run(Command.Commit(Vector(Entry.Acted("", "", Some("code")))), Source.Policy): Unit
    val recorder = Recorder(journal, mask = secrets.maskJson)
    recorder.startCell(journal.log.last.seq)
    intercept[NeedsApproval](recorder.call("exec", call)("out")): Unit
    // The pending rule holds the arguments with the mask, as the journal holds them, and the grant holds that rule.
    val pending = journal.state.pending.headOption.getOrElse(fail("no pending call"))
    assert(!pending.toString.contains(secret), pending.toString)
    assert(journal.run(Command.Grant(RequestId("g1"), pending, GrantLifetime.Once), Source.User).isRight)
    assertEquals(recorder.call("exec", call)("out"), "out")
  }
