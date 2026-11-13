package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, ParseError, RuntimeContext}
import dspy4s.core.runtime.{UsageScope, UsageTracker}
import dspy4s.harness.kernel.{CellOutcome, Command, Position, Source}
import dspy4s.harness.repl.{CellRun, ScalaRepl, lostNames, topLevelNames}
import dspy4s.programs.strategies.Predict
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.json.Json

/** The effects of a turn of a harness (CODE_HARNESS_DESIGN.md, 4.6 and 9).
  *
  *   - `callPolicy` updates the derived data of `strategy`, projects the log of `journal` into the budget of `limits`,
  *     spends one call of the budget that `budget` gives (the budget of the program call, or of the turn of a work
  *     session), and calls `policy` with the task and the API reference. A spent budget fails the call with a plain
  *     error, so the turn ends `Failed` (8.2). The status says when a step is the last one, and the code of a step that
  *     must answer does not run. The call has the adapter of the context in a `PolicyAdapter`, so a reply with no
  *     text field gets a step from the rules of `PolicyAdapter.values`, with no second model call. A code in one outer
  *     ``` fence is the code in the fence (`unfenced`), so `Acted` and the history hold no fence. A code that is
  *     empty, `null` or `None` is no code, because the chat adapter keeps the text of an empty field (9). The error of
  *     a reply that did not parse has the start of the reply (`failure`). The trace of the call goes to the
  *     `RuntimeContext`, so an optimizer sees it. The call of `policy` runs through `runner`, so a work session can
  *     cut it (10.3). After the update, `zooms` gets the reader `strategy.zoom` of the new derived data, so `zoom` of a
  *     cell reads it (7.1). After the projection, `progress` gets its size and the budget, so a client shows them
  *     (10.6). With `drafts`, the call streams: each model call of the policy writes its draft there while it runs,
  *     and the draft goes away at the end of the call (CLI_SCREEN.md, 13). The stream is a preview, so the step is the
  *     same as with no `drafts`. Each call has the options of the model of the session in the state of the journal, so
  *     a `ModelChanged` of this activation applies to the next call (CLI_SCREEN.md, 14.4). At the end of the call,
  *     `progress` gets the prompt and completion tokens of its last model call, when the reply had a usage.
  *   - `runCell` starts the calls of the cell in `recorder`, takes the snapshot of the store, and runs the code in the
  *     current REPL. The writes of the store go into the result only when the cell completes. The output in the journal
  *     is `CellRun.text`, the text that the MCP server also shows (6): the hints of a compile error, and a note of a
  *     failure, a timeout or a submit. It has at most `limits.maxObservation` characters, with a note of the rest. The
  *     hints of a compile error are at the end of the text, so each hint that the cut removed comes after the cut text.
  *     When the thread of the cell still runs, or the heap guard closed the REPL (6), `runCell` closes `recorder` and
  *     stops the turn (9). After the cell, `host` joins the trace of the queries and the children of the cell into the
  *     trace of this thread. Only the top session has `host`. A child gives its trace to the queue and does not join,
  *     so the top session gets the trace of the whole tree in the order of the timestamps (`HostThreads.join`).
  *   - When another cell can come after this cell, and the compiler cannot go on after the error of the cell
  *     (`ScalaRepl.needsRestart`) or the REPL is full (`ScalaRepl.full`), `runCell` closes the REPL and starts a new
  *     one from `newRepl`, with the same mode, bindings, preamble and limits (6). The store, the files and the journal
  *     stay. After the output, the observation has a note with the reason and with the names of the cells that the new
  *     REPL does not have, from `lostNames`. Each restart starts a new REPL. No cell comes after a cell that ended by
  *     `submit` (5.1), after the cell of the last step (4.3), or after a stop, so these cells start no new REPL.
  *
  * The effects own the current REPL: the first one starts with the effects, and `close` closes the current one. When a
  * new REPL cannot start, its exception goes on to the caller.
  */
