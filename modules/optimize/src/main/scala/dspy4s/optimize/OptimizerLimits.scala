package dspy4s.optimize

import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Greater
import io.github.iltotore.iron.constraint.numeric.Positive
import io.github.iltotore.iron.constraint.numeric.Positive0

/** A strictly positive number of optimizer candidates. */
type CandidateCount = CandidateCount.T

object CandidateCount extends RefinedSubtype[Int, Positive]

/** A non-negative number of additional random-search candidates. */
type SearchCandidateCount = SearchCandidateCount.T

object SearchCandidateCount extends RefinedSubtype[Int, Positive0]

/** A strictly positive number of optimization trials. */
type TrialCount = TrialCount.T

object TrialCount extends RefinedSubtype[Int, Positive]

/** A strictly positive number of induced rules. */
type RuleCount = RuleCount.T

object RuleCount extends RefinedSubtype[Int, Positive]

/** A strictly positive number of optimizer rounds. */
type RoundCount = RoundCount.T

object RoundCount extends RefinedSubtype[Int, Positive]

/** COPRO requires at least two candidates so one generated proposal accompanies the current instruction. */
type CoproBreadth = CoproBreadth.T

object CoproBreadth extends RefinedSubtype[Int, Greater[1]]

/** A strictly positive number of members sampled from an ensemble. */
type EnsembleSize = EnsembleSize.T

object EnsembleSize extends RefinedSubtype[Int, Positive]

/** A strictly positive number of examples sampled for a dataset summary. */
type DatasetSampleSize = DatasetSampleSize.T

object DatasetSampleSize extends RefinedSubtype[Int, Positive]
