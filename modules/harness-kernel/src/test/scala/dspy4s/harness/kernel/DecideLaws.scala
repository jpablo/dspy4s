package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import zio.blocks.schema.json.Json

import Fixtures.*
import Schedules.*

/** The table of `decide` (4.5), and the laws Input, Log, Language and Rest of 4.7. */
class DecideLaws extends ScalaCheckSuite:

  private val rule = GuardRule("exec", ArgMatch.Exact(args))

  // ── Each row of the table of 4.5, and the rules for `Commit` ──

  test("Decide table: Activate commits repair(s), then Activated") {
    assertEquals(decide(Command.Activate(versions, config()), inCell), Right(repair(inCell) :+ activated()))
  }

  test("Decide table: Activate with an input that waits also commits TurnStarted") {
    assertEquals(
      decide(Command.Activate(versions, config()), inTurn),
      Right(repair(inTurn) ++ Vector(activated(), Entry.TurnStarted))
    )
  }

  test("Decide table: a Send with a known request id commits nothing") {
    assertEquals(decide(Command.Send(RequestId("r1"), "again", Map.empty), inTurn), Right(Vector.empty))
  }

  test("Decide table: a Send to a session that has ended commits nothing and gets SessionClosed") {
    assertEquals(decide(Command.Send(RequestId("r2"), "hi", Map.empty), ended), Left(Rejection.SessionClosed))
  }

  test("Decide table: a Send with no open turn commits Input and TurnStarted") {
    val values = Map("x" -> Json.String("1"))
    assertEquals(
      decide(Command.Send(RequestId("r2"), "hi", values), idle),
      Right(Vector(Entry.Input(RequestId("r2"), "hi", values), Entry.TurnStarted))
    )
  }

  test("Decide table: a Send in a turn commits only the Input") {
    assertEquals(
      decide(Command.Send(RequestId("r2"), "more", Map.empty), inTurn),
      Right(Vector(Entry.Input(RequestId("r2"), "more", Map.empty)))
    )
  }

  test("Decide table: a Commit of entries that are valid at their place commits them") {
    assertEquals(decide(Command.Commit(Vector(Entry.StepStarted(0))), inTurn), Right(Vector(Entry.StepStarted(0))))
  }

  test("Decide table: a Commit of an EffectStarted with the id of an open effect is refused") {
    // A second intent with one id would replace the first, and the first would get no outcome of its own.
    val started = Entry.EffectStarted(EffectId(cell, 0), "read", args)
    val open    = commit(inCell, Vector(started))
    assertEquals(decide(Command.Commit(Vector(started)), open), Left(Rejection.OutOfOrder(started)))
    val other = Entry.EffectStarted(EffectId(cell, 0), "write", args)
    assertEquals(decide(Command.Commit(Vector(other)), open), Left(Rejection.OutOfOrder(other)))
    val next = Entry.EffectStarted(EffectId(cell, 1), "write", args)
    assertEquals(decide(Command.Commit(Vector(next)), open), Right(Vector(next)))
  }

  test("Decide table: an EffectStarted needs an id above each id that its cell started, also a closed one") {
    // A replay answers each call by its id, so one id must name one call of the cell.
    def started(n: Int) = Entry.EffectStarted(EffectId(cell, n), "read", args)
    val closed          = commit(inCell, Vector(started(0), Entry.EffectDone(EffectId(cell, 0), EffectResult.Unknown)))
    assertEquals(decide(Command.Commit(Vector(started(0))), closed), Left(Rejection.OutOfOrder(started(0))))
    assertEquals(decide(Command.Commit(Vector(started(2))), closed), Right(Vector(started(2))))
    val later = commit(closed, Vector(started(2)))
    assertEquals(decide(Command.Commit(Vector(started(1))), later), Left(Rejection.OutOfOrder(started(1))))
  }

  test("Decide table: an Abort in a turn commits StopRequested, which clears the inputs that wait") {
    val entries = decide(Command.Abort("stop"), inTurn)
    assertEquals(entries, Right(Vector(Entry.StopRequested("stop"))))
    assertEquals(commit(inTurn, entries.getOrElse(Vector.empty)).waiting, 0)
  }

  test("Decide table: StopJobs commits JobsStopRequested, also with no open turn") {
    assertEquals(
      decide(Command.StopJobs(JobSelector.All, "stop all"), idle),
      Right(Vector(Entry.JobsStopRequested(JobSelector.All, "stop all")))
    )
  }

  test("Decide table: a Grant with a known request id commits nothing") {
    assertEquals(decide(Command.Grant(RequestId("r1"), rule, GrantLifetime.Once), inTurn), Right(Vector.empty))
  }

  test("Decide table: a Grant to a session that has ended commits nothing and gets SessionClosed") {
    assertEquals(decide(Command.Grant(RequestId("g1"), rule, GrantLifetime.Once), ended), Left(Rejection.SessionClosed))
  }

  test(
    "Decide table: a Grant that answers a pending call commits Granted, an Input and, with no open turn, TurnStarted"
  ) {
    val asked = state(
      journal(
        (cellEntries(config(guard = Guard(Vector(VerdictRule(rule, Verdict.Ask))))) ++ Vector(
          Entry.EffectStarted(EffectId(cell, 0), "exec", args),
          Entry.EffectDone(EffectId(cell, 0), EffectResult.NeedsApproval(rule)),
          Entry.Observed("", CellOutcome.Failed("NeedsApproval"), Map.empty),
          Entry.TurnEnded(TurnOutcome.Replied("May I push?"))
        ))*
      )
    )
    val request = RequestId("g1")
    assertEquals(
      decide(Command.Grant(request, rule, GrantLifetime.Once), asked),
      Right(
        Vector(
          Entry.Granted(request, rule, GrantLifetime.Once),
          Entry.Input(request, approvedText(rule), Map.empty),
          Entry.TurnStarted
        )
      )
    )
  }

  test("Decide table: a Grant with no pending call commits only Granted, and it starts no turn") {
    val request = RequestId("g1")
    assertEquals(
      decide(Command.Grant(request, rule, GrantLifetime.Turn), idle),
      Right(Vector(Entry.Granted(request, rule, GrantLifetime.Turn)))
    )
  }

  test("Decide table: Close commits Ended only with no open turn") {
    assertEquals(
      decide(Command.Close(SessionOutcome.Merged, "done"), idle),
      Right(Vector(Entry.Ended(SessionOutcome.Merged, "done")))
    )
    assertEquals(decide(Command.Close(SessionOutcome.Merged, "done"), inTurn), Left(Rejection.TurnOpen))
  }

  test("Decide table: after StopRequested, only outcomes can commit") {
    val stopped = commit(inCell, Vector(Entry.StopRequested("stop")))
    val intent  = Entry.EffectStarted(EffectId(cell, 0), "read", args)
    assertEquals(decide(Command.Commit(Vector(intent)), stopped), Left(Rejection.OutOfOrder(intent)))
    val outcome = Entry.Observed("", CellOutcome.Failed("stopped"), Map.empty)
    assertEquals(decide(Command.Commit(Vector(outcome)), stopped), Right(Vector(outcome)))
  }

  test("Decide table: EffectStarted needs an EffectId of the current cell") {
    val late = Entry.EffectStarted(EffectId(cell - 1, 0), "write", args)
    assertEquals(decide(Command.Commit(Vector(late)), inCell), Left(Rejection.OutOfOrder(late)))
  }

  test("Decide table: a late EffectDone of a closed effect adds nothing") {
    val late = Entry.EffectDone(EffectId(cell, 0), EffectResult.Unknown)
    assertEquals(decide(Command.Commit(Vector(late)), inCell), Right(Vector.empty))
  }

  test("Decide table: if an input waits when TurnEnded commits, TurnStarted commits with it") {
    val answered = state(
      journal(
        (turnEntries() ++ Vector(
          Entry.StepStarted(0),
          Entry.Acted("", "an answer", None),
          Entry.Input(RequestId("r2"), "one more", Map.empty)
        ))*
      )
    )
    val end = Entry.TurnEnded(TurnOutcome.Replied("an answer"))
    assertEquals(decide(Command.Commit(Vector(end)), answered), Right(Vector(end, Entry.TurnStarted)))
  }

  // ── The laws of 4.7 ──

  property("Input: two Send commands with one request id give one Input") {
    forAll(states) { s =>
      val send = Command.Send(RequestId("law-input"), "hi", Map.empty)
      decide(send, s) match
        case Left(_)      => ()
        case Right(first) =>
          assertEquals(first.count { case Entry.Input(_, _, _) => true; case _ => false }, 1)
          assertEquals(decide(send, commit(s, first)), Right(Vector.empty))
    }
  }

  property("Log: the seq of each entry is its place in the journal, so no entry changes or goes away") {
    forAll(schedules)(run => assertEquals(run.journal.map(_.seq), run.journal.indices.map(_.toLong).toVector))
  }

  property("Language: each journal of a run is a word of the language of 4.1") {
    forAll(schedules)(run => assertEquals(grammar(run.journal), Vector.empty))
  }

  property("Language: a journal after a repair is a word of the language of 4.1") {
    forAll(schedules)(run => assertEquals(grammar(append(run.journal, repair(state(run.journal)))), Vector.empty))
  }

  property("Rest: with no open turn, no input waits") {
    forAll(states)(s => if s.position == Position.NoTurn then assertEquals(s.waiting, 0))
  }
