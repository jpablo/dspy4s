package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A strictly positive character budget for rendering RLM REPL output into prompts. */
type OutputCharLimit = OutputCharLimit.T

object OutputCharLimit extends RefinedSubtype[Int, Positive]
