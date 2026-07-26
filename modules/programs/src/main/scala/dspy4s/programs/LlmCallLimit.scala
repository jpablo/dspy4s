package dspy4s.programs

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive

/** A strictly positive cap on sub-LM calls made by an RLM run. */
type LlmCallLimit = LlmCallLimit.T

object LlmCallLimit extends RefinedSubtype[Int, Positive]
