package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId

import scala.util.Random

/** The evolving GEPA search state: the candidate pool, each candidate's full-validation per-instance scores, lineage,
  * and round-robin pointer, plus the running metric-call count (the budget meter). The instance-type Pareto frontier is
  * derived from `valSubscores`. v0 is single-objective with the instance frontier. See PORT_GAPS G-12.
  *
  * `candidates`, `valSubscores`, and `parents` are aligned by candidate index; `valSubscores(i)` is candidate `i`'s
  * score on each validation example (aligned by validation index).
  */
final case class GepaState(
    candidates      : CandidatePool,
    valSubscores    : Vector[Vector[Double]],
    parents         : Vector[Vector[Int]],
    totalMetricCalls: MetricCallCount,
    /** The round-robin pointer of each candidate: the index of the component that the next reflective mutation of that
      * candidate changes (gepa's `named_predictor_id_to_update_next_for_program_candidate`). Empty means 0 for each
      * candidate, which is the value of a state that an older version saved. Read a pointer with [[pointerOf]].
      */
    nextComponent: Vector[Int] = Vector.empty
):
  require(
    valSubscores.length == candidates.length && parents.length == candidates.length,
    "GepaState candidates, valSubscores, and parents must be aligned by index"
  )
  require(
    nextComponent.isEmpty || nextComponent.length == candidates.length,
    "GepaState nextComponent must be empty or aligned with candidates"
  )
  require(nextComponent.forall(_ >= 0), "GepaState nextComponent pointers must not be negative")
  require(
    valSubscores.map(_.length).distinct.sizeIs <= 1,
    "GepaState valSubscores rows must all have the same length (one score per validation example) — " +
      "[[paretoFrontier]] indexes every candidate at every instance"
  )
  require(
    parents.zipWithIndex.forall { case (parentIds, child) =>
      parentIds.forall(parent => parent >= 0 && parent < child)
    },
    "GepaState parent ids must refer to earlier candidates"
  )

  /** Mean validation score of candidate `i`. */
  def aggregateScore(i: Int): Double =
    val scores = valSubscores(i)
    if scores.isEmpty then 0.0 else scores.sum / scores.size

  /** The best candidate by mean validation score — the program GEPA ultimately returns. */
  def bestIndex: Int = candidates.indices.maxBy(aggregateScore)

  /** The transitive ancestor set of candidate `i` (its parents, their parents, …), excluding `i` itself. Merge
    * crossover needs full ancestor chains to find a common ancestor of two frontier descendants; with reflective
    * mutation lineage is linear, but a merged candidate has two parents so chains can branch.
    */
  def ancestors(i: Int): Set[Int] =
    def walk(node: Int, found: Set[Int]): Set[Int] =
      parents(node).foldLeft(found) { (acc, parent) =>
        if acc.contains(parent) then acc else walk(parent, acc + parent)
      }
    walk(i, Set.empty)

  /** The round-robin pointer of candidate `i`. */
  def pointerOf(i: Int): Int = nextComponent.lift(i).getOrElse(0)

  /** This state with `pointer` as the round-robin pointer of candidate `i`. */
  def withPointer(i: Int, pointer: Int): GepaState = copy(nextComponent = allPointers.updated(i, pointer))

  private def allPointers: Vector[Int] = candidates.indices.toVector.map(pointerOf)

  /** Append a newly-accepted candidate with its full-validation subscores, parent lineage (one parent for a reflective
    * mutation, two for a merge — empty only for the seed), and the metric calls its discovery consumed.
    *
    * The new candidate takes the largest round-robin pointer of its parents (gepa `core/state.py`,
    * `update_state_with_new_program`). The pointer of a parent has already moved when its child is added, so the child
    * continues with the next component.
    */
  def add(candidate: Candidate, subscores: Vector[Double], parents: Vector[Int], metricCalls: Int): GepaState =
    copy(
      candidates = CandidatePool.assume(candidates :+ candidate),
      valSubscores = valSubscores :+ subscores,
      parents = this.parents :+ parents,
      totalMetricCalls = MetricCallCount.add(totalMetricCalls, metricCalls),
      nextComponent = allPointers :+ parents.map(pointerOf).maxOption.getOrElse(0)
    )

  /** Instance-type Pareto frontier: for each validation-example index, the set of candidate indices achieving the max
    * score on it. A candidate appearing on more instances is "on the frontier" more — the basis for Pareto-weighted
    * selection.
    */
  def paretoFrontier: Map[Int, Set[Int]] =
    val numInstances = valSubscores.headOption.map(_.size).getOrElse(0)
    (0 until numInstances).iterator.map { j =>
      val best = candidates.indices.iterator.map(i => valSubscores(i)(j)).max
      j -> candidates.indices.filter(i => valSubscores(i)(j) == best).toSet
    }.toMap

  /** [[paretoFrontier]] without the dominated candidates (gepa's `remove_dominated_programs`, with [[aggregateScore]]
    * as the score). Candidate selection and merge take their candidates from this frontier.
    */
  def nonDominatedFrontier: Map[Int, Set[Int]] = GepaState.removeDominated(paretoFrontier, aggregateScore)

