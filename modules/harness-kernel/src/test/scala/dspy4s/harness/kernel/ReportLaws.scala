package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import zio.blocks.schema.json.Json

import Fixtures.*
import Schedules.*

/** The law Report of 4.7, for the part that the kernel alone can show: `reports` and `missing` (8.6). The report pass
  * of a real host comes in phase 9.
  */
class ReportLaws extends ScalaCheckSuite:

  private val owner = Source.Controller

  private def turn(outcome: TurnOutcome, from: Source): Vector[(Source, Entry)] =
    Vector(
      input(s"from-$from-$outcome", from),
      Source.Harness -> Entry.TurnStarted,
      Source.Harness -> Entry.StepStarted(0),
      Source.Policy  -> Entry.Acted("", "text", None),
      Source.Harness -> Entry.TurnEnded(outcome)
    )

  /** A reply keeps the debt open, a person answers by typing, a submit closes the debt, and a later typed turn gives no
    * report. The `TurnEnded` entries have the `seq` 5, 10 and 15.
    */
  private val work = journalFrom(
    (Vector(Source.Harness -> activated()) ++
      turn(TurnOutcome.Replied("A question?"), owner) ++
      turn(TurnOutcome.Submitted(args), Source.User) ++
      turn(TurnOutcome.Replied("Thanks."), Source.User))*
  )

  private def ids(sends: Vector[Command.Send]): Vector[RequestId] = sends.map(_.request)

  /** The owner after the commit of each report as an `Input`. */
  private def received(sends: Vector[Command.Send]): SessionState =
    commit(state(journal(activated())), sends.map(send => Entry.Input(send.request, send.text, send.values)))

  test("Report: a debt opens at the first step that sees an Input of the owner, and only the work closes it") {
    assertEquals(ids(reports(session, owner, work)), Vector(RequestId("report:s1:5"), RequestId("report:s1:10")))
  }

  test("Report: the end of a session that owes a report gives a report") {
    val open  = journalFrom((Vector(Source.Harness -> activated()) ++ turn(TurnOutcome.Replied("?"), owner))*)
    val ended = append(open, Vector(Entry.Ended(SessionOutcome.Parked, "done")))
    assertEquals(ids(reports(session, owner, ended)), Vector(RequestId("report:s1:5"), RequestId("report:s1:6")))
  }

  property("Report: the reports of a prefix of a journal are a prefix of its reports") {
    forAll(schedules, Gen.choose(0.0, 1.0)) { (run, at) =>
      val all    = reports(session, owner, run.journal)
      val prefix = reports(session, owner, run.journal.take((run.journal.size * at).toInt))
      assertEquals(all.take(prefix.size), prefix)
    }
  }

  test("Report: missing gives the reports that the owner does not have") {
    val all = reports(session, owner, work)
    assertEquals(missing(session, owner, work, received(all.take(1))), all.drop(1))
  }

  property("Report: after the report pass, nothing is missing") {
    forAll(schedules) { run =>
      val all = reports(session, owner, run.journal)
      assertEquals(missing(session, owner, run.journal, received(all)), Vector.empty)
    }
  }

  test("Report: the text gives the outcome in words, the value of submit as text, and the reason of an end once") {
    // The text of the report of `entry`, after a message of the owner and a step.
    def textOf(entry: Entry): String =
      val log = journalFrom(
        Source.Harness -> activated(),
        input("m1", owner),
        Source.Harness -> Entry.TurnStarted,
        Source.Harness -> Entry.StepStarted(0),
        Source.Harness -> entry
      )
      reports(session, owner, log).lastOption.fold(fail("no report"))(_.text)
    def ended(outcome: TurnOutcome): String = textOf(Entry.TurnEnded(outcome))
    assertEquals(ended(TurnOutcome.Submitted(Json.String("42 lines"))), "The turn ended with submit: 42 lines")
    val record = ended(TurnOutcome.Submitted(Json.Object(("lines", Json.Number(42)))))
    assert(record.startsWith("The turn ended with submit: {") && record.contains("lines"), record)
    assertEquals(ended(TurnOutcome.Replied("done")), "The turn ended with a reply: done")
    assertEquals(ended(TurnOutcome.Exhausted), "The turn ended: it used all its steps and gave no result.")
    assertEquals(ended(TurnOutcome.Aborted), "The turn ended: a stop ended it.")
    assertEquals(ended(TurnOutcome.Crashed), "The turn crashed: its host stopped during the turn.")
    assertEquals(ended(TurnOutcome.Failed("no model")), "The turn failed: no model")
    def closed(outcome: SessionOutcome, reason: String): String = textOf(Entry.Ended(outcome, reason))
    assertEquals(
      closed(SessionOutcome.Abandoned("not needed"), "not needed"),
      "The session ended: abandoned, not needed."
    )
    assertEquals(closed(SessionOutcome.Merged, "FAKE"), "The session ended: merged, FAKE.")
    assertEquals(closed(SessionOutcome.Parked, "FAKE"), "The session ended: parked, FAKE.")
    assertEquals(closed(SessionOutcome.Replaced(SessionId("s2")), "FAKE"), "The session ended: replaced by s2, FAKE.")
  }