final class HarnessEffects(
    task     : String,
    reference: String,
    policy   : Predict[PolicyInput, PolicyStep],
    strategy : ContextStrategy,
    journal  : Journal,
    newRepl  : () => ScalaRepl,
    recorder : Recorder,
    store    : SnapshotStore,
    limits   : HarnessLimits,
    budget   : () => Budget,
    host     : Option[HostThreads],
    runner   : PolicyRunner   = PolicyRunner.Direct,
    zooms    : ZoomSource     = ZoomSource(),
    progress : MemoryProgress = MemoryProgress.none,
    drafts   : Option[Drafts] = None
)(using RuntimeContext)
    extends TurnEffects:

  private var derived: strategy.D = strategy.empty
  private var repl: ScalaRepl     = newRepl()

  /** The context of a policy call: the context of the effects, with the adapter in a `PolicyAdapter` (9). */
  private val policyContext: RuntimeContext = PolicyAdapter.around(summon[RuntimeContext])

  def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply] =
    val log = journal.log
    strategy.update(log, derived).left.map(_.message).flatMap { data =>
      derived = data
      zooms.setReader(ref => strategy.zoom(data, ref))
      val view = strategy.project(log, data, limits.budget)
      progress.projected(view.size, limits.budget)
      val input   = PolicyInput(task, reference, view.memory, view.status, view.observation, view.history)
      val number  = drafts.map(d => d -> d.begin())
      val context = number.fold(policyContext)((d, n) => PolicyStream.around(policyContext, d, n))
      // The model of the session now: a `ModelChanged` of this activation changes the next call (CLI_SCREEN.md, 14).
      val options = HarnessEffects.optionsOf(journal.state.config.model.options)
      val tokens  = UsageTracker()
      val call    = () => UsageScope.track(tokens)(policy(input, options)(using context)).left.map(HarnessEffects.failure)
      try
        budget().spend().flatMap(_ => runner.run(call)).map { step =>
          val code =
            step.output.code.map(HarnessEffects.unfenced).filterNot(c => HarnessEffects.NoCode.contains(c.strip))
          // The code of the last step of a chat does not run (4.3).
          PolicyReply(step.output.reasoning, step.output.text, if mustAnswer then None else code)
        }
      finally
        number.foreach((d, n) => d.end(n))
        HarnessEffects.lastUsage(tokens).foreach(u => progress.used(u.promptTokens, u.completionTokens))
    }

  def runCell(cell: Long, code: String): CellResult =
    val last = HarnessEffects.lastStep(journal)
    recorder.startCell(cell)
    store.begin(journal.state.store)
    val run = repl.run(code)
    host.foreach(_.join())
    val stop = HarnessEffects.unsafe(repl)
    stop.foreach { reason =>
      // A thread of the cell can still run. After the close, its calls get no id and reach no handler.
      recorder.close()
      journal.run(Command.Abort(reason), Source.Harness): Unit
    }
    val writes = run.outcome match
      case CellOutcome.Ok | CellOutcome.Submitted(_) => store.writes
      case _                                         => Map.empty
    // A turn that stops, a cell that submitted and the cell of the last step have no next cell, so no new REPL (6).
    val next = stop.isEmpty && !last && !HarnessEffects.submitted(run.outcome)
    val note = if next then HarnessEffects.restartReason(repl).map(restart(code, run, _)) else None
    CellResult((HarnessEffects.observation(run, limits.maxObservation) ++ note).mkString("\n"), run.outcome, writes)

  /** Closes the current REPL. */
  def close(): Unit = repl.close()

  /** Stops the running cell of the current REPL, and keeps the REPL (10.3, phase 7: the hard stop of a session). */
  def stopCell(): Unit = repl.stopCell()

  /** True when a cell thread of the current REPL did not stop (protection 3 of Phase 0). */
  def unclean: Boolean = repl.unclean

  /** Closes the current REPL, starts a new one, and gives the note of the lost names (6). The journal does not have the
    * `Observed` of this cell yet. So the note takes the names of the earlier cells from the log, and the names of this
    * cell from its code when it completed. A failed cell defines nothing.
    */
  private def restart(code: String, run: CellRun, reason: String): String =
    repl.close()
    repl = newRepl()
    val defined = if run.outcome == CellOutcome.Ok then topLevelNames(code).distinct else Vector.empty
    val earlier = lostNames(journal.log).map(_._1).distinct.filterNot(defined.contains)
    HarnessEffects.restartNote(reason, earlier ++ defined, run.outcome)

