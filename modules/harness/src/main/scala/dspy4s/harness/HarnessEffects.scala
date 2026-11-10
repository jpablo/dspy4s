package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.kernel.SessionState
import dspy4s.harness.repl.ScalaRepl
import dspy4s.programs.strategies.Predict

import scala.annotation.nowarn

/** The effects of a turn of a harness (CODE_HARNESS_DESIGN.md, 4.6 and 9).
  *
  *   - `callPolicy` updates the derived data of `strategy`, projects the log of `journal` into the budget of `limits`,
  *     and calls `policy` with the task and the API reference. A step that must answer gets a note that it is the last
  *     one, and its code does not run. The trace of the call goes to the `RuntimeContext`, so an optimizer sees it.
  *   - `runCell` starts the calls of the cell in `recorder`, takes the snapshot of the store, and runs the code in
  *     `repl`. The writes of the store go into the result only when the cell completes.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
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
  def callPolicy(state: SessionState, mustAnswer: Boolean): Either[String, PolicyReply] = ???
  def runCell(cell    : Long, code              : String): CellResult                   = ???
