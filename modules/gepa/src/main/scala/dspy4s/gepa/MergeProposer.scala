package dspy4s.gepa

import dspy4s.core.data.Example
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext

import scala.util.Random

/** A proposed merge: the crossover candidate, its two parents, whether it cleared the subsample gate (its merged
  * subsample score is at least the better parent's), and the metric calls the subsample eval consumed. `triplet` and
  * `description` go to the schedule ([[MergeSchedule.afterMerge]]), so the run does not try the same merge again. The
  * engine decides what to do (full-eval + add on accept).
  */
final case class MergeProposal(
    candidate  : Candidate,
    parents    : Vector[Int],
    accepted   : Boolean,
    metricCalls: Int,
    triplet    : (Int, Int, Int),
    description: (Int, Int, Vector[Int])
)

/** The merge schedule of a run (the state of gepa's `MergeProposer`). [[GepaState.merge]] holds it, so a saved state
  * keeps it and a resumed run continues it. Upstream does not save it.
  *
  * The engine schedules merges after each reflective iteration ([[afterReflective]]) and attempts one when
  * [[shouldAttempt]]. [[afterMerge]] consumes an accepted merge. The performed triplets and descriptions stop a retry
  * of the same merge.
  *
  * @param mergesDue
  *   the number of merges that are scheduled and not done
  * @param totalMergesTested
  *   the number of accepted merges of the run
  * @param lastIterFoundNewProgram
  *   `true` when the last iteration added a candidate
  * @param performedTriplets
  *   the `(i, j, ancestor)` triplets that the run tried
  * @param performedDescriptions
  *   the descriptions of the merged candidates that the run tried
  */
final case class MergeSchedule(
    mergesDue              : Int                          = 0,
    totalMergesTested      : Int                          = 0,
    lastIterFoundNewProgram: Boolean                      = false,
    performedTriplets      : Set[(Int, Int, Int)]         = Set.empty,
    performedDescriptions  : Set[(Int, Int, Vector[Int])] = Set.empty
) derives CanEqual:

  /** A merge is worth attempting this iteration iff one is scheduled AND the last iteration produced a new program (so
    * the frontier just changed).
    */
  def shouldAttempt: Boolean = mergesDue > 0 && lastIterFoundNewProgram

  /** The schedule after a reflective iteration that added `added` candidates. Each added candidate schedules a merge,
    * while the number of accepted merges is below `limit`.
    */
  def afterReflective(added: Int, limit: MergeInvocationLimit): MergeSchedule =
    copy(
      mergesDue = if totalMergesTested < limit then mergesDue + added else mergesDue,
      lastIterFoundNewProgram = added > 0
    )

  /** The schedule after the engine examined `proposal`. An accepted merge consumes one scheduled merge and counts. Then
    * [[shouldAttempt]] is `false` until a reflective iteration adds a candidate (gepa sets
    * `last_iter_found_new_program = False`).
    */
  def afterMerge(proposal: MergeProposal): MergeSchedule =
    val accepted = if proposal.accepted then 1 else 0
    copy(
      mergesDue = mergesDue - accepted,
      totalMergesTested = totalMergesTested + accepted,
      lastIterFoundNewProgram = false,
      performedTriplets = performedTriplets + proposal.triplet,
      performedDescriptions = performedDescriptions + proposal.description
    )

object MergeSchedule:
  /** The schedule at the start of a run. */
  val initial: MergeSchedule = MergeSchedule()

/** GEPA's merge proposer — the crossover ("genetic") half of Genetic-Pareto. It combines two Pareto-frontier
  * descendants of a common ancestor: for each component, it takes the version from whichever descendant IMPROVED it
  * relative to the ancestor, yielding a child that stacks the two lineages' complementary gains. A faithful port of
  * gepa's `MergeProposer` / `sample_and_attempt_merge_programs_by_common_predictors`.
  *
  * Merge only fires for multi-component programs with branching lineage: the triplet must have a desirable component (a
  * component exactly one descendant changed), which a single-component program can never satisfy, so merge is a safe
  * no-op there.
  *
  * The functions have no state of their own. [[propose]] reads the schedule of the state ([[GepaState.merge]]), and the
  * engine writes the next schedule to the state ([[MergeSchedule]]).
  */
