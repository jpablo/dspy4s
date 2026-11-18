package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.guest.{SessionCard, SessionRef}
import dspy4s.harness.kernel.*

/** The client laws of the goal loop (CODE_HARNESS_DESIGN.md, 8.5): the `/goal` lines, their commands, `/status`, a bare
  * `/goal`, `/config`, the lines of the terminal, and the card of `/sessions`.
  */
class GoalCliLaws extends munit.FunSuite:

  private val config = Support.config(TurnKind.Chat, 3).copy(owner = Owner.User, goal = Some("FAKE-GOAL"))

  private def at(seq: Int, source: Source, entry: Entry): Logged =
    Logged(seq.toLong, Meta(0L, source, Taint.Trusted, true), entry)

  /** A session with a loop of 50, after one turn of the harness and a check with a blocker. */
  private def looping: Vector[Logged] =
    Vector(
      at(0, Source.Harness, Entry.Activated(Support.versions, config)),
      at(1, Source.User, Entry.GoalLoopStarted(50)),
      at(2, Source.Harness, Entry.Input(RequestId("goal:x"), "go on", Map.empty)),
      at(3, Source.Harness, Entry.TurnStarted),
      at(4, Source.Harness, Entry.TurnEnded(TurnOutcome.Replied("working"))),
      at(5, Source.Harness, Entry.GoalChecked(GoalCheck(met = false, "FAKE-REASON", "FAKE-BLOCKER", same = false)))
    )

  test("Goal client: /goal, /goal on [N], /goal off and /goal set TEXT parse, and N must be 1 or more") {
    assertEquals(parseLine("/goal"), Line.Goal(GoalLine.Show))
    assertEquals(parseLine("/goal on"), Line.Goal(GoalLine.On(SessionState.LoopTurns)))
    assertEquals(parseLine("/goal on 200"), Line.Goal(GoalLine.On(200)))
    assertEquals(parseLine("/goal off"), Line.Goal(GoalLine.Off))
    assertEquals(parseLine("/goal set Prove the  lemma"), Line.Goal(GoalLine.Change("Prove the lemma")))
    for bad <- Vector("/goal on 0", "/goal on -2", "/goal on x", "/goal set", "/goal start") do
      assertEquals(parseLine(bad), Line.Unknown(bad), bad)
  }

  test("Goal client: each /goal line gives its command, and a bare /goal gives none") {
    val s              = state(looping)
    def of(line: Line) = commandsFor(line, s, RequestId("r"))
    assertEquals(of(Line.Goal(GoalLine.On(7))), Right(Vector(Command.StartLoop(7))))
    assertEquals(of(Line.Goal(GoalLine.Off)), Right(Vector(Command.StopLoop)))
    assertEquals(of(Line.Goal(GoalLine.Change("g"))), Right(Vector(Command.ChangeGoal("g"))))
    assertEquals(of(Line.Goal(GoalLine.Show)), Right(Vector.empty))
  }

  test("Goal client: /status and a bare /goal give the goal, the turns used of N, the last check and the blocker") {
    val s    = state(looping)
    val text = loopText(s)
    for part <- Vector(
                  "The goal: FAKE-GOAL",
                  "The goal loop is on: 1 of 50 turns with no person.",
                  "The last goal check: not met. FAKE-REASON",
                  "The blocker, 1 of 3 checks: FAKE-BLOCKER"
                )
    do assert(text.contains(part), s"$part is not in:\n$text")
    assert(statusText(s, Map.empty, Vector.empty).contains(text))
    val ended = state(looping :+ at(6, Source.User, Entry.GoalLoopEnded(LoopEnd.Off)))
    assert(loopText(ended).contains("The goal loop is off. " + LoopEnd.Off.report), loopText(ended))
    val none = state(Vector(at(0, Source.Harness, Entry.Activated(Support.versions, config.copy(goal = None)))))
    assert(!statusText(none, Map.empty, Vector.empty).contains("goal"), statusText(none, Map.empty, Vector.empty))
  }

  test("Goal client: /config gives the goal and the limit of the loop, or off") {
    val on  = configText(SessionId("s1"), state(looping), ClientFacts.unknown, ModelFacts.unknown, "")
    val off = configText(SessionId("s1"), state(looping.take(1)), ClientFacts.unknown, ModelFacts.unknown, "")
    assert(on.contains("goal: FAKE-GOAL") && on.contains("goal loop: on, at most 50 turns with no person"), on)
    assert(off.contains("goal loop: off"), off)
  }

  test("Goal client: the terminal shows the goal, the start and the end of a loop, and no line for each check") {
    assert(entryText(at(1, Source.User, Entry.GoalLoopStarted(50))).exists(_.contains("at most 50 turns")))
    assertEquals(
      entryText(at(9, Source.User, Entry.GoalLoopEnded(LoopEnd.Met("done")))),
      Some(LoopEnd.Met("done").report)
    )
    assertEquals(entryText(at(9, Source.User, Entry.GoalChanged("FAKE"))), Some("The goal of the session: FAKE"))
    assertEquals(entryText(looping(5)), None)
    val look = Look(Detail.Compact, ReasoningView.Off, Glyphs.ascii)
    val rows = (looping :+ at(6, Source.Harness, Entry.GoalLoopEnded(LoopEnd.Limit)))
      .foldLeft((Screen.empty, Vector.empty[String])) { case ((screen, out), logged) =>
        val (next, made) = Screen.after(screen, logged, look)
        (next, out ++ made.map(_.plain))
      }._2.mkString("\n")
    assert(rows.contains("at most 50 turns") && rows.contains(LoopEnd.Limit.report), rows)
    assert(!rows.contains("FAKE-REASON"), rows)
  }

  test("Goal client: the card line of /sessions gives a loop that runs or ended, and nothing for off") {
    def card(loop: String) =
      SessionCard(
        SessionRef("s2"),
        "FAKE-GOAL",
        None,
        "the person",
        None,
        "idle",
        4L,
        0,
        Vector.empty,
        0,
        None,
        None,
        loop
      )
    assert(cardText(card("on 12/50")).contains("goal loop on 12/50"), cardText(card("on 12/50")))
    assert(cardText(card("met")).contains("goal loop met"))
    assert(!cardText(card("off")).contains("goal loop"))
  }
