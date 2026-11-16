package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, Reasoning, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Entry, GuardRule, Logged, Position, SessionState, TurnKind, state}
import dspy4s.harness.repl.lostNames
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.util.Locale
import scala.annotation.unused

/** The input of the policy leaf (CODE_HARNESS_DESIGN.md, 7 and 9). `task` holds the instructions and the input fields
  * of the call. `apiReference` is the API reference (5.1): an input field, so an optimizer cannot change it.
  */
final case class PolicyInput(
    task        : String,
    apiReference: String,
    memory      : String,
    status      : String,
    observation : String,
    history     : History
) derives Schema

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
final case class Projection(status: String, observation: String, history: History, memory: String = "")
    derives CanEqual:

  /** The characters that a budget counts: the status, the observation, each string in the history, and the memory. */
  def size: Int = status.length + observation.length + history.messages.map(Projection.text).sum + memory.length

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

/** The status section (7): the step and the step limit, the keys of the store, the kind of the turn, and the times of
  * the open turn. It is typed, so it can render only its changes later.
  *
  * A proposal for the review: the first version renders in full at each step. The render of only the changes comes with
  * the prompt-cache marks (Later), and the jobs come with phase 7.
  */
final case class Status(
    step : Int,
    steps: Int,
    keys : Vector[String],
    kind : TurnKind,
    times: Option[TurnTimes] = None
) derives CanEqual:

  /** For example `Step 3 of 5. The keys of the store: a, b.` A chat has one more step after the limit, which only
    * answers (4.3). So before the step `steps - 1` of a chat, the status says that the next step only answers, and
    * before the step `steps` it says that this step is the last one. A program call has no such step, so its last step
    * gets no note. The live sessions of 2026-11-10 gave this rule: the model read "Step 10 of 10" as the last step and
    * answered one step early. The times of the turn (`TurnTimes.render`) come last.
    */
  def render: String =
    val where =
      if step >= steps then "The last step: answer with no code."
      else if kind == TurnKind.Chat && step == steps - 1 then
        s"Step $steps of $steps. The next step only answers, with no code."
      else s"Step ${step + 1} of $steps."
    val store = if keys.isEmpty then "none" else keys.mkString(", ")
    s"$where The keys of the store: $store." + times.fold("")(t => s" ${t.render}")

object Status:

  /** The status after the last entry of the journal of `state`, with no times. */
  def of(state: SessionState): Status =
    val step = state.position match
      case Position.BeforeStep(i)                                        => i
      case Position.InPolicy(i)                                          => i
      case Position.InCell(i, _, _)                                      => i
      case Position.Answered(_) | Position.Finished(_) | Position.NoTurn => 0
    Status(step, state.config.steps, state.store.keys.toVector.sorted, state.config.kind)

  /** The status after the last entry of `journal`, whose fold is `folded`, with the times of the open turn. A state has
    * no times, so they come from the journal (`TurnTimes.of`). A chat and a program call get the same times.
    */
  def of(folded: SessionState, journal: Vector[Logged]): Status = of(folded).copy(times = TurnTimes.of(journal))

/** The times of the open turn in milliseconds, for the status (7): how long the turn has run, and how long the last
  * call to the model took. In session 00429ec8 (2026-11-11), the model saw no times, so it gave a false reason for a
  * slow turn.
  */
final case class TurnTimes(turn: Long, call: Long) derives CanEqual:

  /** For example `The turn has run for 19.7 s, and the last call to the model took 2.0 s.` */
  def render: String =
    val (ran, took) = (TurnTimes.seconds(turn), TurnTimes.seconds(call))
    s"The turn has run for $ran s, and the last call to the model took $took s."

object TurnTimes:

  /** The times of the open turn of `journal`, from the times of its entries (`Meta.atMillis`) alone, so a projection
    * stays a pure function of the journal (7):
    *
    *   - the time of the turn is the time of the last entry minus the time of the last `TurnStarted`;
    *   - the time of the last call to the model is the time of the last `Acted` of the open turn minus the time of the
    *     `StepStarted` before it. The memory update of a step comes before its call, so this time holds it too.
    *
    * Before the first `Acted` of the open turn, there is no call to the model, so there are no times.
    */
  def of(journal: Vector[Logged]): Option[TurnTimes] =
    val start = journal.lastIndexWhere(_.entry == Entry.TurnStarted)
    val acted = journal.lastIndexWhere(logged => isActed(logged.entry))
    val step  = journal.lastIndexWhere(logged => isStepStarted(logged.entry), acted)
    Option.when(start >= 0 && acted > start && step > start) {
      TurnTimes(
        journal.last.meta.atMillis - journal(start).meta.atMillis,
        journal(acted).meta.atMillis - journal(step).meta.atMillis
      )
    }

  /** The seconds of `millis` with one decimal and a dot, also in another locale, for example `12.3`. A negative time,
    * for example after a change of the clock, is 0.0.
    */
  def seconds(millis: Long): String = "%.1f".formatLocal(Locale.ROOT, millis.max(0L) / 1000.0)

  private def isActed(entry: Entry): Boolean =
    entry match
      case Entry.Acted(_, _, _) => true
      case _                    => false

  private def isStepStarted(entry: Entry): Boolean =
    entry match
      case Entry.StepStarted(_) => true
      case _                    => false

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

  private[harness] def acted(entry: Entry): Boolean =
    entry match
      case Entry.Acted(_, _, _) => true
      case _                    => false

/** The note after a message that refused one or more pending calls (5.5). Each message starts a new turn, and the start
  * of a turn clears the pending calls (`SessionState`). In a live run of 2026-11-16, the model got no sign of this: it
  * said that a call still waited, and it told the person to type /allow, which then said that no call waits.
  */
object RefusedCallNote:

  /** The note for the refused calls `calls`, one or more, for example: The message that started this turn refused the
    * call of `start`, which waited for an approval. Make the call again if you still need it. Each operation comes one
    * time, in the order of its name.
    */
  private def text(calls: Vector[GuardRule]): String =
    val ops   = calls.map(rule => s"`${rule.op}`").distinct.sorted
    val named = if ops.size == 1 then ops.head else s"${ops.init.mkString(", ")} and ${ops.last}"
    val noun  = if calls.size == 1 then "the call" else "the calls"
    s"The message that started this turn refused $noun of $named, which waited for an approval. " +
      "Make the call again if you still need it."

  /** The note for the first step of a turn whose start cleared one or more pending calls, or none. The refused calls
    * are the pending calls of the state just before the last `TurnStarted`. A grant removes the pending call that it
    * covers, so a turn that a grant starts gets no note for that call. No `Acted` comes after the last `TurnStarted`
    * until the first step of the turn acts, so a later step gets no note. The note comes from the journal alone, so a
    * projection that holds it stays a pure function of the journal (7).
    */
  def afterTurnStart(journal: Vector[Logged]): Option[String] =
    val turn  = journal.lastIndexWhere(_.entry == Entry.TurnStarted)
    val first = turn >= 0 && !journal.iterator.drop(turn + 1).exists(logged => NewReplNote.acted(logged.entry))
    val calls = if first then state(journal.take(turn)).pending.toVector else Vector.empty
    Option.when(calls.nonEmpty)(text(calls))
