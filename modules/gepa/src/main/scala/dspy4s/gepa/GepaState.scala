package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId

import scala.util.Random

/** The Pareto fronts that candidate selection and merge use (gepa's `frontier_type`). A front is the set of candidates
  * with the best value for one key ([[FrontierKey]]).
  *
  *   - `Instance`: one front for each validation example, by the score of the example. The default, as upstream.
  *   - `Objective`: one front for each objective, by the mean objective score of a candidate.
  *   - `Hybrid`: the fronts of `Instance` and the fronts of `Objective` together.
  *   - `Cartesian`: one front for each pair of a validation example and an objective, by the objective score of the
  *     example.
  *
  * Each type other than `Instance` needs objective scores from the metric
  * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]).
  */
enum FrontierType derives CanEqual:
  case Instance, Objective, Hybrid, Cartesian

object FrontierType:

  /** The type with the given name, as [[GepaStatePersistence]] writes it. */
  def parse(name: String): Option[FrontierType] = values.find(_.toString == name)

/** The key of one Pareto front of a [[GepaState]] (gepa's `FrontierKey`). */
enum FrontierKey derives CanEqual:
  /** The validation example with this index. */
  case Instance(validationIndex: Int)

  /** The objective with this name. */
  case Objective(name: String)

  /** The objective `objective` on the validation example with the index `validationIndex`. */
  case Cartesian(validationIndex: Int, objective: String)

