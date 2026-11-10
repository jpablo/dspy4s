package dspy4s.harness

import dspy4s.core.contracts.{DspyError, History, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Logged, SessionState}
import zio.blocks.schema.Schema

/** The input of the policy leaf (CODE_HARNESS_DESIGN.md, 7 and 9). `task` holds the instructions and the input fields
  * of the call. `apiReference` is the API reference (5.1): an input field, so an optimizer cannot change it.
  */
final case class PolicyInput(task: String, apiReference: String, status: String, observation: String, history: History)
    derives Schema

/** The output of the policy leaf (9): a text, and the code of a cell, if any. A step with no code answers. */
final case class PolicyStep(text: String, code: Option[String]) derives Schema

/** What a strategy gives for one step (7): the status section, the last observation, and the earlier steps.
  *
  * A proposal for the review: the design gives `project` the result `PolicyInput`. The task and the API reference do
  * not come from the journal, so the harness adds them.
  */
final case class Projection(status: String, observation: String, history: History) derives CanEqual:

  /** The characters that a budget counts: the status, the observation, and each string in the history. */
  def size: Int = ???

/** The data that a strategy derives from the journal (7). It never changes the journal. An update can call `Predict`
  * leaves, so it needs the runtime of dspy4s.
  */
trait Derivation:
  type D
  def empty: D
  def update(journal: Vector[Logged], d: D)(using RuntimeContext): Either[DspyError, D]

/** A context strategy (7): derived data, and a pure projection of the journal and the derived data into a budget.
  *
  * A proposal for the review: `D` is a type member, so `Harness` needs no third type parameter. The design has
  * `guestApi` for `zoom`, and it waits for the memory tree (phase 8). 10.5 puts the strategies in `harness-kernel`, but
  * `update` needs `RuntimeContext` and `DspyError`, and `History` is a type of dspy4s, so they are in `harness`.
  */
trait ContextStrategy extends Derivation:
  def name: StrategyName
  def project(journal: Vector[Logged], d: D, budget: Int): Projection

/** The status section (7): the step and the step limit, and the keys of the store. It is typed, so it can render only
  * its changes later.
  *
  * A proposal for the review: the first version renders in full at each step. The render of only the changes comes with
  * the prompt-cache marks (Later), and the jobs come with phase 7.
  */
final case class Status(step: Int, steps: Int, keys: Vector[String]) derives CanEqual:
  def render: String = ???

object Status:

  /** The status after the last entry of the journal of `state`. */
  def of(state: SessionState): Status = ???
