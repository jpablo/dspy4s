package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.kernel.{CellOutcome, SessionState}
import dspy4s.harness.repl.ScalaRepl
import dspy4s.programs.strategies.Predict

/** The effects of a turn of a harness (CODE_HARNESS_DESIGN.md, 4.6 and 9).
  *
  *   - `callPolicy` updates the derived data of `strategy`, projects the log of `journal` into the budget of `limits`,
  *     and calls `policy` with the task and the API reference. A step that must answer gets a note that it is the last
  *     one, and its code does not run. The trace of the call goes to the `RuntimeContext`, so an optimizer sees it.
  *   - `runCell` starts the calls of the cell in `recorder`, takes the snapshot of the store, and runs the code in
  *     `repl`. The writes of the store go into the result only when the cell completes. The output in the journal has
  *     at most `limits.maxObservation` characters, with a note of the rest.
  */
final class HarnessEffects(
    task     : String,
    reference: String,
    policy   : Predict[PolicyInput, PolicyStep],
    strategy : ContextStrategy,
    journal  : Journal,
    repl     : ScalaRepl,
    recorder : Recorder,
    store    : SnapshotStore,
    limits   : HarnessLimits
)(using RuntimeContext)
    extends TurnEffects:

  private var derived: strategy.D = strategy.empty

  def callPolicy(state: SessionState, mustAnswer: Boolean): Either[String, PolicyReply] =
    val log = journal.log
    strategy.update(log, derived).left.map(_.message).flatMap { data =>
      derived = data
      val view   = strategy.project(log, data, limits.budget)
      val status = if mustAnswer then view.status + " " + HarnessEffects.LastStep else view.status
      policy(PolicyInput(task, reference, status, view.observation, view.history)).left.map(_.message).map { step =>
        // The code of the last step of a chat does not run (4.3).
        PolicyReply("", step.output.text, if mustAnswer then None else step.output.code.filter(_.trim.nonEmpty))
      }
    }

  def runCell(cell: Long, code: String): CellResult =
    recorder.startCell(cell)
    store.begin(journal.state.store)
    val run    = repl.run(code)
    val writes = run.outcome match
      case CellOutcome.Ok | CellOutcome.Submitted(_) => store.writes
      case _                                         => Map.empty
    CellResult(Window.cut(run.output, limits.maxObservation), run.outcome, writes)

object HarnessEffects:

  /** The note of a step that must answer. */
  val LastStep: String = "This is the last step: give the answer as text, with no code."
