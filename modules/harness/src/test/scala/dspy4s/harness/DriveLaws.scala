package dspy4s.harness

import dspy4s.harness.kernel.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import zio.blocks.schema.json.Json

import Support.*

/** The laws of the worker (CODE_HARNESS_DESIGN.md, 4.6), with scripts of `TurnEffects`: the checks "drive with scripts"
  * of section 12. A journal in memory holds the entries.
  */
class DriveLaws extends ScalaCheckSuite:

  private val value = Json.String("v")

  // The random scripts. The generators come before the tests, because a test captures this class.

  private val replies: Gen[Either[String, PolicyReply]] = Gen.frequency(
    6 -> Gen.oneOf("a", "b", "submit(v)").map(code),
    2 -> Gen.oneOf("x", "y").map(answer),
    1 -> Gen.const(Left("no model"))
  )

  private val outcomes: Gen[CellOutcome] = Gen.oneOf(
    CellOutcome.Ok,
    CellOutcome.Failed("boom"),
    CellOutcome.CompileError(Vector("error")),
    CellOutcome.TimedOut(10L),
    CellOutcome.Submitted(Json.String("v"))
  )

  /** What happens in a cell: nothing, an effect with its outcome, an effect that stays open, or a stop. */
  private val actions: Gen[Int] = Gen.frequency(6 -> 0, 2 -> 1, 1 -> 2, 1 -> 3)

  test("Drive: a chat answer ends the turn with Replied") {
    val j = journal()
    open(j, config(kind = TurnKind.Chat))
    drive(j, Script(Vector(answer("hi")))): Unit
    assertEquals(
      turn(j),
      Vector(Entry.StepStarted(0), Entry.Acted("", "hi", None), Entry.TurnEnded(TurnOutcome.Replied("hi")))
    )
  }

  test("Drive: submit ends the turn with the value, and the writes of the cell go into Observed") {
    val j      = journal()
    val writes = Map("k" -> Json.String("w"))
    open(j)
    drive(j, Script(Vector(code("submit(v)")), Vector(CellResult("", CellOutcome.Submitted(value), writes)))): Unit
    assertEquals(
      turn(j),
      Vector(
        Entry.StepStarted(0),
        Entry.Acted("", "", Some("submit(v)")),
        Entry.Observed("", CellOutcome.Submitted(value), writes),
        Entry.TurnEnded(TurnOutcome.Submitted(value))
      )
    )
  }

  test("Drive: a program call ends at the step limit with Exhausted") {
    val j = journal()
    open(j, config(steps = 2))
    drive(j, Script(Vector(code("a"), code("b")), Vector(done, done))): Unit
    assertEquals(
      turn(j),
      Vector(
        Entry.StepStarted(0),
        Entry.Acted("", "", Some("a")),
        Entry.Observed(done.output, done.outcome, done.writes),
        Entry.StepStarted(1),
        Entry.Acted("", "", Some("b")),
        Entry.Observed(done.output, done.outcome, done.writes),
        Entry.TurnEnded(TurnOutcome.Exhausted)
      )
    )
  }

  test("Drive: a chat turn gets one last step, which must answer") {
    val j      = journal()
    val script = Script(Vector(code("a"), answer("end")), Vector(done))
    open(j, config(kind = TurnKind.Chat, steps = 1))
    drive(j, script): Unit
    assertEquals(script.mustAnswer.toVector, Vector(false, true))
    assertEquals(turn(j).last, Entry.TurnEnded(TurnOutcome.Replied("end")))
  }

  test("Drive: after a failed cell, the next step comes") {
    val j      = journal()
    val failed = CellResult("boom", CellOutcome.Failed("boom"), Map.empty)
    open(j)
    drive(j, Script(Vector(code("a"), answer("x")), Vector(failed))): Unit
    assertEquals(
      turn(j),
      Vector(
        Entry.StepStarted(0),
        Entry.Acted("", "", Some("a")),
        Entry.Observed("boom", CellOutcome.Failed("boom"), Map.empty),
        Entry.StepStarted(1),
        Entry.Acted("", "x", None),
        Entry.TurnEnded(TurnOutcome.Replied("x"))
      )
    )
  }

  test("Drive: a failed LM call ends the turn with Failed") {
    val j = journal()
    open(j)
    drive(j, Script(Vector(Left("no model")))): Unit
    assertEquals(turn(j), Vector(Entry.StepStarted(0), Entry.TurnEnded(TurnOutcome.Failed("no model"))))
  }

  test("Drive: a stop during a cell ends the turn with Aborted, and the open effect gets Unknown") {
    val j     = journal()
    val write = Json.String("x")
    open(j)
    val stop = (cell: Long, _: String) =>
      j.commit(Entry.EffectStarted(EffectId(cell, 0), "write", write)): Unit
      j.run(Command.Abort("stop"), Source.User): Unit
    drive(j, Script(Vector(code("a")), Vector(done), stop)): Unit
    val cell = j.log.map(_.entry).indexWhere {
      case Entry.Acted(_, _, Some(_)) => true
      case _                          => false
    }
    val id = EffectId(j.log(cell).seq, 0)
    assertEquals(
      turn(j),
      Vector(
        Entry.StepStarted(0),
        Entry.Acted("", "", Some("a")),
        Entry.EffectStarted(id, "write", write),
        Entry.StopRequested("stop"),
        Entry.EffectDone(id, EffectResult.Unknown),
        Entry.Observed(done.output, done.outcome, done.writes),
        Entry.TurnEnded(TurnOutcome.Aborted)
      )
    )
  }

  test("Drive: a crash during a cell leaves the turn open, and the next Activate repairs it") {
    val j     = journal()
    val crash = (_: Long, _: String) => throw IllegalStateException("crash")
    open(j)
    intercept[IllegalStateException](drive(j, Script(Vector(code("a")), Vector(done), crash))): Unit
    assert(j.state.position != Position.NoTurn)
    j.run(Command.Activate(versions, config()), Source.Harness): Unit
    val last = j.log.map(_.entry).takeRight(3)
    assertEquals(last(0), Entry.Observed("", CellOutcome.Failed("the cell did not complete"), Map.empty))
    assertEquals(last(1), Entry.TurnEnded(TurnOutcome.Crashed))
    assertEquals(j.state.position, Position.NoTurn)
  }

  test("Drive: with no open turn, drive commits nothing") {
    val j = journal()
    j.run(Command.Activate(versions, config()), Source.Harness): Unit
    val before = j.log
    drive(j, Script(Vector(answer("never")))): Unit
    assertEquals(j.log, before)
  }

  property("Drive: each script ends the turn, and the turn is a word of the language of 4.1") {
    forAll(
      Gen.oneOf(TurnKind.Chat, TurnKind.Program),
      Gen.choose(1, 4),
      Gen.listOfN(6, replies),
      Gen.listOfN(6, outcomes),
      Gen.listOfN(6, actions)
    ) { (kind, steps, rs, os, as) =>
      val j    = journal()
      var cell = 0
      val act  = (id: Long, _: String) =>
        as.lift(cell).getOrElse(0) match
          case 1 =>
            j.commit(Entry.EffectStarted(EffectId(id, 0), "read", Json.String("a"))): Unit
            j.commit(Entry.EffectDone(EffectId(id, 0), EffectResult.Value(Json.String("t"), "t"))): Unit
          case 2 => j.commit(Entry.EffectStarted(EffectId(id, 0), "write", Json.String("a"))): Unit
          case 3 => j.run(Command.Abort("stop"), Source.User): Unit
          case _ => ()
        cell += 1
      open(j, config(kind = kind, steps = steps))
      val end = drive(j, Script(rs.toVector, os.map(CellResult("out", _, Map.empty)).toVector, act))
      assertEquals(end.position, Position.NoTurn)
      assertEquals(next(end), Next.Wait)
      assert(isTurn(turn(j)), turn(j).mkString("\n"))
      assertEquals(
        turn(j).count {
          case Entry.TurnEnded(_) => true
          case _                  => false
        },
        1
      )
    }
  }
