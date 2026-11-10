package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.harness.guest.StrategyName
import dspy4s.harness.kernel.Logged

/** The window strategy (CODE_HARNESS_DESIGN.md, 7): no derived data, and the last steps of the open turn that fit the
  * budget, oldest first. The last step is always in it, and a long observation is cut with no LM call (cheap steps
  * first), with a note that says how to read the rest. A child and a rollout use it.
  *
  * A proposal for the review: the budget counts characters, not tokens, so the projection needs no tokenizer.
  */
object Window extends ContextStrategy:
  type D = Unit

  val name: StrategyName = StrategyName.Window

  def empty: Unit = ()

  def update(journal: Vector[Logged], d: Unit)(using RuntimeContext): Either[DspyError, Unit] = ???

  def project(journal: Vector[Logged], d: Unit, budget: Int): Projection = ???

  /** The first `max` characters of `text`, and a note with the number of characters that the cut removed. A text of
    * `max` characters or less stays as it is.
    */
  def cut(text: String, max: Int): String = ???
