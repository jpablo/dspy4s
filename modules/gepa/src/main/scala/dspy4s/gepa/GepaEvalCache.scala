package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
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
  private val cache = mutable.HashMap.empty[(Candidate, String), Double]

  /** A stable, content-based key for an example (two examples with identical content share an entry, which is
    * correct — the same candidate scores them identically). */
  private def exampleKey(example: Example): String = DynamicValues.renderText(example.values)

  /** The batch's per-example scores (aligned with `batch`) plus the count of examples that had to be actually
    * evaluated (cache misses). Cache hits are free. */
  def scores(candidate: Candidate, batch: Vector[Example])(using RuntimeContext): (Vector[Double], Int) =
    val keys        = batch.map(exampleKey)
    val uncachedIdx = batch.indices.iterator.filterNot(i => cache.contains((candidate, keys(i)))).toVector
    if uncachedIdx.nonEmpty then
      val evaled = adapter.evaluate(uncachedIdx.map(batch), candidate, captureTraces = false).scores
      uncachedIdx.iterator.zip(evaled.iterator).foreach { case (i, s) => cache((candidate, keys(i))) = s }
    (batch.indices.map(i => cache((candidate, keys(i)))).toVector, uncachedIdx.size)