object HarnessEffects:

  /** The option bag of the provider of `options`, the options of the model of a session: the options of each request of
    * a call to the policy, for example `reasoning_effort` (CLI_SCREEN.md, 14.4).
    */
  private[harness] def optionsOf(options: Map[String, Json]): DynamicValue.Record =
    DynamicValues.recordFromEntries(options.toVector.sortBy(_._1).map((key, value) => key -> value.toDynamicValue))

  /** The usage of the last model call of `tokens`, or none. A step makes a second model call only for the fallback
    * adapter, and the prompt of the last call is the context of the step.
    */
  private def lastUsage(tokens: UsageTracker): Option[LmUsage] =
    tokens.usageData.values.flatMap(_.lastOption).lastOption

  /** The texts of a code field that hold no code. The chat adapter keeps the text `null`, as DSPy does (9). */
  private val NoCode: Set[String] = Set("", "null", "None")

  /** The most characters of the start of a reply in the error of a policy call. */
  private val ReplyStart = 200

  /** The error text of a failed policy call. The parse error of an adapter does not show the reply. So for a reply that
    * did not parse, the text also has the start of the reply, at most `ReplyStart` characters, and a person sees what
    * the model wrote (9). After a fallback, the reply is the reply to the fallback. The journal masks the known secrets
    * of the text, as it does for each entry (5.6).
    */
  private def failure(error: DspyError): String =
    error match
      case ParseError(_, message, Some(raw)) if raw.strip.nonEmpty =>
        val start = raw.strip
        // A surrogate pair stays whole, because the writer of a session file refuses a surrogate with no pair.
        val shown = if start.length <= ReplyStart then start else s"${textPrefix(start, ReplyStart)} [cut]"
        s"${message.stripSuffix(".")}. The start of the reply: $shown"
      case other => other.message

  /** The code of a code field (9). A model can put the code in one Markdown fence: a first line of ``` with an optional
    * word for the language, and ``` at the end. A fence does not parse, so the code is the text between the first line
    * and the last ```. The white space around the field does not count. Only the outer fence goes, so a fence inside
    * the code, for example in a string, stays. A text with no outer fence stays as it is. A Scala code cannot start
    * with ```, so the rule changes no code that parses. The live sessions of 2026-11-10 gave this rule.
    */
  private def unfenced(field: String): String =
    field.strip match
      case Fence(code) => code
      case _           => field

  /** One outer fence around a whole text: ``` and an optional word for the language on the first line, and ``` at the
    * end, on a line of its own or after the last line of the code. The group is the code between the two.
    */
  private val Fence = """(?s)```[\w+-]*[ \t]*\r?\n(.*?)\r?\n?```""".r

  /** The text of a cell, cut to `max` characters (6). The hints of a compile error are at the end of the text, so each
    * hint that the cut removed comes after the cut text.
    */
  private def observation(run: CellRun, max: Int): Vector[String] =
    val shown = Window.cut(run.text, max)
    val hints = run.outcome match
      case CellOutcome.CompileError(lines) => lines.filter(_.startsWith("Hint:")).filterNot(shown.contains)
      case _                               => Vector.empty
    shown +: hints

  /** True when the open cell of `journal` is in the last step that can run a cell (4.3). A program turn ends after the
    * step `steps - 1`, and the step `steps` of a chat only answers, because its code does not run.
    */
  private def lastStep(journal: Journal): Boolean =
    journal.state.position match
      case Position.InCell(step, _, _) => step + 1 >= journal.state.config.steps
      case _                           => false

  /** True for a cell that ended by `submit`, which ends the turn (5.1). */
  private def submitted(outcome: CellOutcome): Boolean =
    outcome match
      case CellOutcome.Submitted(_) => true
      case _                        => false

  /** The reason for a new REPL after a cell (6), or none. The compiler reason comes first, because its note tells the
    * policy that it can run the failed cell again.
    */
  private def restartReason(repl: ScalaRepl): Option[String] =
    if repl.needsRestart then Some("the compiler could not go on after this error")
    else if repl.full then Some("the REPL ran its limit of cells")
    else None

  /** The note after a new REPL (6), with its reason and the names of the cells that the new REPL does not have. A new
    * activation gives the same text (`NewReplNote`). A cell that did not compile never ran, so it repeats no side
    * effect, and the note tells the policy that it can run the cell again. A cell that ran can have side effects, so
    * the note does not tell the policy to run it again.
    */
  private def restartNote(reason: String, names: Vector[String], outcome: CellOutcome): String =
    val again = outcome match
      case CellOutcome.CompileError(_) => " Then you can run the failed cell again."
      case _                           => ""
    NewReplNote.text(reason, names) + again

  /** The reason to stop the turn after a cell, when the REPL cannot run the next cell safely (6). A full REPL does not
    * stop the turn: the harness starts a new REPL for it (`restartReason`).
    */
  private def unsafe(repl: ScalaRepl): Option[String] =
    val reasons = Vector(
      repl.unclean     -> "the thread of a cell did not stop after its timeout",
      repl.outOfMemory -> "the heap guard stopped a cell and closed the REPL"
    ).collect { case (true, reason) => reason }
    Option.when(reasons.nonEmpty)(reasons.mkString("; ") + ", so the harness stopped the turn")
