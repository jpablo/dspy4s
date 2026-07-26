package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A number of attempts that can lawfully configure a program expecting one or more candidates.
  *
  * Literal construction is checked at compile time with `AttemptCount(3)`. Values obtained at runtime must cross the
  * smart-constructor boundary with `AttemptCount.either(value)` (or another validation method supplied by Iron).
  */
type AttemptCount = AttemptCount.T

object AttemptCount extends RefinedSubtype[Int, Positive]
