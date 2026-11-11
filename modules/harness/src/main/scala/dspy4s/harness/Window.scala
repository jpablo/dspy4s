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
  *   - the status of the state after the journal;
  *   - the observation: the output of the last cell of the open turn, cut to half of the budget;
  *   - the history: one message for each earlier step with an `Acted`. A message holds the input field `observation`,
  *     the output before that step, and the output fields `text` and `code` of the step. Each of the three is cut to an
  *     eighth of the budget, so the last step fits when the status is short (9). The output field `reasoning` is empty.
  *     The newest messages that fit the rest of the budget stay, oldest first.
  */
object Window extends ContextStrategy:
  type D = Unit

  val name: StrategyName = StrategyName.Window

  def empty: Unit = ()

  def update(journal: Vector[Logged], d: Unit)(using RuntimeContext): Either[DspyError, Unit] = Right(())

  def project(journal: Vector[Logged], d: Unit, budget: Int): Projection =
    val now = state(journal)
    if now.config.kind == TurnKind.Chat then chat(journal, Status.of(now).render, budget)
    else program(journal, Status.of(now).render, budget)

  /** The projection of a program call: the steps of its one turn. The task of the policy holds the input. */
  private def program(journal: Vector[Logged], status: String, budget: Int): Projection =
    val steps       = openTurn(journal).filter(_.acted)
    val outputs     = steps.flatMap(_.output)
    val observation = outputs.lastOption.fold("")(cut(_, budget / 2))
    // The observation before step k is the output of step k - 1.
    val before   = "" +: steps.map(_.output.getOrElse(""))
    val messages = steps.zipWithIndex.map((step, k) => message(before(k), step, budget / 8))
    Projection(status, observation, History(newest(messages, budget - status.length - observation.length)))

  /** The first `max` characters of `text`, and a note with the number of characters that the cut removed. A text of
    * `max` characters or less stays as it is.
    */
  def cut(text: String, max: Int): String =
    if text.length <= max then text
    else
      text.take(max.max(0)) +
        s"\n[${text.length - max.max(0)} more characters were cut. Print a smaller part of the value to see them.]"

  /** The projection of a chat (phase 7). The messages that a step sees come before its observation, and each earlier
    * turn is one message of the history before the steps of the open turn:
    *
    *   - The messages of the open turn before a step are in the observation before that step, after the output of the
    *     cell before it. A message from the person is "The person wrote: ...", and a message from the harness, for
    *     example the end of a job, is "The harness wrote: ...".
    *   - An earlier turn is a message whose observation holds all its messages, and whose text is its reply: the text
    *     of `Replied`, or a note of another outcome with the last text of the turn.
    *   - The newest messages that fit the rest of the budget stay, oldest first, so the oldest turns go first.
    */
  private def chat(journal: Vector[Logged], status: String, budget: Int, earlier: Boolean = true): Projection =
    val (turns, open) = chatTurns(journal)
    val acted         = open.filter(_.acted)
    val current       = open.lastOption.filterNot(_.acted)
    val heardNow      = current.fold(Vector.empty[String])(_.heard)
    val lastOutput    = acted.flatMap(_.output).lastOption.toVector
    val observation   = cut((lastOutput ++ heardNow).mkString("\n"), budget / 2)
    val before        = "" +: acted.map(_.output.getOrElse(""))
    val stepMessages  = acted.zipWithIndex.map { (step, k) =>
      val seen = (Vector(before(k)).filter(_.nonEmpty) ++ step.heard).mkString("\n")
      message(seen, Step(acted = true, step.text, step.code, step.output), budget / 8)
    }
    val turnMessages =
      if !earlier then Vector.empty
      else
        turns.map { turn =>
          message(turn.heard.mkString("\n"), Step(acted = true, turn.reply, None, None), budget / 8)
        }
    val history = newest(turnMessages ++ stepMessages, budget - status.length - observation.length)
    Projection(status, observation, History(history))

  /** The projection of the open turn of a chat in `budget`, with no earlier turn: the part of the memory tree (7.1).
    * The view of the memory tree holds the earlier turns. A program call gives its one turn, as `project` does.
    */
  private[harness] def openTurn(journal: Vector[Logged], budget: Int): Projection =
    val now = state(journal)
    if now.config.kind == TurnKind.Chat then chat(journal, Status.of(now).render, budget, earlier = false)
    else program(journal, Status.of(now).render, budget)

  /** A turn of a chat that ended: the messages of the turn, and its reply. */
  private final case class ChatTurn(heard: Vector[String], reply: String)

  /** A step of the open turn of a chat: the messages before it, its text and code, and the output of its cell. */
  private final case class ChatStep(
      heard : Vector[String],
      acted : Boolean,
      text  : String,
      code  : Option[String],
      output: Option[String]
  )

  /** The turns of a chat that ended, and the steps of the open turn. */
  private def chatTurns(journal: Vector[Logged]): (Vector[ChatTurn], Vector[ChatStep]) =
    final case class Walk(
        turns  : Vector[ChatTurn],
        steps  : Vector[ChatStep],
        pending: Vector[String],
        heard  : Vector[String],
        last   : String
    )
    val done = journal.foldLeft(Walk(Vector.empty, Vector.empty, Vector.empty, Vector.empty, "")) { (w, logged) =>
      logged.entry match
        case Entry.Input(_, text, _) =>
          val line = s"${speaker(logged.meta.source)} wrote: $text"
          w.copy(pending = w.pending :+ line, heard = w.heard :+ line)
        case Entry.StepStarted(_) =>
          w.copy(steps = w.steps :+ ChatStep(w.pending, acted = false, "", None, None), pending = Vector.empty)
        case Entry.Acted(_, text, code) if w.steps.nonEmpty =>
          w.copy(steps = w.steps.dropRight(1) :+ w.steps.last.copy(acted = true, text = text, code = code), last = text)
        case Entry.Observed(output, _, _) if w.steps.nonEmpty =>
          w.copy(steps = w.steps.dropRight(1) :+ w.steps.last.copy(output = Some(output)))
        case Entry.TurnEnded(outcome) =>
          val reply = outcome match
            case TurnOutcome.Replied(text) => text
            case other                     => Vector(s"The turn ended: $other.", w.last).filter(_.nonEmpty).mkString(" ")
          // A message after the last step of the turn waits for the next turn, as in the kernel (4.2).
          Walk(w.turns :+ ChatTurn(w.heard.dropRight(w.pending.size), reply), Vector.empty, w.pending, w.pending, "")
        case _ => w
    }
    // The messages after the last step belong to the next step, as the first messages of a turn belong to its first step.
    val open =
      if done.pending.isEmpty then done.steps else done.steps :+ ChatStep(done.pending, acted = false, "", None, None)
    (done.turns, open)

  /** Who wrote a message, for the text of the window. */
  private def speaker(source: Source): String =
    source match
      case Source.User                           => "The person"
      case Source.Harness                        => "The harness"
      case Source.Controller                     => "The controller"
      case Source.Child(_) | Source.Parent(_, _) => "Another session"
      case Source.Policy                         => "The model"

  /** One step of the open turn: its text and code, and the output of its cell when the cell ran. */
  private final case class Step(acted: Boolean, text: String, code: Option[String], output: Option[String])

  /** The steps after the last `TurnStarted`. */
  private def openTurn(journal: Vector[Logged]): Vector[Step] =
    val turn = journal.map(_.entry).reverse.takeWhile(_ != Entry.TurnStarted).reverse
    turn.foldLeft(Vector.empty[Step]) { (steps, entry) =>
      entry match
        case Entry.StepStarted(_)                           => steps :+ Step(acted = false, "", None, None)
        case Entry.Acted(_, text, code)                     => steps.dropRight(1) :+ Step(acted = true, text, code, None)
        case Entry.Observed(output, _, _) if steps.nonEmpty =>
          steps.dropRight(1) :+ steps.last.copy(output = Some(output))
        case _ => steps
    }

  /** The message of `step`, with each field cut to `max` characters. The window keeps no thought, so `reasoning` is
    * empty. With no such key, the adapter shows the text `None` for the field, and a model can copy it (9).
    */
  private def message(observation: String, step: Step, max: Int): DynamicValue.Record =
    val fields = Vector("observation" -> observation, "reasoning" -> "", "text" -> step.text) ++
      step.code.map("code" -> _)
    DynamicValues.record(fields.map((key, value) => key -> DynamicValues.string(cut(value, max)))*)

  /** The newest messages whose characters fit `room`, in their order. */
  private def newest(messages: Vector[DynamicValue.Record], room: Int): Vector[DynamicValue.Record] =
    @tailrec
    def take(rest: List[DynamicValue.Record], left: Int, kept: List[DynamicValue.Record]): Vector[DynamicValue.Record] =
      rest match
        case m :: older if Projection.text(m) <= left => take(older, left - Projection.text(m), m :: kept)
        case _                                        => kept.toVector
    take(messages.reverse.toList, room, Nil)
