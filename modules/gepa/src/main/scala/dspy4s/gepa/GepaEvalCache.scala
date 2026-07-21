package dspy4s.gepa

import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext

import scala.collection.mutable

/** Memoizes scores-only evaluations by `(candidate, example)` so an identical pair is never re-run against the LM
  * (gepa's `EvaluationCache`). The headline saving: a merged candidate's subsample eval is reused when that
  * candidate is later full-evaluated on accept, and any candidate re-scored on an example it already saw is free.
  *
  * Only the scores-only path is cached; the reflective minibatch (which captures per-predictor traces) is not, since
  * traces aren't memoized. `scores` returns the batch's scores plus the number of ACTUAL (uncached) evaluations —
  * the engine charges only those against the metric-call budget, matching gepa's `total_num_evals += actual`. */
final class GepaEvalCache[P](adapter: GepaAdapter[P]):
  private val cache = mutable.HashMap.empty[(Candidate, Example), Double]

  /** Number of structurally distinct candidate/example pairs not yet cached. */
  def uncachedCount(candidate: Candidate, batch: Vector[Example]): Int =
    batch.distinct.count(example => !cache.contains((candidate, example)))

  /** Restore already-accounted scores (for example from a persisted [[GepaState]]) without re-evaluating them.
    * Conflicting scores for an identical pair are rejected as corrupt state. */
  def restore(candidate: Candidate, batch: Vector[Example], scores: Vector[Double]): Either[String, Unit] =
    if batch.size != scores.size then
      Left(s"Cannot restore ${scores.size} GEPA scores for a batch of ${batch.size} examples")
    else
      val entries = batch.zip(scores).map { case (example, score) => (candidate, example) -> score }
      val conflict = entries.collectFirst {
        case (key, score) if cache.get(key).exists(existing => java.lang.Double.compare(existing, score) != 0) =>
          s"Conflicting restored GEPA scores for an identical candidate/example pair: ${cache(key)} and $score"
      }.orElse {
        entries.groupMap(_._1)(_._2).collectFirst {
          case (_, values) if values.distinctBy(java.lang.Double.doubleToLongBits).size > 1 =>
            s"Conflicting restored GEPA scores for an identical candidate/example pair: ${values.mkString(", ")}"
        }
      }
      conflict match
        case Some(error) => Left(error)
        case None =>
          cache.addAll(entries)
          Right(())

  /** The batch's per-example scores (aligned with `batch`) plus the count of examples that had to be actually
    * evaluated (cache misses). Cache hits are free. */
  def scores(candidate: Candidate, batch: Vector[Example])(using RuntimeContext): (Vector[Double], Int) =
    val missing = batch.distinct.filterNot(example => cache.contains((candidate, example)))
    if missing.nonEmpty then
      val evaluated = adapter.evaluate(missing, candidate, captureTraces = false).scores
      require(
        evaluated.size == missing.size,
        s"GEPA adapter returned ${evaluated.size} scores for ${missing.size} examples"
      )
      missing.zip(evaluated).foreach { case (example, score) => cache((candidate, example)) = score }
    (batch.map(example => cache((candidate, example))), missing.size)
