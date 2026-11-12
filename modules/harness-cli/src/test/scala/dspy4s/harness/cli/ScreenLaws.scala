package dspy4s.harness.cli

import dspy4s.harness.Draft
import dspy4s.harness.guest.{Mode, Scope}
import dspy4s.harness.host.{Activity, MemoryUse, WorkRuntime}
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

/** The laws of the screen model of the line editor (CLI_SCREEN.md): the rows of the history, the live block, the footer
  * and `/show`.
  */
class ScreenLaws extends munit.FunSuite:

  private val look = Look(Detail.Compact, ReasoningView.Line, Glyphs.unicode)

  // The draft of a step (CLI_SCREEN.md, 13).
  private val thought = "the first line of a fake thought\nthe second line\nthe third line\nthe fourth line"
  private val drafted = Draft(thought, "[[ ## text ## ]]\nI'll read the file.\n\n[[ ## code ## ]]\nread(\"a\")")

  private def at(seq: Long, millis: Long, entry: Entry, source: Source = Source.Harness): Logged =
    Logged(seq, Meta(millis, source, Taint.Trusted, true), entry)

  private def call(cell: Long, n: Int, op: String, fields: (String, Json)*): Entry =
    Entry.EffectStarted(EffectId(cell, n), op, Json.Object(Chunk(fields*)))

  private def done(cell: Long, n: Int, rendered: String): Entry =
    Entry.EffectDone(EffectId(cell, n), EffectResult.Value(Json.String(rendered), rendered))

  private def text(s: String): Json = Json.String(s)

  /** The rows that the entries of `log` print, as plain text. */
  private def printed(log: Vector[Logged], with_ : Look = look): Vector[String] =
    log.foldLeft((Screen.empty, Vector.empty[String])) { case ((screen, out), logged) =>
      val (next, rows) = Screen.after(screen, logged, with_)
      (next, out ++ rows.map(_.plain))
    }._2

  /** The screen after the entries of `log`. */
  private def screenOf(log: Vector[Logged], with_ : Look = look): Screen =
    log.foldLeft(Screen.empty)((screen, logged) => Screen.after(screen, logged, with_)._1)

  private val state: SessionState =
    val base = SessionState.initial
    base.copy(config = base.config.copy(steps = 10, scope = Scope.Directory("/home/a/code/shop")))

  // A turn of two steps: a cell with a failed test command, then the reply.
  private val turn = Vector(
    at(1, 1_000, Entry.TurnStarted),
    at(2, 1_100, Entry.StepStarted(0)),
    at(
      3,
      2_000,
      Entry.Acted("Run the tests.", "I'll run the tests of the cart first.", Some("exec(\"sbt cart/test\")"))
    ),
    at(4, 2_100, call(3, 0, "exec", "command" -> text("sbt cart/test"))),
    at(5, 6_300, done(3, 0, "[error] Failed: Total 14, Failed 2\n[exit code 1]")),
    at(6, 6_400, Entry.Observed("res0: String = ...", CellOutcome.Ok, Map.empty)),
    at(7, 6_500, Entry.StepStarted(1)),
    at(8, 13_000, Entry.Acted("", "Two tests fail on the rounding.\nThe total rounds each line.", None)),
    at(9, 13_100, Entry.TurnEnded(TurnOutcome.Replied("Two tests fail on the rounding.\nThe total rounds each line.")))
  )

  // ── The history ──

  test("Screen: a finished step prints its reasoning line, its text and the summary of its cell") {
    assertEquals(
      printed(turn.take(6)),
      Vector(
        "  ▸ Reasoning · 14 characters",
        "  I'll run the tests of the cart first.",
        "  ▸ Ran 1 command · exit 1 · 4.4 s  #3"
      )
    )
  }

  test("Screen: the reply prints in full after a blank row, and the end of the turn prints one line") {
    assertEquals(
      printed(turn).drop(3),
      Vector(
        "",
        "● Two tests fail on the rounding.",
        "  The total rounds each line.",
        "",
        "  Done · 2 steps · 1 cell · 12 s"
      )
    )
  }

  test("Screen: the summary of a cell names what its calls did, in the order of the calls") {
    val calls = Vector(
      call(3, 0, "read", "path"               -> text("a.txt")),
      call(3, 1, "read", "path"               -> text("b.txt")),
      call(3, 2, "exec", "command"            -> text("ls")),
      call(3, 3, "edit", "path"               -> text("a.txt"), "old"       -> text("x"), "new" -> text("y")),
      call(3, 4, "write", "path"              -> text("c.txt"), "content"   -> text("z")),
      call(3, 5, "spawn", "command"           -> text("sleep 30"), "report" -> Json.Boolean(true)),
      call(3, 6, "llmQueryBatched", "prompts" -> Json.Array(Chunk(text("a"), text("b")))),
      call(3, 7, "store", "key"               -> text("plan"), "value"      -> text("x")),
      call(3, 8, "zoom", "ref"                -> text("3.5"))
    )
    val log =
      Vector(at(1, 0, Entry.TurnStarted), at(2, 0, Entry.StepStarted(0)), at(3, 0, Entry.Acted("", "", Some("x")))) ++
        calls.zipWithIndex.flatMap((c, i) => Vector(at(4L + 2 * i, 0, c), at(5L + 2 * i, 0, done(3, i, "ok")))) :+
        at(30, 1_000, Entry.Observed("", CellOutcome.Ok, Map.empty))
    assertEquals(
      printed(log).last,
      "  ▸ Read 2 files, ran 1 command, edited 1 file, wrote 1 file, started job 3.5, asked the model 2 questions, " +
        "wrote the store, read the memory · 1.0 s  #3"
    )
  }

  test("Screen: a cell with no call, and each cell that failed, has its own summary") {
    def ended(outcome: CellOutcome, output: String = ""): String =
      printed(Vector(
        at(1, 0, Entry.TurnStarted),
        at(2, 0, Entry.StepStarted(0)),
        at(3, 0, Entry.Acted("", "", Some("x"))),
        at(4, 60_000, Entry.Observed(output, outcome, Map.empty))
      )).last
    assertEquals(ended(CellOutcome.Ok, "a\nb\nc"), "  ▸ Ran a cell · 3 lines of output · 1 m 0 s  #3")
    assertEquals(ended(CellOutcome.CompileError(Vector("e1", "e2"))), "  ✗ The cell did not compile · 2 errors  #3")
    assertEquals(
      ended(CellOutcome.Failed("java.io.IOException: gone\n\tat x")),
      "  ✗ The cell failed: java.io.IOException: gone  #3"
    )
    assertEquals(ended(CellOutcome.TimedOut(60_000)), "  ✗ The cell stopped at its time limit · 60 s  #3")
  }

  test("Screen: with the full detail, a finished cell prints its code, each call with its result, and its output") {
    val rows = printed(turn.take(6), look.copy(detail = Detail.Full)).drop(2)
    assertEquals(
      rows,
      Vector(
        "  ▾ Ran 1 command · exit 1 · 4.4 s  #3",
        "    │ exec(\"sbt cart/test\")",
        "    ✗ $ sbt cart/test · exit 1 · 4.2 s  #3.0",
        "      [error] Failed: Total 14, Failed 2",
        "      [exit code 1]",
        "    ✓ The cell ended · 1 line of output",
        "      res0: String = ..."
      )
    )
  }

  test("Screen: the reasoning shows as one line, in full, or not at all") {
    val log = turn.take(6)
    assert(printed(log, look.copy(reasoning = ReasoningView.Off)).forall(!_.contains("Reasoning")))
    assert(printed(log, look.copy(reasoning = ReasoningView.Full)).contains("  │ Run the tests."))
  }

  test("Screen: a call that needs an approval prints its card after the summary of the step") {
    val rule = GuardRule("exec", ArgMatch.Exact(Json.Object(Chunk("command" -> text("git push origin main")))))
    val log  = Vector(
      at(1, 0, Entry.TurnStarted),
      at(2, 0, Entry.StepStarted(0)),
      at(3, 0, Entry.Acted("", "I push the branch.", Some("x"))),
      at(4, 0, call(3, 0, "exec", "command" -> text("git push origin main"))),
      at(5, 0, Entry.EffectDone(EffectId(3, 0), EffectResult.NeedsApproval(rule))),
      at(6, 0, Entry.Observed("", CellOutcome.Ok, Map.empty))
    )
    assertEquals(
      printed(log),
      Vector(
        "  I push the branch.",
        "  ▸ Ran 1 command · waits for your approval · 0.0 s  #3",
        "  ⚠ The call 5 needs your approval",
        "    exec \"git push origin main\"",
        "    /allow 5 once · /allow 5 turn · /allow 5 session · or type what to do instead"
      )
    )
  }

  test("Screen: a message during a turn, a job message and the other events print one line each") {
    val log = Vector(
      at(1, 0, Entry.TurnStarted),
      at(2, 0, Entry.Input(RequestId("r1"), "also check the tax", Map.empty), Source.User),
      at(3, 0, Entry.Input(RequestId("r2"), "The job 3.0 ended with the exit code 0.", Map.empty)),
      at(4, 0, Entry.JobsStopRequested(JobSelector.One(EffectId(3, 0)), "the person")),
      at(5, 0, Entry.TurnEnded(TurnOutcome.Aborted))
    )
    assertEquals(
      printed(log),
      Vector(
        "  ↳ The next step reads your message.",
        "● The job 3.0 ended with the exit code 0.",
        "  ■ The job 3.0 stops: the person.",
        "",
        "  ■ Stopped · 0 steps · 0 cells · 0.0 s"
      )
    )
  }

  test("Screen: the end of a turn names each outcome") {
    def end(outcome: TurnOutcome): String =
      printed(Vector(at(1, 0, Entry.TurnStarted), at(2, 63_000, Entry.TurnEnded(outcome)))).last
    assertEquals(end(TurnOutcome.Exhausted), "  Step limit · 0 steps · 0 cells · 1 m 3 s")
    assertEquals(end(TurnOutcome.Failed("no model")), "  ✗ Failed: no model · 0 steps · 0 cells · 1 m 3 s")
    assertEquals(end(TurnOutcome.Crashed), "  ✗ The turn crashed · 0 steps · 0 cells · 1 m 3 s")
  }

  test("Screen: a command shows each hidden character as an escape, on one line") {
    val log = turn.take(3) ++ Vector(
      at(4, 2_100, call(3, 0, "exec", "command" -> text("echo a‮b\nls"))),
      at(5, 2_200, done(3, 0, "ok")),
      at(6, 2_300, Entry.Observed("", CellOutcome.Ok, Map.empty))
    )
    val rows = printed(log, look.copy(detail = Detail.Full))
    assert(rows.exists(_.contains("$ echo a\\u202eb (+1 line)")), rows.mkString("\n"))
  }

  // ── The live block ──

  test("Screen: during the call to the policy, the live block is only the status line, with no label") {
    val live = Screen.live(screenOf(turn.take(2)), state, 10_100, look, None)
    assert(live.busy)
    assertEquals(live.rows.map(_.plain.drop(2)), Vector("9 s · step 1 of 10 · ctrl-c stops"))
    assertEquals(live.title, "● dspy4s · shop")
  }

  test("Screen: the status line gives the memory summaries before the call to the policy, and a stop") {
    val memory = Screen.live(screenOf(turn.take(2)), state, 3_000, look, Some(Activity.MemorySummaries(2, 5)))
    assert(
      memory.rows.last.plain.contains("step 1 of 10 · the memory: 2 of 5 summaries · ctrl-c stops"),
      memory.toString
    )
    val stop    = screenOf(turn.take(2) :+ at(3, 1_200, Entry.StopRequested("the person")))
    val stopped = Screen.live(stop, state, 3_000, look, None)
    assert(stopped.rows.last.plain.contains("the turn stops"), stopped.toString)
  }

  test("Screen: an open cell shows its text, its code and its calls, and a command gives the walk for secret files") {
    val live = Screen.live(screenOf(turn.take(4)), state, 5_100, look, Some(Activity.SecretScan(640_000, None)))
    val rows = live.rows.map(_.plain)
    assertEquals(
      rows.take(3),
      Vector("  I'll run the tests of the cart first.", "  ▾ Cell #3", "    │ exec(\"sbt cart/test\")")
    )
    assert(rows(3).startsWith("    ") && rows(3).endsWith(" $ sbt cart/test"), rows(3))
    assert(
      rows(
        4
      ).endsWith("4 s · step 1 of 10 · the scan for secret files: 640,000 folders · ctrl-c stops · ctrl-o details"),
      rows(4)
    )
  }

  // ── The draft of a step (CLI_SCREEN.md, 13) ──

  test("Screen: during the call to the policy, the live block shows the draft above the status line") {
    val live = Screen.live(screenOf(turn.take(2)), state, 10_100, look, None, Some(drafted))
    val rows = live.rows.map(_.plain)
    assertEquals(rows.init, Vector(s"  ▸ Reasoning · ${thought.length} characters", "  I'll read the file."))
    assertEquals(rows.last.drop(2), "9 s · step 1 of 10 · ctrl-c stops")
  }

  test("Screen: the view of the reasoning sets the reasoning of a draft: none, its size, or its last lines") {
    def rows(view: ReasoningView): Vector[String] =
      Screen.live(screenOf(turn.take(2)), state, 10_100, look.copy(reasoning = view), None, Some(drafted))
        .rows.map(_.plain).init
    assertEquals(rows(ReasoningView.Off), Vector("  I'll read the file."))
    assertEquals(
      rows(ReasoningView.Short),
      Vector(
        "  │ … +1 line",
        "  │ the second line",
        "  │ the third line",
        "  │ the fourth line",
        "  I'll read the file."
      )
    )
    val long = (1 to 20).map(i => s"line $i").mkString("\n")
    val full = Screen.live(
      screenOf(turn.take(2)),
      state,
      10_100,
      look.copy(reasoning = ReasoningView.Full),
      None,
      Some(Draft(long, ""))
    ).rows.map(_.plain).init
    assertEquals(full, "  │ … +8 lines" +: (9 to 20).map(i => s"  │ line $i").toVector)
  }

  test("Screen: a long text of a draft shows its last 16 lines, with a note of the lines above them") {
    val text = (1 to 30).map(i => s"line $i").mkString("\n")
    val rows = Screen.live(screenOf(turn.take(2)), state, 10_100, look, None, Some(Draft("", text))).rows.map(_.plain)
    assertEquals(rows.init, "  … +14 lines" +: (15 to 30).map(i => s"  line $i").toVector)
  }

  test("Screen: a draft with no text and no reasoning shows only the status line") {
    val live = Screen.live(screenOf(turn.take(2)), state, 10_100, look, None, Some(Draft.empty))
    assertEquals(live.rows.map(_.plain.drop(2)), Vector("9 s · step 1 of 10 · ctrl-c stops"))
  }

  test("Screen: a step with its Acted shows its own text and cell, and not the draft") {
    val log = turn.take(4)
    assertEquals(
      Screen.live(screenOf(log), state, 5_100, look, None, Some(drafted)),
      Screen.live(screenOf(log), state, 5_100, look, None, None)
    )
  }

  test("Screen: with no open turn, the live block is empty and the title has no mark") {
    val live = Screen.live(screenOf(turn), state, 20_000, look, None)
    assertEquals(live, Live(Vector.empty, busy = false, title = "dspy4s · shop"))
  }

  test("Screen: the code of an open cell shows at most 8 lines, and the full detail shows all of them") {
    val code = (1 to 12).map(i => s"val a$i = $i").mkString("\n")
    val open = screenOf(Vector(
      at(1, 0, Entry.TurnStarted),
      at(2, 0, Entry.StepStarted(0)),
      at(3, 0, Entry.Acted("", "", Some(code)))
    ))
    val compact = Screen.live(open, state, 0, look, None).rows.map(_.plain)
    assertEquals(compact.count(_.startsWith("    │ val")), 8)
    assert(compact.contains("    │ … +4 lines"), compact.mkString("\n"))
    val full = Screen.live(open, state, 0, look.copy(detail = Detail.Full), None).rows.map(_.plain)
    assertEquals(full.count(_.startsWith("    │ val")), 12)
  }

  // ── The footer and /show ──

  test("Screen: the footer gives the folder, the mode, the model, the memory, the jobs and the calls that wait") {
    val rule = GuardRule("exec", ArgMatch.AnyArgs)
    val work = state.copy(
      config = state.config.copy(mode = WorkRuntime.workMode, model = ModelChoice("example-model", Map.empty)),
      pending = Set(rule)
    )
    assertEquals(
      Screen.footer(work, Some(MemoryUse(12_300, 48_000)), 1, "/home/a", Glyphs.unicode).plain,
      "  ~/code/shop · work mode · example-model · memory 12k of 48k chars · 1 job · 1 call waits"
    )
    val plain = state.copy(config = state.config.copy(mode = Mode.readOnly, model = ModelChoice("m", Map.empty)))
    assertEquals(
      Screen.footer(plain, None, 0, "/elsewhere", Glyphs.unicode).plain,
      "  /home/a/code/shop · read-only mode · m"
    )
  }

  test("Screen: /show gives a cell or a call in full, and an error for an unknown id") {
    val screen = screenOf(turn)
    val cell   = Screen.show(screen, "3", look).map(_.map(_.plain)).getOrElse(fail("no cell"))
    assertEquals(cell.head, "  ▾ Ran 1 command · exit 1 · 4.4 s  #3")
    val one = Screen.show(screen, "3.0", look).map(_.map(_.plain)).getOrElse(fail("no call"))
    assertEquals(
      one,
      Vector(
        "  ✗ $ sbt cart/test · exit 1 · 4.2 s  #3.0",
        "    [error] Failed: Total 14, Failed 2",
        "    [exit code 1]"
      )
    )
    assertEquals(Screen.show(screen, "99", look), Left("The session has no cell 99 and no call 99."))
    assertEquals(Screen.of(turn), screen)
  }

  test("Screen: the ASCII glyphs give rows with no character outside ASCII") {
    val ascii = look.copy(glyphs = Glyphs.ascii, detail = Detail.Full)
    val rows  = printed(turn, ascii) ++
      Screen.live(screenOf(turn.take(4), ascii), state, 5_100, ascii, None).rows.map(_.plain)
    assert(rows.forall(_.forall(_ < 128)), rows.filterNot(_.forall(_ < 128)).mkString("\n"))
  }
