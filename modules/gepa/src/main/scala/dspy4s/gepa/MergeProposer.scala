package dspy4s.gepa

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext

import scala.collection.mutable
import scala.util.Random

/** A proposed merge: the crossover candidate, its two parents, whether it cleared the subsample gate (its merged
  * subsample score is at least the better parent's), and the metric calls the subsample eval consumed. The engine
  * decides what to do (full-eval + add on accept). */
final case class MergeProposal(candidate: Candidate, parents: Vector[Int], accepted: Boolean, metricCalls: Int)

/** GEPA's merge proposer — the crossover ("genetic") half of Genetic-Pareto. It combines two Pareto-frontier
  * descendants of a common ancestor: for each component (predictor), it takes the version from whichever descendant
  * IMPROVED it relative to the ancestor, yielding a child that stacks the two lineages' complementary gains. A
  * faithful port of gepa's `MergeProposer` / `sample_and_attempt_merge_programs_by_common_predictors`.
  *
  * Merge only fires for multi-component programs with branching lineage: the triplet must have a "desirable
  * predictor" (a component exactly one descendant changed), which a single-component program can never satisfy, so
  * merge is a safe no-op there.
  *
  * Stateful, mirroring gepa: the engine schedules merges after each accepted reflective mutation
  * ([[onReflectiveAccepted]]) and attempts one when [[shouldAttempt]]; accepted merges are consumed via
  * [[onMergeAccepted]]. `performed*` track already-attempted triplets so the same merge is not retried. */
final class MergeProposer[P](
    adapter: GepaAdapter[P],
    valset: Vector[Example],
    maxMergeInvocations: Int,
    rng: Random,
    maxAttempts: Int = 10,
    subsampleSize: Int = 5
):
  import MergeProposer.*

  var mergesDue: Int                 = 0
  var totalMergesTested: Int         = 0
  var lastIterFoundNewProgram        = false
  private val performedTriplets      = mutable.Set.empty[(Int, Int, Int)]
  private val performedDescs         = mutable.Set.empty[(Int, Int, Vector[Int])]

  /** A merge is worth attempting this iteration iff one is scheduled AND the last iteration produced a new program
    * (so the frontier just changed). */
  def shouldAttempt: Boolean = mergesDue > 0 && lastIterFoundNewProgram

  /** After an accepted reflective mutation: the frontier changed, so schedule a merge (capped at the budget). */
  def onReflectiveAccepted(): Unit =
    lastIterFoundNewProgram = true
    if totalMergesTested < maxMergeInvocations then mergesDue += 1

  /** After an accepted merge: consume one scheduled merge and count it. */
  def onMergeAccepted(): Unit =
    mergesDue -= 1
    totalMergesTested += 1

  /** Attempt a merge over the current state's Pareto dominators. Returns the subsample-gated proposal, or `None`
    * when no mergeable triplet is found (the engine then falls through to reflective mutation). */
  def propose(state: GepaState)(using RuntimeContext): Option[MergeProposal] =
    val dominators = state.paretoFrontier.values.flatten.toSet.toVector.sorted
    if dominators.size < 2 || state.candidates.size < 3 then return None

    findTriplet(state, dominators).flatMap { case (id1, id2, ancestor) =>
      val (merged, desc) =
        crossover(state.candidates(ancestor), id1, state.candidates(id1), id2, state.candidates(id2), state.aggregateScore, rng)
      if performedDescs.contains((id1, id2, desc)) then None // this exact merge was already produced
      else
        performedTriplets += ((id1, id2, ancestor))
        performedDescs += ((id1, id2, desc))

        val subIdx = selectSubsample(state.valSubscores(id1), state.valSubscores(id2), subsampleSize, rng)
        if subIdx.isEmpty then None
        else
          val before1     = subIdx.iterator.map(state.valSubscores(id1)).sum
          val before2     = subIdx.iterator.map(state.valSubscores(id2)).sum
          val mergedScore = adapter.evaluate(subIdx.map(valset), merged, captureTraces = false).scores.sum
          Some(MergeProposal(merged, Vector(id1, id2), accepted = mergedScore >= math.max(before1, before2), metricCalls = subIdx.size))
    }

  /** Up to `maxAttempts` times: sample two distinct dominators (neither an ancestor of the other) and look for a
    * common ancestor that is (a) not already merged for this pair, (b) outperformed by both descendants, and (c)
    * "desirable" (some component exactly one descendant changed). Picks among valid ancestors weighted by score. */
  private def findTriplet(state: GepaState, dominators: Vector[Int]): Option[(Int, Int, Int)] =
    var attempt = 0
    while attempt < maxAttempts do
      attempt += 1
      val pair = rng.shuffle(dominators).take(2)
      if pair.size == 2 then
        val i = math.min(pair(0), pair(1))
        val j = math.max(pair(0), pair(1))
        val ancI = state.ancestors(i)
        val ancJ = state.ancestors(j)
        if !ancI.contains(j) && !ancJ.contains(i) then
          val common = (ancI intersect ancJ).filter { a =>
            !performedTriplets.contains((i, j, a)) &&
            state.aggregateScore(a) <= state.aggregateScore(i) &&
            state.aggregateScore(a) <= state.aggregateScore(j) &&
            hasDesirablePredictors(state.candidates(a), state.candidates(i), state.candidates(j))
          }.toVector
          if common.nonEmpty then
            return Some((i, j, weightedPick(common, a => state.aggregateScore(a), rng)))
    None

