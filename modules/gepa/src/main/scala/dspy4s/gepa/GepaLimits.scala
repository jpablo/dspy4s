package dspy4s.gepa

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.collection.MinLength
import io.github.iltotore.iron.constraint.numeric.Positive
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A non-negative number of metric evaluations. */
type MetricCallCount = MetricCallCount.T

object MetricCallCount extends RefinedSubtype[Int, Positive0]:
  /** Add a derived non-negative delta while checking overflow and the internal counting law. */
  def add(current: MetricCallCount, delta: Int): MetricCallCount =
    val validDelta = applyUnsafe(delta)
    applyUnsafe(Math.addExact(current, validDelta))

/** A strictly positive reflection minibatch size. */
type MinibatchSize = MinibatchSize.T

object MinibatchSize extends RefinedSubtype[Int, Positive]

/** A non-negative cap on merge invocations or attempts. */
type MergeInvocationLimit = MergeInvocationLimit.T

object MergeInvocationLimit extends RefinedSubtype[Int, Positive0]

/** A strictly positive merge-evaluation subsample size. */
type MergeSubsampleSize = MergeSubsampleSize.T

object MergeSubsampleSize extends RefinedSubtype[Int, Positive]

/** A candidate pool containing at least the seed candidate. */
type CandidatePool = CandidatePool.T

object CandidatePool extends RefinedSubtype[Vector[Candidate], MinLength[1]]

/** A strictly positive number of candidates in a GEPA result. */
type GepaCandidateCount = GepaCandidateCount.T

object GepaCandidateCount extends RefinedSubtype[Int, Positive]
