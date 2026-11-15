package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.guest.{JobStatus, Scope}
import dspy4s.harness.host.{Behind, ContextUse, Feed, HostError, MemoryUse, SessionHandle, WorkRuntime}
import dspy4s.harness.kernel.*
import zio.blocks.schema.json.Json

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The laws of the reasoning effort and of the context of the model in the footer (CLI_SCREEN.md, 14). */
class EffortLaws extends munit.FunSuite:

  private val request = RequestId("fake-request")

  private def effort(level: String): Map[String, Json] = Map(Effort.Key -> Json.String(level))

  /** A work session in `/home/a/code/shop` whose model has the options `options`. */
  private def work(options: Map[String, Json]): SessionState =
    val base = SessionState.initial
    base.copy(
      closed = false,
      config = base.config.copy(
        mode = WorkRuntime.workMode,
        scope = Scope.Directory("/home/a/code/shop"),
        model = ModelChoice("example-model", options)
      )
    )

  /** The reply of `GET /models` of the endpoint of the probe (14.6), with two models. */
  private val models = """{"data": [
      |  {"id": "owner/other-model", "context_window": 131072, "supports_reasoning": false},
      |  {"id": "owner/example-model", "context_window": 1048576, "supports_reasoning": true,
      |   "reasoning_effort_levels": ["low", "high"], "input_modalities": ["text"]}
      |]}""".stripMargin

  private val facts = ModelFacts(Some(1_048_576L), Vector("low", "high"))

  // ── The effort of a new session ──

  test("Effort: a new session takes DSPY_REASONING_EFFORT, else low with native reasoning, else no effort") {
    assertEquals(Effort.startOptions(Map("DSPY_NATIVE_REASONING" -> "true")), effort("low"))
    assertEquals(Effort.startOptions(Map("DSPY_NATIVE_REASONING" -> "true", Effort.Variable -> "high")), effort("high"))
    assertEquals(Effort.startOptions(Map(Effort.Variable -> " none ")), effort("none"))
    assertEquals(Effort.startOptions(Map("DSPY_NATIVE_REASONING" -> "false")), Map.empty)
    assertEquals(Effort.startOptions(Map.empty), Map.empty)
  }

  test("Effort: of reads the effort of a model, and set changes only the effort") {
    val model = ModelChoice("example-model", effort("low") + ("fake_option" -> Json.String("FAKE")))
    assertEquals(Effort.of(model), Some("low"))
    assertEquals(Effort.of(ModelChoice("example-model", Map.empty)), None)
    assertEquals(
      Effort.set(model, "high"),
      model.copy(options = effort("high") + ("fake_option" -> Json.String("FAKE")))
    )
  }

  // ── The facts of the model ──

  test("Facts: parse reads the window and the levels of the entry of the model, also with the prefix of an owner") {
    assertEquals(ModelFacts.parse(models, "example-model"), facts)
    assertEquals(ModelFacts.parse(models, "owner/example-model"), facts)
    assertEquals(ModelFacts.parse(models, "other-model"), ModelFacts(Some(131_072L), Vector.empty))
  }

  test("Facts: parse reads max_model_len, and gives no facts for a model that is not in the list or a bad text") {
    val vllm = """{"object": "list", "data": [{"id": "example-model", "max_model_len": 32768}]}"""
    assertEquals(ModelFacts.parse(vllm, "example-model"), ModelFacts(Some(32_768L), Vector.empty))
    assertEquals(ModelFacts.parse(models, "missing-model"), ModelFacts.unknown)
    // Only a whole name after the prefix counts.
    assertEquals(ModelFacts.parse(models, "model"), ModelFacts.unknown)
    assertEquals(ModelFacts.parse("not json", "example-model"), ModelFacts.unknown)
    assertEquals(ModelFacts.parse("""{"data": 3}""", "example-model"), ModelFacts.unknown)
  }

  test("Facts: DSPY_CONTEXT_WINDOW wins over the endpoint, and a value that is not a positive number changes nothing") {
    assertEquals(ModelFacts.withEnv(facts, Map(ModelFacts.WindowVariable -> "200000")).window, Some(200_000L))
    assertEquals(ModelFacts.withEnv(ModelFacts.unknown, Map(ModelFacts.WindowVariable -> "8192")).window, Some(8_192L))
    assertEquals(ModelFacts.withEnv(facts, Map(ModelFacts.WindowVariable -> "lots")), facts)
    assertEquals(ModelFacts.withEnv(facts, Map(ModelFacts.WindowVariable -> "0")), facts)
  }

  test("Facts: the levels of /effort are none and the levels of the endpoint, or none, low, medium and high") {
    assertEquals(facts.choices, Vector("none", "low", "high"))
    assertEquals(ModelFacts.unknown.choices, Vector("none", "low", "medium", "high"))
  }

  // ── The footer ──

  test("Footer: it gives the effort, the context with its window and percent, and the memory in characters") {
    assertEquals(
      Screen.footer(
        work(effort("high")),
        Some(MemoryUse(64, 40_000)),
        0,
        "/home/a",
        Glyphs.unicode,
        Some(ContextUse(18_432L, 100L)),
        facts
      ).plain,
      "  ~/code/shop · work mode · example-model · effort high · context 18k of 1M (2%) · memory 64 of 40k chars"
    )
  }

  test("Footer: with no window the context is only its count, under 1% the percent is <1%, and no effort no part") {
    val shown = Screen.footer(work(Map.empty), None, 0, "/home/a", Glyphs.unicode, Some(ContextUse(900L, 1L))).plain
    assertEquals(shown, "  ~/code/shop · work mode · example-model · context 900")
    assertEquals(
      Screen.footer(work(Map.empty), None, 0, "/home/a", Glyphs.unicode, Some(ContextUse(5_000L, 1L)), facts).plain,
      "  ~/code/shop · work mode · example-model · context 5k of 1M (<1%)"
    )
  }

  test("Footer: a count shows as it is, in whole thousands, or in millions with one decimal and no .0") {
    assertEquals(
      Vector(999L, 18_432L, 131_072L, 1_048_576L, 1_572_864L, 2_000_000L).map(Screen.tokens),
      Vector("999", "18k", "131k", "1M", "1.6M", "2M")
    )
  }

  /** A work session in `folder` with the model `model` and the effort `low`. */
  private def at(folder: String, model: String): SessionState =
    val base = work(effort("low"))
    base.copy(config = base.config.copy(scope = Scope.Directory(folder), model = ModelChoice(model, effort("low"))))

  /** The footer of `state` at `width`, with a memory of 12k of 48k characters, 1 job, and a context of 18k. */
  private def footerAt(state: SessionState, width: Int, glyphs: Glyphs = Glyphs.unicode): String =
    val use = Some(ContextUse(18_432L, 100L))
    Screen.footer(state, Some(MemoryUse(12_300, 48_000)), 1, "/home/a", glyphs, use, facts, width).plain

  test("Footer: when the line is wider than the terminal, the folder keeps its last parts after …/, and memory stays") {
    // A live run of 2026-11-12 at 120 columns lost the memory part when the context showed (CLI_SCREEN.md, 14.3).
    val state = at("/home/a/GitHub/dspy4s/modules/harness-cli", "Qwen3.8-27B-NVFP4")
    val full  = footerAt(state, Int.MaxValue)
    assertEquals(footerAt(state, full.length), full)
    assertEquals(
      footerAt(state, 120),
      "  …/harness-cli · work mode · Qwen3.8-27B-NVFP4 · effort low · context 18k of 1M (2%) · " +
        "memory 12k of 48k chars · 1 job"
    )
    // "…/code/shop" is as long as "~/code/shop", so one character less gives the last part only.
    val shop = at("/home/a/code/shop", "example-model")
    assertEquals(
      footerAt(shop, footerAt(shop, Int.MaxValue).length - 1),
      "  …/shop · work mode · example-model · effort low · context 18k of 1M (2%) · memory 12k of 48k chars · 1 job"
    )
    assert(footerAt(shop, footerAt(shop, Int.MaxValue).length - 1, Glyphs.ascii).startsWith("  .../shop - work mode"))
  }

  test("Footer: a folder longer than the terminal leaves the other parts in view, and the line fits the width") {
    // A live run of 2026-11-12 showed only the cut folder when the folder was longer than the width.
    val state = at("/home/a/" + "deep/" * 40 + "shop", "example-model")
    val line  = footerAt(state, 120)
    assert(line.length <= 120, line)
    assert(line.startsWith("  …/"), line)
    assert(line.contains("/shop · work mode · example-model · effort low · context 18k of 1M (2%) · "), line)
    assert(line.endsWith(" · memory 12k of 48k chars · 1 job"), line)
  }

  test(
    "Footer: when the shortest folder does not fit with the memory part, the memory part goes, and the folder is the " +
      "longest form that fits"
  ) {
    val state = at("/home/a/GitHub/dspy4s/.claude/worktrees/code-harness", "deepseek-ai/DeepSeek-V4.1-Flash-1017")
    assertEquals(
      footerAt(state, 120),
      "  …/code-harness · work mode · deepseek-ai/DeepSeek-V4.1-Flash-1017 · effort low · context 18k of 1M (2%) · 1 job"
    )
  }

  test("Footer: a last part longer than the width becomes … and its end, and with no room the folder is only …") {
    val state = at("/home/a/code/FAKE-" + "long-" * 40 + "name", "example-model")
    val rest  = "work mode · example-model · effort low · context 18k of 1M (2%) · 1 job"
    assertEquals(footerAt(state, 120), "  …ong-long-long-long-long-long-long-long-name · " + rest)
    assertEquals(footerAt(state, 120).length, 120)
    assertEquals(footerAt(state, 10), "  … · " + rest)
    val plain = rest.replace(" · ", " - ")
    assertEquals(footerAt(state, 120, Glyphs.ascii), "  ...g-long-long-long-long-long-long-long-name - " + plain)
    assertEquals(footerAt(state, 120, Glyphs.ascii).length, 120)
    assertEquals(footerAt(state, 10, Glyphs.ascii), "  ... - " + plain)
    // The cut keeps each character whole, so a pair of surrogates stays together, and the line is one unit shorter.
    val faces = footerAt(at("/home/a/FAKE-" + "😀" * 40, "example-model"), 120)
    assertEquals(faces, "  …" + "😀" * 21 + " · " + rest)
  }

  test("Footer: at each width from the line whose folder part is only …, the line fits the width, and it keeps the " +
    "mode, the model, the effort and the context") {
    val folders = Vector(
      "/home/a/code/shop",
      "/home/a/GitHub/dspy4s/.claude/worktrees/code-harness",
      "/home/a/" + "deep/" * 40 + "shop",
      "/home/a/code/FAKE-" + "long-" * 40 + "name",
      "/home/a/FAKE-" + "😀" * 40,
      "/home/a",
      "/srv/FAKE-shop",
      "/FAKE-" + "x" * 150
    )
    for
      folder <- folders
      model  <- Vector("example-model", "deepseek-ai/DeepSeek-V4.1-Flash-1017")
      glyphs <- Vector(Glyphs.unicode, Glyphs.ascii)
    do
      val state = at(folder, model)
      val sep   = s" ${glyphs.dot} "
      val kept  = Vector("work mode", model, "effort low", "context 18k of 1M (2%)").mkString(sep)
      val least = s"  ${glyphs.more}$sep$kept${sep}1 job"
      val full  = footerAt(state, Int.MaxValue, glyphs)
      for width <- least.length to full.length + 1 do
        val line = footerAt(state, width, glyphs)
        assert(line.length <= width, s"$folder at $width: $line")
        assert(line.contains(sep + kept + sep), s"$folder at $width: $line")
        if width >= full.length then assertEquals(line, full)
  }

  // ── /effort ──

  test("/effort: parseLine reads /effort with no level and with one level") {
    assertEquals(parseLine("/effort"), Line.Effort(None))
    assertEquals(parseLine("/effort high"), Line.Effort(Some("high")))
    assertEquals(parseLine("/effort a b"), Line.Unknown("/effort a b"))
  }

  test("/effort: a level of the list gives ChangeModel with the level, and keeps the other options of the model") {
    val state = work(effort("low") + ("fake_option" -> Json.String("FAKE")))
    assertEquals(
      commandsFor(Line.Effort(Some("high")), state, request, choices = facts.choices),
      Right(Vector(Command.ChangeModel(
        ModelChoice("example-model", effort("high") + ("fake_option" -> Json.String("FAKE")))
      )))
    )
    assertEquals(
      commandsFor(Line.Effort(Some("none")), state, request, choices = facts.choices),
      Right(Vector(Command.ChangeModel(ModelChoice(
        "example-model",
        effort("none") + ("fake_option" -> Json.String("FAKE"))
      ))))
    )
  }

  test("/effort: a level that is not in the list gives an error with the list, and /effort alone gives no command") {
    assertEquals(
      commandsFor(Line.Effort(Some("medium")), work(effort("low")), request, choices = facts.choices),
      Left("The model has no effort medium. Its levels are none, low and high.")
    )
    assertEquals(
      commandsFor(Line.Effort(None), work(effort("low")), request, choices = facts.choices),
      Right(Vector.empty)
    )
  }

  test("/effort: effortText gives the effort of the session and the levels of the model") {
    assertEquals(
      effortText(work(effort("low")), facts.choices),
      "The effort is low. The levels of the model are none, low and high. /effort LEVEL sets the effort of the next steps."
    )
    assertEquals(
      effortText(work(Map.empty), facts.choices),
      "The session sends no effort, so the model uses its own. The levels of the model are none, low and high. " +
        "/effort LEVEL sets the effort of the next steps."
    )
  }

  test("/status: it gives the effort and the context of the model") {
    val text = statusText(work(effort("high")), Map.empty, Vector.empty, Some(ContextUse(18_432L, 100L)), facts)
    assert(text.contains("The effort: high."), text)
    assert(text.contains("The context: 18k of 1M tokens (2%)."), text)
    val none = statusText(work(Map.empty), Map.empty, Vector.empty)
    assert(!none.contains("The effort") && !none.contains("The context"), none)
  }

  test("/effort: a ModelChanged shows the new effort in the plain lines and on the screen") {
    val changed = Support.logOf(1000L, Entry.ModelChanged(ModelChoice("example-model", effort("high")))).head
    val words   = "The effort is now high. The next step uses it."
    assertEquals(entryText(changed), Some(words))
    val other = Support.logOf(1000L, Entry.ModelChanged(ModelChoice("example-model", Map.empty))).head
    assertEquals(entryText(other), Some("The options of the model changed. The next step uses them."))
    val rows = Screen.after(Screen.empty, changed, Look(Detail.Compact, ReasoningView.Line, Glyphs.unicode))._2
    assert(rows.exists(_.plain.contains(words)), rows.map(_.plain).mkString("\n"))
  }

  test("/effort: the loop prints the effort, sends ChangeModel for a level, and prints the error of a bad level") {
    val handle = ModelHandle(work(effort("low")))
    val out    = ByteArrayOutputStream()
    val lines  = BufferedReader(StringReader("/effort\n/effort high\n/effort turbo\n"))
    assert(Cli.loop(handle, lines, PrintStream(out, true, UTF_8), script = true))
    val printed = out.toString(UTF_8)
    assert(printed.contains("The effort is low."), printed)
    assertEquals(handle.ran.asScala.toVector, Vector(Command.ChangeModel(ModelChoice("example-model", effort("high")))))
    assert(printed.contains("The model has no effort turbo. Its levels are none, low, medium and high."), printed)
  }

/** A session in the state `start` for the laws of `/effort`. Each command through `run` or `send` goes to `ran` and
  * changes nothing. The feed gives no entry, and it ends at once, so a loop ends after its input.
  */
private final class ModelHandle(start: SessionState) extends SessionHandle:
  val ran: ConcurrentLinkedQueue[Command] = ConcurrentLinkedQueue()

  def id: SessionId                                          = SessionId("fake-model-session")
  def send(command: Command): Either[HostError, Unit]        = run(command).map(_ => ())
  def run(command: Command): Either[HostError, SessionState] =
    ran.add(command): Unit
    Right(start)
  def state: SessionState                 = start
  def jobStatus: Map[EffectId, JobStatus] = Map.empty
  def read(after: Long): Vector[Logged]   = Vector.empty
  def subscribe(capacity: Int): Feed      =
    new Feed:
      def poll(waitMillis: Long): Option[Either[Behind, Logged]] = None
      def ended: Boolean                                         = false
      def close(): Unit                                          = ()
