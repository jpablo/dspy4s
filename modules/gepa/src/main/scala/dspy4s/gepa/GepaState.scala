package dspy4s.gepa

import scala.util.Random

/** The evolving GEPA search state: the candidate pool, each candidate's full-validation per-instance scores, and
  * lineage, plus the running metric-call count (the budget meter). The instance-type Pareto frontier is derived
  * from `valSubscores`. v0 is single-objective with the instance frontier and no merge. See PORT_GAPS G-12.
  *
  * `candidates`, `valSubscores`, and `parents` are aligned by candidate index; `valSubscores(i)` is candidate `i`'s
  * score on each validation example (aligned by validation index). */
final case class GepaState(
    candidates: Vector[Candidate],
    valSubscores: Vector[Vector[Double]],
    parents: Vector[Vector[Int]],
    totalMetricCalls: Int
):
  require(candidates.nonEmpty, "GepaState needs at least the seed candidate")
  require(
    valSubscores.length == candidates.length && parents.length == candidates.length,
    "GepaState candidates, valSubscores, and parents must be aligned by index"
  )
  require(
    valSubscores.map(_.length).distinct.sizeIs <= 1,
    "GepaState valSubscores rows must all have the same length (one score per validation example) — " +
      "[[paretoFrontier]] indexes every candidate at every instance"
  )

  /** Mean validation score of candidate `i`. */
  def aggregateScore(i: Int): Double =
    val scores = valSubscores(i)
    if scores.isEmpty then 0.0 else scores.sum / scores.size

  /** The best candidate by mean validation score — the program GEPA ultimately returns. */
  def bestIndex: Int = candidates.indices.maxBy(aggregateScore)

  /** The transitive ancestor set of candidate `i` (its parents, their parents, …), excluding `i` itself. Merge
    * crossover needs full ancestor chains to find a common ancestor of two frontier descendants; with reflective
    * mutation lineage is linear, but a merged candidate has two parents so chains can branch. */
  def ancestors(i: Int): Set[Int] =
    def walk(node: Int, found: Set[Int]): Set[Int] =
      parents(node).foldLeft(found) { (acc, parent) =>
        if acc.contains(parent) then acc else walk(parent, acc + parent)
      }
    walk(i, Set.empty)

  /** Append a newly-accepted candidate with its full-validation subscores, parent lineage (one parent for a
    * reflective mutation, two for a merge — empty only for the seed), and the metric calls its discovery
    * consumed. */
  def add(candidate: Candidate, subscores: Vector[Double], parents: Vector[Int], metricCalls: Int): GepaState =
    copy(
      candidates = candidates :+ candidate,
      valSubscores = valSubscores :+ subscores,
      parents = this.parents :+ parents,
      totalMetricCalls = totalMetricCalls + metricCalls
    )

  /** Instance-type Pareto frontier: for each validation-example index, the set of candidate indices achieving the
    * max score on it. A candidate appearing on more instances is "on the frontier" more — the basis for
    * Pareto-weighted selection. */
  def paretoFrontier: Map[Int, Set[Int]] =
    val numInstances = valSubscores.headOption.map(_.size).getOrElse(0)
    (0 until numInstances).iterator.map { j =>
      val best = candidates.indices.iterator.map(i => valSubscores(i)(j)).max
      j -> candidates.indices.filter(i => valSubscores(i)(j) == best).toSet
    }.toMap

object GepaState:
  /** Initialize from the seed candidate's full-validation evaluation. */
  def seed(candidate: Candidate, subscores: Vector[Double], metricCalls: Int): GepaState =
    GepaState(Vector(candidate), Vector(subscores), Vector(Vector.empty), metricCalls)

/** Picks which existing candidate to mutate next (gepa's CandidateSelector). */
trait CandidateSelector:
  def select(state: GepaState, rng: Random): Int

object CandidateSelector:
  /** Sample a candidate from the instance Pareto frontier, weighted by how many validation instances it is best
    * on (gepa's default "pareto" strategy — frequency-weighted frontier sampling). Falls back to a uniform draw
    * when there is no frontier yet (e.g. zero validation instances). */
  object Pareto extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int =
      val weighted: Seq[Int] = state.paretoFrontier.values.toSeq.flatten
      if weighted.isEmpty then rng.nextInt(state.candidates.size) else weighted(rng.nextInt(weighted.size))

  /** Always the current best by mean validation score (greedy). */
  object CurrentBest extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int = state.bestIndex

/** Picks WHICH component(s) of the selected parent to evolve this iteration (gepa's ReflectionComponentSelector).
  * Given the parent's component names (a stable, sorted list) and its current round-robin pointer, returns the
  * components to update and the next pointer. */
trait ComponentSelector:
  def select(components: Vector[String], pointer: Int): (Vector[String], Int)

object ComponentSelector:
  /** Update ONE component per iteration, cycling through them (gepa's default). Cheaper in reflection-LM calls and
    * lets each component's instruction settle independently; needs components that improve independently. */
  object RoundRobin extends ComponentSelector:
    def select(components: Vector[String], pointer: Int): (Vector[String], Int) =
      if components.isEmpty then (Vector.empty, pointer)
      else
        val i = pointer % components.size
        (Vector(components(i)), (i + 1) % components.size)

  /** Update ALL components every iteration. Costs more reflection calls but can fix interdependent components in one
    * accepted mutation (where round-robin would stall). */
  object All extends ComponentSelector:
    def select(components: Vector[String], pointer: Int): (Vector[String], Int) = (components, pointer)
