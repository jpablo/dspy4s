package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.kernel.{CellOutcome, Command, Source}
import dspy4s.harness.repl.{ScalaRepl, lostNames}
import dspy4s.programs.strategies.Predict

/** The effects of a turn of a harness (CODE_HARNESS_DESIGN.md, 4.6 and 9).
  *
  *   - `callPolicy` updates the derived data of `strategy`, projects the log of `journal` into the budget of `limits`,
  *     and calls `policy` with the task and the API reference. The status says when a step is the last one, and the
  *     code of a step that must answer does not run. A code that is empty, `null` or `None` is no code, because the
  *     chat adapter keeps the text of an empty field (9). The trace of the call goes to the `RuntimeContext`, so an
  *     optimizer sees it.
  *   - `runCell` starts the calls of the cell in `recorder`, takes the snapshot of the store, and runs the code in the
  *     current REPL. The writes of the store go into the result only when the cell completes. The output in the journal
  *     is `CellRun.text`, the text that the MCP server also shows (6): the hints of a compile error, and a note of a
  *     failure, a timeout or a submit. It has at most `limits.maxObservation` characters, with a note of the rest. When
  *     the thread of the cell still runs, the heap guard closed the REPL, or the REPL is full (6), `runCell` closes
  *     `recorder` and stops the turn (9).
  *   - When the turn goes on and the compiler cannot go on after the error of the cell (`ScalaRepl.needsRestart`),
  *     `runCell` closes the REPL and starts a new one from `newRepl`, with the same mode, bindings, preamble and cell
  *     time limit (6). The store, the files and the journal stay. After the output, the observation has a note with the
  *     names of earlier cells that the new REPL does not have, from `lostNames`. Each restart starts a new REPL.
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
    limits   : HarnessLimits
)(using RuntimeContext)
    extends TurnEffects:

  private var derived: strategy.D = strategy.empty
  private var repl: ScalaRepl     = newRepl()

  def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply] =
    val log = journal.log
    strategy.update(log, derived).left.map(_.message).flatMap { data =>
      derived = data
      val view  = strategy.project(log, data, limits.budget)
      val input = PolicyInput(task, reference, view.status, view.observation, view.history)
      policy(input).left.map(_.message).map { step =>
        val code = step.output.code.filterNot(c => HarnessEffects.NoCode.contains(c.strip))
        // The code of the last step of a chat does not run (4.3).
        PolicyReply(step.output.reasoning, step.output.text, if mustAnswer then None else code)
      }
    }

  def runCell(cell: Long, code: String): CellResult =
    recorder.startCell(cell)
    store.begin(journal.state.store)
    val run  = repl.run(code)
    val stop = HarnessEffects.unsafe(repl)
    stop.foreach { reason =>
      // A thread of the cell can still run. After the close, its calls get no id and reach no handler.
      recorder.close()
      journal.run(Command.Abort(reason), Source.Harness): Unit
    }
    val writes = run.outcome match
      case CellOutcome.Ok | CellOutcome.Submitted(_) => store.writes
      case _                                         => Map.empty
    // A turn that stops runs no more cells, so it needs no new REPL.
    val note = if stop.isEmpty && repl.needsRestart then Some(restart()) else None
    CellResult(Window.cut(run.text, limits.maxObservation) + note.fold("")("\n" + _), run.outcome, writes)

  /** Closes the current REPL. */
  def close(): Unit = repl.close()

  /** Closes the REPL that the compiler cannot use, starts a new one, and gives the note of the lost names (6). The
    * failed cell defines nothing, and the journal does not have its `Observed` yet, so the note comes from the log.
    */
  private def restart(): String =
    repl.close()
    repl = newRepl()
    HarnessEffects.restartNote(lostNames(journal.log).map(_._1).distinct)

object HarnessEffects:

  /** The texts of a code field that hold no code. The chat adapter keeps the text `null`, as DSPy does (9). */
  private val NoCode: Set[String] = Set("", "null", "None")

  /** The note after a new REPL (6), with the names of earlier cells that the new REPL does not have. A cell that did
    * not compile never ran, so it repeats no side effect, and the note tells the policy that it can run the cell again.
    */
  private def restartNote(names: Vector[String]): String =
    val head = "The harness started a new REPL, because the compiler could not go on after this error."
    val lost =
      if names.isEmpty then "No names of earlier cells were lost."
      else s"These names of earlier cells are gone: ${names.mkString(", ")}. Define them again if you need them."
    s"$head $lost Then you can run the failed cell again."

  /** The reason to stop the turn after a cell, when the REPL cannot run the next cell safely (6). */
  private def unsafe(repl: ScalaRepl): Option[String] =
    val reasons = Vector(
      repl.unclean     -> "the thread of a cell did not stop after its timeout",
      repl.outOfMemory -> "the heap guard stopped a cell and closed the REPL",
      repl.full        -> "the REPL ran its limit of cells"
    ).collect { case (true, reason) => reason }
    Option.when(reasons.nonEmpty)(reasons.mkString("; ") + ", so the harness stopped the turn")
