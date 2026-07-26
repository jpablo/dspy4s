package dspy4s.lm.providers

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A strictly positive number of inputs sent in one provider request. */
type BatchSize = BatchSize.T

object BatchSize extends RefinedSubtype[Int, Positive]