object GepaState:

  /** Remove the dominated candidates from `fronts` (gepa's `remove_dominated_programs`). A candidate is dominated when
    * each front that holds it also holds a different candidate that stays (gepa's `is_dominated`).
    *
    * The routine examines the candidates in ascending `score` order, so a weak candidate leaves before a strong one.
    * The sort is stable. Candidates with equal scores keep the order of their first appearance in the fronts (fronts by
    * validation index, candidates by index in a front). Each front keeps at least one candidate.
    */
  private[gepa] def removeDominated(fronts: Map[Int, Set[Int]], score: Int => Double): Map[Int, Set[Int]] =
    val ordered  = fronts.toVector.sortBy(_._1).map(_._2)
    val frontsOf = ordered.flatMap(front => front.iterator.map(_ -> front)).groupMap(_._1)(_._2)
    val programs = ordered.flatMap(_.toVector.sorted).distinct.map(p => p -> score(p)).sortBy(_._2).map(_._1)
    // gepa starts the scan again after each removal. A removal only makes the other candidates harder to dominate, so
    // a candidate that one scan keeps also stays in each later scan. One pass gives the same result.
    val dominated = programs.foldLeft(Set.empty[Int]) { (removed, y) =>
      val isDominated = frontsOf(y).forall(_.exists(p => p != y && !removed.contains(p)))
      if isDominated then removed + y else removed
    }
    fronts.map((instance, front) => instance -> front.diff(dominated))

  /** Initialize from the seed candidate's full-validation evaluation. */
  def seed(candidate: Candidate, subscores: Vector[Double], metricCalls: Int): GepaState =
    GepaState(
      CandidatePool.assume(Vector(candidate)),
      Vector(subscores),
      Vector(Vector.empty),
      MetricCallCount.applyUnsafe(metricCalls)
    )

/** Picks which existing candidate to mutate next (gepa's CandidateSelector). */
trait CandidateSelector:
  def select(state: GepaState, rng: Random): Int

object CandidateSelector:
  /** Sample a candidate from the instance Pareto frontier, weighted by how many validation instances it is best on
    * (gepa's default "pareto" strategy — frequency-weighted frontier sampling). Falls back to a uniform draw when there
    * is no frontier yet (e.g. zero validation instances). The sample excludes dominated candidates
    * ([[GepaState.nonDominatedFrontier]]).
    */
  object Pareto extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int =
      val weighted: Seq[Int] = state.nonDominatedFrontier.values.toSeq.flatten
      if weighted.isEmpty then rng.nextInt(state.candidates.size) else weighted(rng.nextInt(weighted.size))

  /** Always the current best by mean validation score (greedy). */
  object CurrentBest extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int = state.bestIndex

/** Picks WHICH component(s) of the selected parent to evolve this iteration (gepa's ReflectionComponentSelector). Given
  * the parent's optimizable IDs (a stable, sorted list) and its current round-robin pointer, returns the components to
  * update and the next pointer.
  */
trait ComponentSelector:
  def select(components: Vector[OptimizableId], pointer: Int): (Vector[OptimizableId], Int)

object ComponentSelector:
  /** Update ONE component per iteration, cycling through them (gepa's default). Cheaper in reflection-LM calls and lets
    * each component's instruction settle independently; needs components that improve independently.
    */
  object RoundRobin extends ComponentSelector:
    def select(components: Vector[OptimizableId], pointer: Int): (Vector[OptimizableId], Int) =
      if components.isEmpty then (Vector.empty, pointer)
      else
        val i = pointer % components.size
        (Vector(components(i)), (i + 1) % components.size)

  /** Update ALL components every iteration. Costs more reflection calls but can fix interdependent components in one
    * accepted mutation (where round-robin would stall).
    */
  object All extends ComponentSelector:
    def select(components: Vector[OptimizableId], pointer: Int): (Vector[OptimizableId], Int) = (components, pointer)