/** The evolving GEPA search state: the candidate pool, each candidate's full-validation per-instance scores, objective
  * scores, lineage, and round-robin pointer, plus the running metric-call count (the budget meter). The Pareto fronts
  * are derived from the scores. See PORT_GAPS G-12 and G-45.
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
    nextComponent: Vector[Int] = Vector.empty,
    /** The objective scores of each candidate on each validation example (gepa keeps the aggregates only, in
      * `prog_candidate_objective_scores`). The field has one of two forms:
      *   - Empty, when no candidate has an objective score. This is also the value of a state that an older version
      *     saved.
      *   - One row for each candidate. Row `i` is aligned with `valSubscores(i)`, and an example with no objective
      *     score has an empty map.
      *
      * [[add]] and [[GepaState.seed]] keep these forms. Read a row with [[objectiveScoresOf]].
      */
    valObjectiveScores: Vector[Vector[Map[String, Double]]] = Vector.empty,
    /** The fronts that [[frontier]] gives (gepa's `frontier_type`). */
    frontierType: FrontierType = FrontierType.Instance,
    /** The number of iterations that the run finished (gepa's `i + 1`). It is the index of the next iteration, so a
      * resumed run continues the walk of the minibatch sampler and the random draws. A merge iteration counts.
      */
    iteration: Int = 0,
    /** The merge schedule of the run. */
    merge: MergeSchedule = MergeSchedule.initial,
    /** The total metric calls of the run before the validation of each candidate (gepa's
      * `num_metric_calls_by_discovery`), aligned with `candidates`. The seed has 0. Empty for a state that an older
      * version saved: [[add]] then keeps it empty.
      */
    discoveryCalls: Vector[Int] = Vector.empty,
    /** The number of proposal tasks for which the proposer failed two times, and that thus gave no child. Upstream logs
      * each of them; dspy4s counts them, so a run in which each reflection call fails is visible in the result.
      */
    failedProposals: Int = 0
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
    valObjectiveScores.isEmpty ||
      (valObjectiveScores.length == candidates.length &&
        valObjectiveScores.zip(valSubscores).forall((objectives, scores) => objectives.length == scores.length)),
    "GepaState valObjectiveScores must be empty or aligned with valSubscores"
  )
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
  require(iteration >= 0, s"GepaState iteration must not be negative: $iteration")
  require(failedProposals >= 0, s"GepaState failedProposals must not be negative: $failedProposals")
  require(
    discoveryCalls.isEmpty || discoveryCalls.length == candidates.length,
    "GepaState discoveryCalls must be empty or aligned with candidates"
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

  /** The objective scores of candidate `i` on each validation example, aligned with `valSubscores(i)`. An example with
    * no objective score has an empty map.
    */
  def objectiveScoresOf(i: Int): Vector[Map[String, Double]] =
    valObjectiveScores.lift(i).getOrElse(GepaState.noObjectives(valSubscores(i)))

  /** The mean of each objective of candidate `i`, over the validation examples that have a score for that objective
    * (gepa's `_aggregate_objective_scores`). Empty when the candidate has no objective score.
    */
  def objectiveAggregates(i: Int): Map[String, Double] =
    // `groupMap` keeps the order of the validation examples, so the sum has the terms in the order of gepa.
    objectiveScoresOf(i).flatten.groupMap(_._1)(_._2).map((name, scores) => name -> scores.sum / scores.size)

  /** The names of the objectives that one candidate at least has a score for, in alphabetical order. */
  def objectiveNames: Vector[String] =
    valObjectiveScores.iterator.flatten.flatMap(_.keysIterator).toSet.toVector.sorted

  /** The largest [[objectiveAggregates]] value of each objective, over the candidates that have that objective (gepa's
    * `objective_pareto_front`).
    */
  def objectiveFront: Map[String, Double] =
    aggregatesByObjective.map((name, values) => name -> values.iterator.map(_._2).max)

  /** The candidates with the [[objectiveFront]] value of each objective (gepa's `program_at_pareto_front_objectives`).
    */
  def objectiveBest: Map[String, Set[Int]] =
    aggregatesByObjective.map((name, values) => name -> GepaState.best(values))

  /** The [[objectiveAggregates]] value of each candidate that has the objective, for each objective. */
  private def aggregatesByObjective: Map[String, Vector[(Int, Double)]] =
    candidates.indices.toVector
      .flatMap(i => objectiveAggregates(i).iterator.map((name, value) => name -> (i -> value)))
      .groupMap(_._1)(_._2)

  /** Append a newly-accepted candidate with its full-validation subscores, parent lineage (one parent for a reflective
    * mutation, two for a merge, and none only for the seed), and the metric calls of its validation. The caller adds
    * each other metric call of the iteration to [[totalMetricCalls]] before this call, because [[discoveryCalls]]
    * records the total before the validation (gepa's `num_metric_calls_by_discovery`).
    *
    * The new candidate takes the largest round-robin pointer of its parents (gepa `core/state.py`,
    * `update_state_with_new_program`). The pointer of a parent has already moved when its child is added, so the child
    * continues with the next component.
    *
    * `objectiveScores` is empty (the candidate has no objective score), or it is aligned with `subscores`. The result
    * keeps the forms of [[valObjectiveScores]]. A candidate with no objective score is in no objective front and in no
    * cartesian front. gepa raises for such a candidate when the frontier type is not `instance`. dspy4s keeps it in the
    * pool, because a candidate that fails on each validation example has no objective score.
    */
  def add(
      candidate      : Candidate,
      subscores      : Vector[Double],
      parents        : Vector[Int],
      metricCalls    : Int,
      objectiveScores: Vector[Map[String, Double]] = Vector.empty
  ): GepaState =
    val row = GepaState.objectiveRow(subscores, objectiveScores)
    copy(
      candidates = CandidatePool.assume(candidates :+ candidate),
      valSubscores = valSubscores :+ subscores,
      parents = this.parents :+ parents,
      totalMetricCalls = MetricCallCount.add(totalMetricCalls, metricCalls),
      nextComponent = allPointers :+ parents.map(pointerOf).maxOption.getOrElse(0),
      discoveryCalls = if discoveryCalls.isEmpty then discoveryCalls else discoveryCalls :+ totalMetricCalls,
      valObjectiveScores =
        if valObjectiveScores.nonEmpty then valObjectiveScores :+ row
        // The first objective score in the pool: each earlier candidate gets a row of empty maps.
        else if row.exists(_.nonEmpty) then valSubscores.map(GepaState.noObjectives) :+ row
        else Vector.empty
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

  /** The Pareto fronts of [[frontierType]] with their keys, in a fixed order (gepa's `get_pareto_front_mapping`). A
    * front is the set of the candidates with the largest value for its key. Only a candidate that has a value for the
    * key takes part, and a key that no candidate has a value for has no front.
    *
    *   - `Instance`: the key `Instance(j)` for each validation index `j`, in index order. The value of a candidate is
    *     its score on example `j`. These are the fronts of [[paretoFrontier]].
    *   - `Objective`: the key `Objective(name)` for each name of [[objectiveNames]], in that order. The value of a
    *     candidate is its [[objectiveAggregates]] value. These are the fronts of [[objectiveBest]].
    *   - `Hybrid`: the fronts of `Instance`, then the fronts of `Objective`.
    *   - `Cartesian`: the key `Cartesian(j, name)`, ordered by `j` and then by `name`. The value of a candidate is its
    *     objective score `name` on example `j`.
    *
    * gepa keeps the keys in the order of their first appearance. dspy4s uses the order above, because a map of
    * objective scores has no order. The order has an effect only on the candidate that a seeded random draw selects.
    */
  def fronts: Vector[(FrontierKey, Set[Int])] =
    frontierType match
      case FrontierType.Instance  => instanceFronts
      case FrontierType.Objective => objectiveFronts
      case FrontierType.Hybrid    => instanceFronts ++ objectiveFronts
      case FrontierType.Cartesian => cartesianFronts

  private def instanceFronts: Vector[(FrontierKey, Set[Int])] =
    paretoFrontier.toVector.sortBy(_._1).map((j, front) => FrontierKey.Instance(j) -> front)

  private def objectiveFronts: Vector[(FrontierKey, Set[Int])] =
    val best = objectiveBest
    objectiveNames.map(name => FrontierKey.Objective(name) -> best(name))

  private def cartesianFronts: Vector[(FrontierKey, Set[Int])] =
    val rows         = candidates.indices.toVector.map(objectiveScoresOf)
    val numInstances = valSubscores.headOption.map(_.size).getOrElse(0)
    (0 until numInstances).toVector.flatMap { j =>
      val cells = rows.map(_(j))
      cells.flatMap(_.keysIterator).distinct.sorted.map { name =>
        FrontierKey.Cartesian(j, name) ->
          GepaState.best(cells.zipWithIndex.flatMap((cell, i) => cell.get(name).map(i -> _)))
      }
    }

  /** The fronts of [[fronts]] by their position in that sequence. For `Instance`, the position is the validation index,
    * so this frontier is [[paretoFrontier]].
    */
  def frontier: Map[Int, Set[Int]] =
    frontierType match
      case FrontierType.Instance => paretoFrontier
      case _                     => fronts.map(_._2).zipWithIndex.map(_.swap).toMap

  /** [[frontier]] without the dominated candidates (gepa's `remove_dominated_programs`, with [[aggregateScore]] as the
    * score). Candidate selection and merge take their candidates from this frontier. The score is the mean validation
    * score for each frontier type.
    */
  def nonDominatedFrontier: Map[Int, Set[Int]] = GepaState.removeDominated(frontier, aggregateScore)

object GepaState:

  /** A row of [[GepaState.valObjectiveScores]] for a candidate with no objective score: one empty map for each score.
    */
  private def noObjectives(subscores: Vector[Double]): Vector[Map[String, Double]] = subscores.map(_ => Map.empty)

  /** The row of [[GepaState.valObjectiveScores]] for the given `objectiveScores` of a candidate. Empty
    * `objectiveScores` give a row of empty maps.
    */
  private def objectiveRow(
      subscores      : Vector[Double],
      objectiveScores: Vector[Map[String, Double]]
  ): Vector[Map[String, Double]] =
    require(
      objectiveScores.isEmpty || objectiveScores.length == subscores.length,
      "GepaState objectiveScores must be empty or aligned with subscores"
    )
    if objectiveScores.isEmpty then noObjectives(subscores) else objectiveScores

  /** The candidates with the largest value. `values` holds one value at least. */
  private def best(values: Vector[(Int, Double)]): Set[Int] =
    val top = values.iterator.map(_._2).max
    values.iterator.collect { case (candidate, value) if value == top => candidate }.toSet

  /** Remove the dominated candidates from `fronts` (gepa's `remove_dominated_programs`). A candidate is dominated when
    * each front that holds it also holds a different candidate that stays (gepa's `is_dominated`).
    *
    * The routine examines the candidates in ascending `score` order, so a weak candidate leaves before a strong one.
    * The sort is stable. Candidates with equal scores keep the order of their first appearance in the fronts (fronts by
    * key, candidates by index in a front). Each front keeps at least one candidate. The key of a front is its position
    * in [[GepaState.fronts]], which is the validation index for the instance type.
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

  /** Initialize from the seed candidate's full-validation evaluation. `objectiveScores` is empty (the seed has no
    * objective score), or it is aligned with `subscores`.
    */
  def seed(
      candidate      : Candidate,
      subscores      : Vector[Double],
      metricCalls    : Int,
      objectiveScores: Vector[Map[String, Double]] = Vector.empty,
      frontierType   : FrontierType                = FrontierType.Instance
  ): GepaState =
    val row = objectiveRow(subscores, objectiveScores)
    GepaState(
      CandidatePool.assume(Vector(candidate)),
      Vector(subscores),
      Vector(Vector.empty),
      MetricCallCount.applyUnsafe(metricCalls),
      valObjectiveScores = if row.exists(_.nonEmpty) then Vector(row) else Vector.empty,
      frontierType = frontierType,
      discoveryCalls = Vector(0)
    )

/** Picks which existing candidate to mutate next (gepa's CandidateSelector). */
trait CandidateSelector:
  def select(state: GepaState, rng: Random): Int

object CandidateSelector:
  /** Sample a candidate from the Pareto frontier of the state, weighted by the number of fronts that hold it (gepa's
    * default "pareto" strategy — frequency-weighted frontier sampling). For the instance type, that number is how many
    * validation instances the candidate is best on. Falls back to a uniform draw when there is no frontier yet (e.g.
    * zero validation instances). The sample excludes dominated candidates ([[GepaState.nonDominatedFrontier]]).
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
    * accepted mutation (where round-robin would stall). The proposer writes the texts of an iteration in one call, so a
    * proposal that fails for one component gives no new text for the other components of that iteration.
    */
  object All extends ComponentSelector:
    def select(components: Vector[OptimizableId], pointer: Int): (Vector[OptimizableId], Int) = (components, pointer)