object MergeProposer:

  /** A triplet is mergeable iff some component is unchanged in exactly one descendant (so the OTHER descendant's
    * change is the one to carry forward). gepa's `does_triplet_have_desirable_predictors`. */
  private[gepa] def hasDesirablePredictors(ancestor: Candidate, cand1: Candidate, cand2: Candidate): Boolean =
    ancestor.keys.exists { name =>
      val (anc, v1, v2) = (ancestor(name), cand1(name), cand2(name))
      (anc == v1 || anc == v2) && v1 != v2
    }

  /** Per-component crossover over a common ancestor (gepa's inner merge loop). For each component: if exactly one
    * descendant changed it, take the changed one; if both changed it, take the higher-scoring descendant's (ties
    * broken randomly); if both agree, take either. Returns the merged candidate and the per-component source
    * candidate index (the "description" used to dedup identical merges). */
  private[gepa] def crossover(
      ancestor: Candidate,
      id1: Int, cand1: Candidate,
      id2: Int, cand2: Candidate,
      aggregateScore: Int => Double,
      rng: Random
  ): (Candidate, Vector[Int]) =
    val names = ancestor.keys.toVector.sorted
    val chosen = names.map { name =>
      val (anc, v1, v2) = (ancestor(name), cand1(name), cand2(name))
      val src =
        if (anc == v1 || anc == v2) && v1 != v2 then if anc == v1 then id2 else id1
        else if anc != v1 && anc != v2 then
          if aggregateScore(id1) > aggregateScore(id2) then id1
          else if aggregateScore(id2) > aggregateScore(id1) then id2
          else rng.shuffle(Vector(id1, id2)).head
        else id1 // v1 == v2
      name -> src
    }
    val merged = chosen.map { case (name, src) => name -> (if src == id1 then cand1(name) else cand2(name)) }.toMap
    (merged, chosen.map(_._2))

  /** Pick from `items` with probability proportional to a non-negative `weight`; uniform when all weights are 0. */
  private[gepa] def weightedPick[A](items: Vector[A], weight: A => Double, rng: Random): A =
    val weights = items.map(a => math.max(0.0, weight(a)))
    val total   = weights.sum
    if total <= 0.0 then items(rng.nextInt(items.size))
    else
      val target = rng.nextDouble() * total
      var acc    = 0.0
      var idx    = 0
      while idx < items.size - 1 do
        acc += weights(idx)
        if target < acc then return items(idx)
        idx += 1
      items.last

  /** Choose validation indices to subsample the merged program on, stratified by where the two parents disagree
    * (parent-1-better, parent-2-better, ties) so the gate is discriminating, then top up randomly. gepa's
    * `select_eval_subsample_for_merged_program`. Both parents have full validation subscores, so every index is
    * common. */
  private[gepa] def selectSubsample(s1: Vector[Double], s2: Vector[Double], num: Int, rng: Random): Vector[Int] =
    val common = s1.indices.toVector
    if common.isEmpty then Vector.empty
    else
      val buckets  = Vector(common.filter(i => s1(i) > s2(i)), common.filter(i => s2(i) > s1(i)), common.filter(i => s1(i) == s2(i)))
      val nEach    = math.max(1, math.ceil(num / 3.0).toInt)
      val selected = mutable.ArrayBuffer.empty[Int]
      buckets.foreach { bucket =>
        if selected.size < num then
          val available = bucket.filterNot(selected.contains)
          val take      = math.min(math.min(available.size, nEach), num - selected.size)
          if take > 0 then selected ++= rng.shuffle(available).take(take)
      }
      val remaining = num - selected.size
      if remaining > 0 then
        val unused = common.filterNot(selected.contains)
        if unused.size >= remaining then selected ++= rng.shuffle(unused).take(remaining)
        else selected ++= (0 until remaining).map(_ => common(rng.nextInt(common.size))) // top up with replacement
      selected.take(num).toVector
