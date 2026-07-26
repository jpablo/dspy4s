package dspy4s.core.contracts

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A strictly positive number of worker threads. */
type ThreadCount = ThreadCount.T

object ThreadCount extends RefinedSubtype[Int, Positive]

/** A strictly positive number of errors an execution may tolerate before stopping. */
type ErrorLimit = ErrorLimit.T

object ErrorLimit extends RefinedSubtype[Int, Positive]

/** A non-negative cap on retained history entries. Zero disables history retention. */
type HistoryLimit = HistoryLimit.T

object HistoryLimit extends RefinedSubtype[Int, Positive0]
