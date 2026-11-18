package dspy4s.harness.kernel

import munit.FunSuite
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import zio.blocks.schema.json.Json

import Fixtures.*

/** The kernel laws of the goal loop (CODE_HARNESS_DESIGN.md, 8.5 and 4.7): the start and the end of a loop, its limit,
  * the check after a turn, the goal message, the same blocker, the reports to an owner, and the pool of turns.
  */
class GoalLaws extends FunSuite:

  private val goal  = "The fake build passes"
  private val other = Source.Child(SessionId("s2"))
  private val owner = Source.Controller

  private def goalConfig(owner: Owner = Owner.User): SessionConfig = config(owner = owner).copy(goal = Some(goal))

  /** A session with no turn, with a goal. */
  private def withGoal: SessionState = state(journal(activated(goalConfig())))

  /** A session with no turn and no goal. */
  private def noGoal: SessionState = state(journal(activated()))

  /** The state after `command` from `sender`, with the sources that `sourceOf` gives. */
  private def commitFrom(s: SessionState, command: Command, sender: Source): SessionState =
    decide(command, s, sender).fold(e => fail(s"decide refused $command: $e"), identity).foldLeft(s) { (now, entry) =>
      evolve(now, Logged(now.nextSeq, Meta(0L, sourceOf(command, sender, entry), Taint.Trusted, true), entry))
    }

  /** The open turn of `s` to its end with a reply. */
  private def endTurn(s: SessionState): SessionState =
    val answered = commit(s, Vector(Entry.StepStarted(0), Entry.Acted("", "done", None)))
    commitFrom(answered, Command.Commit(Vector(Entry.TurnEnded(TurnOutcome.Replied("done")))), Source.Harness)

  /** `n` turns that a message of `from` starts, each to its end. A message that waits starts no turn. */
  private def turns(start: SessionState, n: Int, from: Source): SessionState = (0 until n).foldLeft(start) { (s, i) =>
    val sent = commitFrom(s, Command.Send(RequestId(s"m$i-${s.nextSeq}"), "work", Map.empty), from)
    if sent.position == Position.NoTurn then sent else endTurn(sent)
  }

  private def started(s: SessionState, limit: Int): SessionState = commitFrom(s, Command.StartLoop(limit), Source.User)

  private def check(met: Boolean, blocker: String = "", same: Boolean = false): GoalCheck =
    GoalCheck(met, if met then "the build passes" else "the build fails", blocker, same)

  // ── Loop ──

  test("Loop: only StartLoop from the person starts a loop, and N is 1 or more") {
    assertEquals(decide(Command.StartLoop(5), withGoal, Source.User), Right(Vector(Entry.GoalLoopStarted(5))))
    for sender <- Vector(Source.Controller, Source.Harness, Source.Policy, other) do
      assertEquals(decide(Command.StartLoop(5), withGoal, sender), Left(Rejection.NotPerson))
    assertEquals(decide(Command.StartLoop(0), withGoal, Source.User), Left(Rejection.LoopLimit(0)))
    assertEquals(decide(Command.StartLoop(-3), withGoal, Source.User), Left(Rejection.LoopLimit(-3)))
    assertEquals(decide(Command.StartLoop(5), ended, Source.User), Left(Rejection.SessionClosed))
    assertEquals(started(withGoal, 5).loop, Some(GoalLoop.start(5)))
  }

  test("Loop: Begin with a loop gives GoalLoopStarted after Activated, and Begin with 0 gives none") {
    val task  = RequestId("start:controller:3.0")
    val begin = Command.Begin(versions, goalConfig(), task, "Fix the fake build", loop = 3)
    assertEquals(
      decide(begin, SessionState.initial, Source.Controller),
      Right(Vector(
        Entry.Activated(versions, goalConfig()),
        Entry.GoalLoopStarted(3),
        Entry.Input(task, "Fix the fake build", Map.empty),
        Entry.TurnStarted
      ))
    )
    val none  = Command.Begin(versions, goalConfig(), task, "Fix the fake build", loop = 0)
    val loops = decide(none, SessionState.initial, Source.Controller).map(_.collect {
      case Entry.GoalLoopStarted(limit) => limit
    })
    assertEquals(loops, Right(Vector.empty))
  }

  test("Loop: the start sets the count of the unattended turns to 0, and a new start takes the new N") {
    val busy = turns(noGoal, 5, other)
    assertEquals(busy.unattended, 5)
    val on = started(busy, 20)
    assertEquals(on.unattended, 0)
    assertEquals(started(on, 30).loop.map(_.limit), Some(30))
  }

  test("Loop: while a loop is on, its N is the limit of the unattended turns, and a person sets the count to 0") {
    val on   = started(noGoal, 12)
    val many = turns(on, 11, other)
    assertEquals(many.unattended, 11)
    assert(many.loop.isDefined)
    // The turns above 8 started, because the loop changed the limit.
    val sent = commitFrom(many, Command.Send(RequestId("x"), "work", Map.empty), other)
    assertEquals(sent.position, Position.BeforeStep(0))
    val person = commitFrom(many, Command.Send(RequestId("p"), "go on", Map.empty), Source.User)
    assertEquals(endTurn(person).unattended, 0)
  }

  test("Loop: a session with no goal ends its loop with Limit at the end of the turn at N, and the limit is 8 again") {
    val on   = started(noGoal, 12)
    val last = turns(on, 11, other)
    val sent = commitFrom(last, Command.Send(RequestId("x"), "work", Map.empty), other)
    assertEquals(sent.unattended, 12)
    val answered = commit(sent, Vector(Entry.StepStarted(0), Entry.Acted("", "done", None)))
    val ended    = Vector(Entry.TurnEnded(TurnOutcome.Replied("done")))
    assertEquals(
      decide(Command.Commit(ended), answered, Source.Harness),
      Right(ended :+ Entry.GoalLoopEnded(LoopEnd.Limit))
    )
    val after = commitFrom(answered, Command.Commit(ended), Source.Harness)
    assertEquals(after.loop, None)
    assertEquals(after.loopEnd, Some(LoopEnd.Limit))
    assertEquals(
      decide(Command.Send(RequestId("y"), "more", Map.empty), after, other),
      Right(Vector(Entry.Input(RequestId("y"), "more", Map.empty)))
    )
  }

  test("Loop: Activate ends a loop with Restarted, and no loop stays after Ended or in a fork") {
    val on = started(withGoal, 10)
    decide(Command.Activate(versions, goalConfig()), on) match
      case Right(entries) => assertEquals(
          entries.takeRight(2),
          Vector(Entry.GoalLoopEnded(LoopEnd.Restarted), Entry.Activated(versions, goalConfig()))
        )
      case Left(error) => fail(error.toString)
    val again = run(on, Command.Activate(versions, goalConfig()))
    assertEquals(again.loop, None)
    assertEquals(again.loopEnd, Some(LoopEnd.Restarted))
    assertEquals(decide(Command.Activate(versions, goalConfig()), withGoal).map(_.size), Right(1))
    assertEquals(run(on, Command.Close(SessionOutcome.Parked, "done")).loop, None)
    val log = journal(activated(goalConfig()), Entry.GoalLoopStarted(10))
    assertEquals(state(log).loop.map(_.limit), Some(10))
    assertEquals(forked(log).loop, None)
  }

  test("Loop: after a restart that ends a loop, a message waits for the person, and a message or /goal on starts it") {
    // A host closed during a loop: the goal message waits in an open turn, which the repair closes.
    val on      = started(withGoal, 10)
    val sent    = commitFrom(on, Command.Send(RequestId("goal:9"), "Go on with the work.", Map.empty), Source.Harness)
    val crashed = sent.copy(position = Position.BeforeStep(0))
    decide(Command.Activate(versions, goalConfig()), crashed) match
      case Right(entries) => assert(!entries.contains(Entry.TurnStarted), entries.toString)
      case Left(error)    => fail(error.toString)
    val back = commitFrom(crashed, Command.Activate(versions, goalConfig()), Source.Harness)
    assertEquals((back.position, back.waiting, back.loop), (Position.NoTurn, 1, None))
    assert(waitsForPerson(back) && held(back), back.toString)
    assert(!goalCheckDue(back))
    // A message of another sender waits too, and a message of the person starts the turn that reads both.
    val more = commitFrom(back, Command.Send(RequestId("job:1"), "The job ended.", Map.empty), Source.Harness)
    assertEquals(more.position, Position.NoTurn)
    val person = commitFrom(more, Command.Send(RequestId("p"), "go on", Map.empty), Source.User)
    assertEquals((person.position, person.afterLoop), (Position.BeforeStep(0), false))
    // The person gives the loop again, and the message that waits starts its turn.
    assertEquals(
      decide(Command.StartLoop(5), back, Source.User),
      Right(Vector(Entry.GoalLoopStarted(5), Entry.TurnStarted))
    )
    // With no loop, a restart starts the turn of a message that waits, as before.
    decide(Command.Activate(versions, goalConfig()), crashed.copy(loop = None)) match
      case Right(entries) => assertEquals(entries.lastOption, Some(Entry.TurnStarted))
      case Left(error)    => fail(error.toString)
  }

  test("Loop: a grant of the person with no pending call frees a message that waits, and starts its turn") {
    // The random runs of 2026-11-17 found it: the grant sets personWaits, so held frees the message, and no turn read it.
    val low   = turns(started(withGoal, 2), 3, other)
    val ended = commitFrom(low, Command.StopLoop, Source.User)
    assert(waitsForPerson(ended))
    val rule = GuardRule("exec", ArgMatch.AnyArgs)
    assertEquals(
      decide(Command.Grant(RequestId("g1"), rule, GrantLifetime.Once), ended, Source.User),
      Right(Vector(Entry.Granted(RequestId("g1"), rule, GrantLifetime.Once), Entry.TurnStarted))
    )
    // The limit of 8 holds a message in the same way.
    val held8 = commitFrom(turns(withGoal, 8, other), Command.Send(RequestId("x"), "more", Map.empty), other)
    assertEquals(
      decide(Command.Grant(RequestId("g2"), rule, GrantLifetime.Once), held8, Source.User),
      Right(Vector(Entry.Granted(RequestId("g2"), rule, GrantLifetime.Once), Entry.TurnStarted))
    )
    // With no message that waits, a grant starts no turn.
    assertEquals(
      decide(Command.Grant(RequestId("g3"), rule, GrantLifetime.Once), withGoal, Source.User),
      Right(Vector(Entry.Granted(RequestId("g3"), rule, GrantLifetime.Once)))
    )
  }

  test("Loop: StartLoop frees a message that the old limit held, and starts its turn") {
    val busy  = turns(withGoal, 8, other)
    val held8 = commitFrom(busy, Command.Send(RequestId("x"), "more", Map.empty), other)
    assertEquals((held8.position, held8.waiting), (Position.NoTurn, 1))
    assertEquals(
      decide(Command.StartLoop(20), held8, Source.User),
      Right(Vector(Entry.GoalLoopStarted(20), Entry.TurnStarted))
    )
  }

  test("Loop: a stop of the person ends the loop with Stopped, also with no turn, and a stop of the harness keeps it") {
    val on     = started(withGoal, 10)
    val inTurn = commitFrom(on, Command.Send(RequestId("m"), "work", Map.empty), Source.User)
    assertEquals(
      decide(Command.Abort("stop"), inTurn, Source.User),
      Right(Vector(Entry.StopRequested("stop"), Entry.GoalLoopEnded(LoopEnd.Stopped)))
    )
    assertEquals(decide(Command.Abort("stop"), inTurn, Source.Harness), Right(Vector(Entry.StopRequested("stop"))))
    assertEquals(decide(Command.Abort("stop"), on, Source.User), Right(Vector(Entry.GoalLoopEnded(LoopEnd.Stopped))))
    assertEquals(decide(Command.Abort("stop"), on, Source.Harness), Right(Vector.empty))
    assertEquals(decide(Command.Abort("stop"), withGoal, Source.User), Right(Vector.empty))
  }

  test("Loop: StopLoop from each sender ends the loop with Off, and gives nothing with no loop") {
    val on = started(withGoal, 10)
    for sender <- Vector(Source.User, Source.Controller, Source.Harness, other) do
      assertEquals(decide(Command.StopLoop, on, sender), Right(Vector(Entry.GoalLoopEnded(LoopEnd.Off))))
    assertEquals(decide(Command.StopLoop, withGoal, Source.User), Right(Vector.empty))
    assertEquals(decide(Command.StopLoop, ended, Source.User), Right(Vector.empty))
    val off = commitFrom(on, Command.StopLoop, Source.User)
    assertEquals((off.loop, off.loopEnd), (None, Some(LoopEnd.Off)))
  }

  test("Loop: GoalChanged puts the goal in the configuration, and the same goal gives no entry") {
    assertEquals(decide(Command.ChangeGoal("Prove it"), noGoal), Right(Vector(Entry.GoalChanged("Prove it"))))
    val changed = run(noGoal, Command.ChangeGoal("Prove it"))
    assertEquals(changed.config.goal, Some("Prove it"))
    assertEquals(decide(Command.ChangeGoal("Prove it"), changed), Right(Vector.empty))
    assertEquals(decide(Command.ChangeGoal("Prove it"), ended), Left(Rejection.SessionClosed))
    val inTurn = state(journal(turnEntries()*))
    assertEquals(decide(Command.ChangeGoal("Prove it"), inTurn), Right(Vector(Entry.GoalChanged("Prove it"))))
  }

  test("Loop: only the commands give the entries of the loop, so a Commit of them is refused") {
    val inTurn = started(state(journal(turnEntries(goalConfig())*)), 10)
    for entry <- Vector(
                   Entry.GoalChanged("x"),
                   Entry.GoalLoopStarted(3),
                   Entry.GoalChecked(check(met = true)),
                   Entry.GoalLoopEnded(LoopEnd.Off)
                 )
    do assert(decide(Command.Commit(Vector(entry)), inTurn).isLeft, entry.toString)
  }

  test("Loop: loopWords gives on with the turns used and N, else the last end, or off") {
    assertEquals(loopWords(withGoal), "off")
    val on = started(noGoal, 50)
    assertEquals(loopWords(turns(on, 12, other)), "on 12/50")
    assertEquals(loopWords(commitFrom(on, Command.StopLoop, Source.User)), "off")
    val met = commitFrom(
      endTurn(commitFrom(started(withGoal, 5), Command.Send(RequestId("m"), "go", Map.empty), Source.User)),
      Command.RecordGoalCheck(check(met = true)),
      Source.Harness
    )
    assertEquals(loopWords(met), "met")
    val ends =
      Vector(LoopEnd.Met("r"), LoopEnd.Blocked("b"), LoopEnd.Limit, LoopEnd.Off, LoopEnd.Stopped, LoopEnd.Restarted)
    assertEquals(ends.map(_.words).toSet.size, ends.size)
  }

  test("Loop: the random runs have goal messages and each end of a loop") {
    // So the laws of the random runs also see the goal loop.
    val runs    = (0L until 400L).flatMap(seed => Schedules.schedules(Gen.Parameters.default, Seed(seed)))
    val entries = runs.flatMap(_.journal)
    val ends    = entries.collect { case Logged(_, _, Entry.GoalLoopEnded(end)) => end.words }.toSet
    assertEquals(ends, Set("met", "blocked", "limit", "off", "stopped", "restarted"))
    val messages = entries.collect {
      case Logged(_, Meta(_, Source.Harness, _, _), Entry.Input(request, _, _)) if request.value.startsWith("goal:") =>
        1
    }
    assert(messages.nonEmpty)
  }

  // ── Goal check ──

  /** A session with a goal and a loop of `limit`, after one turn of the person: the check is due. */
  private def due(limit: Int = 10): SessionState =
    endTurn(commitFrom(started(withGoal, limit), Command.Send(RequestId("m"), "go", Map.empty), Source.User))

  test("Goal check: next gives CheckGoal after a turn with a loop and a goal, and Wait with no loop or no goal") {
    assertEquals(next(due()), Next.CheckGoal(goal, ""))
    assertEquals(next(started(withGoal, 10)), Next.CheckGoal(goal, ""))
    assertEquals(next(withGoal), Next.Wait)
    assertEquals(next(started(noGoal, 10)), Next.Wait)
    assertEquals(
      next(endTurn(commitFrom(started(noGoal, 10), Command.Send(RequestId("m"), "go", Map.empty), other))),
      Next.Wait
    )
    assert(goalCheckDue(due()))
    assert(!goalCheckDue(withGoal))
  }

  test("Goal check: no check in a turn, with a pending call, or while a message can start a turn") {
    val on     = started(withGoal, 10)
    val inTurn = commitFrom(on, Command.Send(RequestId("m"), "go", Map.empty), Source.User)
    assert(!goalCheckDue(inTurn))
    assertEquals(next(inTurn), Next.CallPolicy(0, mustAnswer = false))
    val pending = due().copy(pending = Set(GuardRule("exec", ArgMatch.AnyArgs)))
    assert(!goalCheckDue(pending))
    assertEquals(next(pending), Next.Wait)
    assert(!goalCheckDue(due().copy(waiting = 1)))
    assert(!goalCheckDue(due().copy(releasing = true)))
  }

  test("Goal check: a check with not met gives the goal message from the harness, which starts a turn") {
    val s       = due()
    val c       = check(met = false, blocker = "a test fails")
    val request = RequestId(s"goal:${s.nextSeq}")
    assertEquals(
      decide(Command.RecordGoalCheck(c), s, Source.Harness),
      Right(Vector(Entry.GoalChecked(c), Entry.Input(request, goalText(goal, c), Map.empty), Entry.TurnStarted))
    )
    val after = commitFrom(s, Command.RecordGoalCheck(c), Source.Harness)
    assertEquals(after.position, Position.BeforeStep(0))
    assertEquals(after.unattended, 1)
    assertEquals(after.loop.flatMap(_.last), Some(c))
    assertEquals(
      sourceOf(Command.RecordGoalCheck(c), Source.Harness, Entry.Input(request, "", Map.empty)),
      Source.Harness
    )
  }

  test("Goal check: once after each TurnEnded, and a check with met ends the loop with Met") {
    val after = commitFrom(due(), Command.RecordGoalCheck(check(met = false)), Source.Harness)
    assert(!goalCheckDue(after))
    val done = endTurn(after)
    assert(goalCheckDue(done))
    assertEquals(
      decide(Command.RecordGoalCheck(check(met = true)), done, Source.Harness),
      Right(Vector(Entry.GoalChecked(check(met = true)), Entry.GoalLoopEnded(LoopEnd.Met("the build passes"))))
    )
    val met = commitFrom(done, Command.RecordGoalCheck(check(met = true)), Source.Harness)
    assertEquals((met.loop, met.position, next(met)), (None, Position.NoTurn, Next.Wait))
  }

  test("Goal check: at the limit, a check with not met ends the loop with Limit and sends no message") {
    val first  = commitFrom(due(limit = 2), Command.RecordGoalCheck(check(met = false)), Source.Harness)
    val second = endTurn(commitFrom(endTurn(first), Command.RecordGoalCheck(check(met = false)), Source.Harness))
    assertEquals(second.unattended, 2)
    assertEquals(
      decide(Command.RecordGoalCheck(check(met = false)), second, Source.Harness),
      Right(Vector(Entry.GoalChecked(check(met = false)), Entry.GoalLoopEnded(LoopEnd.Limit)))
    )
  }

  test("Goal check: at the limit a message that waits lets the check run, and after the end it waits for the person") {
    val held9 = turns(started(withGoal, 9), 10, other)
    assertEquals((held9.waiting, held9.position), (1, Position.NoTurn))
    assert(goalCheckDue(held9))
    assertEquals(
      decide(Command.RecordGoalCheck(check(met = false)), held9, Source.Harness),
      Right(Vector(Entry.GoalChecked(check(met = false)), Entry.GoalLoopEnded(LoopEnd.Limit)))
    )
    // With N below 8, the limit of 8 frees the message after the end, and the end still starts no turn.
    val low   = turns(started(withGoal, 2), 3, other)
    val ended = commitFrom(low, Command.RecordGoalCheck(check(met = false)), Source.Harness)
    assertEquals((ended.position, ended.loopEnd), (Position.NoTurn, Some(LoopEnd.Limit)))
    assert(waitsForPerson(ended), ended.toString)
  }

  test("Loop: an end of a loop while a message waits starts no turn, and the message waits for the person") {
    // A loop of 2 holds the third message. /goal off, a stop of the person and the limit of a session with no goal
    // end the loop, and the limit of 8 would free the message. It waits for the person.
    val low = turns(started(withGoal, 2), 3, other)
    for (command, sender) <- Vector(Command.StopLoop -> Source.User, Command.Abort("stop") -> Source.User) do
      val ended = commitFrom(low, command, sender)
      assertEquals((ended.position, ended.loop), (Position.NoTurn, None), command.toString)
      assert(waitsForPerson(ended), command.toString)
    // A session with no goal ends its loop at the end of its second turn, with no message that waits.
    val noGoal2 = turns(started(noGoal, 2), 2, other)
    assertEquals((noGoal2.loopEnd, noGoal2.afterLoop), (Some(LoopEnd.Limit), false))
    val inTurn = commitFrom(noGoal2, Command.Send(RequestId("x"), "more", Map.empty), other)
    assertEquals(inTurn.position, Position.BeforeStep(0))
    // With no message that waits, an end holds nothing, and a later message of another session starts its turn.
    val free = commitFrom(started(withGoal, 5), Command.StopLoop, Source.User)
    assert(!free.afterLoop)
    assertEquals(
      decide(Command.Send(RequestId("y"), "more", Map.empty), free, other),
      Right(Vector(Entry.Input(RequestId("y"), "more", Map.empty), Entry.TurnStarted))
    )
  }

  test("Goal check: a check after the end of its loop, or a second check, commits nothing") {
    val off = commitFrom(due(), Command.StopLoop, Source.User)
    assertEquals(decide(Command.RecordGoalCheck(check(met = false)), off, Source.Harness), Right(Vector.empty))
    val checked = commitFrom(due(), Command.RecordGoalCheck(check(met = true)), Source.Harness)
    assertEquals(decide(Command.RecordGoalCheck(check(met = false)), checked, Source.Harness), Right(Vector.empty))
  }

  test("Goal check: goalText gives the goal, the reason, the blocker if any, and Go on with the work") {
    val text = goalText(goal, check(met = false, blocker = "a test fails"))
    assert(text.contains(goal) && text.contains("the build fails") && text.contains("a test fails"), text)
    assert(text.endsWith("Go on with the work."), text)
    assert(!goalText(goal, check(met = false)).contains("blocker"), goalText(goal, check(met = false)))
  }

  // ── Blocked ──

  test("Blocked: the count is the number of checks in a row with a blocker, from the first new one") {
    def after(s: SessionState, c: GoalCheck): SessionState =
      val checked = commitFrom(s, Command.RecordGoalCheck(c), Source.Harness)
      if checked.position == Position.NoTurn then checked else endTurn(checked)
    val one = after(due(limit = 50), check(met = false, "a", same = false))
    assertEquals(one.loop.map(l => (l.blocked, l.blocker)), Some((1, "a")))
    val two = after(one, check(met = false, "a", same = true))
    assertEquals(two.loop.map(_.blocked), Some(2))
    assertEquals(after(two, check(met = false, "b", same = false)).loop.map(_.blocked), Some(1))
    assertEquals(after(two, check(met = false, "", same = false)).loop.map(_.blocked), Some(0))
    assertEquals(after(due(), check(met = false, "a", same = true)).loop.map(_.blocked), Some(1))
    assertEquals(
      decide(Command.RecordGoalCheck(check(met = false, "a", same = true)), two, Source.Harness),
      Right(Vector(Entry.GoalChecked(check(met = false, "a", same = true)), Entry.GoalLoopEnded(LoopEnd.Blocked("a"))))
    )
  }

  // ── Loop report ──

  /** A journal of a session that the controller owns: its task, a loop of 10, and the entries `rest`. */
  private def owned(rest: (Source, Entry)*): Vector[Logged] =
    journalFrom(
      (Vector(
        Source.Harness -> activated(goalConfig(Owner.Session(SessionId("controller")))),
        owner          -> Entry.GoalLoopStarted(10),
        owner          -> Entry.Input(RequestId("start:controller:1.0"), "Fix the fake build", Map.empty),
        owner          -> Entry.TurnStarted
      ) ++ rest)*
    )

  private def turnOf(outcome: TurnOutcome): Vector[(Source, Entry)] =
    Vector(
      Source.Harness -> Entry.StepStarted(0),
      Source.Policy  -> Entry.Acted("", "x", None),
      Source.Harness -> Entry.TurnEnded(outcome)
    )

  test("Loop report: while a loop is on, a TurnEnded gives no report, and the end of the loop gives exactly one") {
    val goOn = Vector(
      Source.Harness -> Entry.GoalChecked(check(met = false)),
      Source.Harness -> Entry.Input(RequestId("goal:7"), "go on", Map.empty),
      Source.Harness -> Entry.TurnStarted
    )
    val looping =
      owned((turnOf(TurnOutcome.Replied("working")) ++ goOn ++ turnOf(TurnOutcome.Submitted(Json.String("done"))))*)
    assertEquals(reports(SessionId("s1"), owner, looping), Vector.empty)
    val ended = append(
      looping,
      Vector(Entry.GoalChecked(check(met = true)), Entry.GoalLoopEnded(LoopEnd.Met("the build passes")))
    )
    val sent = reports(SessionId("s1"), owner, ended)
    assertEquals(sent.size, 1)
    assertEquals(sent.head.request, RequestId(s"report:s1:${ended.last.seq}"))
    assert(sent.head.text.startsWith(LoopEnd.Met("the build passes").report), sent.head.text)
    assert(sent.head.text.contains("The turn ended with submit: done"), sent.head.text)
  }

  test("Loop report: an end with no open debt gives its report, and an Ended while the loop is on gives one") {
    val stopped = append(owned(turnOf(TurnOutcome.Aborted)*), Vector(Entry.GoalLoopEnded(LoopEnd.Stopped)))
    assertEquals(
      reports(SessionId("s1"), owner, stopped).map(_.text.linesIterator.next()),
      Vector(LoopEnd.Stopped.report)
    )
    val closed = append(owned(turnOf(TurnOutcome.Aborted)*), Vector(Entry.Ended(SessionOutcome.Parked, "done")))
    assertEquals(reports(SessionId("s1"), owner, closed).size, 1)
  }

  test("Loop report: after the end of the loop, the rules of 8.6 hold") {
    val after = append(
      owned(turnOf(TurnOutcome.Replied("working"))*),
      Vector(Entry.GoalLoopEnded(LoopEnd.Off), Entry.TurnStarted, Entry.StepStarted(0), Entry.Acted("", "x", None)) :+
        Entry.TurnEnded(TurnOutcome.Replied("more"))
    )
    // The debt of the task is still open, so the turn after the loop gives its report.
    assertEquals(reports(SessionId("s1"), owner, after).size, 2)
  }

  // ── Pool ──

  private def startArgs(loop: Option[Int]): Json =
    Json.Object((Vector("goal" -> Json.String("review")) ++ loop.map(k => "loop" -> Json.Number(k)))*)

  private def refOf(id: String): Json = Json.Object("id" -> Json.String(id))

  /** A controller in a cell with a loop of `limit`. */
  private def inCellWith(limit: Int): SessionState =
    started(state(journal(cellEntries()*)), limit)

  test("Pool: the turns that a session gave count after the outcome of a start with a loop") {
    val s      = inCellWith(10)
    val id     = EffectId(cell, 0)
    val intent = commit(s, Vector(Entry.EffectStarted(id, "start", startArgs(Some(3)))))
    assertEquals(intent.loop.map(_.gave), Some(0))
    val done = commit(intent, Vector(Entry.EffectDone(id, EffectResult.Value(refOf("c1"), "c1"))))
    assertEquals(done.loop.map(_.gave), Some(3))
    assertEquals(loopLeft(done), 10 - done.unattended - 3)
    val failed = commit(intent, Vector(Entry.EffectDone(id, EffectResult.Error("no"))))
    assertEquals(failed.loop.map(_.gave), Some(0))
    val none = commit(
      s,
      Vector(
        Entry.EffectStarted(id, "start", startArgs(None)),
        Entry.EffectDone(id, EffectResult.Value(refOf("c1"), "c1"))
      )
    )
    assertEquals(none.loop.map(_.gave), Some(0))
  }

  test("Pool: the turns given count toward the limit, and a message of the person sets them to 0") {
    val s    = inCellWith(10)
    val id   = EffectId(cell, 0)
    val gave = commit(
      s,
      Vector(
        Entry.EffectStarted(id, "start", startArgs(Some(10))),
        Entry.EffectDone(id, EffectResult.Value(refOf("c1"), "c1"))
      )
    )
    val ended =
      commit(gave, Vector(Entry.Observed("", CellOutcome.Ok, Map.empty), Entry.TurnEnded(TurnOutcome.Replied("ok"))))
    assertEquals(loopLeft(ended), 0)
    assert(held(ended))
    val person = commitFrom(ended, Command.Send(RequestId("p"), "go on", Map.empty), Source.User)
    assertEquals(person.loop.map(_.gave), Some(0))
    assertEquals(loopLeft(person), 10)
  }

  test("Pool: loopLeft is 0 with no loop and never below 0") {
    assertEquals(loopLeft(withGoal), 0)
    val over = started(withGoal, 3).copy(unattended = 7)
    assertEquals(loopLeft(over), 0)
  }

  test("Pool: loopChildren gives each session that got turns from the current loop") {
    val c   = cell
    val log = journal(
      (cellEntries() ++ Vector(
        Entry.GoalLoopStarted(10),
        Entry.EffectStarted(EffectId(c, 0), "start", startArgs(Some(2))),
        Entry.EffectDone(EffectId(c, 0), EffectResult.Value(refOf("c1"), "c1")),
        Entry.GoalLoopEnded(LoopEnd.Off),
        Entry.GoalLoopStarted(5),
        Entry.EffectStarted(EffectId(c, 1), "start", startArgs(Some(1))),
        Entry.EffectDone(EffectId(c, 1), EffectResult.Value(refOf("c2"), "c2")),
        Entry.EffectStarted(EffectId(c, 2), "start", startArgs(Some(0))),
        Entry.EffectDone(EffectId(c, 2), EffectResult.Value(refOf("c3"), "c3")),
        Entry.EffectStarted(EffectId(c, 3), "start", startArgs(Some(2))),
        Entry.EffectDone(EffectId(c, 3), EffectResult.Error("no")),
        Entry.EffectStarted(EffectId(c, 4), "start", startArgs(Some(1))),
        Entry.EffectDone(EffectId(c, 4), EffectResult.Value(refOf("c4"), "c4")),
        Entry.GoalLoopEnded(LoopEnd.Off)
      ))*
    )
    assertEquals(loopChildren(log), Vector(SessionId("c2"), SessionId("c4")))
    assertEquals(loopChildren(journal(cellEntries()*)), Vector.empty)
  }
