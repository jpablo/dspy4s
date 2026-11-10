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

  test("Chat window: with a small budget, the oldest turns go first, and the message of the open turn stays") {
    val text = shown(Window.project(chat(40), (), 2_000))
    assert(text.contains("open-question"), text)
    assert(text.contains("question <40>"), text)
    assert(!text.contains("question <1>"), text)
  }

  test("Chat window: a projection of one open turn has no earlier turn") {
    val text = shown(Window.project(chat(0), (), 40_000))
    assert(!text.contains("reply <"), text)
  }
