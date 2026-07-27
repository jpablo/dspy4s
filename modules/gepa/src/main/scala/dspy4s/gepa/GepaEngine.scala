package dspy4s.gepa

import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel

import java.nio.file.Path
import scala.util.Random

/** Configuration for a GEPA run. Covers the reflective-mutation loop, merge crossover, and the evaluation cache;
  * multi-objective frontiers and run-dir resume are deferred (PORT_GAPS G-12). */
final case class GepaConfig(
    /** Budget: total metric (evaluation) calls before stopping. Reflection-LM calls do NOT count, matching gepa. */
    maxMetricCalls: MetricCallCount,
    reflectionMinibatchSize: MinibatchSize = MinibatchSize(3),
    candidateSelector: CandidateSelector = CandidateSelector.Pareto,
    componentSelector: ComponentSelector = ComponentSelector.RoundRobin,
    /** Minibatch sampling strategy. Default `EpochShuffled` (gepa's default): walk a per-epoch shuffle so every
      * train example is used once per epoch before repeats. `RandomDraw` is GEPA v0's independent random draw. */
    batchSampler: BatchSamplerKind = BatchSamplerKind.EpochShuffled,
    /** Whether to interleave merge (crossover) proposals with reflective mutation. Default ON, matching dspy's GEPA
      * wrapper (`use_merge=True`); the standalone gepa engine defaults it off. A no-op for single-component
      * programs (which can't satisfy the merge triplet's "desirable predictor" requirement). */
    useMerge: Boolean = true,
    /** Cap on accepted merge attempts over a run (gepa's `max_merge_invocations`). */
    maxMergeInvocations: MergeInvocationLimit = MergeInvocationLimit(5),
    skipPerfectScore: Boolean = true,
    perfectScore: Double = 1.0,
    failureScore: Double = 0.0,
    /** Opt-in efficiency stop: halt once the best candidate is perfect (mean validation score >= `perfectScore`),
      * since nothing further can improve it. OFF by default to match upstream gepa, which has no perfect-score
      * stopper and runs to the metric-call budget (its `perfect_score` only drives the per-minibatch
      * `skipPerfectScore` skip). The budget (`maxMetricCalls`) is always an upper bound regardless. */
    stopOnPerfectScore: Boolean = false,
    seed: Long = 0L
)

/** The outcome of a GEPA run: the best candidate (by mean validation score) and the program it yields. */
final case class GepaResult[P](
    bestCandidate: Candidate,
    bestProgram: P,
    bestScore: Double,
    numCandidates: GepaCandidateCount,
    totalMetricCalls: MetricCallCount
)

/** The GEPA engine: genetic-Pareto reflective prompt evolution. Each iteration selects a parent candidate from the
  * Pareto frontier, reflects on its failures over a train minibatch to propose a better instruction, accepts the
  * mutation iff it improves the minibatch, then full-evaluates the accepted candidate on the validation set and
  * folds it into the Pareto frontier — until no atomic step fits in the remaining metric-call budget. After an
  * accepted mutation it schedules a [[MergeProposer]] crossover, attempted first the next iteration. A
  * [[GepaEvalCache]] memoizes
  * scores-only evals so repeated `(candidate, example)` pairs are free. A faithful port of the external `gepa`
  * engine. Atomic seed/iteration steps never cross `maxMetricCalls`; a run stops when the remaining budget cannot
  * cover the next step's worst-case cost. See PORT_GAPS G-12. */
