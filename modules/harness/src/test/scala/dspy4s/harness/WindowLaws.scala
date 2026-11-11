package dspy4s.harness

import dspy4s.core.contracts.{DynamicValues, RuntimeContext}
import dspy4s.harness.kernel.{state as foldOf, *}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

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

  test("Window: the projection depends only on the entries, not on their times") {
    val entries = steps(Vector("a", "b", "c"))
    assertEquals(Window.project(logOf(1L, entries*), (), 10_000), Window.project(logOf(999L, entries*), (), 10_000))
  }

  property("Window: the projection fits the budget") {
    forAllNoShrink(Gen.listOf(Gen.choose(0, 5_000).map("x" * _)), Gen.choose(4_000, 20_000)) { (outputs, budget) =>
      val p = Window.project(logOf(1L, steps(outputs.toVector)*), (), budget)
      assert(p.size <= budget, s"${p.size} > $budget")
    }
  }

  test("Window: the last observation is in the projection, cut to at most half of the budget") {
    val long = "x" * 10_000
    val p    = Window.project(logOf(1L, steps(Vector("a", long))*), (), 4_000)
    assertEquals(p.observation, Window.cut(long, 2_000))
    assertEquals(Window.project(logOf(1L, steps(Vector("a", "short"))*), (), 4_000).observation, "short")
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
