package dspy4s.programs.retrievers

import dspy4s.core.data.Example
import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.collection.MinLength

/** A corpus containing at least one passage. */
type NonEmptyCorpus = NonEmptyCorpus.T

object NonEmptyCorpus extends RefinedSubtype[Vector[String], MinLength[1]]

/** A training set containing at least one example, suitable for nearest-neighbor retrieval. */
type NonEmptyTrainset = NonEmptyTrainset.T

object NonEmptyTrainset extends RefinedSubtype[Vector[Example], MinLength[1]]
