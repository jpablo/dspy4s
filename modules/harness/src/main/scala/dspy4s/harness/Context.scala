package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, Reasoning, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Logged, Position, SessionState}
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import scala.annotation.unused

/** The input of the policy leaf (CODE_HARNESS_DESIGN.md, 7 and 9). `task` holds the instructions and the input fields
  * of the call. `apiReference` is the API reference (5.1): an input field, so an optimizer cannot change it.
  */
final case class PolicyInput(task: String, apiReference: String, status: String, observation: String, history: History)
    derives Schema

/** The output of the policy leaf (9): the thought, a text, and the code of a cell, if any. A step with no code answers.
  *
  * `reasoning` has the type `Reasoning`, so a model with native reasoning gets no such field in its prompt, and the
  * adapter fills it from the reasoning of the reply. A model with no native reasoning writes the field.
  * `Acted.reasoning` holds either one.
  */
final case class PolicyStep(reasoning: Reasoning, text: String, code: Option[String]) derives Schema

/** What a strategy gives for one step (7): the status section, the last observation, and the earlier steps.
  *
  * A proposal for the review: the design gives `project` the result `PolicyInput`. The task and the API reference do
  * not come from the journal, so the harness adds them.
  */
final case class Projection(status: String, observation: String, history: History) derives CanEqual:

  /** The characters that a budget counts: the status, the observation, and each string in the history. */
  def size: Int = status.length + observation.length + history.messages.map(Projection.text).sum

object Projection:

  /** The characters of the strings in a value of a history message. */
  def text(value: DynamicValue): Int =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => s.length
      case record: DynamicValue.Record                      => DynamicValues.recordEntries(record).map((_, v) => text(v)).sum
      case sequence: DynamicValue.Sequence                  => sequence.elements.map(text).sum
      case _                                                => 0

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

  /** The text of the part `ref` of the derived data, for `zoom` of a cell (7.1). A strategy with no memory gives an
    * error with a plain message.
    */
  def zoom(@unused d: D, @unused ref: String): Either[String, String] = Left(ContextStrategy.NoMemory)

object ContextStrategy:

  /** The error of `zoom` in a session with no memory tree (7.1). */
  val NoMemory = "this session has no memory tree, so zoom has nothing to read"

/** The status section (7): the step and the step limit, and the keys of the store. It is typed, so it can render only
  * its changes later.
  *
  * A proposal for the review: the first version renders in full at each step. The render of only the changes comes with
  * the prompt-cache marks (Later), and the jobs come with phase 7.
  */
final case class Status(step: Int, steps: Int, keys: Vector[String]) derives CanEqual:

  /** For example `Step 3 of 5. The keys of the store: a, b.` The step after the limit is the last one, of a chat. */
  def render: String =
    val where = if step < steps then s"Step ${step + 1} of $steps." else "The last step: answer with no code."
    val store = if keys.isEmpty then "none" else keys.mkString(", ")
    s"$where The keys of the store: $store."

object Status:

  /** The status after the last entry of the journal of `state`. */
  def of(state: SessionState): Status =
    val step = state.position match
      case Position.BeforeStep(i)                                        => i
      case Position.InPolicy(i)                                          => i
      case Position.InCell(i, _, _)                                      => i
      case Position.Answered(_) | Position.Finished(_) | Position.NoTurn => 0
    Status(step, state.config.steps, state.store.keys.toVector.sorted)
