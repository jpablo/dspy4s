package dspy4s.harness.kernel

import munit.FunSuite

import Fixtures.*

/** The kernel laws of the controller (CODE_HARNESS_DESIGN.md, 8.6 and 8.7): the start of a session with its task, the
  * release, the turns that no person starts, and the form of a session id.
  */
class ControlLaws extends FunSuite:

  private val task  = Command.Begin(versions, config(), RequestId("start:controller:3.0"), "Fix the fake build")
  private val step  = ReleaseStep(ReleaseItem.Keep("/home/p/work", "a folder of the person"), ReleaseResult.Done)
  private def idle  = state(journal(activated()))
  private def other = Source.Child(SessionId("s2"))

  // ── Begin ──

  test("Begin: on a journal with no entry it gives Activated, the Input of the task and TurnStarted") {
    assertEquals(
      decide(task, SessionState.initial, Source.Controller),
      Right(Vector(
        Entry.Activated(versions, config()),
        Entry.Input(RequestId("start:controller:3.0"), "Fix the fake build", Map.empty),
        Entry.TurnStarted
      ))
    )
  }

  test("Begin: a journal with an entry gives NotNew, so a second start activates nothing") {
    assertEquals(decide(task, idle, Source.Controller), Left(Rejection.NotNew))
  }

  test("Begin: Activated gets the source Harness and the task the sender, so reports sees a message of the owner") {
    val begun = decide(task, SessionState.initial, Source.Controller).fold(e => fail(e.toString), identity)
    assertEquals(
      begun.map(sourceOf(task, Source.Controller, _)),
      Vector(Source.Harness, Source.Controller, Source.Controller)
    )
    val log = journalFrom(
      (begun.map(e => sourceOf(task, Source.Controller, e) -> e) ++ Vector(
        Source.Harness -> Entry.StepStarted(0),
        Source.Policy  -> Entry.Acted("", "done", None),
        Source.Harness -> Entry.TurnEnded(TurnOutcome.Replied("done"))
      ))*
    )
    assertEquals(reports(SessionId("s1"), Source.Controller, log).size, 1)
  }

  // ── Releasing ──

  private def releasing: SessionState = commit(idle, Vector(Entry.Releasing(Vector(step.item))))

  test("Releasing: it commits only with no open turn, before Ended and once, and it flushes") {
    val start  = Entry.Releasing(Vector(step.item))
    val inTurn = run(idle, Command.Send(RequestId("m1"), "work", Map.empty))
    val ended  = run(idle, Command.Close(SessionOutcome.Parked, "done"))
    assertEquals(decide(Command.Commit(Vector(start)), idle), Right(Vector(start)))
    assert(decide(Command.Commit(Vector(start)), inTurn).isLeft)
    assert(decide(Command.Commit(Vector(start)), ended).isLeft)
    assert(decide(Command.Commit(Vector(start)), releasing).isLeft)
    assert(needsFlush(start) && needsFlush(Entry.Released(step)))
  }

  test("Releasing: after it, Send and Grant give Rejection.Releasing, and Released, StopJobs and Close stay valid") {
    val rule = GuardRule("exec", ArgMatch.AnyArgs)
    assertEquals(decide(Command.Send(RequestId("m1"), "work", Map.empty), releasing), Left(Rejection.Releasing))
    assertEquals(decide(Command.Grant(RequestId("g1"), rule, GrantLifetime.Once), releasing), Left(Rejection.Releasing))
    assertEquals(decide(Command.Commit(Vector(Entry.Released(step))), releasing), Right(Vector(Entry.Released(step))))
    assert(decide(Command.StopJobs(JobSelector.All, "release"), releasing).isRight)
    assert(decide(Command.Close(SessionOutcome.Parked, "done"), releasing).isRight)
  }

  test("Releasing: a Released needs its Releasing, and an Activate of a releasing session starts no turn") {
    assert(decide(Command.Commit(Vector(Entry.Released(step))), idle).isLeft)
    val waiting = releasing.copy(waiting = 1)
    decide(Command.Activate(versions, config()), waiting) match
      case Right(entries) => assert(!entries.contains(Entry.TurnStarted), entries.toString)
      case Left(error)    => fail(error.toString)
  }

  test("Releasing: Ended closes the release, so a session that opens again after its end takes a message") {
    val ended  = run(releasing, Command.Close(SessionOutcome.Parked, "done"))
    val reopen = run(ended, Command.Activate(versions, config()))
    assert(!reopen.releasing)
    decide(Command.Send(RequestId("m1"), "work", Map.empty), reopen) match
      case Right(entries) => assert(entries.contains(Entry.TurnStarted), entries.toString)
      case Left(error)    => fail(error.toString)
  }

  // ── Unattended turns ──

  /** `n` turns that a message of `from` starts, each to its end. */
  private def turns(n: Int, from: Source): SessionState = (0 until n).foldLeft(idle) { (s, i) =>
    val started = commitFrom(s, Command.Send(RequestId(s"m$i"), "work", Map.empty), from)
    commit(
      started,
      Vector(Entry.StepStarted(0), Entry.Acted("", "done", None), Entry.TurnEnded(TurnOutcome.Replied("done")))
    )
  }

  /** The state after `command` from `sender`, with the sources that `sourceOf` gives. */
  private def commitFrom(s: SessionState, command: Command, sender: Source): SessionState =
    decide(command, s, sender).fold(e => fail(e.toString), identity).foldLeft(s) { (now, entry) =>
      evolve(
        now,
        Logged(now.nextSeq, Meta(0L, sourceOf(command, sender, entry), Taint.Trusted, required = true), entry)
      )
    }

  test("Unattended: a turn that no person starts counts, and a message of the person or a grant sets the count to 0") {
    assertEquals(turns(3, other).unattended, 3)
    assertEquals(turns(3, Source.Harness).unattended, 3)
    val person = commitFrom(turns(3, other), Command.Send(RequestId("p1"), "go on", Map.empty), Source.User)
    assertEquals(person.unattended, 0)
    val rule    = GuardRule("exec", ArgMatch.AnyArgs)
    val granted = commitFrom(turns(3, other), Command.Grant(RequestId("g1"), rule, GrantLifetime.Once), Source.User)
    assertEquals(commitFrom(granted, Command.Send(RequestId("m9"), "work", Map.empty), other).unattended, 0)
  }

  test("Unattended: at the limit, the end of a turn starts no turn for a message of another session") {
    val last = commitFrom(
      turns(SessionState.UnattendedTurns - 1, other),
      Command.Send(RequestId("t"), "work", Map.empty),
      other
    )
    assertEquals(last.unattended, SessionState.UnattendedTurns)
    val answered = commit(last, Vector(Entry.StepStarted(0), Entry.Acted("", "done", None)))
    val ended    = Vector(Entry.TurnEnded(TurnOutcome.Replied("done")))
    val fromPeer = commitFrom(answered, Command.Send(RequestId("x"), "more", Map.empty), other)
    assertEquals(decide(Command.Commit(ended), fromPeer), Right(ended))
    val fromPerson = commitFrom(answered, Command.Send(RequestId("p"), "go on", Map.empty), Source.User)
    assertEquals(decide(Command.Commit(ended), fromPerson), Right(ended :+ Entry.TurnStarted))
  }

  test("Unattended: at the limit, a message of another session waits, and a message of the person starts the turn") {
    val full = turns(SessionState.UnattendedTurns, other)
    assertEquals(full.unattended, SessionState.UnattendedTurns)
    assertEquals(
      decide(Command.Send(RequestId("x"), "more", Map.empty), full, other),
      Right(Vector(Entry.Input(RequestId("x"), "more", Map.empty)))
    )
    val waits = commitFrom(full, Command.Send(RequestId("x"), "more", Map.empty), other)
    assertEquals(waits.position, Position.NoTurn)
    decide(Command.Send(RequestId("p"), "go on", Map.empty), waits, Source.User) match
      case Right(entries) => assert(entries.contains(Entry.TurnStarted), entries.toString)
      case Left(error)    => fail(error.toString)
    // A restart does not go on with the waiting message either.
    decide(Command.Activate(versions, config()), waits) match
      case Right(entries) => assert(!entries.contains(Entry.TurnStarted), entries.toString)
      case Left(error)    => fail(error.toString)
  }

  // ── The limit of the activation ──

  /** The configuration with facts whose limit of the unattended turns is `limit`. */
  private def configWith(limit: Option[TurnLimit]): SessionConfig =
    config().copy(host = Some(hostFacts.copy(limits = hostFacts.limits.copy(unattendedTurns = limit))))

  /** `n` turns after `start` that a message of `from` starts, each to its end. */
  private def turnsFrom(start: SessionState, n: Int, from: Source): SessionState = (0 until n).foldLeft(start) {
    (s, i) =>
      val started = commitFrom(s, Command.Send(RequestId(s"m$i"), "work", Map.empty), from)
      commit(
        started,
        Vector(Entry.StepStarted(0), Entry.Acted("", "done", None), Entry.TurnEnded(TurnOutcome.Replied("done")))
      )
  }

  private def idleWith(limit: Option[TurnLimit]): SessionState = state(journal(activated(configWith(limit))))

  private def more = Command.Send(RequestId("x"), "more", Map.empty)

  private def starts(entries: Either[Rejection, Vector[Entry]]): Boolean =
    entries.fold(e => fail(e.toString), _.contains(Entry.TurnStarted))

  test("Unattended: the limit of the activation holds a message of another session after its own count of turns") {
    val two = turnsFrom(idleWith(Some(TurnLimit.AtMost(2))), 2, other)
    assertEquals(decide(more, two, other), Right(Vector(Entry.Input(RequestId("x"), "more", Map.empty))))
    assert(starts(decide(more, turnsFrom(idleWith(Some(TurnLimit.AtMost(2))), 1, other), other)))
  }

  test("Unattended: with no limit, each message of another session starts its turn, also after many turns") {
    val many = turnsFrom(idleWith(Some(TurnLimit.NoLimit)), 3 * SessionState.UnattendedTurns, other)
    assertEquals(many.unattended, 3 * SessionState.UnattendedTurns)
    assert(starts(decide(more, many, other)))
  }

  test("Unattended: an activation with no facts, or with the facts of an older host, holds after 8 turns") {
    assertEquals(SessionState.UnattendedTurns, 8)
    assertEquals(config().unattendedLimit, TurnLimit.AtMost(8))
    assertEquals(configWith(None).unattendedLimit, TurnLimit.AtMost(8))
    assertEquals(configWith(Some(TurnLimit.AtMost(3))).unattendedLimit, TurnLimit.AtMost(3))
    assertEquals(configWith(Some(TurnLimit.NoLimit)).unattendedLimit, TurnLimit.NoLimit)
    val old = turnsFrom(idleWith(None), 8, other)
    assertEquals(decide(more, old, other), Right(Vector(Entry.Input(RequestId("x"), "more", Map.empty))))
  }

  test("Unattended: a restart with a higher limit or no limit starts the turn of the waiting message") {
    val waits = commitFrom(turnsFrom(idleWith(Some(TurnLimit.AtMost(2))), 2, other), more, other)
    assert(waitsForPerson(waits))
    assert(starts(decide(Command.Activate(versions, configWith(Some(TurnLimit.AtMost(5)))), waits)))
    assert(starts(decide(Command.Activate(versions, configWith(Some(TurnLimit.NoLimit))), waits)))
    assert(!starts(decide(Command.Activate(versions, configWith(Some(TurnLimit.AtMost(2)))), waits)))
  }

  test("Unattended: waitsForPerson holds only while the limit holds a message, with no turn open") {
    val full = turns(SessionState.UnattendedTurns, other)
    assert(!waitsForPerson(full), "no message waits")
    val waits = commitFrom(full, more, other)
    assert(waitsForPerson(waits))
    assert(!waitsForPerson(commitFrom(waits, Command.Send(RequestId("p"), "go on", Map.empty), Source.User)))
    val below = commitFrom(turns(SessionState.UnattendedTurns - 1, other), more, other)
    assert(below.position != Position.NoTurn)
    assert(!waitsForPerson(below))
    assert(!waitsForPerson(waits.copy(closed = true)))
  }

  // ── The words of a refusal ──

  test("Rejection: words give each refusal in words, with no Scala value") {
    assertEquals(Rejection.SessionClosed.words, "the session has ended")
    assertEquals(Rejection.TurnOpen.words, "a turn is open")
    assertEquals(Rejection.NotNew.words, "the session has a journal already")
    assertEquals(Rejection.Releasing.words, "the session runs its release, so it takes no message")
    assertEquals(
      Rejection.OutOfOrder(Entry.StepStarted(0)).words,
      "a StepStarted entry cannot come at this place of the journal"
    )
    assertEquals(
      Rejection.OutOfOrder(Entry.EffectDone(EffectId(1L, 0), EffectResult.Unknown)).words,
      "an EffectDone entry cannot come at this place of the journal"
    )
  }

  // ── Session ids ──

  test("Id: valid takes a letter or a digit, then letters, digits, '.', '_' or '-', and refuses each other text") {
    Vector("controller", "s1", "a-b_c.d", "3f2a9c1e-0b7d-4c1e-9a8f-2b6c4d8e0f11", "x" * 128)
      .foreach(id => assert(SessionId.valid(id), id))
    Vector("", "../x", "/tmp/x", "a/b", "-x", ".x", "a..b", "my work", "x" * 129, "a\nb", "é")
      .foreach(id => assert(!SessionId.valid(id), id))
  }
