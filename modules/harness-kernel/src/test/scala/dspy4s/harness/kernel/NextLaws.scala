package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import zio.blocks.schema.json.Json

import Fixtures.*
import Schedules.*

/** The rules of `next` (4.3), and the law Stop of 4.7. */
class NextLaws extends ScalaCheckSuite:

  private def after(entries: Entry*): SessionState = state(journal((turnEntries() ++ entries)*))

  test("Next rules: with no open turn, next waits") {
    assertEquals(next(idle), Next.Wait)
  }

  test("Next rules: before a step under the step limit, next calls the policy") {
    assertEquals(next(inTurn), Next.CallPolicy(0, mustAnswer = false))
  }

  test("Next rules: a chat turn at the step limit gets one more policy call, which must answer") {
    assertEquals(next(state(journal(turnEntries(config(steps = 0))*))), Next.CallPolicy(0, mustAnswer = true))
  }

  test("Next rules: a program call at the step limit ends with Exhausted") {
    val program = state(journal(turnEntries(config(kind = TurnKind.Program, steps = 0))*))
    assertEquals(next(program), Next.End(TurnOutcome.Exhausted))
  }

  test("Next rules: the harness runs no cell from the extra policy call of a chat turn") {
    val extra = state(
      journal((turnEntries(config(steps = 0)) ++ Vector(Entry.StepStarted(0), Entry.Acted("", "", Some("1"))))*)
    )
    assertEquals(next(extra), Next.End(TurnOutcome.Exhausted))
  }

  test("Next rules: during the policy call, next waits") {
    assertEquals(next(after(Entry.StepStarted(0))), Next.Wait)
  }

  test("Next rules: after Acted with code, next runs the cell") {
    assertEquals(next(inCell), Next.RunCell(cell, "1 + 1"))
  }

  test("Next rules: a reply with no cell ends the turn with Replied") {
    assertEquals(
      next(after(Entry.StepStarted(0), Entry.Acted("", "an answer", None))),
      Next.End(TurnOutcome.Replied("an answer"))
    )
  }

  test("Next rules: a cell that calls submit ends the turn with Submitted") {
    val value = Json.Number(42)
    val done  = after(
      Entry.StepStarted(0),
      Entry.Acted("", "", Some("submit(42)")),
      Entry.Observed("", CellOutcome.Submitted(value), Map.empty)
    )
    assertEquals(next(done), Next.End(TurnOutcome.Submitted(value)))
  }

  test("Next rules: after a cell, next calls the policy for the next step") {
    val done = after(
      Entry.StepStarted(0),
      Entry.Acted("", "", Some("1 + 1")),
      Entry.Observed("2", CellOutcome.Ok, Map.empty)
    )
    assertEquals(next(done), Next.CallPolicy(1, mustAnswer = false))
  }

  property("Stop: after StopRequested, next ends the turn with Aborted") {
    forAll(states) { s =>
      if s.position != Position.NoTurn then
        assertEquals(next(commit(s, Vector(Entry.StopRequested("stop")))), Next.End(TurnOutcome.Aborted))
    }
  }

  property("Stop: after StopRequested, decide accepts no new intent") {
    forAll(states) { s =>
      if s.position != Position.NoTurn then
        val stopped = commit(s, Vector(Entry.StopRequested("stop")))
        val cellNow = stopped.position match
          case Position.InCell(_, c, _) => c
          case _                        => 0L
        val step = stopped.position match
          case Position.BeforeStep(i) => i
          case _                      => 0
        val intents = Vector(
          Entry.StepStarted(step),
          Entry.Acted("", "", Some("1")),
          Entry.EffectStarted(EffectId(cellNow, 1000), "write", Json.Null)
        )
        for intent <- intents do
          assertEquals(decide(Command.Commit(Vector(intent)), stopped), Left(Rejection.OutOfOrder(intent)))
    }
  }
