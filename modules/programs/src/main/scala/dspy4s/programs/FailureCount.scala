package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A non-negative number of failures tolerated before an attempt loop aborts. */
type FailureCount = FailureCount.T

object FailureCount extends RefinedSubtype[Int, Positive0]
