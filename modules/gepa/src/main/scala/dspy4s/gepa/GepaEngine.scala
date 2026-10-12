package dspy4s.gepa

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.optimization.OptimizableId

import java.nio.file.Path
import scala.util.Random

/** Configuration for a GEPA run. Covers the reflective-mutation loop, merge crossover, and the evaluation cache.
  * Objective-aware frontiers and more than one proposal in an iteration are not ported (PORT_GAPS G-45).
  */
final case class GepaConfig(
    /** Budget: total metric (evaluation) calls before stopping. Reflection-LM calls do NOT count, matching gepa. */
    maxMetricCalls         : MetricCallCount,
    reflectionMinibatchSize: MinibatchSize     = MinibatchSize(3),
    candidateSelector      : CandidateSelector = CandidateSelector.Pareto,
    componentSelector      : ComponentSelector = ComponentSelector.RoundRobin,
    /** Minibatch sampling strategy. Default `EpochShuffled` (gepa's default): walk a per-epoch shuffle so every train
      * example is used once per epoch before repeats. `RandomDraw` is GEPA v0's independent random draw.
      */
    batchSampler: BatchSamplerKind = BatchSamplerKind.EpochShuffled,
    /** Whether to interleave merge (crossover) proposals with reflective mutation. Default ON, matching dspy's GEPA
      * wrapper (`use_merge=True`); the standalone gepa engine defaults it off. A no-op for single-component programs
      * (which can't satisfy the merge triplet's "desirable predictor" requirement).
      */
    useMerge: Boolean = true,
    /** Cap on accepted merge attempts over a run (gepa's `max_merge_invocations`). */
    maxMergeInvocations: MergeInvocationLimit = MergeInvocationLimit(5),
    /** The smallest number of validation examples that the two candidates of a merge must share (gepa's
      * `merge_val_overlap_floor`). Each candidate has a score for each validation example, so a validation set with
      * fewer examples than this value gives no merge.
      */
    mergeValOverlapFloor: MergeOverlapFloor = MergeProposer.DefaultValOverlapFloor,
    skipPerfectScore    : Boolean           = true,
    perfectScore        : Double            = 1.0,
    failureScore        : Double            = 0.0,
    /** Whether a predictor call that failed gives the reflection LM a record: the raw response of the model and the
      * output structure to follow (dspy's `add_format_failure_as_feedback`). OFF by default, as upstream: a run that
      * failed then gives no reflective record. [[Gepa.compile]] passes this value to the [[GepaAdapter]].
      */
    addFormatFailureAsFeedback: Boolean = false,
    /** Opt-in efficiency stop: halt once the best candidate is perfect (mean validation score >= `perfectScore`), since
      * nothing further can improve it. OFF by default to match upstream gepa, which has no perfect-score stopper and
      * runs to the metric-call budget (its `perfect_score` only drives the per-minibatch `skipPerfectScore` skip). The
      * budget (`maxMetricCalls`) is always an upper bound regardless.
      */
    stopOnPerfectScore: Boolean = false,
    seed              : Long    = 0L
)

/** The outcome of a GEPA run: the best candidate (by mean validation score) and the program it yields. */
final case class GepaResult[P](
    bestCandidate   : Candidate,
    bestProgram     : P,
    bestScore       : Double,
    numCandidates   : GepaCandidateCount,
    totalMetricCalls: MetricCallCount
)

/** The GEPA engine: genetic-Pareto reflective prompt evolution. Each iteration selects a parent candidate from the
  * Pareto frontier, reflects on its failures over a train minibatch to propose a better instruction, accepts the
  * mutation iff it improves the minibatch, then full-evaluates the accepted candidate on the validation set and folds
  * it into the Pareto frontier — until no atomic step fits in the remaining metric-call budget. After an accepted
  * mutation it schedules a [[MergeProposer]] crossover, attempted first the next iteration. A [[GepaEvalCache]]
  * memoizes scores-only evals so repeated `(candidate, example)` pairs are free. A faithful port of the external `gepa`
  * engine. Atomic seed/iteration steps never cross `maxMetricCalls`; a run stops when the remaining budget cannot cover
  * the next step's worst-case cost. See PORT_GAPS G-12.
  *
  * The `proposer` writes the new instruction text of an iteration. The engine calls it in a context whose language
  * model is `reflectionLm`, so the engine itself makes no call to that model. See [[InstructionProposer]].
  *
  * @param adapter
  *   applies a candidate to the program, evaluates it, and builds the reflective records
  * @param reflectionLm
  *   the language model of the context of each proposer call
  * @param config
  *   the budget and the search settings
  * @param proposer
  *   writes the new instruction text. The default is [[InstructionProposer.default]].
  */
