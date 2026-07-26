package dspy4s.optimize

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A non-negative number of demonstrations.
  *
  * Zero is lawful for zero-shot configurations. Literal construction is checked at compile time with
  * `DemoCount(4)`; runtime values must cross the validation boundary through `DemoCount.either(value)` or another
  * Iron smart constructor.
  */
type DemoCount = DemoCount.T

object DemoCount extends RefinedSubtype[Int, Positive0]
