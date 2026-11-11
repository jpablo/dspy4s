package dspy4s.harness.cli

import dspy4s.harness.host.Activity
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

/** The laws of what runs, for the busy prompt of the client (CODE_HARNESS_DESIGN.md, 10.6). A live session of
  * 2026-11-11 showed `$ "ls -la"` and then `> ` for many seconds, so the person could not see that the session worked.
  */
class BusyLaws extends munit.FunSuite:

  private def at(seq: Long, millis: Long, entry: Entry): Logged =
    Logged(seq, Meta(millis, Source.Harness, Taint.Trusted, true), entry)

  private def command(op: String, text: String): Entry =
    Entry.EffectStarted(EffectId(1L, 0), op, Json.Object(Chunk(("command", Json.String(text)))))

  private val Frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".toSet

  test("CLI busy: the state follows the entries of a turn, and the end of the turn clears it") {
    val log = Vector(
      at(1, 1000, Entry.TurnStarted),
      at(2, 1100, Entry.StepStarted(0)),
      at(3, 2000, Entry.Acted("", "Let me look.", Some("exec(\"ls -la\")"))),
      at(4, 2100, command("exec", "ls -la")),
      at(5, 9000, Entry.EffectDone(EffectId(1L, 0), EffectResult.Value(Json.String("a"), "a"))),
      at(6, 9100, Entry.Observed("a", CellOutcome.Ok, Map.empty)),
      at(7, 9200, Entry.StepStarted(1)),
      at(8, 12000, Entry.Acted("", "Done.", None)),
      at(9, 12100, Entry.TurnEnded(TurnOutcome.Replied("Done.")))
    )
    val states = log.scanLeft(Option.empty[Busy])(Busy.after).tail
    assertEquals(
      states,
      Vector(
        Some(Busy("the turn starts", 1000, 0)),
        Some(Busy("step 1: the model works", 1100, 0)),
        Some(Busy("step 1: the cell runs", 2000, 0)),
        Some(Busy("step 1: exec \"ls -la\"", 2100, 0, command = true)),
        Some(Busy("step 1: the cell runs", 9000, 0)),
        Some(Busy("step 1: the cell ended", 9100, 0)),
        Some(Busy("step 2: the model works", 9200, 1)),
        Some(Busy("step 2: the model works", 9200, 1)),
        None
      )
    )
  }

  test("CLI busy: a spawn shows its command, another call shows its op, and a stop shows that the turn stops") {
    val step = Some(Busy("step 3: the cell runs", 500, 2))
    val read = Entry.EffectStarted(EffectId(1L, 1), "read", Json.Object(Chunk(("path", Json.String("a.txt")))))
    assertEquals(
      Busy.after(step, at(1, 600, command("spawn", "sleep 30"))),
      Some(Busy("step 3: spawn \"sleep 30\"", 600, 2, command = true))
    )
    assertEquals(Busy.after(step, at(1, 700, read)), Some(Busy("step 3: read", 700, 2)))
    assertEquals(Busy.after(step, at(1, 800, Entry.StopRequested("the person"))), Some(Busy("the turn stops", 800, 2)))
    assertEquals(Busy.after(None, at(1, 900, Entry.Input(RequestId("r1"), "hello", Map.empty))), None)
  }

  test("CLI busy: the label of a long command is its start, in quotes, on one line") {
    val long  = "find . -name '*.scala'\n" + ("x" * 300)
    val label = Busy.after(None, at(1, 0, command("exec", long))).map(_.label).getOrElse(fail("no state"))
    assert(label.startsWith("step 1: exec \"find . -name '*.scala'\\n"), label)
    assert(!label.contains("\n"), label)
    assert(label.length < 120, label)
  }

  test("CLI busy: the prompt shows a frame of the spinner and the time since the start, and an idle prompt is > ") {
    val busy = Some(Busy("step 1: the model works", 1000, 0))
    assertEquals(Busy.prompt(None, 5000), "> ")
    val prompt = Busy.prompt(busy, 13_500)
    assert(Frames.contains(prompt.head), prompt)
    assertEquals(prompt.tail, " 12 s > ")
    assert(Busy.prompt(busy, 1000 + 75_000).endsWith(" 1 m 15 s > "), Busy.prompt(busy, 76_000))
    assert(Busy.prompt(busy, 500).endsWith(" 0 s > "), Busy.prompt(busy, 500))
    val frames = (0 until 10).map(i => Busy.prompt(busy, 13_000L + i * 125L).head).toSet
    assertEquals(frames.size, 10, frames.toString)
  }

  test("CLI busy: the right prompt is the label, cut to the width with no surrogate pair cut in two") {
    assertEquals(Busy.rightPrompt(None, None, 80), "")
    assertEquals(Busy.rightPrompt(Some(Busy("step 1: the model works", 0, 0)), None, 80), "step 1: the model works")
    val label = ("a" * 18) + "😀" + ("b" * 30)
    val cut   = Busy.rightPrompt(Some(Busy(label, 0, 0)), None, 20)
    assert(cut.length <= 20, cut)
    assert(cut.endsWith("…"), cut)
    assert(!cut.exists(c => c.isHighSurrogate || c.isLowSurrogate), cut)
  }

  test("CLI busy: during a command the right prompt adds the walk for secret files, and the label gives it room") {
    val exec  = Some(Busy("step 1: exec \"ls -la\"", 0, 0, command = true))
    val first = Some(Activity.SecretScan(640_000L, None))
    val again = Some(Activity.SecretScan(120_000L, Some(300_000L)))
    assertEquals(
      Busy.rightPrompt(exec, first, 120),
      "step 1: exec \"ls -la\" · the scan for secret files: 640,000 folders"
    )
    assertEquals(
      Busy.rightPrompt(exec, again, 120),
      "step 1: exec \"ls -la\" · the scan for secret files: 120,000 of 300,000 folders"
    )
    assertEquals(Busy.rightPrompt(Some(Busy("step 1: the model works", 0, 0)), first, 120), "step 1: the model works")
    val narrow = Busy.rightPrompt(exec, first, 60)
    assert(narrow.length <= 60, narrow)
    assert(narrow.startsWith("step 1") && narrow.contains("…"), narrow)
    assert(narrow.endsWith(" · the scan for secret files: 640,000 folders"), narrow)
  }
