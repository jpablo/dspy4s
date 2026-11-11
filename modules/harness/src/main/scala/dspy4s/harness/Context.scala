package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, Reasoning, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Entry, Logged, Position, SessionState, TurnKind}
import dspy4s.harness.repl.lostNames
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

/** The status section (7): the step and the step limit, the keys of the store, and the kind of the turn. It is typed,
  * so it can render only its changes later.
  *
  * A proposal for the review: the first version renders in full at each step. The render of only the changes comes with
  * the prompt-cache marks (Later), and the jobs come with phase 7.
  */
final case class Status(step: Int, steps: Int, keys: Vector[String], kind: TurnKind) derives CanEqual:

  /** For example `Step 3 of 5. The keys of the store: a, b.` A chat has one more step after the limit, which only
    * answers (4.3). So before the step `steps - 1` of a chat, the status says that the next step only answers, and
    * before the step `steps` it says that this step is the last one. A program call has no such step, so its last step
    * gets no note. The live sessions of 2026-11-10 gave this rule: the model read "Step 10 of 10" as the last step and
    * answered one step early.
    */
  def render: String =
    val where =
      if step >= steps then "The last step: answer with no code."
      else if kind == TurnKind.Chat && step == steps - 1 then
        s"Step $steps of $steps. The next step only answers, with no code."
      else s"Step ${step + 1} of $steps."
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
    Status(step, state.config.steps, state.store.keys.toVector.sorted, state.config.kind)

/** The note after a new REPL (6): the reason, and the names of the earlier cells that the new REPL does not have. A
  * restart after a cell (`HarnessEffects`) and a new activation give the same text, so the model sees one form.
  */
object NewReplNote:

  /** For example `The harness started a new REPL, because the session opened again. These names of earlier cells are
    * gone: total. Define them again if you need them.`
    */
  def text(reason: String, names: Vector[String]): String =
    val lost =
      if names.isEmpty then "No names of earlier cells were lost."
      else s"These names of earlier cells are gone: ${names.mkString(", ")}. Define them again if you need them."
    s"The harness started a new REPL, because $reason. $lost"

  /** The note for the first step after a new activation (6), or none. Each activation has a new REPL, so the names of
    * the cells before the last `Activated` are gone (`lostNames`). The first activation has no earlier cell, so it gets
    * no note. No `Acted` comes after the last `Activated` until the first step of the activation acts, so a later step
    * or turn of the same activation gets no note. A step whose policy call gave no `Acted` gets the note again at the
    * next step. With no lost name, there is no note. The note comes from the journal alone, so a projection that holds
    * it stays a pure function of the journal (7).
    */
  def afterActivation(journal: Vector[Logged]): Option[String] =
    val entries = journal.map(_.entry)
    val first   = entries.count(activated) > 1 && !entries.reverse.takeWhile(!activated(_)).exists(acted)
    // No cell completed after the last `Activated`, so the fold of the whole journal gives the names before it.
    val names = if first then lostNames(journal).map(_._1) else Vector.empty
    Option.when(names.nonEmpty)(text("the session opened again", names))

  private def activated(entry: Entry): Boolean =
    entry match
      case Entry.Activated(_, _) => true
      case _                     => false

  private def acted(entry: Entry): Boolean =
    entry match
      case Entry.Acted(_, _, _) => true
      case _                    => false
