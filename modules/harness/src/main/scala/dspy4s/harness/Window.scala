package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, History, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.{Entry, Logged, state}
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec

/** The window strategy (CODE_HARNESS_DESIGN.md, 7): no derived data, and the last steps of the open turn that fit the
  * budget, oldest first. The last step is always in it, and a long observation is cut with no LM call (cheap steps
  * first), with a note that says how to read the rest. A child and a rollout use it.
  *
  * A proposal for the review: the budget counts characters, not tokens, so the projection needs no tokenizer.
  *
  * The projection of a step:
  *   - the status of the state after the journal;
  *   - the observation: the output of the last cell of the open turn, cut to half of the budget;
  *   - the history: one message for each earlier step with an `Acted`. A message holds the input field `observation`,
  *     the output before that step, cut to an eighth of the budget, and the output fields `text` and `code` of the
  *     step. The newest messages that fit the rest of the budget stay, oldest first.
  */
object Window extends ContextStrategy:
  type D = Unit

  val name: StrategyName = StrategyName.Window

  def empty: Unit = ()

  def update(journal: Vector[Logged], d: Unit)(using RuntimeContext): Either[DspyError, Unit] = Right(())

  def project(journal: Vector[Logged], d: Unit, budget: Int): Projection =
    val status      = Status.of(state(journal)).render
    val steps       = openTurn(journal).filter(_.acted)
    val outputs     = steps.flatMap(_.output)
    val observation = outputs.lastOption.fold("")(cut(_, budget / 2))
    // The observation before step k is the output of step k - 1.
    val before   = "" +: steps.map(_.output.getOrElse(""))
    val messages = steps.zipWithIndex.map((step, k) => message(cut(before(k), budget / 8), step))
    Projection(status, observation, History(newest(messages, budget - status.length - observation.length)))

  /** The first `max` characters of `text`, and a note with the number of characters that the cut removed. A text of
    * `max` characters or less stays as it is.
    */
  def cut(text: String, max: Int): String =
    if text.length <= max then text
    else
      text.take(max.max(0)) +
        s"\n[${text.length - max.max(0)} more characters were cut. Print a smaller part of the value to see them.]"

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

  private def message(observation: String, step: Step): DynamicValue.Record =
    val fields = Vector("observation" -> observation, "text" -> step.text) ++ step.code.map("code" -> _)
    DynamicValues.record(fields.map((key, value) => key -> DynamicValues.string(value))*)

  /** The newest messages whose characters fit `room`, in their order. */
  private def newest(messages: Vector[DynamicValue.Record], room: Int): Vector[DynamicValue.Record] =
    @tailrec
    def take(rest: List[DynamicValue.Record], left: Int, kept: List[DynamicValue.Record]): Vector[DynamicValue.Record] =
      rest match
        case m :: older if Projection.text(m) <= left => take(older, left - Projection.text(m), m :: kept)
        case _                                        => kept.toVector
    take(messages.reverse.toList, room, Nil)