object MergeProposer:

  /** gepa's default for `val_overlap_floor`. */
  val DefaultValOverlapFloor: MergeOverlapFloor = MergeOverlapFloor(5)

  /** gepa's `max_attempts`: the number of attempts of [[findMerge]], and of pair draws of each attempt. */
  private val MaxAttempts = 10

  /** gepa's `num_subsample_ids`: the number of validation examples of the subsample gate. */
  private val SubsampleSize = 5

  /** Attempt a merge over the current state's Pareto dominators (gepa's `find_dominator_programs`: the candidates that
    * stay on [[GepaState.nonDominatedFrontier]]). Returns the subsample-gated proposal, or `None` when no mergeable
    * triplet is found (the engine then falls through to reflective mutation).
    *
    * @param valOverlapFloor
    *   the smallest number of validation examples that the two candidates of a merge must share (gepa's
    *   `val_overlap_floor`). A pair with fewer shared examples gives no merge.
    * @param rng
    *   the random source of the iteration. The engine continues with it when the result is `None`.
    */
  def propose[P](
      state          : GepaState,
      valset         : Vector[Example],
      cache          : GepaEvalCache[P],
      valOverlapFloor: MergeOverlapFloor,
      rng            : Random
  )(using RuntimeContext): Option[MergeProposal] =
    val dominators = state.nonDominatedFrontier.flatten.toSet.toVector.sorted
    if dominators.size < 2 || state.candidates.size < 3 then None
    else
      findMerge(state, dominators, valset.size, valOverlapFloor, rng).map {
        case (triplet @ (id1, id2, _), merged, sources) =>
          val subIdx  = selectSubsample(state.valSubscores(id1), state.valSubscores(id2), SubsampleSize, rng)
          val before1 = PythonText.sum(subIdx.map(state.valSubscores(id1)))
          val before2 = PythonText.sum(subIdx.map(state.valSubscores(id2)))
          val result  = cache.scored(merged, subIdx.map(valset)) // cached so the later full-eval reuses these
          MergeProposal(
            merged,
            Vector(id1, id2),
            accepted = PythonText.sum(result.scores) >= math.max(before1, before2),
            metricCalls = result.evaluated,
            triplet,
            (id1, id2, sources)
          )
      }

  /** Up to [[MaxAttempts]] attempts (gepa's `sample_and_attempt_merge_programs_by_common_predictors`). Each attempt
    * looks for a triplet with [[findCommonAncestorPair]] and makes its crossover. The search continues with the next
    * attempt in these cases:
    *   - The attempt finds no triplet.
    *   - The schedule of the state already holds this exact merge.
    *   - The validation set has fewer than `valOverlapFloor` examples. Each candidate of a [[GepaState]] has a score
    *     for each validation example, so the two candidates share all of them (gepa's `has_val_support_overlap`).
    *
    * Returns the triplet, the merged candidate, and the source of each of its components. `dominators` holds two
    * candidates at least.
    */
  private[gepa] def findMerge(
      state          : GepaState,
      dominators     : Vector[Int],
      valsetSize     : Int,
      valOverlapFloor: MergeOverlapFloor,
      rng            : Random
  ): Option[((Int, Int, Int), Candidate, Vector[Int])] =
    Iterator.range(0, MaxAttempts).flatMap { _ =>
      findCommonAncestorPair(state, dominators, rng).flatMap { case triplet @ (id1, id2, ancestor) =>
        val (merged, sources) = crossover(
          state.candidates(ancestor),
          id1,
          state.candidates(id1),
          id2,
          state.candidates(id2),
          state.aggregateScore,
          rng
        )
        val isNew = !state.merge.performedDescriptions.contains((id1, id2, sources))
        Option.when(isNew && valsetSize >= valOverlapFloor)((triplet, merged, sources))
      }
    }.nextOption()

  /** Up to [[MaxAttempts]] times (gepa's `find_common_ancestor_pair`): sample two distinct dominators (neither an
    * ancestor of the other) and look for a common ancestor that is (a) not already merged for this pair, (b)
    * outperformed by both descendants, and (c) "desirable" (some component exactly one descendant changed). Picks among
    * valid ancestors weighted by score, in sorted order: the order of a hash set is a detail of the collection, and a
    * seeded draw must not depend on it.
    */
  private def findCommonAncestorPair(state: GepaState, dominators: Vector[Int], rng: Random): Option[(Int, Int, Int)] =
    Iterator.range(0, MaxAttempts).flatMap { _ =>
      val pair = rng.shuffle(dominators).take(2)
      val i    = math.min(pair(0), pair(1))
      val j    = math.max(pair(0), pair(1))
      val ancI = state.ancestors(i)
      val ancJ = state.ancestors(j)
      if ancI.contains(j) || ancJ.contains(i) then None
      else
        val common = (ancI intersect ancJ).filter { a =>
          !state.merge.performedTriplets.contains((i, j, a)) &&
          state.aggregateScore(a) <= state.aggregateScore(i) &&
          state.aggregateScore(a) <= state.aggregateScore(j) &&
          hasDesirableComponents(state.candidates(a), state.candidates(i), state.candidates(j))
        }.toVector.sorted
        Option.when(common.nonEmpty)((i, j, weightedPick(common, a => state.aggregateScore(a), rng)))
    }.nextOption()

  /** A triplet is mergeable iff some component is unchanged in exactly one descendant (so the OTHER descendant's change
    * is the one to carry forward). Corresponds to gepa's `does_triplet_have_desirable_predictors`.
    */
  private[gepa] def hasDesirableComponents(ancestor: Candidate, cand1: Candidate, cand2: Candidate): Boolean =
    ancestor.keys.exists { name =>
      val (anc, v1, v2) = (ancestor(name), cand1(name), cand2(name))
      (anc == v1 || anc == v2) && v1 != v2
    }

  /** Per-component crossover over a common ancestor (gepa's inner merge loop). For each component: if exactly one
    * descendant changed it, take the changed one; if both changed it, take the higher-scoring descendant's (ties broken
    * randomly); if both agree, take either. Returns the merged candidate and the per-component source candidate index
    * (the "description" used to dedup identical merges).
    */
  private[gepa] def crossover(
      ancestor      : Candidate,
      id1           : Int,
      cand1         : Candidate,
      id2           : Int,
      cand2         : Candidate,
      aggregateScore: Int => Double,
      rng           : Random
  ): (Candidate, Vector[Int]) =
    val components = ancestor.keys.toVector.sorted
    val chosen     = components.map { component =>
      val (anc, v1, v2) = (ancestor(component), cand1(component), cand2(component))
      val src           =
        if (anc == v1 || anc == v2) && v1 != v2 then if anc == v1 then id2 else id1
        else if anc != v1 && anc != v2 then
          if aggregateScore(id1) > aggregateScore(id2) then id1
          else if aggregateScore(id2) > aggregateScore(id1) then id2
          else rng.shuffle(Vector(id1, id2)).head
        else id1 // v1 == v2
      component -> src
    }
    val merged = chosen.map { case (component, src) =>
      component -> (if src == id1 then cand1(component) else cand2(component))
    }.toMap
    (merged, chosen.map(_._2))

  /** Pick from `items` with probability proportional to a non-negative `weight`; uniform when all weights are 0. The
    * total is a plain sum, as in gepa: `random.choices` adds the weights with `itertools.accumulate`, not with `sum`.
    */
  private[gepa] def weightedPick[A](items: Vector[A], weight: A => Double, rng: Random): A =
    val weights = items.map(a => math.max(0.0, weight(a)))
    val total   = weights.sum
    if total <= 0.0 then items(rng.nextInt(items.size))
    else
      val target = rng.nextDouble() * total
      items.iterator
        .zip(weights.iterator.scanLeft(0.0)(_ + _).drop(1))
        .collectFirst { case (item, reached) if target < reached => item }
        .getOrElse(items.last)

  /** Choose validation indices to subsample the merged program on, stratified by where the two parents disagree
    * (parent-1-better, parent-2-better, ties) so the gate is discriminating, then top up randomly. gepa's
    * `select_eval_subsample_for_merged_program`. Both parents have full validation subscores, so every index is common.
    * `s1` and `s2` hold one score at least, and `num` is positive.
    */
  private[gepa] def selectSubsample(s1: Vector[Double], s2: Vector[Double], num: Int, rng: Random): Vector[Int] =
    val common  = s1.indices.toVector
    val buckets =
      Vector(common.filter(i => s1(i) > s2(i)), common.filter(i => s2(i) > s1(i)), common.filter(i => s1(i) == s2(i)))
    val nEach      = math.ceil(num / 3.0).toInt
    val stratified = buckets.foldLeft(Vector.empty[Int]) { (selected, bucket) =>
      val take = math.min(math.min(bucket.size, nEach), num - selected.size)
      if take > 0 then selected ++ rng.shuffle(bucket).take(take) else selected
    }
    val remaining = num - stratified.size
    val unused    = common.filterNot(stratified.contains)
    if remaining == 0 then stratified
    else if unused.size >= remaining then stratified ++ rng.shuffle(unused).take(remaining)
    else stratified ++ Vector.fill(remaining)(common(rng.nextInt(common.size))) // top up with replacement
