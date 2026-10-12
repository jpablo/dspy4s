package dspy4s.gepa

import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext

import scala.collection.mutable

/** The result of [[GepaEvalCache.scored]]: the score and the objective scores of each example of a batch, and the
  * number of examples that an evaluation ran for. `objectiveScores` is aligned with the batch. An example with no
  * objective score has an empty map.
  */
final case class ScoredBatch(
    scores         : Vector[Double],
    objectiveScores: Vector[Map[String, Double]],
    evaluated      : Int
)

/** Memoizes scores-only evaluations by `(candidate, example)` so an identical pair is never re-run against the LM
  * (gepa's `EvaluationCache`). The headline saving: a merged candidate's subsample eval is reused when that candidate
  * is later full-evaluated on accept, and any candidate re-scored on an example it already saw is free.
  *
  * The cache keeps the score and the objective scores of each pair, as gepa's `CachedEvaluation` does. A pair with no
  * objective score has an empty map.
  *
  * Only the scores-only path is cached; the reflective minibatch (which captures per-predictor traces) is not, since
  * traces aren't memoized. [[scored]] returns the scores and the objective scores of the batch plus the number of
  * ACTUAL (uncached) evaluations. The engine charges only those against the metric-call budget, as gepa does
  * (`total_num_evals += actual`).
  */
