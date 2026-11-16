package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Entry, Logged, Source, TurnKind, TurnOutcome, state}
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec

/** The window strategy (CODE_HARNESS_DESIGN.md, 7): no derived data, and the last steps of the open turn that fit the
  * budget, oldest first. The last step is always in it, and a long observation, text or code is cut with no LM call
  * (cheap steps first), with a note of the rest. A child and a rollout use it.
  *
  * A proposal for the review: the budget counts characters, not tokens, so the projection needs no tokenizer.
  *
  * The projection of a step:
  *   - the status of the state after the journal, with the times of the open turn (`TurnTimes`);
  *   - the observation: the output of the last cell of the open turn, cut to half of the budget;
  *   - the history: one message for each earlier step with an `Acted`. A message holds the input field `observation`,
  *     the output before that step, and the output fields `text` and `code` of the step. Each of the three is cut to an
  *     eighth of the budget, so the last step fits when the status is short (9). The output field `reasoning` is empty.
  *     The newest messages that fit the rest of the budget stay, oldest first.
  *
  * The output of each cell ends with its time on its own line, for example `The cell took 12.3 s.` (`cellNote`). The
  * time of a cell is the time of its `Observed` minus the time of its `Acted`. The note comes after the cut of the
  * output, so a cut does not remove it, in the observation and in each message. A cell with no `Observed`, for example
  * after a stop, has no output and no note. The times come from the journal alone, so the projection stays a pure
  * function of the journal. In session 00429ec8 (2026-11-11), the model saw no times, so it gave a false reason for a
  * slow turn.
  */
