package dspy4s.programs.retrievers

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A strictly positive number of nearest neighbors or passages to retrieve. */
type NeighborCount = NeighborCount.T

object NeighborCount extends RefinedSubtype[Int, Positive]
