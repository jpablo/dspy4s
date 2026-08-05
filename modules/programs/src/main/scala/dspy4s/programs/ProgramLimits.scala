package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.{Positive, Positive0}

/** A number of attempts that can lawfully configure a program expecting one or more candidates.
  *
  * Literal construction is checked at compile time with `AttemptCount(3)`. Values obtained at runtime must cross the
  * smart-constructor boundary with `AttemptCount.either(value)` (or another validation method supplied by Iron).
  */
type AttemptCount = AttemptCount.T

object AttemptCount extends RefinedSubtype[Int, Positive]

/** A non-negative number of failures tolerated before an attempt loop aborts. */
type FailureCount = FailureCount.T

object FailureCount extends RefinedSubtype[Int, Positive0]

/** A strictly positive iteration budget for a public agent program.
  *
  * Internal loop machinery may still accept zero when "perform no steps" is lawful; this type belongs at agent
  * configuration boundaries that promise at least one opportunity to act.
  */
type IterationLimit = IterationLimit.T

object IterationLimit extends RefinedSubtype[Int, Positive]

/** A strictly positive cap on sub-LM calls made by an RLM run. */
type LlmCallLimit = LlmCallLimit.T

object LlmCallLimit extends RefinedSubtype[Int, Positive]

/** A strictly positive character budget for rendering RLM REPL output into prompts. */
type OutputCharLimit = OutputCharLimit.T

object OutputCharLimit extends RefinedSubtype[Int, Positive]
