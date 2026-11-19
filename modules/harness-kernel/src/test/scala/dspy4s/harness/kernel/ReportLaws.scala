package dspy4s.harness.kernel

import dspy4s.harness.guest.Scope
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

  /** The text of the report of `entry`, after a message of the owner and a step. */
  private def textOf(entry: Entry): String =
    val log = journalFrom(
      Source.Harness -> activated(),
      input("m1", owner),
      Source.Harness -> Entry.TurnStarted,
      Source.Harness -> Entry.StepStarted(0),
      Source.Harness -> entry
    )
    reports(session, owner, log).lastOption.fold(fail("no report"))(_.text)

  /** The outcomes of a turn that a report can give. */
  private val outcomes = Vector(
    TurnOutcome.Submitted(Json.String("42 lines")),
    TurnOutcome.Submitted(Json.Object(("lines", Json.Number(42)))),
    TurnOutcome.Replied("done"),
    TurnOutcome.Exhausted,
    TurnOutcome.Aborted,
    TurnOutcome.Crashed,
    TurnOutcome.Failed("no model")
  )

  test("Report: a debt opens at the first step that sees an Input of the owner, and only the work closes it") {
    assertEquals(ids(reports(session, owner, work)), Vector(RequestId("report:s1:5"), RequestId("report:s1:10")))
  }

  test("Report: owes is open from an Input of the owner until the work ends, and a typed turn does not open it") {
    // `work`: seq 1 is the Input of the owner, seq 10 ends the turn with submit, and seq 11 is a typed Input.
    // `at(seq)` is the debt after the entry `seq`.
    def at(seq: Int): Boolean = owes(owner, work.take(seq + 1))
    assertEquals(at(0), false)
    assert((1 to 9).forall(at), (1 to 9).map(at).toString)
    assert((10 to 15).forall(n => !at(n)), (10 to 15).map(at).toString)
    // A new message of the owner opens the debt again, and a stop of a turn closes it.
    val asked = journalFrom(
      (Vector(Source.Harness -> activated()) ++
        turn(TurnOutcome.Replied("A question?"), owner) ++
        turn(TurnOutcome.Submitted(args), Source.User) :+
        input("from-owner-again", owner))*
    )
    assertEquals(owes(owner, asked), true)
    val stopped = journalFrom((Vector(Source.Harness -> activated()) ++ turn(TurnOutcome.Aborted, owner))*)
    assertEquals(owes(owner, stopped), false)
  }

  property("Report: a TurnEnded gives a report exactly when the journal before it owes one and no goal loop is on") {
    // `owes` counts an Input of the owner that no step or stop saw yet, because the next step sees it. A TurnEnded
    // that comes before such a step gives no report for that Input, so the law skips that case. While a goal loop is
    // on, a TurnEnded gives no report (8.5).
    def seen(entry: Entry): Boolean =
      entry match
        case Entry.StepStarted(_) | Entry.StopRequested(_) => true
        case _                                             => false
    def unseen(log: Vector[Logged]): Boolean =
      log.reverseIterator.takeWhile(l => !seen(l.entry)).exists { l =>
        l.entry match
          case Entry.Input(_, _, _) => l.meta.source == owner
          case _                    => false
      }
    forAll(schedules) { run =>
      run.journal.indices.forall { i =>
        run.journal(i).entry match
          case Entry.TurnEnded(_) =>
            val before  = run.journal.take(i)
            val sent    = reports(session, owner, run.journal.take(i + 1)).size > reports(session, owner, before).size
            val looping = state(before).loop.isDefined
            unseen(before) || sent == (owes(owner, before) && !looping)
          case _ => true
      }
    }
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
    // The first line of the report of a turn. The law below tells what comes after it.
    def ended(outcome: TurnOutcome): String = textOf(Entry.TurnEnded(outcome)).linesIterator.next()
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

  // In the live session of 2026-11-15, the controller read "The turn ended with submit: ..." as the end of the session.
  // So it did not use tell for more work, and it started a new session for the same work.
  test("Report: the report of a turn says that the session stays open and that tell gives it more work") {
    val open   = s"The session ${session.value} stays open. To give it more work, send it a message with tell."
    val folder = "The folder of the session is /tmp/session. A relative path in this report is a path in that folder."
    outcomes.foreach(outcome => assertEquals(textOf(Entry.TurnEnded(outcome)), s"${outcome.report}\n$open\n$folder"))
    // The end of the session closes it, so its report has no such line.
    val end = SessionOutcome.Abandoned("not needed")
    assertEquals(textOf(Entry.Ended(end, "not needed")), end.report("not needed"))
  }

  // In the live session of 2026-11-15, a work session submitted `hello.txt`, a path in its folder. The report gave only
  // that path, so the controller ran an observe probe after each report to learn the folder of the session.
  test("Report: a report of a turn names the folder of the last activation, so the owner can find a relative path") {
    def folderLine(path: String): String =
      s"The folder of the session is $path. A relative path in this report is a path in that folder."
    def withScope(scope: Scope): SessionConfig                = config().copy(scope = scope)
    def textAfter(activations: Vector[SessionConfig]): String =
      val log = journalFrom(
        (activations.map(c => Source.Harness -> activated(c)) ++
          turn(TurnOutcome.Submitted(Json.String("hello.txt")), owner))*
      )
      reports(session, owner, log).lastOption.fold(fail("no report"))(_.text)
    val scopes = Vector(
      Scope.Scratch("/tmp/a")                           -> "/tmp/a",
      Scope.Directory("/tmp/b")                         -> "/tmp/b",
      Scope.Worktree("/tmp/c", "jp/c", "/repo", "main") -> "/tmp/c",
      Scope.Fork("/tmp/d", "/tmp/a")                    -> "/tmp/d",
      Scope.Home("/home/e")                             -> "/home/e"
    )
    scopes.foreach { (scope, path) =>
      val text = textAfter(Vector(withScope(scope)))
      assertEquals(text.linesIterator.toVector.last, folderLine(path), text)
    }
    // A later activation can have another scope, and its folder counts.
    val moved = textAfter(Vector(withScope(Scope.Scratch("/tmp/old")), withScope(Scope.Directory("/tmp/new"))))
    assertEquals(moved.linesIterator.toVector.last, folderLine("/tmp/new"), moved)
    // A scope with no path names no folder.
    val empty = textAfter(Vector(withScope(Scope.Scratch(""))))
    assert(!empty.contains("The folder of the session"), empty)
    // The report of the end of a goal loop names the folder too.
    val loop = journalFrom(
      (Vector(Source.Harness -> activated(withScope(Scope.Directory("/tmp/loop")).copy(goal = Some("tests pass")))) ++
        Vector(input("m1", owner), Source.Harness -> Entry.GoalLoopStarted(3)) ++
        turn(TurnOutcome.Submitted(Json.String("done")), Source.Harness).drop(1) ++
        Vector(Source.Harness -> Entry.GoalLoopEnded(LoopEnd.Limit)))*
    )
    val loopText = reports(session, owner, loop).lastOption.fold(fail("no report"))(_.text)
    assertEquals(loopText.linesIterator.toVector.last, folderLine("/tmp/loop"), loopText)
  }
