package dspy4s.harness.cli

import dspy4s.harness.guest.{Mode, Scope}
import dspy4s.harness.host.{Activity, ContextUse, MemoryUse, WorkRuntime, jobName}
import dspy4s.harness.kernel.*
import dspy4s.harness.{Draft, textPrefix}
import zio.blocks.schema.json.Json

/** The tone of a part of a row of the screen. The console gives each tone a color, or no color with `NO_COLOR`. */
enum Tone derives CanEqual:
  case Plain, Dim, Accent, Good, Bad, Warn

/** A part of a row, with one tone. Its text has no control character. */
final case class Span(text: String, tone: Tone) derives CanEqual

/** One row of the screen (CLI_SCREEN.md). */
final case class Row(spans: Vector[Span]) derives CanEqual:

  /** The text of the row, with no tone. */
  def plain: String = spans.map(_.text).mkString

object Row:

  /** A row of one span. */
  def of(text: String, tone: Tone = Tone.Plain): Row = Row(Vector(Span(text, tone)))

/** The glyphs of the screen: the Unicode set, and an ASCII set for a terminal with no Unicode (CLI_SCREEN.md, 9.1). */
final case class Glyphs(
    open   : String,
    closed : String,
    ok     : String,
    fail   : String,
    stop   : String,
    warn   : String,
    dot    : String,
    bar    : String,
    more   : String,
    reply  : String,
    arrow  : String,
    prompt : String,
    rule   : String,
    spinner: Vector[String]
) derives CanEqual

object Glyphs:
  val unicode: Glyphs = Glyphs(
    "▾",
    "▸",
    "✓",
    "✗",
    "■",
    "⚠",
    "·",
    "│",
    "…",
    "●",
    "↳",
    "›",
    "─",
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  )
  val ascii: Glyphs =
    Glyphs("v", ">", "+", "x", "#", "!", "-", "|", "...", "*", "->", ">", "-", Vector("|", "/", "-", "\\"))

/** How much a finished cell shows: its summary line, or each part of it. Ctrl-O switches it. */
enum Detail derives CanEqual:
  case Compact, Full

/** How the screen shows the texts: the detail of a cell, the view of the reasoning, and the glyphs. */
final case class Look(detail: Detail, reasoning: ReasoningView, glyphs: Glyphs) derives CanEqual

/** A call of the guest API in a cell: its id, op and arguments, the time of its start, and its result with the time of
  * its end.
  */
final case class CallView(id: EffectId, op: String, args: Json, startMillis: Long, end: Option[(EffectResult, Long)])
    derives CanEqual

/** The end of a cell: its output, its outcome and its time. */
final case class CellEnd(output: String, outcome: CellOutcome, atMillis: Long) derives CanEqual

/** A cell: its id (the `seq` of its `Acted` entry), its code, the time of its start, its calls and its end. */
final case class CellView(id: Long, code: String, startMillis: Long, calls: Vector[CallView], end: Option[CellEnd])
    derives CanEqual

/** A step of a turn: its number from 0, the time of its start, the reasoning and the text of the model, whether the
  * model answered, its cell, the calls that asked for an approval with the `seq` of each one, and whether the history
  * has it.
  */
final case class StepView(
    step       : Int,
    startMillis: Long,
    reasoning  : String,
    text       : String,
    acted      : Boolean,
    cell       : Option[CellView],
    asked      : Vector[(Long, GuardRule)],
    printed    : Boolean
) derives CanEqual

/** The turn that runs: the time of its start, its steps, and whether a stop came. */
final case class TurnView(startMillis: Long, steps: Vector[StepView], stopping: Boolean) derives CanEqual

/** What the live block shows: its rows, whether a turn is open, and the title of the window. */
final case class Live(rows: Vector[Row], busy: Boolean, title: String) derives CanEqual

/** The model of the screen of a session (CLI_SCREEN.md): the turn that runs, and each cell of the session for `/show`.
  */
final case class Screen(turn: Option[TurnView], cells: Map[Long, CellView]) derives CanEqual