final class GepaEngine[P](
    adapter: GepaAdapter[P],
    reflectionLm: LanguageModel,
    config: GepaConfig
):

  def optimize(seedCandidate: Candidate, trainset: Vector[Example], valset: Vector[Example], runDir: Option[Path] = None)(using
      RuntimeContext
  ): GepaResult[P] =
    val rng     = new Random(config.seed)
    val sampler = MinibatchSampler.of(config.batchSampler, config.reflectionMinibatchSize, config.seed)
    val cache   = new GepaEvalCache(adapter)
    val merger  = Option.when(config.useMerge)(new MergeProposer(valset, config.maxMergeInvocations, rng, cache))

    // Resume from a saved run dir if present; otherwise seed by full-evaluating the starting candidate on the valset.
    // Seed evaluation is atomic, so reject a budget that cannot hold it instead of violating the advertised bound.
    val restored = runDir.flatMap { dir =>
      GepaStatePersistence.load(dir) match
        case Right(state) => state
        case Left(error)  => throw IllegalStateException(s"Invalid GEPA checkpoint in '$dir': $error")
    }
    var state = restored match
      case Some(saved) =>
        validateAndWarm(saved, seedCandidate, valset, cache)
        saved
      case None =>
        val seedCost = cache.uncachedCount(seedCandidate, valset)
        require(
          seedCost <= config.maxMetricCalls,
          s"maxMetricCalls=${config.maxMetricCalls} cannot cover the seed validation cost of $seedCost"
        )
        val (seedScores, seedEvals) = fullEval(seedCandidate, valset, cache)
        GepaState.seed(seedCandidate, seedScores, metricCalls = seedEvals)
    runDir.foreach(GepaStatePersistence.save(_, state))
    // Per-candidate round-robin pointer (which component to evolve next), threaded across iterations.
    var pointers = Map.empty[Int, Int]
    var i        = 0 // iteration index, drives the epoch-shuffled batch sampler
    var canContinue = true

    // A reflective iteration evaluates the parent and proposal on one minibatch, then may validate every distinct
    // example for a newly accepted candidate. Reserving this atomic worst case keeps maxMetricCalls a hard bound.
    val reflectiveIterationCost = 2L * config.reflectionMinibatchSize + valset.distinct.size
    // A merge's subsample is drawn from valset and shares the cache with its possible full eval, so at most one call
    // per distinct validation example is needed for the merged candidate.
    val mergeIterationCost = valset.distinct.size.toLong

    // Opt-in: stop once the best candidate is already perfect on validation — further iterations can only re-discover
    // it (and the budget would be spent on `skipPerfectScore` minibatches). Off by default for gepa parity.
    def converged(s: GepaState): Boolean =
      config.stopOnPerfectScore && s.aggregateScore(s.bestIndex) >= config.perfectScore

    while canContinue && state.totalMetricCalls < config.maxMetricCalls && !converged(state) do
      val remaining = config.maxMetricCalls.toLong - state.totalMetricCalls
      // 1) Merge first if one is scheduled and the last iteration produced a new program (gepa's ordering). A merge
      //    proposal (accepted or rejected) consumes the iteration; only a "no triplet found" falls through.
      val mergeConsumedIteration = merger.filter(mp => mp.shouldAttempt && remaining >= mergeIterationCost).flatMap { mp =>
        val proposalOpt = mp.propose(state)
        mp.lastIterFoundNewProgram = false
        proposalOpt.map { proposal =>
          if proposal.accepted then
            val (subscores, evals) = fullEval(proposal.candidate, valset, cache)
            state = state.add(proposal.candidate, subscores, proposal.parents, proposal.metricCalls + evals)
            mp.onMergeAccepted()
          else
            state = state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, proposal.metricCalls))
          true
        }
      }.getOrElse(false)

      // 2) Otherwise, a reflective-mutation iteration; an acceptance schedules a future merge.
      if !mergeConsumedIteration then
        if remaining < reflectiveIterationCost then canContinue = false
        else
          merger.foreach(_.lastIterFoundNewProgram = false)
          val (nextState, nextPointers, accepted) = iterate(state, pointers, trainset, valset, rng, sampler, cache, i)
          state = nextState
          pointers = nextPointers
          if accepted then merger.foreach(_.onReflectiveAccepted())
      if canContinue then
        i += 1
        runDir.foreach(GepaStatePersistence.save(_, state)) // checkpoint after each iteration for resume

    val best = state.bestIndex
    GepaResult(
      bestCandidate = state.candidates(best),
      bestProgram = adapter.applyCandidate(state.candidates(best)),
      bestScore = state.aggregateScore(best),
      numCandidates = GepaCandidateCount.assume(state.candidates.size),
      totalMetricCalls = state.totalMetricCalls
    )

  /** One reflective-mutation iteration: returns the new state (with the iteration's metric calls accrued, and the
    * accepted candidate appended on success), the updated round-robin pointers, and whether a new candidate was
    * accepted (which schedules a merge). */
  private def iterate(
      state: GepaState,
      pointers: Map[Int, Int],
      trainset: Vector[Example],
      valset: Vector[Example],
      rng: Random,
      sampler: MinibatchSampler,
      cache: GepaEvalCache[P],
      iteration: Int
  )(using RuntimeContext): (GepaState, Map[Int, Int], Boolean) =
    val parentIdx = config.candidateSelector.select(state, rng)
    val parent    = state.candidates(parentIdx)

    // Pick which component(s) to evolve and advance this candidate's round-robin pointer.
    val allComponents = parent.keys.toVector.sorted
    val (components, nextPointer) = config.componentSelector.select(allComponents, pointers.getOrElse(parentIdx, 0))
    val newPointers = pointers.updated(parentIdx, nextPointer)

    val minibatch  = sampler.sample(trainset.size, iteration).map(trainset)
    val parentEval = adapter.evaluate(minibatch, parent, captureTraces = true)
    var calls      = minibatch.size

    // Nothing to learn from a perfect minibatch.
    if config.skipPerfectScore && parentEval.scores.nonEmpty && parentEval.scores.forall(_ >= config.perfectScore) then
      return (state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, calls)), newPointers, false)

    val reflective   = adapter.makeReflectiveDataset(parent, parentEval, components)
    val newCandidate = components.foldLeft(parent) { (cand, component) =>
      InstructionProposer.propose(
        parent.getOrElse(component, None).getOrElse(""),
        reflective.getOrElse(component, Vector.empty),
        reflectionLm
      ) match
        case Right(text) => cand.updated(component, Some(text))
        case Left(_)     => cand // reflection failed for this component — keep its instruction
    }

    // Re-evaluate on the SAME minibatch; accept only on strict minibatch improvement.
    val newEval = adapter.evaluate(minibatch, newCandidate, captureTraces = false)
    calls += minibatch.size

    val accepted = newEval.scores.sum > parentEval.scores.sum
    val nextState =
      if accepted then
        val (newSubscores, evals) = fullEval(newCandidate, valset, cache)
        calls += evals
        state.add(newCandidate, newSubscores, parents = Vector(parentIdx), metricCalls = calls)
      else
        state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, calls))
    (nextState, newPointers, accepted)

  /** Full validation scores for a candidate, via the shared eval cache: returns the per-instance scores and the
    * number of ACTUAL (uncached) evaluations to charge against the budget. */
  private def fullEval(candidate: Candidate, valset: Vector[Example], cache: GepaEvalCache[P])(using
      RuntimeContext
  ): (Vector[Double], Int) =
    cache.scores(candidate, valset)

  /** Validate a checkpoint against the current program/data topology and restore its already-accounted validation
    * scores into the evaluation cache. */
  private def validateAndWarm(
      state: GepaState,
      seedCandidate: Candidate,
      valset: Vector[Example],
      cache: GepaEvalCache[P]
  ): Unit =
    val expectedIds = seedCandidate.keySet
    val mismatched = state.candidates.zipWithIndex.collect {
      case (candidate, index) if candidate.keySet != expectedIds => index -> candidate.keySet
    }
    if mismatched.nonEmpty then
      throw IllegalStateException(
        s"GEPA checkpoint optimizable ids do not match the current program at candidates ${mismatched.map(_._1).mkString(", ")}"
      )
    val wrongRows = state.valSubscores.zipWithIndex.collect { case (scores, index) if scores.size != valset.size => index }
    if wrongRows.nonEmpty then
      throw IllegalStateException(
        s"GEPA checkpoint validation rows do not match the current valset size ${valset.size}: ${wrongRows.mkString(", ")}"
      )
    state.candidates.zip(state.valSubscores).foreach { case (candidate, scores) =>
      cache.restore(candidate, valset, scores).fold(error => throw IllegalStateException(error), identity)
    }
