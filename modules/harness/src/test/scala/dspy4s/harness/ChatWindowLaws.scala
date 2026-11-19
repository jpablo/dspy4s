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

  test("Chat window: an earlier turn with no reply shows the text of its report, with no Scala value") {
    val log = chat(1).map {
      case Logged(seq, meta, Entry.TurnEnded(_)) => Logged(seq, meta, Entry.TurnEnded(TurnOutcome.Exhausted))
      case other                                 => other
    }
    val text = shown(Window.project(log, (), 40_000))
    assert(text.contains(TurnOutcome.Exhausted.report), text)
    assert(!text.contains("Exhausted"), text)
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

  // ── A long turn (the 50-turn live pilot of 2026-11-19) ──

  // The last turn of the pilot ran 9 steps with large zoom outputs. The history keeps the newest steps that fit, so at
  // step 8 the first step, with the question of the person, went out of the prompt. The model then answered only the
  // questions 1 to 7 of 10, from the line of the question in the memory, which `MemoryLine.of` cut at 512 bytes.

  /** The question of the long turn: about 900 characters, as in the pilot, with a mark at its end. */
  private def longQuestion: String = "QUESTION-START " + ("q" * 870) + " QUESTION-END"

  /** An open turn of a chat: the message `longQuestion`, then `steps` steps, each with a cell output of `output`
    * characters, and the message `late` from the person after the cell of each step in `lateAt`. The last entry is the
    * start of the next step.
    */
  private def longTurn(steps: Int, output: Int, lateAt: Set[Int], late: Int => String): Vector[Logged] =
    val work = (0 until steps).flatMap { k =>
      Vector(
        Entry.StepStarted(k),
        Entry.Acted("", s"look $k", Some(s"println($k)")),
        Entry.Observed(s"out-$k " + ("x" * output), CellOutcome.Ok, Map.empty)
      ) ++ Option.when(lateAt.contains(k))(Entry.Input(RequestId(s"late$k"), late(k), Map.empty))
    }
    val start = Vector(
      Entry.Activated(Support.versions, Support.config(TurnKind.Chat)),
      Entry.Input(RequestId("q"), longQuestion, Map.empty),
      Entry.TurnStarted
    )
    Support.logOf(1000L, (start ++ work :+ Entry.StepStarted(steps))*)

  test("Chat window: in a long turn, the projection keeps each message of the open turn, and the newest step") {
    // 12 steps of about 3,600 characters do not fit the budget of 40,000 with the last observation.
    val budget = 40_000
    val log    = longTurn(12, 3_500, Set(6), _ => "LATE-MESSAGE of the person")
    Vector("project" -> Window.project(log, (), budget), "openTurn" -> Window.openTurn(log, budget)).foreach {
      (path, p) =>
        val text = shown(p)
        assert(p.size <= budget, s"$path: the projection has ${p.size} characters")
        assert(text.contains(longQuestion), s"$path: the question of the turn is not whole in the projection")
        assert(text.contains("LATE-MESSAGE of the person"), s"$path: the late message is not in the projection")
        assert(text.contains("look 11"), s"$path: the newest step is not in the projection")
        // The output of the first cell is only in the message of step 1, the oldest step with no message of the turn.
        assert(!text.contains("out-0 "), s"$path: the step 1 stays, so the law has no long turn")
    }
  }

  /** An open turn of a chat: the message `question`, then one step for each cell output size of `outputs`, and the
    * message `late(k)`, if any, after the cell of step `k`. The last entry is the start of the next step.
    */
  private def turnWith(question: String, outputs: Vector[Int], late: Int => Option[String]): Vector[Logged] =
    val work = outputs.indices.flatMap { k =>
      Vector(
        Entry.StepStarted(k),
        Entry.Acted("", s"look $k", Some(s"println($k)")),
        Entry.Observed(s"out-$k " + ("x" * outputs(k)), CellOutcome.Ok, Map.empty)
      ) ++ late(k).map(text => Entry.Input(RequestId(s"late$k"), text, Map.empty))
    }
    val start = Vector(
      Entry.Activated(Support.versions, Support.config(TurnKind.Chat)),
      Entry.Input(RequestId("q"), question, Map.empty),
      Entry.TurnStarted
    )
    Support.logOf(1000L, (start ++ work :+ Entry.StepStarted(outputs.size))*)

  test("Chat window: when the whole steps of a long turn do not fit, their short form keeps each message, in order") {
    // Each of the 12 steps heard a message, and the 12 whole messages of about 3,700 characters do not fit.
    val budget = 40_000
    val log    = turnWith("THE-QUESTION " + "q" * 800, Vector.fill(12)(3_500), k => Some(s"LATE-$k " + "m" * 150))
    Vector("project" -> Window.project(log, (), budget), "openTurn" -> Window.openTurn(log, budget)).foreach {
      (path, p) =>
        val text = shown(p)
        assert(p.size <= budget, s"$path: the projection has ${p.size} characters")
        assert(text.contains("THE-QUESTION " + "q" * 800), s"$path: the question is not whole in the projection")
        (0 until 12).foreach(k => assert(text.contains(s"LATE-$k "), s"$path: LATE-$k is not in the projection"))
        // The output of a cell is `out-k ` and 3,500 characters.
        assert(text.contains(Window.leftOutNote(3_506)), s"$path: no step has the short form")
        val order = "look (\\d+)".r.findAllMatchIn(text).map(_.group(1).toInt).toVector
        assertEquals(order, order.sorted, s"$path: the steps are not oldest first")
    }
  }

  test("Chat window: a step with a message that fits in no form stays out, and an older one that fits comes") {
    // The newest step takes most of the budget. The 5 late messages of 2,000 characters fit in no form, but the
    // short question of step 0 fits.
    val budget = 8_000
    val log    = turnWith(
      "SMALL-QUESTION",
      Vector.fill(6)(2_000) :+ 3_900,
      k => Option.when(k >= 1 && k <= 5)(s"LATE-$k " + "m" * 2_000)
    )
    val p    = Window.project(log, (), budget)
    val text = shown(p)
    assert(p.size <= budget, s"the projection has ${p.size} characters")
    assert(text.contains("SMALL-QUESTION"), "the question is not in the projection")
    assert(!text.contains("LATE-1 "), "LATE-1 is in the projection, so no step stayed out")
  }

  test("Chat window: when the messages of the open turn alone do not fit, the newest stay, and the projection fits") {
    // 30 late messages of about 2,000 characters each are more than the budget of 20,000.
    val budget = 20_000
    val log    = longTurn(30, 100, (0 until 30).toSet, k => s"LATE-$k " + ("m" * 2_000))
    Vector("project" -> Window.project(log, (), budget), "openTurn" -> Window.openTurn(log, budget)).foreach {
      (path, p) =>
        val text = shown(p)
        assert(p.size <= budget, s"$path: the projection has ${p.size} characters")
        assert(text.contains("LATE-29 "), s"$path: the newest message is not in the projection")
        assert(text.contains("look 29"), s"$path: the newest step is not in the projection")
    }
  }