object Window extends ContextStrategy:
  type D = Unit

  val name: StrategyName = StrategyName.Window

  def empty: Unit = ()

  def update(journal: Vector[Logged], d: Unit)(using RuntimeContext): Either[DspyError, Unit] = Right(())

  def project(journal: Vector[Logged], d: Unit, budget: Int): Projection = projection(journal, budget, earlier = true)

  /** The projection of `journal` in `budget`, with the earlier turns of a chat when `earlier` is true. A chat and a
    * program call get the same status, with the times of the open turn.
    */
  private def projection(journal: Vector[Logged], budget: Int, earlier: Boolean): Projection =
    val now    = state(journal)
    val status = Status.of(now, journal).render
    if now.config.kind == TurnKind.Chat then chat(journal, status, budget, earlier)
    else program(journal, status, budget)

  /** The projection of a program call: the steps of its one turn. The task of the policy holds the input. */
  private def program(journal: Vector[Logged], status: String, budget: Int): Projection =
    val steps       = openTurn(journal).filter(_.acted)
    val observation = seen(steps.flatMap(_.cell).lastOption, Vector.empty, budget / 2)
    // The observation before step k is the output of step k - 1.
    val before   = None +: steps.map(_.cell)
    val messages = steps.zipWithIndex.map { (step, k) =>
      message(seen(before(k), Vector.empty, budget / 8), step.text, step.code, budget / 8)
    }
    Projection(status, observation, History(newest(messages, budget - status.length - observation.length)))

  /** The first `max` characters of `text`, and a note with the number of characters that the cut removed. A text of
    * `max` characters or less stays as it is.
    */
  def cut(text: String, max: Int): String =
    if text.length <= max then text
    else
      // The text goes to a model, so the cut keeps each character whole.
      val kept = textPrefix(text, max)
      kept +
        s"\n[${text.length - kept.length} more characters were cut. Print a smaller part of the value to see them.]"

  /** The note of the time of a cell, for example `The cell took 12.3 s.` (`TurnTimes.seconds`). */
  def cellNote(millis: Long): String = s"The cell took ${TurnTimes.seconds(millis)} s."

  /** The text that the model sees of `cell` and of the texts `after` it, joined with new lines and cut to `max`
    * characters. The time note of the cell comes after the cut of its output, so a cut does not remove it: the note
    * comes right after a whole output, or after the note of the cut when the cut is in the output. An empty output
    * gives only the note. With no cell, only the texts after it are cut.
    */
  private def seen(cell: Option[Cell], after: Vector[String], max: Int): String =
    cell match
      case None                     => cut(after.mkString("\n"), max)
      case Some(Cell(output, took)) =>
        val note = cellNote(took)
        val kept = cut((output +: after).mkString("\n"), max)
        if output.length > max then s"$kept\n$note"
        else Vector(output, note).filter(_.nonEmpty).mkString("\n") + kept.drop(output.length)

  /** The projection of a chat (phase 7). The messages that a step sees come before its observation, and each earlier
    * turn is one message of the history before the steps of the open turn:
    *
    *   - The messages of the open turn before a step are in the observation before that step, after the output of the
    *     cell before it and its time. A message from the person is "The person wrote: ...", and a message from the
    *     harness, for example the end of a job, is "The harness wrote: ...".
    *   - The first step after a new activation has the note of the lost names (`NewReplNote.afterActivation`) in its
    *     observation, before its messages. Only the observation of that step holds the note. A program call has one
    *     activation, so its projection has no such note.
    *   - The first step of a turn whose start cleared one or more pending calls has the note of the refused calls
    *     (`RefusedCallNote.afterTurnStart`) in its observation, after the note of the lost names and before its
    *     messages. Only the observation of that step holds the note. The memory tree uses this projection for the open
    *     turn (`openTurn`), so it gets the note too.
    *   - An earlier turn is a message whose observation holds all its messages, and whose text is its reply: the text
    *     of `Replied`, or the text of the report of another outcome (`TurnOutcome.report`) and then the last text of
    *     the turn. So the model reads the outcome in words, and not as a Scala value.
    *   - The newest messages that fit the rest of the budget stay, oldest first, so the oldest turns go first.
    */
  private def chat(journal: Vector[Logged], status: String, budget: Int, earlier: Boolean): Projection =
    val (turns, open) = chatTurns(journal)
    val acted         = open.filter(_.acted)
    val current       = open.lastOption.filterNot(_.acted)
    val heardNow      = current.fold(Vector.empty[String])(_.heard)
    val notes         = (NewReplNote.afterActivation(journal) ++ RefusedCallNote.afterTurnStart(journal)).toVector
    val observation   = seen(acted.flatMap(_.cell).lastOption, notes ++ heardNow, budget / 2)
    val before        = None +: acted.map(_.cell)
    val stepMessages  = acted.zipWithIndex.map { (step, k) =>
      message(seen(before(k), step.heard, budget / 8), step.text, step.code, budget / 8)
    }
    val turnMessages =
      if !earlier then Vector.empty
      else turns.map(turn => message(seen(None, turn.heard, budget / 8), turn.reply, None, budget / 8))
    val history = newest(turnMessages ++ stepMessages, budget - status.length - observation.length)
    Projection(status, observation, History(history))

  /** The projection of the open turn of a chat in `budget`, with no earlier turn: the part of the memory tree (7.1).
    * The view of the memory tree holds the earlier turns. A program call gives its one turn, as `project` does.
    */
  private[harness] def openTurn(journal: Vector[Logged], budget: Int): Projection =
    projection(journal, budget, earlier = false)

  /** A turn of a chat that ended: the messages of the turn, and its reply. */
  private final case class ChatTurn(heard: Vector[String], reply: String)

  /** A step of the open turn of a chat: the messages before it, its text and code, the output of its cell with its
    * time, and the time of its `Acted`.
    */
  private final case class ChatStep(
      heard  : Vector[String],
      acted  : Boolean,
      text   : String,
      code   : Option[String],
      cell   : Option[Cell],
      actedAt: Long
  )

  /** The turns of a chat that ended, and the steps of the open turn. The reply of a turn that ended with no `Replied`
    * is the text of its report (`TurnOutcome.report`), then the last text of the turn.
    */
  private def chatTurns(journal: Vector[Logged]): (Vector[ChatTurn], Vector[ChatStep]) =
    final case class Walk(
        turns  : Vector[ChatTurn],
        steps  : Vector[ChatStep],
        pending: Vector[String],
        heard  : Vector[String],
        last   : String
    )
    val done = journal.foldLeft(Walk(Vector.empty, Vector.empty, Vector.empty, Vector.empty, "")) { (w, logged) =>
      val at = logged.meta.atMillis
      logged.entry match
        case Entry.Input(_, text, _) =>
          val line = s"${speaker(logged.meta.source)} wrote: $text"
          w.copy(pending = w.pending :+ line, heard = w.heard :+ line)
        case Entry.StepStarted(_) =>
          w.copy(steps = w.steps :+ ChatStep(w.pending, acted = false, "", None, None, at), pending = Vector.empty)
        case Entry.Acted(_, text, code) if w.steps.nonEmpty =>
          val step = w.steps.last.copy(acted = true, text = text, code = code, actedAt = at)
          w.copy(steps = w.steps.dropRight(1) :+ step, last = text)
        case Entry.Observed(output, _, _) if w.steps.nonEmpty =>
          val step = w.steps.last
          w.copy(steps = w.steps.dropRight(1) :+ step.copy(cell = Some(Cell(output, at - step.actedAt))))
        case Entry.TurnEnded(outcome) =>
          val reply = outcome match
            case TurnOutcome.Replied(text) => text
            case other                     => Vector(other.report, w.last).filter(_.nonEmpty).mkString(" ")
          // A message after the last step of the turn waits for the next turn, as in the kernel (4.2).
          Walk(w.turns :+ ChatTurn(w.heard.dropRight(w.pending.size), reply), Vector.empty, w.pending, w.pending, "")
        case _ => w
    }
    // The messages after the last step belong to the next step, as the first messages of a turn belong to its first step.
    val open =
      if done.pending.isEmpty then done.steps
      else done.steps :+ ChatStep(done.pending, acted = false, "", None, None, 0L)
    (done.turns, open)

  /** Who wrote a message, for the text of the window and of the memory log. */
  private[harness] def speaker(source: Source): String =
    source match
      case Source.User           => "The person"
      case Source.Harness        => "The harness"
      case Source.Controller     => "The controller"
      case Source.Child(session) => s"The session ${session.value}"
      case Source.Parent(_, _)   => "Another session"
      case Source.Policy         => "The model"

  /** The output of a cell, and its time in milliseconds: the time of its `Observed` minus the time of its `Acted`. */
  private final case class Cell(output: String, took: Long)

  /** One step of the open turn: its text and code, the output of its cell with its time when the cell ran, and the time
    * of its `Acted`.
    */
  private final case class Step(acted: Boolean, text: String, code: Option[String], cell: Option[Cell], actedAt: Long)

  /** The steps after the last `TurnStarted`. */
  private def openTurn(journal: Vector[Logged]): Vector[Step] =
    val turn = journal.reverse.takeWhile(_.entry != Entry.TurnStarted).reverse
    turn.foldLeft(Vector.empty[Step]) { (steps, logged) =>
      val at = logged.meta.atMillis
      logged.entry match
        case Entry.StepStarted(_)                           => steps :+ Step(acted = false, "", None, None, at)
        case Entry.Acted(_, text, code)                     => steps.dropRight(1) :+ Step(acted = true, text, code, None, at)
        case Entry.Observed(output, _, _) if steps.nonEmpty =>
          steps.dropRight(1) :+ steps.last.copy(cell = Some(Cell(output, at - steps.last.actedAt)))
        case _ => steps
    }

  /** The message of a step: the text `observation` that the model saw before the step (`seen`), and the text and the
    * code of the step, each cut to `max` characters. The window keeps no thought, so `reasoning` is empty. With no such
    * key, the adapter shows the text `None` for the field, and a model can copy it (9).
    */
  private def message(observation: String, text: String, code: Option[String], max: Int): DynamicValue.Record =
    val fields = Vector("observation" -> observation, "reasoning" -> "", "text" -> cut(text, max)) ++
      code.map(c => "code" -> cut(c, max))
    DynamicValues.record(fields.map((key, value) => key -> DynamicValues.string(value))*)

  /** The newest messages whose characters fit `room`, in their order. */
  private def newest(messages: Vector[DynamicValue.Record], room: Int): Vector[DynamicValue.Record] =
    @tailrec
    def take(rest: List[DynamicValue.Record], left: Int, kept: List[DynamicValue.Record]): Vector[DynamicValue.Record] =
      rest match
        case m :: older if Projection.text(m) <= left => take(older, left - Projection.text(m), m :: kept)
        case _                                        => kept.toVector
    take(messages.reverse.toList, room, Nil)
