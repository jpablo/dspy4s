package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A strictly positive iteration budget for a public agent program.
  *
  * Internal loop machinery may still accept zero when "perform no steps" is lawful; this type belongs at agent
  * configuration boundaries that promise at least one opportunity to act.
  */
type IterationLimit = IterationLimit.T

object IterationLimit extends RefinedSubtype[Int, Positive]
