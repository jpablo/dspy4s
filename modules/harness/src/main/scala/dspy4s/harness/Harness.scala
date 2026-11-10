package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.harness.guest.Data
import dspy4s.programs.contracts.{Module, ModuleLifecycle, Prediction, ProgramCall}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature

import java.nio.file.Path
import scala.annotation.nowarn

/** A harness for one task (9): a dspy4s `Module`, so the optimizers take it.
  *
  * `forward` copies the workspace, starts a journal in memory and a REPL with the handlers of the mode, and sends an
  * `Input` with the values of the input fields (5.1). Then it runs `drive` and reads the last `TurnEnded`. `Submitted`
  * gives the output through `Data[O]`. `Replied` and `Exhausted` run the `finisher` leaf. `Failed`, `Aborted` and
  * `Crashed` give a `DspyError`. This path needs no writer and no thread other than the cell thread.
  *
  * A proposal for the review: phase 4 has only the leaves `policy` and `finisher`, the leaves that run in a program
  * call. `subQuery` comes with `llmQuery` in phase 5, and `goal` with the goals of sessions in phase 7.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final case class Harness[I: Data, O: Data](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],
    finisher : Predict[FinishInput, O],
    strategy : ContextStrategy,
    env      : HarnessEnv,
    limits   : HarnessLimits
) extends Module[I, O]:
  val moduleName: String = "harness"

  protected val lifecycle: ModuleLifecycle[I] = ModuleLifecycle.typed(signature.inputShape)

  protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] = ???

object Harness extends HarnessGivens:

  /** The first instructions of the policy leaf: the work rules (9). An optimizer can change them. */
  val policyInstructions: String =
    "Solve the task with Scala 3 code in a REPL. At each step, give a short text and the code of one cell. The cell " +
      "runs, and the next step sees its output. Use the functions of the API reference. Keep a value for a later cell " +
      "in a val or in the store. When you have the result, call submit with it. A step with no code ends the work."

  /** The first instructions of the finisher leaf. */
  val finisherInstructions: String = "Give the output of the task from the steps of the work."

  /** A harness for `signature` with the first leaves: a policy with `policyInstructions`, and a finisher that gives the
    * output fields of `signature`. The two leaves have the names `policy` and `finisher`, and the window strategy.
    */
  def forTask[I: Data, O: Data](
      signature: Signature[I, O],
      env      : HarnessEnv,
      limits   : HarnessLimits = HarnessLimits()
  ): Harness[I, O] = ???

  /** A copy of `workspace` in a new private temporary folder, or an empty folder for `None`. The copy keeps the
    * symbolic links as links. The caller deletes the folder.
    */
  def forkWorkspace(workspace: Option[Path]): Path = ???
