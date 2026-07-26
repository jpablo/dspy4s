package dspy4s.lm.runtime

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Interval
import io.github.iltotore.iron.constraint.numeric.Positive
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A non-negative number of retries after the initial LM call. */
type RetryCount = RetryCount.T

object RetryCount extends RefinedSubtype[Int, Positive0]

/** A non-negative retry delay in milliseconds. */
type RetryDelayMillis = RetryDelayMillis.T

object RetryDelayMillis extends RefinedSubtype[Long, Positive0]

/** A retry jitter factor in the closed interval `[0.0, 1.0]`. */
type JitterFactor = JitterFactor.T

object JitterFactor extends RefinedSubtype[Double, Interval.Closed[0.0, 1.0]]

/** A strictly positive maximum number of cache entries. */
type CacheCapacity = CacheCapacity.T

object CacheCapacity extends RefinedSubtype[Int, Positive]