object Screen:

  val empty: Screen = Screen(None, Map.empty)

  /** The most lines of the code of an open cell in the compact live block. */
  private val CodeLines = 8

  /** The most lines of the result of a call, and of the output of a cell, in the full detail of a cell. */
  private val ResultLines = 3
  private val OutputLines = 20

  /** The most lines of a draft in the live block (CLI_SCREEN.md, 13): of its reasoning in the views `short` and `full`,
    * and of its text.
    */
  private val DraftShort = 3
  private val DraftFull  = 12
  private val DraftText  = 16

  /** The most characters of a command, or of another argument, on the line of a call. */
  private val ArgChars = 80

  /** The time of each frame of the spinner. */
  private val FrameMillis = 125L

  /** The note of `SandboxedShell` at the end of the output of a command whose exit code is not 0. */
  private val ExitCode = """\[exit code (-?\d+)\]""".r

  /** The look of `of`: it prints no row, so only the model counts. */
  private val Quiet = Look(Detail.Compact, ReasoningView.Off, Glyphs.ascii)

  /** The screen after each entry of `log`, with no row for the history. */
  def of(log: Vector[Logged]): Screen = log.foldLeft(empty)((screen, logged) => after(screen, logged, Quiet)._1)

  /** The screen after `logged`, and the rows that go into the history. */
  def after(screen: Screen, logged: Logged, look: Look): (Screen, Vector[Row]) =
    val g  = look.glyphs
    val at = logged.meta.atMillis
    logged.entry match
      case Entry.TurnStarted    => (screen.copy(turn = Some(TurnView(at, Vector.empty, stopping = false))), Vector.empty)
      case Entry.StepStarted(n) =>
        val (flushed, rows) = flush(screen, look)
        val step            = StepView(n, at, "", "", acted = false, None, Vector.empty, printed = false)
        (flushed.withTurn(t => t.copy(steps = t.steps :+ step)), rows)
      case Entry.Acted(reasoning, text, code) =>
        val cell  = code.map(c => CellView(logged.seq, c, at, Vector.empty, None))
        val acted = screen.withStep(_.copy(reasoning = reasoning, text = text, acted = true, cell = cell))
        val kept  = cell.fold(acted)(c => acted.copy(cells = acted.cells.updated(c.id, c)))
        // A step with no code is the reply, so it goes into the history at once.
        if code.isEmpty then flush(kept, look) else (kept, Vector.empty)
      case Entry.EffectStarted(id, op, args) =>
        (screen.withCell(c => c.copy(calls = c.calls :+ CallView(id, op, args, at, None))), Vector.empty)
      case Entry.EffectDone(id, result) =>
        val ended = screen.withCell(c =>
          c.copy(calls = c.calls.map(k => if k.id == id then k.copy(end = Some((result, at))) else k))
        )
        result match
          case EffectResult.NeedsApproval(rule) =>
            (ended.withStep(s => s.copy(asked = s.asked :+ (logged.seq -> rule))), Vector.empty)
          case _ => (ended, Vector.empty)
      case Entry.Observed(output, outcome, _) =>
        flush(screen.withCell(_.copy(end = Some(CellEnd(output, outcome, at)))), look)
      case Entry.StopRequested(_)   => (screen.withTurn(_.copy(stopping = true)), Vector.empty)
      case Entry.TurnEnded(outcome) =>
        val (flushed, rows) = flush(screen, look)
        (flushed.copy(turn = None), rows ++ endRows(flushed.turn, outcome, at, g))
      case Entry.Input(_, _, _) if logged.meta.source == Source.User =>
        val notice = Row.of(s"  ${g.arrow} The next step reads your message.", Tone.Dim)
        (screen, if screen.turn.isDefined then Vector(notice) else Vector.empty)
      case Entry.Input(_, text, _) =>
        // A message of the harness, as at the end of a job, speaks for itself. Another sender shows its name.
        val from = if logged.meta.source == Source.Harness then "" else s"[${sourceWords(logged.meta.source)}] "
        (screen, marked(g.reply, Tone.Accent, from + text))
      case Entry.JobsStopRequested(which, reason) => (
          screen,
          Vector(Row(Vector(
            Span(s"  ${g.stop} ", Tone.Warn),
            Span(printable(s"${jobsWords(which)}: $reason."), Tone.Plain)
          )))
        )
      case Entry.Granted(_, rule, lifetime) => (
          screen,
          Vector(Row(Vector(
            Span(s"  ${g.ok} ", Tone.Good),
            Span(printable(s"Allowed ${lifetime.words}: ${callText(rule)}."), Tone.Plain)
          )))
        )
      case Entry.ModelChanged(model) =>
        (screen, Vector(Row.of(s"  ${g.arrow} ${printable(changedText(model))}", Tone.Dim)))
      case Entry.Ended(outcome, reason) => (screen, marked(g.reply, Tone.Accent, outcome.report(reason)))
      case _                            => (screen, Vector.empty)

  /** The live block at `nowMillis`, for the session in `state`, with the activity of the host. A step with no `Acted`
    * shows `draft`, the text that the model writes now (CLI_SCREEN.md, 13).
    */
  def live(
      screen   : Screen,
      state    : SessionState,
      nowMillis: Long,
      look     : Look,
      activity : Option[Activity],
      draft    : Option[Draft] = None
  ): Live =
    val g    = look.glyphs
    val name = folderName(state)
    screen.turn match
      case None       => Live(Vector.empty, busy = false, title = s"dspy4s ${g.dot} $name")
      case Some(turn) =>
        val frame = g.spinner(((nowMillis / FrameMillis) % g.spinner.size).toInt)
        val step  = turn.steps.lastOption.filterNot(_.printed)
        val body  = step.toVector.flatMap { s =>
          if s.acted then liveStep(s, frame, look) else draft.toVector.flatMap(draftRows(_, look))
        }
        Live(
          body :+ statusRow(turn, step, state, nowMillis, frame, activity, g),
          busy = true,
          s"${g.reply} dspy4s ${g.dot} $name"
        )

  /** The footer: the folder, the mode, the model, the reasoning effort, the context, the memory, the jobs that run and
    * the calls that wait. `home` is the home folder, which the footer shows as `~`. A part with no value does not show.
    *
    * The effort is the effort of the model of the session (`Effort.of`). The context is the count of prompt tokens of
    * the last call to the policy (`context`), with the window of `facts` and the percent of it, as
    * `context 18k of 1M (2%)`. The memory is the size of the last view of the memory and its budget, in characters.
    * When the line is wider than `width`, the footer shows no memory part (CLI_SCREEN.md, 14.3).
    */
  def footer(
      state  : SessionState,
      memory : Option[MemoryUse],
      jobs   : Int,
      home   : String,
      glyphs : Glyphs,
      context: Option[ContextUse] = None,
      facts  : ModelFacts         = ModelFacts.unknown,
      width  : Int                = Int.MaxValue
  ): Row =
    val folder = folderOf(state)
    val shown  =
      if home.nonEmpty && (folder == home || folder.startsWith(home + "/")) then "~" + folder.drop(home.length)
      else folder
    val pending = state.pending.size
    val head    = Vector(
      Option.when(shown.nonEmpty)(visible(shown)),
      Some(modeName(state.config.mode)),
      Option.when(state.config.model.model.nonEmpty)(visible(state.config.model.model)),
      Effort.of(state.config.model).map(level => s"effort ${visible(level)}"),
      context.map(use => s"context ${contextWords(use, facts.window, "")}")
    ).flatten
    val kept = memory.map(m => s"memory ${thousands(m.chars)} of ${thousands(m.budget)} chars")
    val tail = Vector(
      Option.when(jobs > 0)(count(jobs, "job")),
      Option.when(pending > 0)(if pending == 1 then "1 call waits" else s"$pending calls wait")
    ).flatten
    def line(parts: Vector[String]): String = "  " + parts.mkString(s" ${glyphs.dot} ")
    val full                                = line(head ++ kept ++ tail)
    Row.of(if full.length <= width then full else line(head ++ tail), Tone.Dim)

  /** The context of `use` in words: the prompt tokens, and with a window `window`, the window with `unit` and the
    * percent, as `18k of 1M (2%)` or `18k of 1M tokens (2%)`. With no window, the count with `unit`.
    */
  private[cli] def contextWords(use: ContextUse, window: Option[Long], unit: String): String =
    window.fold(s"${tokens(use.prompt)}$unit")(w =>
      s"${tokens(use.prompt)} of ${tokens(w)}$unit (${percent(use.prompt, w)})"
    )

  /** The rows of `/show id`: a cell (`12`) or a call (`12.0`), in full. An unknown id gives an error text. */
  def show(screen: Screen, id: String, look: Look): Either[String, Vector[Row]] =
    val unknown = Left(s"The session has no cell $id and no call $id.")
    id.split('.') match
      case Array(cell) =>
        cell.toLongOption.flatMap(screen.cells.get).fold(unknown)(c => Right(cellRows(c, Detail.Full, look.glyphs)))
      case Array(cell, n) =>
        val found =
          for
            c    <- cell.toLongOption.flatMap(screen.cells.get)
            k    <- n.toIntOption
            call <- c.calls.find(_.id.n == k)
          yield callRow(call, "  ", look.glyphs) +: resultRows(call, "    ", Int.MaxValue, look.glyphs)
        found.toRight(unknown.value)
      case _ => unknown

  // ── The rows of a step ──

  /** The rows of a step that ends: the reasoning, then the reply, or the text with its cell and the approval cards. */
  private def stepRows(step: StepView, look: Look): Vector[Row] =
    val g = look.glyphs
    reasoningRows(step.reasoning, look.reasoning, g) ++
      (step.cell match
        case None =>
          if step.text.strip.nonEmpty then Row.of("") +: marked(g.reply, Tone.Accent, step.text) else Vector.empty
        case Some(cell) => textRows(step.text) ++ cellRows(cell, look.detail, g) ++
            step.asked.flatMap(askedRows(_, _, g)))

  private def reasoningRows(reasoning: String, view: ReasoningView, g: Glyphs): Vector[Row] =
    val text = reasoning.strip
    if text.isEmpty then Vector.empty
    else
      view match
        case ReasoningView.Off  => Vector.empty
        case ReasoningView.Line =>
          Vector(Row.of(s"  ${g.closed} Reasoning ${g.dot} ${count(text.length, "character")}", Tone.Dim))
        case other => reasoningText(text, other).toVector.flatMap(_.linesIterator).map(line =>
            Row.of("  " + printable(line), Tone.Dim)
          )

  private def textRows(text: String): Vector[Row] =
    printable(text.strip).linesIterator.toVector.filter(_.nonEmpty || text.strip.nonEmpty).map(line =>
      Row.of("  " + line)
    )

  /** `text` with `glyph` in `tone` before its first line, and each other line after two spaces. */
  private def marked(glyph: String, tone: Tone, text: String): Vector[Row] =
    printable(text.strip).linesIterator.toVector.zipWithIndex.map { (line, i) =>
      if i == 0 then Row(Vector(Span(s"$glyph ", tone), Span(line, Tone.Plain))) else Row.of("  " + line)
    }

  private def askedRows(seq: Long, rule: GuardRule, g: Glyphs): Vector[Row] =
    Vector(
      Row.of(s"  ${g.warn} The call $seq needs your approval", Tone.Warn),
      Row.of(s"    ${printable(callText(rule))}"),
      Row.of(
        s"    /allow $seq once ${g.dot} /allow $seq turn ${g.dot} /allow $seq session ${g.dot} or type what to do instead",
        Tone.Dim
      )
    )

  // ── The rows of a cell ──

  /** The summary line of `cell`, or with `Full` its code, each call with its result, and its outcome. */
  private def cellRows(cell: CellView, detail: Detail, g: Glyphs): Vector[Row] =
    val failed = cell.end.exists(e => failure(e.outcome))
    val glyph  = if failed then g.fail else if detail == Detail.Full then g.open else g.closed
    val head   = Row(Vector(
      Span(s"  $glyph ", if failed then Tone.Bad else Tone.Dim),
      Span(summary(cell, g), Tone.Dim),
      Span(s"  #${cell.id}", Tone.Dim)
    ))
    if detail == Detail.Compact then Vector(head)
    else
      head +:
        (codeRows(cell.code, Int.MaxValue, g) ++
          cell.calls.flatMap(c => callRow(c, "    ", g) +: resultRows(c, "      ", ResultLines, g)) ++
          cell.end.toVector.flatMap(outcomeRows(_, g)))

  private def codeRows(code: String, most: Int, g: Glyphs): Vector[Row] =
    val lines = printable(code).linesIterator.toVector
    val shown = lines.take(most).map(line => Row(Vector(Span(s"    ${g.bar} ", Tone.Dim), Span(line, Tone.Plain))))
    if lines.size <= most then shown
    else shown :+ Row.of(s"    ${g.bar} ${g.more} +${count(lines.size - most, "line")}", Tone.Dim)

  /** The line of a call: its glyph, what it does, its exit code or other problem, its time and its id. */
  private def callRow(call: CallView, indent: String, g: Glyphs): Row = callRowWith(call, indent, g, g.ok)

  private def callRowWith(call: CallView, indent: String, g: Glyphs, openGlyph: String): Row =
    val (glyph, tone, notes) = call.end match
      case None              => (openGlyph, Tone.Accent, Vector.empty)
      case Some((result, _)) => result match
          case EffectResult.Value(_, rendered) => exitOf(rendered) match
              case Some(code) => (g.fail, Tone.Bad, Vector(s"exit $code"))
              case None       => (g.ok, Tone.Good, Vector.empty)
          case EffectResult.Error(message)   => (g.fail, Tone.Bad, Vector(cut(firstLine(message), ArgChars, g)))
          case EffectResult.NeedsApproval(_) => (g.warn, Tone.Warn, Vector("waits for your approval"))
          case EffectResult.Unknown          => (g.fail, Tone.Bad, Vector("unknown"))
    val time = call.end.map((_, end) => seconds(end - call.startMillis))
    val text = (callLine(call, g) +: (notes ++ time)).mkString(s" ${g.dot} ")
    // The id is for `/show`, so only a call that ended shows it.
    val id = call.end.map(_ => Span(s"  #${jobName(call.id)}", Tone.Dim))
    Row(Vector(Span(s"$indent$glyph ", tone), Span(text, Tone.Plain)) ++ id)

  /** The result of a call, at most `most` lines, after `indent`. */
  private def resultRows(call: CallView, indent: String, most: Int, g: Glyphs): Vector[Row] =
    val text = call.end.map(_._1) match
      case Some(EffectResult.Value(_, rendered)) => rendered
      case Some(EffectResult.Error(message))     => message
      case _                                     => ""
    cutRows(text, indent, most, g)

  private def outcomeRows(end: CellEnd, g: Glyphs): Vector[Row] =
    def line(ok: Boolean, text: String): Row =
      Row(Vector(
        Span(s"    ${if ok then g.ok else g.fail} ", if ok then Tone.Good else Tone.Bad),
        Span(text, Tone.Plain)
      ))
    val lines  = printable(end.output).linesIterator.toVector
    val output = cutRows(end.output, "      ", OutputLines, g)
    end.outcome match
      case CellOutcome.Ok                => line(true, s"The cell ended ${g.dot} ${count(lines.size, "line")} of output") +: output
      case CellOutcome.Submitted(_)      => line(true, "The cell submitted a value") +: output
      case CellOutcome.CompileError(all) =>
        line(false, s"The cell did not compile ${g.dot} ${count(all.size, "error")}") +:
          all.flatMap(d => cutRows(d, "      ", OutputLines, g))
      case CellOutcome.Failed(exception) =>
        line(false, s"The cell failed: ${cut(firstLine(exception), ArgChars, g)}") +: output
      case CellOutcome.TimedOut(millis) =>
        line(false, s"The cell stopped at its time limit ${g.dot} ${millis / 1000} s") +: output

  /** The lines of `text` after `indent`, at most `most`, and a note of the rest. */
  private def cutRows(text: String, indent: String, most: Int, g: Glyphs): Vector[Row] =
    val lines = printable(text).linesIterator.toVector
    val shown = lines.take(most).map(line => Row.of(indent + line, Tone.Dim))
    if lines.size <= most then shown
    else shown :+ Row.of(s"$indent${g.more} +${count(lines.size - most, "line")}", Tone.Dim)

  /** The summary of a cell: what its calls did, its problem, and its time. */
  private def summary(cell: CellView, g: Glyphs): String =
    val verbs  = callVerbs(cell.calls)
    val dot    = s" ${g.dot} "
    val time   = cell.end.map(e => seconds(e.atMillis - cell.startMillis))
    val output = cell.end.map(e => printable(e.output).linesIterator.size).filter(_ > 0)
    cell.end.map(_.outcome) match
      case Some(CellOutcome.CompileError(all)) => s"The cell did not compile${dot}${count(all.size, "error")}"
      case Some(CellOutcome.Failed(exception)) =>
        val problem = cut(firstLine(exception), ArgChars, g)
        if verbs.isEmpty then s"The cell failed: $problem" else s"${capital(verbs)}, then the cell failed: $problem"
      case Some(CellOutcome.TimedOut(millis)) => s"The cell stopped at its time limit$dot${millis / 1000} s"
      case outcome                            =>
        val base =
          if verbs.isEmpty then "Ran a cell" +: output.map(n => s"${count(n, "line")} of output").toVector
          else Vector(capital(verbs))
        val exit = cell.calls.iterator.flatMap(c => c.end.collect { case (EffectResult.Value(_, r), _) => r }).flatMap(
          exitOf
        ).nextOption()
        val asked = Option.when(cell.calls.exists(_.end.exists {
          case (EffectResult.NeedsApproval(_), _) => true
          case _                                  => false
        }))("waits for your approval")
        val submit = outcome.collect { case CellOutcome.Submitted(_) => "submitted" }
        (base ++ exit.map(c => s"exit $c") ++ asked ++ submit ++ time).mkString(dot)

  /** What the calls of a cell did, in the order of the first call of each kind. */
  private def callVerbs(calls: Vector[CallView]): String =
    val kinds = calls.map(c => kindOf(c.op) -> c)
    kinds.map(_._1).distinct.map { kind =>
      val same = kinds.collect { case (k, c) if k == kind => c }
      kind match
        case "exec"  => s"ran ${count(same.size, "command")}"
        case "spawn" => if same.size == 1 then s"started job ${jobName(same.head.id)}" else s"started ${same.size} jobs"
        case "read"  => s"read ${count(same.size, "file")}"
        case "write" => s"wrote ${count(same.size, "file")}"
        case "edit"  => s"edited ${count(same.size, "file")}"
        case "ask"   => s"asked the model ${count(same.map(questions).sum, "question")}"
        case "rlm"   => s"ran ${count(same.size, "sub-program")}"
        case "load"  => "read the store"
        case "store" => "wrote the store"
        case "zoom"  => "read the memory"
        case "jobs"  => s"looked at ${count(same.size, "job")}"
        case other   => s"called $other"
    }.mkString(", ")

  private def kindOf(op: String): String =
    op match
      case "llmQuery" | "llmQueryBatched" => "ask"
      case "rlm" | "rlmBatched"           => "rlm"
      case "load" | "keys"                => "load"
      case "stopJob" | "jobOutput"        => "jobs"
      case other                          => other

  /** The questions of a call to the model: the prompts of a batch, else one. */
  private def questions(call: CallView): Int =
    call.args match
      case Json.Object(fields) => fields.collectFirst { case ("prompts", Json.Array(items)) => items.size }.getOrElse(1)
      case _                   => 1

  /** What a call does, on one line. */
  private def callLine(call: CallView, g: Glyphs): String =
    def field(name: String): String =
      call.args match
        case Json.Object(fields) => fields.collectFirst { case (`name`, Json.String(text)) => text }.getOrElse("")
        case _                   => ""
    def arg(name: String): String = oneLine(field(name), g)
    call.op match
      case "exec"            => s"$$ ${arg("command")}"
      case "spawn"           => s"$$ ${arg("command")} ${g.dot} job ${jobName(call.id)}"
      case "read"            => s"read ${arg("path")}"
      case "write"           => s"write ${arg("path")}"
      case "edit"            => s"edit ${arg("path")}"
      case "llmQuery"        => "ask the model"
      case "llmQueryBatched" => s"ask the model ${g.dot} ${count(questions(call), "question")}"
      case "rlm"             => s"run a sub-program: ${arg("task")}"
      case "rlmBatched"      => "run sub-programs"
      case "load"            => s"load ${arg("key")}"
      case "store"           => s"store ${arg("key")}"
      case "keys"            => "keys of the store"
      case "zoom"            => s"zoom ${arg("ref")}"
      case "stopJob"         => "stop a job"
      case "jobOutput"       => "read the output of a job"
      case other             => s"${visible(other)} ${cut(visible(call.args.print), ArgChars, g)}"

  /** The first line of `text` with each hidden character as an escape, cut, and a note of the other lines. */
  private def oneLine(text: String, g: Glyphs): String =
    val lines = text.linesIterator.toVector
    val first = cut(visible(lines.headOption.getOrElse("")), ArgChars, g)
    if lines.size <= 1 then first else s"$first (+${count(lines.size - 1, "line")})"

  // ── The live block and the end of a turn ──

  /** The rows of the draft of a step (CLI_SCREEN.md, 13): the reasoning in the view of `look`, then the last lines of
    * the text with a note of the lines above them.
    */
  private def draftRows(draft: Draft, look: Look): Vector[Row] =
    val g         = look.glyphs
    val reasoning = look.reasoning match
      case ReasoningView.Short => lastRows(draft.thought, DraftShort, s"  ${g.bar} ", Tone.Dim, g)
      case ReasoningView.Full  => lastRows(draft.thought, DraftFull, s"  ${g.bar} ", Tone.Dim, g)
      case other               => reasoningRows(draft.thought, other, g)
    reasoning ++ lastRows(draft.text, DraftText, "  ", Tone.Plain, g)

  /** The last `most` lines of `text` after `prefix`, in `tone`, with a dim row of the number of lines above them. */
  private def lastRows(text: String, most: Int, prefix: String, tone: Tone, g: Glyphs): Vector[Row] =
    val lines = if text.strip.isEmpty then Vector.empty else printable(text.strip).linesIterator.toVector
    val shown = lines.takeRight(most)
    val above = lines.size - shown.size
    Option.when(above > 0)(Row.of(s"$prefix${g.more} +${count(above, "line")}", Tone.Dim)).toVector ++
      shown.map(line => Row.of(prefix + line, tone))

  private def liveStep(step: StepView, frame: String, look: Look): Vector[Row] =
    val g = look.glyphs
    textRows(step.text) ++ step.cell.toVector.flatMap { cell =>
      val most = if look.detail == Detail.Full then Int.MaxValue else CodeLines
      Row(Vector(Span(s"  ${g.open} ", Tone.Dim), Span(s"Cell #${cell.id}", Tone.Dim))) +:
        (codeRows(cell.code, most, g) ++ cell.calls.flatMap { c =>
          callRowWith(c, "    ", g, frame) +:
            (if look.detail == Detail.Full then resultRows(c, "      ", ResultLines, g) else Vector.empty)
        })
    }

  private def statusRow(
      turn     : TurnView,
      step     : Option[StepView],
      state    : SessionState,
      nowMillis: Long,
      frame    : String,
      activity : Option[Activity],
      g        : Glyphs
  ): Row =
    val open  = step.flatMap(_.cell).filter(_.end.isEmpty)
    val label =
      if turn.stopping then Some("the turn stops")
      else
        activity match
          case Some(Activity.MemorySummaries(done, total)) if step.exists(!_.acted) =>
            Some(s"the memory: $done of $total summaries")
          case Some(Activity.SecretScan(folders, total))
              if open.exists(_.calls.exists(c => c.end.isEmpty && (c.op == "exec" || c.op == "spawn"))) =>
            Some(total.fold(s"the scan for secret files: ${grouped(folders)} folders")(t =>
              s"the scan for secret files: ${grouped(folders)} of ${grouped(t)} folders"
            ))
          case _ => None
    val parts = Vector(Some(whole(nowMillis - turn.startMillis))) ++ Vector(
      turn.steps.lastOption.map(s => s"step ${s.step + 1} of ${state.config.steps}"),
      label,
      Some("ctrl-c stops"),
      Option.when(open.isDefined)("ctrl-o details")
    )
    Row(Vector(Span(s"$frame ", Tone.Accent), Span(parts.flatten.mkString(s" ${g.dot} "), Tone.Dim)))

  private def endRows(turn: Option[TurnView], outcome: TurnOutcome, at: Long, g: Glyphs): Vector[Row] =
    val steps = turn.fold(0)(_.steps.size)
    val cells = turn.fold(0)(_.steps.count(_.cell.isDefined))
    val facts = s" ${g.dot} ${count(steps, "step")} ${g.dot} ${count(cells, "cell")} ${g.dot} " +
      seconds(at - turn.fold(at)(_.startMillis))
    val (mark, tone, words) = outcome match
      case TurnOutcome.Replied(_)    => ("", Tone.Dim, "Done")
      case TurnOutcome.Submitted(_)  => ("", Tone.Dim, s"Done ${g.dot} submitted")
      case TurnOutcome.Aborted       => (s"${g.stop} ", Tone.Warn, "Stopped")
      case TurnOutcome.Exhausted     => ("", Tone.Dim, "Step limit")
      case TurnOutcome.Failed(error) =>
        (s"${g.fail} ", Tone.Bad, s"Failed: ${cut(firstLine(printable(error)), ArgChars, g)}")
      case TurnOutcome.Crashed => (s"${g.fail} ", Tone.Bad, "The turn crashed")
    Vector(Row.of(""), Row(Vector(Span(s"  $mark", tone), Span(words + facts, Tone.Dim))))

  // ── Words, numbers and times ──

  private def failure(outcome: CellOutcome): Boolean =
    outcome match
      case CellOutcome.CompileError(_) | CellOutcome.Failed(_) | CellOutcome.TimedOut(_) => true
      case _                                                                             => false

  private def exitOf(rendered: String): Option[Int] =
    ExitCode.findAllMatchIn(rendered).toVector.lastOption.flatMap(_.group(1).toIntOption).filter(_ != 0)

  private def count(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  private def capital(text: String): String = text.take(1).toUpperCase + text.drop(1)

  private def firstLine(text: String): String = text.linesIterator.nextOption().getOrElse("").strip

  private def cut(text: String, most: Int, g: Glyphs): String =
    if text.length <= most then text else textPrefix(text, most) + g.more

  /** A time with one decimal under ten seconds, whole seconds under a minute, then minutes and seconds. */
  private def seconds(millis: Long): String =
    val ms = millis.max(0L)
    if ms < 10_000 then "%.1f s".formatLocal(java.util.Locale.ROOT, ms / 1000.0)
    else whole(ms)

  /** A time in whole seconds under a minute, then minutes and seconds. */
  private def whole(millis: Long): String =
    val s = millis.max(0L) / 1000
    if s < 60 then s"$s s" else s"${s / 60} m ${s % 60} s"

  /** A number with a comma for each group of three digits. */
  private def grouped(n: Long): String = "%,d".formatLocal(java.util.Locale.ROOT, n)

  /** A number of characters in thousands, for the footer. */
  private def thousands(n: Int): String = if n >= 1000 then s"${n / 1000}k" else n.toString

  /** A count of tokens in a few characters: a count under 1,000 as it is, a count under 1,000,000 in whole thousands
    * (`18k`), and a larger count in millions with one decimal and no `.0` (`1M`, `1.6M`).
    */
  private[cli] def tokens(n: Long): String =
    if n < 1_000 then n.toString
    else if n < 1_000_000 then s"${n / 1_000}k"
    else
      val millions = (BigDecimal(n) / 1_000_000).setScale(1, BigDecimal.RoundingMode.HALF_UP)
      s"${millions.bigDecimal.stripTrailingZeros.toPlainString}M"

  /** The part `part` of `whole` as a whole percent, or `<1%` under 1%. */
  private def percent(part: Long, whole: Long): String =
    val exact = part.toDouble * 100 / whole
    if exact < 1 then "<1%" else s"${math.round(exact)}%"

  private def modeName(mode: Mode): String =
    if mode == WorkRuntime.workMode then "work mode"
    else if mode == Mode.full then "full mode"
    else if mode == Mode.local then "local mode"
    else if mode == Mode.readOnly then "read-only mode"
    else if mode == Mode.controller then "controller mode"
    else "custom mode"

  private def folderOf(state: SessionState): String =
    state.config.scope match
      case Scope.Home(path)              => path
      case Scope.Directory(path)         => path
      case Scope.Worktree(path, _, _, _) => path
      case Scope.Scratch(path)           => path
      case Scope.Fork(path, _)           => path

  private def folderName(state: SessionState): String =
    val folder = folderOf(state)
    visible(folder.split('/').filter(_.nonEmpty).lastOption.getOrElse(folder))

  private def flush(screen: Screen, look: Look): (Screen, Vector[Row]) =
    screen.turn.flatMap(_.steps.lastOption).filterNot(_.printed) match
      case Some(step) => (screen.withStep(_.copy(printed = true)), stepRows(step, look))
      case None       => (screen, Vector.empty)

  extension (screen: Screen)
    private def withTurn(f: TurnView => TurnView): Screen = screen.copy(turn = screen.turn.map(f))
    private def withStep(f: StepView => StepView): Screen =
      screen.withTurn(t => t.steps.lastOption.fold(t)(last => t.copy(steps = t.steps.init :+ f(last))))
    private def withCell(f: CellView => CellView): Screen =
      val changed = screen.withStep(s => s.copy(cell = s.cell.map(f)))
      changed.turn.flatMap(_.steps.lastOption).flatMap(_.cell).fold(changed)(c =>
        changed.copy(cells = changed.cells.updated(c.id, c))
      )