final class GepaEvalCache[P](adapter: GepaAdapter[P]):
  import GepaEvalCache.Entry

  private val cache = mutable.HashMap.empty[(Candidate, Example), Entry]

  /** Number of structurally distinct candidate/example pairs not yet cached. */
  def uncachedCount(candidate: Candidate, batch: Vector[Example]): Int =
    batch.distinct.count(example => !cache.contains((candidate, example)))

  /** Restore already-accounted scores (for example from a persisted [[GepaState]]) without re-evaluating them.
    * `objectiveScores` is empty (no objective score for each example) or aligned with `batch`.
    *
    * The cache holds one result for a `(candidate, example)` pair. `restore` gives a `Left` (corrupt state) when the
    * score or the objective scores of a pair are different from the result that the cache holds. It gives a `Left` also
    * when `batch` has the same example two times with different results. A pair with no objective score is different
    * from the same pair with an objective score. [[scored]] gives one result for a pair, so a state that this cache
    * made has no such conflict. A `Left` does not change the cache.
    */
  def restore(
      candidate      : Candidate,
      batch          : Vector[Example],
      scores         : Vector[Double],
      objectiveScores: Vector[Map[String, Double]] = Vector.empty
  ): Either[String, Unit] =
    if batch.size != scores.size then
      Left(s"Cannot restore ${scores.size} GEPA scores for a batch of ${batch.size} examples")
    else if objectiveScores.nonEmpty && objectiveScores.size != batch.size then
      Left(s"Cannot restore ${objectiveScores.size} GEPA objective score maps for a batch of ${batch.size} examples")
    else
      val objectives = if objectiveScores.isEmpty then batch.map(_ => Map.empty[String, Double]) else objectiveScores
      val entries    = batch.indices.toVector.map(i => (candidate, batch(i)) -> Entry(scores(i), objectives(i)))
      val restored   = entries.foldLeft[Either[String, Map[(Candidate, Example), Entry]]](Right(Map.empty)) {
        case (done, (key, entry)) => done.flatMap { pending =>
            cache.get(key).orElse(pending.get(key)).flatMap(_.conflictWith(entry)) match
              case Some(conflict) => Left(conflict)
              case None           => Right(pending.updated(key, entry))
          }
      }
      restored.map { pending =>
        cache.addAll(pending)
        ()
      }

  /** The scores and the objective scores of `batch` for `candidate`, plus the count of examples that an evaluation ran
    * for (cache misses). A cache hit gives the score and the objective scores of the first evaluation of that pair.
    */
  def scored(candidate: Candidate, batch: Vector[Example])(using RuntimeContext): ScoredBatch =
    scoredAll(Vector(candidate), batch).head

  /** [[scored]] for each of `candidates`, in order, with one [[GepaAdapter.batchEvaluate]] call for the examples that
    * are not in the cache. The engine thread reads and writes the cache; only the evaluations run in parallel. Equal
    * candidates in `candidates` are evaluated one time.
    *
    * [[ScoredBatch.evaluated]] of the first of some equal candidates is the count of its cache misses. For each later
    * one it is 0, so the sum over the result is the number of examples that an evaluation ran for.
    */
  def scoredAll(candidates: Vector[Candidate], batch: Vector[Example])(using RuntimeContext): Vector[ScoredBatch] =
    val examples = batch.distinct
    // The examples of each distinct candidate that are not in the cache. A candidate with no such example is not here.
    val missing = candidates.distinct
      .map(candidate => candidate -> examples.filterNot(example => cache.contains((candidate, example))))
      .filter((_, misses) => misses.nonEmpty)
    val evaluated = adapter.batchEvaluate(missing, captureTraces = false)
    require(
      evaluated.size == missing.size,
      s"GEPA adapter returned ${evaluated.size} evaluations for ${missing.size} candidates"
    )
    missing.zip(evaluated).foreach { case ((candidate, misses), evaluation) => store(candidate, misses, evaluation) }
    val missCount  = missing.iterator.map((candidate, misses) => candidate -> misses.size).toMap
    val firstIndex = candidates.zipWithIndex.distinctBy((candidate, _) => candidate).toMap
    candidates.zipWithIndex.map { (candidate, index) =>
      val entries = batch.map(example => cache((candidate, example)))
      ScoredBatch(
        entries.map(_.score),
        entries.map(_.objectiveScores),
        evaluated = if firstIndex(candidate) == index then missCount.getOrElse(candidate, 0) else 0
      )
    }

  /** Write the result of one evaluation of `candidate` on `examples` to the cache. */
  private def store(candidate: Candidate, examples: Vector[Example], evaluation: EvaluationBatch): Unit =
    val objectives = evaluation.objectiveScores.getOrElse(examples.map(_ => Map.empty[String, Double]))
    require(
      evaluation.scores.size == examples.size,
      s"GEPA adapter returned ${evaluation.scores.size} scores for ${examples.size} examples"
    )
    require(
      objectives.size == examples.size,
      s"GEPA adapter returned ${objectives.size} objective score maps for ${examples.size} examples"
    )
    examples.indices.foreach(i => cache((candidate, examples(i))) = Entry(evaluation.scores(i), objectives(i)))

  /** The batch's per-example scores (aligned with `batch`) plus the count of examples that had to be actually evaluated
    * (cache misses). Cache hits are free. The scores of [[scored]], with no objective scores.
    */
  def scores(candidate: Candidate, batch: Vector[Example])(using RuntimeContext): (Vector[Double], Int) =
    val result = scored(candidate, batch)
    (result.scores, result.evaluated)

object GepaEvalCache:

  /** The cached result of one `(candidate, example)` pair. */
  private final case class Entry(score: Double, objectiveScores: Map[String, Double]):

    /** The text of the conflict when `other` is a different result for the same pair. The comparison of two numbers is
      * that of `java.lang.Double.compare`, so a NaN is equal to a NaN.
      */
    def conflictWith(other: Entry): Option[String] =
      if !Entry.same(score, other.score) then
        Some(s"Conflicting restored GEPA scores for an identical candidate/example pair: $score and ${other.score}")
      else if objectiveScores.keySet != other.objectiveScores.keySet ||
        objectiveScores.exists((name, value) => !Entry.same(value, other.objectiveScores(name)))
      then
        Some(
          "Conflicting restored GEPA objective scores for an identical candidate/example pair: " +
            s"${Entry.render(objectiveScores)} and ${Entry.render(other.objectiveScores)}"
        )
      else None

  private object Entry:
    private def same(a: Double, b: Double): Boolean = java.lang.Double.compare(a, b) == 0

    private def render(objectiveScores: Map[String, Double]): String =
      objectiveScores.toVector.sortBy(_._1).map((name, value) => s"$name=$value").mkString("{", ", ", "}")
