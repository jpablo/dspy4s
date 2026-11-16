package dspy4s.harness

import dspy4s.core.contracts.{DynamicValues, RuntimeContext}
import dspy4s.harness.kernel.{state as foldOf, *}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.util.Locale

import Support.*

/** The laws of the window strategy and of the status section (CODE_HARNESS_DESIGN.md, 7). A step k of the history holds
  * the observation before it, its text and its code. The output of the last cell is the observation of the projection.
  */
class WindowLaws extends ScalaCheckSuite:

  /** The entries of a turn with one step for each output: `text-k`, `code-k` and the output. */
  private def steps(outputs: Vector[String]): Vector[Entry] =
    Vector(
      Entry.Activated(versions, config(steps = 50)),
      Entry.Input(RequestId("task"), "the task", Map.empty),
      Entry.TurnStarted
    ) ++ outputs.zipWithIndex.flatMap { (output, k) =>
      Vector(
        Entry.StepStarted(k),
        Entry.Acted("", s"text-$k", Some(s"code-$k")),
        Entry.Observed(output, CellOutcome.Ok, Map.empty)
      )
    } :+ Entry.StepStarted(outputs.size)

  /** The steps that the history holds, by their marker `text-k`. */
  private def stepsIn(p: Projection): Vector[Int] =
    p.history.messages.flatMap(m => "text-(\\d+)".r.findFirstMatchIn(m.toString).map(_.group(1).toInt))

  test("Window: the projection depends on the entries and the time between them, not on the clock time") {
    // The times of the cells and of the turn are differences of times, so a shift of all the times changes nothing.
    val entries = steps(Vector("a", "b", "c"))
    assertEquals(Window.project(logOf(1L, entries*), (), 10_000), Window.project(logOf(999L, entries*), (), 10_000))
  }

  property("Window: the projection fits the budget") {
    forAllNoShrink(Gen.listOf(Gen.choose(0, 5_000).map("x" * _)), Gen.choose(4_000, 20_000)) { (outputs, budget) =>
      val p = Window.project(logOf(1L, steps(outputs.toVector)*), (), budget)
      assert(p.size <= budget, s"${p.size} > $budget")
    }
  }

  test("Window: the last observation is in the projection, cut to at most half of the budget, then its time") {
    // Each entry of `logOf` has the same time, so each cell takes 0.0 s.
    val long = "x" * 10_000
    val p    = Window.project(logOf(1L, steps(Vector("a", long))*), (), 4_000)
    assertEquals(p.observation, s"${Window.cut(long, 2_000)}\nThe cell took 0.0 s.")
    val short = Window.project(logOf(1L, steps(Vector("a", "short"))*), (), 4_000)
    assertEquals(short.observation, "short\nThe cell took 0.0 s.")
  }

  test("Window: cut keeps a short text, and cuts a long one with a note of the rest") {
    assertEquals(Window.cut("abc", 10), "abc")
    val cut = Window.cut("x" * 100, 10)
    assert(cut.startsWith("x" * 10) && !cut.startsWith("x" * 11), cut)
    assert(cut.contains("90 more characters"), cut)
  }

  test("Window: the history holds the earlier steps, oldest first, and leaves out the oldest first") {
    val p    = Window.project(logOf(1L, steps(Vector.tabulate(10)(k => s"out-$k-" + "y" * 500))*), (), 3_000)
    val kept = stepsIn(p)
    assert(kept.nonEmpty && kept.size < 10, kept.toString)
    assertEquals(kept, (10 - kept.size until 10).toVector)
  }

  test("Window: the last step stays in the history, also with a long text and a long code") {
    val entries = Vector(
      Entry.Activated(versions, config(steps = 50)),
      Entry.Input(RequestId("task"), "the task", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "text-0 " + "t" * 30_000, Some("c" * 30_000)),
      Entry.Observed("out", CellOutcome.Ok, Map.empty),
      Entry.StepStarted(1)
    )
    Vector(4_000, 40_000).foreach { budget =>
      val p = Window.project(logOf(1L, entries*), (), budget)
      assertEquals(stepsIn(p), Vector(0), s"budget $budget")
      assert(p.size <= budget, s"${p.size} > $budget")
    }
  }

  test("Window: each message of the history has an empty reasoning, so the adapter shows no None for it") {
    val p = Window.project(logOf(1L, steps(Vector("a", "b"))*), (), 10_000)
    assertEquals(p.history.messages.size, 2)
    p.history.messages.foreach { m =>
      assertEquals(DynamicValues.recordGet(m, "reasoning"), Some(DynamicValues.string("")), m.toString)
    }
  }

  test("Window: before the first cell, the observation is empty and the history has no step") {
    val p = Window.project(logOf(1L, steps(Vector.empty)*), (), 4_000)
    assertEquals(p.observation, "")
    assertEquals(p.history.messages, Vector.empty)
  }

  test("Window: update gives no derived data") {
    given RuntimeContext = RuntimeContext()
    assertEquals(Window.update(logOf(1L, steps(Vector("a"))*), ()), Right(()))
  }

  test("Status: the status gives the step, the step limit and the keys of the store") {
    val log = logOf(
      1L,
      Entry.Activated(versions, config(steps = 5)),
      Entry.Input(RequestId("task"), "the task", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "", Some("a")),
      Entry.Observed("", CellOutcome.Ok, Map("b" -> Json.String("1"), "a" -> Json.String("2"))),
      Entry.StepStarted(1),
      Entry.Acted("", "", Some("b")),
      Entry.Observed("", CellOutcome.Ok, Map.empty)
    )
    val status = Status.of(foldOf(log))
    assertEquals(status, Status(2, 5, Vector("a", "b"), TurnKind.Program))
    assert(status.render.contains("Step 3 of 5"), status.render)
    assert(status.render.contains("a, b"), status.render)
  }

  /** A log with the time of each entry in milliseconds, and `seq` from 0. */
  private def timed(entries: (Long, Entry)*): Vector[Logged] =
    entries.toVector.zipWithIndex.map { case ((at, entry), i) =>
      Logged(i.toLong, Meta(at, Source.Harness, Taint.Trusted, true), entry)
    }

  test(
    "Window: the output of each cell ends with its time, and the status gives the time of the turn and of the last " +
      "call to the model"
  ) {
    // In session 00429ec8 (2026-11-11), the model saw no times, so it gave a false reason for a slow turn. The times
    // come from the journal alone, so the projection stays a pure function of the journal (7).
    val log = timed(
      0L      -> Entry.Activated(versions, config(steps = 5)),
      0L      -> Entry.Input(RequestId("task"), "the task", Map.empty),
      1_000L  -> Entry.TurnStarted,
      1_000L  -> Entry.StepStarted(0),
      4_000L  -> Entry.Acted("", "text-0", Some("code-0")),
      6_500L  -> Entry.Observed("out-0", CellOutcome.Ok, Map.empty),
      6_500L  -> Entry.StepStarted(1),
      8_500L  -> Entry.Acted("", "text-1", Some("code-1")),
      20_700L -> Entry.Observed("out-1", CellOutcome.Ok, Map.empty),
      20_700L -> Entry.StepStarted(2)
    )
    val p = Window.project(log, (), 40_000)
    assert(p.observation.startsWith("out-1"), p.observation)
    assert(p.observation.endsWith("The cell took 12.2 s."), p.observation)
    // The message of step 1 holds the output of step 0, with its time.
    assert(p.history.messages.exists(_.toString.contains("The cell took 2.5 s.")), p.history.messages.toString)
    // The call to the model of step 1 took 2.0 s, and the turn started 19.7 s before the last entry.
    assert(p.status.contains("The turn has run for 19.7 s, and the last call to the model took 2.0 s."), p.status)
    // Before the first step, the turn has no call to the model, so the status gives no time.
    val first = Window.project(log.take(4), (), 40_000)
    assert(!first.status.contains(" s."), first.status)
  }

  /** A turn of the kind `kind` with two cells and the outputs `first` and `second`. The first cell takes 2.5 s, the
    * call to the model of step 1 takes 2.0 s, and the second cell takes 12.2 s. The last entry comes 19.7 s after the
    * start of the turn.
    */
  private def twoCells(kind: TurnKind, first: String, second: String): Vector[(Long, Entry)] =
    Vector(
      0L      -> Entry.Activated(versions, config(kind, steps = 5)),
      0L      -> Entry.Input(RequestId("task"), "the task", Map.empty),
      1_000L  -> Entry.TurnStarted,
      1_000L  -> Entry.StepStarted(0),
      4_000L  -> Entry.Acted("", "text-0", Some("code-0")),
      6_500L  -> Entry.Observed(first, CellOutcome.Ok, Map.empty),
      6_500L  -> Entry.StepStarted(1),
      8_500L  -> Entry.Acted("", "text-1", Some("code-1")),
      20_700L -> Entry.Observed(second, CellOutcome.Ok, Map.empty),
      20_700L -> Entry.StepStarted(2)
    )

  /** The times of `twoCells` in the status. */
  private def twoCellsTimes: String = "The turn has run for 19.7 s, and the last call to the model took 2.0 s."

  /** The field `observation` of each message of the history of `p`, oldest first. */
  private def seenIn(p: Projection): Vector[String] =
    p.history.messages.flatMap(m => DynamicValues.recordGet(m, "observation").flatMap(DynamicValues.asString))

  /** The text of a projection: the status, the observation and each message of the history. */
  private def textOf(p: Projection): String =
    (p.status +: p.observation +: p.history.messages.map(_.toString)).mkString("\n")

  test("Window: a cut output keeps its time note, in the observation and in the message of the history") {
    val long = "x" * 10_000
    Vector(TurnKind.Program, TurnKind.Chat).foreach { kind =>
      val p = Window.project(timed(twoCells(kind, long, long)*), (), 8_000)
      assertEquals(p.observation, s"${Window.cut(long, 4_000)}\nThe cell took 12.2 s.", kind.toString)
      assertEquals(seenIn(p).lastOption, Some(s"${Window.cut(long, 1_000)}\nThe cell took 2.5 s."), kind.toString)
    }
  }

  test("Window: a cell with an empty output gives only its time note") {
    val p = Window.project(timed(twoCells(TurnKind.Program, "", "")*), (), 40_000)
    assertEquals(p.observation, "The cell took 12.2 s.")
    assertEquals(seenIn(p).lastOption, Some("The cell took 2.5 s."))
  }

  test("Window: a cell with no Observed, for example after a stop, has no time note") {
    Vector(TurnKind.Program, TurnKind.Chat).foreach { kind =>
      val stopped = twoCells(kind, "out-0", "out-1").take(8) :+ (9_000L -> Entry.StopRequested("FAKE-STOP"))
      val log     = timed(stopped*)
      val text    = textOf(Window.project(log, (), 40_000))
      // Only the cell of step 0 has an `Observed`, so each note in the projection is the note of that cell.
      assertEquals("The cell took [0-9.-]+ s\\.".r.findAllIn(text).toSet, Set("The cell took 2.5 s."), text)
    }
  }

  test("Window: a chat and a program call give the same times in the status and in the outputs") {
    val program = Window.project(timed(twoCells(TurnKind.Program, "out-0", "out-1")*), (), 40_000)
    val chat    = Window.project(timed(twoCells(TurnKind.Chat, "out-0", "out-1")*), (), 40_000)
    assert(program.status.endsWith(twoCellsTimes), program.status)
    assert(chat.status.endsWith(twoCellsTimes), chat.status)
    assertEquals(chat.observation, "out-1\nThe cell took 12.2 s.")
    assertEquals(program.observation, chat.observation)
    assertEquals(seenIn(chat).lastOption, Some("out-0\nThe cell took 2.5 s."))
    assertEquals(seenIn(program).lastOption, seenIn(chat).lastOption)
    // The memory tree projects the open turn with `openTurn`, so it gets the same times.
    assertEquals(Window.openTurn(timed(twoCells(TurnKind.Chat, "out-0", "out-1")*), 40_000).status, chat.status)
  }

  // In a live run of 2026-11-16, the person asked "What happens to the request now?" while a start waited for an
  // approval. The message refused the call, but the model said that the call still waited, and told the person to type
  // /allow, which then said that no call waits.
  test("Window: the first step after a message that refused a waiting call names the call, and a grant gives no note") {
    val rule  = GuardRule("start", ArgMatch.Exact(Json.Object(zio.blocks.chunk.Chunk(("kind", Json.String("Dir"))))))
    val asked = Vector(
      Source.Harness -> Entry.Activated(versions, config(TurnKind.Chat, steps = 5)),
      Source.User    -> Entry.Input(RequestId("m1"), "start it", Map.empty),
      Source.Harness -> Entry.TurnStarted,
      Source.Harness -> Entry.StepStarted(0),
      Source.Policy  -> Entry.Acted("", "I start it.", Some("start()")),
      Source.Harness -> Entry.EffectStarted(EffectId(4, 0), "start", Json.Object(zio.blocks.chunk.Chunk.empty)),
      Source.Harness -> Entry.EffectDone(EffectId(4, 0), EffectResult.NeedsApproval(rule)),
      Source.Harness -> Entry.Observed("FAKE-NEEDS-APPROVAL", CellOutcome.Failed("NeedsApproval"), Map.empty),
      Source.Harness -> Entry.StepStarted(1),
      Source.Policy  -> Entry.Acted("", "Type /allow.", None),
      Source.Harness -> Entry.TurnEnded(TurnOutcome.Replied("Type /allow."))
    )
    def logOf(entries: Vector[(Source, Entry)]): Vector[Logged] =
      entries.zipWithIndex.map { case ((source, entry), i) =>
        Logged(i.toLong, Meta(0L, source, Taint.Trusted, true), entry)
      }
    val refused = logOf(
      asked ++ Vector(
        Source.User    -> Entry.Input(RequestId("m2"), "What happens to the request now?", Map.empty),
        Source.Harness -> Entry.TurnStarted,
        Source.Harness -> Entry.StepStarted(0)
      )
    )
    val note = Window.project(refused, (), 40_000).observation
    assert(note.contains("refused") && note.contains("start"), note)
    // The memory tree projects the open turn with `openTurn`, so it gets the note too.
    val open = Window.openTurn(refused, 40_000).observation
    assert(open.contains("refused") && open.contains("start"), open)
    // Only the first step of the turn has the note.
    val second = logOf(
      asked ++ Vector(
        Source.User    -> Entry.Input(RequestId("m2"), "What happens to the request now?", Map.empty),
        Source.Harness -> Entry.TurnStarted,
        Source.Harness -> Entry.StepStarted(0),
        Source.Policy  -> Entry.Acted("", "I look.", Some("1")),
        Source.Harness -> Entry.Observed("1", CellOutcome.Ok, Map.empty),
        Source.Harness -> Entry.StepStarted(1)
      )
    )
    val later = Window.project(second, (), 40_000)
    assert(!later.observation.contains("refused"), later.observation)
    assert(!later.history.messages.map(_.toString).exists(_.contains("refused")), later.history.toString)
    val granted = logOf(
      asked ++ Vector(
        Source.User    -> Entry.Granted(RequestId("g1"), rule, GrantLifetime.Once),
        Source.User    -> Entry.Input(RequestId("g1"), "The person approved a call of `start`.", Map.empty),
        Source.Harness -> Entry.TurnStarted,
        Source.Harness -> Entry.StepStarted(0)
      )
    )
    val after = Window.project(granted, (), 40_000).observation
    assert(!after.contains("refused"), after)
  }

  test("Window: in a chat, the time note comes right after the output, before the messages that came during the cell") {
    val log = timed(
      0L     -> Entry.Activated(versions, config(TurnKind.Chat, steps = 5)),
      0L     -> Entry.Input(RequestId("m1"), "FAKE-QUESTION", Map.empty),
      1_000L -> Entry.TurnStarted,
      1_000L -> Entry.StepStarted(0),
      4_000L -> Entry.Acted("", "text-0", Some("code-0")),
      5_000L -> Entry.Input(RequestId("m2"), "FAKE-MESSAGE", Map.empty),
      6_500L -> Entry.Observed("out-0", CellOutcome.Ok, Map.empty),
      6_500L -> Entry.StepStarted(1)
    )
    val p = Window.project(log, (), 40_000)
    assertEquals(p.observation, "out-0\nThe cell took 2.5 s.\nThe harness wrote: FAKE-MESSAGE")
  }

  test("Status: in a chat, the times are of the open turn, and a new turn has no time before its first call") {
    val ended = twoCells(TurnKind.Chat, "out-0", "out-1") ++ Vector(
      21_000L -> Entry.Acted("", "FAKE-REPLY", None),
      21_000L -> Entry.TurnEnded(TurnOutcome.Replied("FAKE-REPLY")),
      30_000L -> Entry.Input(RequestId("m2"), "FAKE-SECOND", Map.empty),
      30_000L -> Entry.TurnStarted,
      30_000L -> Entry.StepStarted(0)
    )
    val before = Window.project(timed(ended*), (), 40_000).status
    assert(!before.contains(" s."), before)
    val acted = ended ++ Vector(
      33_000L -> Entry.Acted("", "text-2", Some("code-2")),
      34_000L -> Entry.Observed("out-2", CellOutcome.Ok, Map.empty),
      34_000L -> Entry.StepStarted(1)
    )
    val after = Window.project(timed(acted*), (), 40_000).status
    assert(after.endsWith("The turn has run for 4.0 s, and the last call to the model took 3.0 s."), after)
  }

  test("Status: the times have one decimal and a dot, also in a locale with a decimal comma") {
    val original = Locale.getDefault
    Locale.setDefault(Locale.GERMANY)
    try
      val p = Window.project(timed(twoCells(TurnKind.Program, "out-0", "out-1")*), (), 40_000)
      assert(p.observation.endsWith("The cell took 12.2 s."), p.observation)
      assert(p.status.endsWith(twoCellsTimes), p.status)
    finally Locale.setDefault(original)
  }

  test("Status: a negative time, for example after a change of the clock, is 0.0 s") {
    assertEquals(Window.cellNote(-1_000L), "The cell took 0.0 s.")
    assertEquals(
      TurnTimes(-1_000L, -1L).render,
      "The turn has run for 0.0 s, and the last call to the model took 0.0 s."
    )
  }

  /** The status text before the step `step` of a turn of the kind `kind` with the step limit 10. */
  private def statusAt(kind: TurnKind, step: Int): String =
    val base = SessionState.initial
    Status.of(base.copy(
      config = base.config.copy(kind = kind, steps = 10),
      position = Position.BeforeStep(step)
    )).render

  test("Status: in a chat, the last step that can run a cell says that the next step only answers") {
    // In a live session of 2026-11-10, "Step 10 of 10" made the model answer one step early, with no code.
    val last = statusAt(TurnKind.Chat, 9)
    assert(last.contains("Step 10 of 10"), last)
    assert(last.contains("The next step only answers, with no code."), last)
    val earlier = statusAt(TurnKind.Chat, 5)
    assert(!earlier.contains("The next step only answers"), earlier)
    assert(statusAt(TurnKind.Chat, 10).contains("answer with no code"), statusAt(TurnKind.Chat, 10))
    // A program call has no step that only answers, so its last step has no such text.
    val program = statusAt(TurnKind.Program, 9)
    assert(!program.contains("The next step only answers"), program)
  }