final class GepaEngine[P](
    adapter     : GepaAdapter[P],
    reflectionLm: LanguageModel,
    config      : GepaConfig,
    proposer    : InstructionProposer = InstructionProposer.default
):

  /** Run the search. A `Left` stops the search. It has one of these causes:
    *   - The adapter cannot find the trace entries of each leaf (UPGRADE-3.2.1-to-3.4.0, decision D2): two leaves have
    *     the same predictor key (found before any run), or a leaf ran but its module recorded no key (found when a
    *     reflective iteration reads the traces).
    *   - The proposer gives a text for a component that the candidate does not hold.
    */
  def optimize(
      seedCandidate: Candidate,
      trainset     : Vector[Example],
      valset       : Vector[Example],
      runDir       : Option[Path] = None
  )(using
      RuntimeContext
  ): Either[DspyError, GepaResult[P]] =
    adapter.distinctKeys.flatMap(_ => search(seedCandidate, trainset, valset, runDir))

  private def search(
      seedCandidate: Candidate,
      trainset     : Vector[Example],
      valset       : Vector[Example],
      runDir       : Option[Path]
  )(using
      RuntimeContext
  ): Either[DspyError, GepaResult[P]] =
    val rng     = new Random(config.seed)
    val sampler = MinibatchSampler.of(config.batchSampler, config.reflectionMinibatchSize, config.seed)
    val cache   = new GepaEvalCache(adapter)
    val merger  = Option.when(config.useMerge)(
      new MergeProposer(
        valset,
        config.maxMergeInvocations,
        rng,
        cache,
        valOverlapFloor = config.mergeValOverlapFloor
      )
    )

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
    var i           = 0 // iteration index, drives the epoch-shuffled batch sampler
    var canContinue = true
    // The error of an iteration that the adapter rejected. It stops the loop and becomes the result.
    var failure = Option.empty[DspyError]

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
      val mergeConsumedIteration =
        merger.filter(mp => mp.shouldAttempt && remaining >= mergeIterationCost).flatMap { mp =>
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
          iterate(state, trainset, valset, rng, sampler, cache, i) match
            case Right((nextState, accepted)) =>
              state = nextState
              if accepted then merger.foreach(_.onReflectiveAccepted())
            case Left(error) =>
              failure = Some(error)
              canContinue = false
      if canContinue then
        i += 1
        runDir.foreach(GepaStatePersistence.save(_, state)) // checkpoint after each iteration for resume

    failure.toLeft {
      val best = state.bestIndex
      GepaResult(
        bestCandidate = state.candidates(best),
        bestProgram = adapter.applyCandidate(state.candidates(best)),
        bestScore = state.aggregateScore(best),
        numCandidates = GepaCandidateCount.assume(state.candidates.size),
        totalMetricCalls = state.totalMetricCalls
      )
    }

  /** One reflective-mutation iteration: returns the new state (with the iteration's metric calls accrued, the
    * round-robin pointer of the parent moved, and the accepted candidate appended on success), and whether a new
    * candidate was accepted (which schedules a merge). A `Left` is an error from the reflective dataset of the adapter,
    * or a text of the proposer for a component that the parent does not hold.
    *
    * The proposer gets one call, with the parent, the selected components, and the records of each selected component
    * that has one record at least. After a `Left`, the proposer gets one more call.
    *
    * The iteration makes no proposal in each of these cases, and it then costs the minibatch evaluation only:
    *   - The selected components have no reflective record. The proposer then gets no call.
    *   - The proposer gives no text.
    *   - The proposer gives a `Left` two times.
    *
    * The component selector runs after the perfect-score check, as in gepa. An iteration that this check skips does not
    * move the pointer of the parent. Each other iteration moves it, also when the iteration makes no proposal.
    */
  private def iterate(
      state    : GepaState,
      trainset : Vector[Example],
      valset   : Vector[Example],
      rng      : Random,
      sampler  : MinibatchSampler,
      cache    : GepaEvalCache[P],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, (GepaState, Boolean)] =
    val parentIdx = config.candidateSelector.select(state, rng)
    val parent    = state.candidates(parentIdx)

    val minibatch  = sampler.sample(trainset.size, iteration).map(trainset)
    val parentEval = adapter.evaluate(minibatch, parent, captureTraces = true)
    var calls      = minibatch.size

    // An iteration that makes no proposal still pays for its minibatch evaluation (gepa counts the parent evaluation
    // before it decides to skip). The sampler gives one example at least, so each iteration decreases the budget and
    // the loop of `search` ends.
    def withoutProposal(from: GepaState): Either[DspyError, (GepaState, Boolean)] =
      Right((from.copy(totalMetricCalls = MetricCallCount.add(from.totalMetricCalls, calls)), false))

    // Nothing to learn from a perfect minibatch.
    if config.skipPerfectScore && parentEval.scores.nonEmpty && parentEval.scores.forall(_ >= config.perfectScore) then
      return withoutProposal(state)

    // Pick which component(s) to evolve and advance this candidate's round-robin pointer. gepa runs its selector after
    // its two skip checks. The other check (no trajectories) does not apply here, because an evaluation with traces
    // gives one trajectory for each example. A child that this iteration adds takes the moved pointer.
    val allComponents             = parent.keys.toVector.sorted
    val (components, nextPointer) = config.componentSelector.select(allComponents, state.pointerOf(parentIdx))
    val moved                     = state.withPointer(parentIdx, nextPointer)

    val reflective = adapter.makeReflectiveDataset(parent, parentEval, components) match
      case Right(records) => records
      case Left(error)    => return Left(error)
    // A component with no reflective record is not in the reflective dataset of the proposer (gepa leaves it out).
    // With no record at all, the proposer gets no call.
    val withRecords = reflective.filter((_, records) => records.nonEmpty)
    val newTexts    =
      if withRecords.isEmpty then Map.empty[OptimizableId, String]
      else proposeTexts(parent, withRecords, components).getOrElse(Map.empty)
    // No new text: the candidate would be equal to its parent, so there is no proposal and no acceptance evaluation
    // (gepa: "Reflection returned no text updates; skipping proposal").
    if newTexts.isEmpty then return withoutProposal(moved)
    // gepa: `assert name in new_candidate`.
    val unknown = newTexts.keySet.diff(parent.keySet).toVector.sorted
    if unknown.nonEmpty then
      return Left(
        ValidationError(
          "The instruction proposer gave a text for a component that the candidate does not hold: " +
            unknown.map(_.render).mkString(", ")
        )
      )
    val newCandidate = newTexts.foldLeft(parent) { case (cand, (component, text)) =>
      cand.updated(component, Some(text))
    }

    // Re-evaluate on the SAME minibatch; accept only on strict minibatch improvement.
    val newEval = adapter.evaluate(minibatch, newCandidate, captureTraces = false)
    calls += minibatch.size

    val accepted  = newEval.scores.sum > parentEval.scores.sum
    val nextState =
      if accepted then
        val (newSubscores, evals) = fullEval(newCandidate, valset, cache)
        calls += evals
        moved.add(newCandidate, newSubscores, parents = Vector(parentIdx), metricCalls = calls)
      else
        moved.copy(totalMetricCalls = MetricCallCount.add(moved.totalMetricCalls, calls))
    Right((nextState, accepted))

  /** The new texts of the proposer for one iteration. `None` when the proposal failed two times: after a `Left`, the
    * engine calls the proposer one more time (gepa's `_propose_texts_batch_safe`). A `Right` gets no second call, also
    * when it holds no text.
    */
  private def proposeTexts(
      parent    : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId]
  )(using RuntimeContext): Option[Map[OptimizableId, String]] =
    def attempt = proposeInReflectionContext(parent, reflective, components)
    attempt.orElse(attempt).toOption

  /** Call the proposer one time, in a context whose language model is the reflection LM (dspy's
    * `dspy.context(lm=reflection_lm)`). The context that the proposer gets and [[RuntimeEnvironment.current]] are the
    * same in the call.
    *
    * The call has a trace and a history of its own. The trace entries do not reach the caller. The history entries go
    * to the context of the caller, also for a call that gives a `Left`.
    */
  private def proposeInReflectionContext(
      parent    : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId]
  )(using ctx: RuntimeContext): Either[DspyError, Map[OptimizableId, String]] =
    val reflection = ctx.withServices(ctx.services.copy(lm = Some(reflectionLm)))
    val executed   = RuntimeEnvironment.isolatedAttempt(reflection)(proposer.propose(parent, reflective, components))
    RuntimeEnvironment.propagate(executed.delta.copy(trace = Vector.empty))
    executed.value

  /** Full validation scores for a candidate, via the shared eval cache: returns the per-instance scores and the number
    * of ACTUAL (uncached) evaluations to charge against the budget.
    */
  private def fullEval(candidate: Candidate, valset: Vector[Example], cache: GepaEvalCache[P])(using
      RuntimeContext
  ): (Vector[Double], Int) =
    cache.scores(candidate, valset)

  /** Validate a checkpoint against the current program/data topology and restore its already-accounted validation
    * scores into the evaluation cache.
    */
  private def validateAndWarm(
      state        : GepaState,
      seedCandidate: Candidate,
      valset       : Vector[Example],
      cache        : GepaEvalCache[P]
  ): Unit =
    val expectedIds = seedCandidate.keySet
    val mismatched  = state.candidates.zipWithIndex.collect {
      case (candidate, index) if candidate.keySet != expectedIds => index -> candidate.keySet
    }
    if mismatched.nonEmpty then
      throw IllegalStateException(
        s"GEPA checkpoint optimizable ids do not match the current program at candidates ${mismatched.map(_._1).mkString(", ")}"
      )
    val wrongRows =
      state.valSubscores.zipWithIndex.collect { case (scores, index) if scores.size != valset.size => index }
    if wrongRows.nonEmpty then
      throw IllegalStateException(
        s"GEPA checkpoint validation rows do not match the current valset size ${valset.size}: ${wrongRows.mkString(", ")}"
      )
    state.candidates.zip(state.valSubscores).foreach { case (candidate, scores) =>
      cache.restore(candidate, valset, scores).fold(error => throw IllegalStateException(error), identity)
    }
