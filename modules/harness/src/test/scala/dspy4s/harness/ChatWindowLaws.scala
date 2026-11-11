package dspy4s.harness

import dspy4s.harness.kernel.*

/** The laws of the window strategy for a chat (CODE_HARNESS_DESIGN.md, 7, phase 7): a step also sees the earlier turns
  * of the session.
  */
class ChatWindowLaws extends munit.FunSuite:

  /** A chat with `n` turns that ended, each with the message `question <k>` and the reply `reply <k>`, and an open turn
    * with the message `open-question` before its first step.
    */
  private def chat(n: Int): Vector[Logged] =
    val turns = (1 to n).flatMap { k =>
      Vector(
        Entry.Input(RequestId(s"m$k"), s"question <$k>", Map.empty),
        Entry.TurnStarted,
        Entry.StepStarted(0),
        Entry.Acted("", s"reply <$k>", None),
        Entry.TurnEnded(TurnOutcome.Replied(s"reply <$k>"))
      )
    }
    val open = Vector(Entry.Input(RequestId("open"), "open-question", Map.empty), Entry.TurnStarted)
    Support.logOf(1000L, (Entry.Activated(Support.versions, Support.config(TurnKind.Chat)) +: turns ++: open)*)

  /** The text of a projection. */
  private def shown(p: Projection): String =
    val parts = p.status +: p.observation +: p.history.messages.map(_.toString)
    parts.mkString("\n")

  test("Chat window: a step sees each earlier turn as its message and its reply, and the message of the open turn") {
    val text = shown(Window.project(chat(2), (), 40_000))
    Vector("question <1>", "reply <1>", "question <2>", "reply <2>", "open-question").foreach { part =>
      assert(text.contains(part), s"$part is not in the projection:\n$text")
    }
    assert(text.indexOf("question <1>") < text.indexOf("question <2>"), "the earlier turns are not oldest first")
  }

  test("Chat window: with a small budget, the oldest turns go first, the newest stay, and the projection fits") {
    // 200 turns hold about 4,500 characters, so a budget of 2,000 keeps only some of them.
    val budget = 2_000
    val p      = Window.project(chat(200), (), budget)
    val text   = shown(p)
    assert(p.size <= budget, s"the projection has ${p.size} characters")
    assert(text.contains("open-question"), text)
    val kept = (1 to 200).filter(k => text.contains(s"question <$k>"))
    assert(kept.nonEmpty && kept.size < 200, s"the window kept ${kept.size} turns")
    assertEquals(kept, kept.min to 200, "the kept turns are not the newest ones")
  }

  test("Chat window: a message of another session names that session, so the controller can answer it (8.7)") {
    val log = chat(0).map {
      case Logged(seq, meta, e @ Entry.Input(_, _, _)) =>
        Logged(seq, meta.copy(source = Source.Child(SessionId("s7"))), e)
      case other => other
    }
    val text = shown(Window.project(log, (), 40_000))
    assert(text.contains("The session s7"), text)
  }

  test("Chat window: a projection of one open turn has no earlier turn") {
    val text = shown(Window.project(chat(0), (), 40_000))
    assert(!text.contains("reply <"), text)
  }

  test("Chat window: a message after the last step of a turn belongs to the next turn, not to the turn that ended") {
    val log = Support.logOf(
      1000L,
      Entry.Activated(Support.versions, Support.config(TurnKind.Chat)),
      Entry.Input(RequestId("m1"), "FIRST-QUESTION", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      // The person types again while the model writes its first reply.
      Entry.Input(RequestId("m2"), "SECOND-QUESTION", Map.empty),
      Entry.Acted("", "FIRST-REPLY", None),
      Entry.TurnEnded(TurnOutcome.Replied("FIRST-REPLY")),
      Entry.TurnStarted,
      Entry.StepStarted(0)
    )
    val p     = Window.project(log, (), 40_000)
    val first = p.history.messages.map(_.toString).find(_.contains("FIRST-REPLY")).getOrElse(fail("no first turn"))
    assert(!first.contains("SECOND-QUESTION"), first)
    assert(p.observation.contains("SECOND-QUESTION"), p.observation)
  }
