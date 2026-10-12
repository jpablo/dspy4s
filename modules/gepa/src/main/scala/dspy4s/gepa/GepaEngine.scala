package dspy4s.gepa

import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.optimization.OptimizableId

import java.nio.file.Path
import scala.util.Random

/** Configuration for a GEPA run. Covers the reflective-mutation loop, merge crossover, the evaluation cache, the
  * objective-aware Pareto fronts ([[frontierType]]), and the proposals of one iteration ([[proposalSampling]],
  * [[proposalSelection]], [[acceptance]]; PORT_GAPS G-45).
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
    /** The Pareto fronts that candidate selection and merge use (gepa's `frontier_type`). The default is `Instance`, as
      * upstream: one front for each validation example. Each other type needs objective scores from the metric
      * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]): a run whose seed candidate has none returns a
      * `Left` after the seed evaluation. See [[FrontierType]].
      */
    frontierType: FrontierType = FrontierType.Instance,
    /** The tasks of one reflective iteration (gepa's `sampling_strategy`). The default is one parent and one minibatch,
      * as upstream. With more than one task, the engine evaluates the parents, the children, and the selected
      * candidates in batches ([[GepaAdapter.batchEvaluate]]).
      *
      * An iteration starts only when its largest possible cost fits in the budget that remains: the minibatch
      * evaluations of each parent and each child, and one validation for each proposal that [[proposalSelection]] can
      * select.
      */
    proposalSampling: ProposalSampling = ProposalSampling.Single,
    /** Which evaluated proposals of an iteration go to the validation set and to the pool (gepa's
      * `selection_strategy`).
      */
    proposalSelection: ProposalSelection = ProposalSelection.AllImprovements,
    /** When an evaluated proposal passes (gepa's `acceptance_criterion`). */
    acceptance: ProposalAcceptance = ProposalAcceptance.StrictImprovement,
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

/** The outcome of a GEPA run: the best candidate (by mean validation score) and the program it yields. `state` is the
  * final search state: each candidate, its scores and objective scores for each validation example, its parents, and
  * the Pareto fronts.
  */
final case class GepaResult[P](
    bestCandidate   : Candidate,
    bestProgram     : P,
    bestScore       : Double,
    numCandidates   : GepaCandidateCount,
    totalMetricCalls: MetricCallCount,
    state           : GepaState
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
  * The text above describes the default iteration, as upstream: one (parent, minibatch) task, and strict acceptance. An
  * iteration can have more than one task ([[GepaConfig.proposalSampling]], PORT_GAPS G-45), and each task can give one
  * proposal. The engine evaluates the parents of the tasks in one batch, and then the children in one batch
  * ([[GepaAdapter.batchEvaluate]]). [[GepaConfig.proposalSelection]] selects the proposals that pass
  * [[GepaConfig.acceptance]]. The engine evaluates the selected candidates on the validation set in one batch, and adds
  * them to the pool in order. Each added candidate schedules one merge.
  *
  * The state keeps the objective scores that the metric gives for each candidate on each validation example
  * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]). [[GepaConfig.frontierType]] selects the Pareto fronts
  * that candidate selection and merge use. The default type has one front for each validation example. Each other type
  * uses the objective scores ([[FrontierType]]). The acceptance of a candidate and the best candidate of a run use the
  * score only.
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
  import GepaEngine.*

  /** Run the search. A `Left` stops the search. It has one of these causes:
    *   - The adapter cannot find the trace entries of each leaf (UPGRADE-3.2.1-to-3.4.0, decision D2): two leaves have
    *     the same predictor key (found before any run), or a leaf ran but its module recorded no key (found when a
    *     reflective iteration reads the traces).
    *   - The proposer gives a text for a component that the candidate does not hold.
    *   - [[GepaConfig.frontierType]] is not `Instance`, and the seed candidate has no objective score. The search stops
    *     after the seed evaluation, before any iteration.
    *   - The state in `runDir` has a frontier type that is not [[GepaConfig.frontierType]]. The search stops before any
    *     LM call.
    *
    * Each other defect of the state in `runDir` is an `IllegalStateException`.
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
    val initial: Either[DspyError, GepaState] = restored match
      case Some(saved) if saved.frontierType != config.frontierType =>
        Left(
          ConfigurationError(
            s"The GEPA checkpoint${runDir.fold("")(dir => s" in '$dir'")} has the frontier type " +
              s"${saved.frontierType}, and the configuration has the frontier type ${config.frontierType}. " +
              "Use the frontier type of the checkpoint, or a new run directory."
          )
        )
      case Some(saved) =>
        validateAndWarm(saved, seedCandidate, valset, cache)
        Right(saved)
      case None =>
        val seedCost = cache.uncachedCount(seedCandidate, valset)
        require(
          seedCost <= config.maxMetricCalls,
          s"maxMetricCalls=${config.maxMetricCalls} cannot cover the seed validation cost of $seedCost"
        )
        val seed = fullEval(seedCandidate, valset, cache)
        Right(
          GepaState.seed(
            seedCandidate,
            seed.scores,
            metricCalls = seed.evaluated,
            objectiveScores = seed.objectiveScores,
            frontierType = config.frontierType
          )
        )
    var state = initial.flatMap(requireObjectiveScores) match
      case Right(ready) => ready
      case Left(error)  => return Left(error)
    runDir.foreach(GepaStatePersistence.save(_, state))
    var i           = 0 // iteration index, drives the epoch-shuffled batch sampler
    var canContinue = true
    // The error of an iteration that the adapter rejected. It stops the loop and becomes the result.
    var failure = Option.empty[DspyError]

    // A reflective iteration evaluates the parent and the proposal of each task on one minibatch, then may validate
    // every distinct example for each candidate that the selection can give. Reserving this atomic worst case keeps
    // maxMetricCalls a hard bound. With one task, this is two minibatches and one validation.
    val taskCount               = config.proposalSampling.taskCount
    val reflectiveIterationCost = 2L * taskCount * config.reflectionMinibatchSize +
      config.proposalSelection.limit(taskCount).toLong * valset.distinct.size
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
              val validation = fullEval(proposal.candidate, valset, cache)
              state = state.add(
                proposal.candidate,
                validation.scores,
                proposal.parents,
                proposal.metricCalls + validation.evaluated,
                validation.objectiveScores
              )
              mp.onMergeAccepted()
            else
              state = state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, proposal.metricCalls))
            true
          }
        }.getOrElse(false)

      // 2) Otherwise, a reflective-mutation iteration; each candidate that it adds schedules a future merge.
      if !mergeConsumedIteration then
        if remaining < reflectiveIterationCost then canContinue = false
        else
          merger.foreach(_.lastIterFoundNewProgram = false)
          iterate(state, trainset, valset, rng, sampler, cache, i) match
            case Right((nextState, added)) =>
              state = nextState
              merger.foreach(mp => (0 until added).foreach(_ => mp.onReflectiveAccepted()))
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
        totalMetricCalls = state.totalMetricCalls,
        state = state
      )
    }

  /** One reflective-mutation iteration: returns the new state (with the iteration's metric calls accrued, the
    * round-robin pointers of the parents moved, and the selected candidates appended), and the number of candidates
    * that the iteration added (each one schedules a merge). A `Left` is an error from the reflective dataset of the
    * adapter, or a text of the proposer for a component that the parent does not hold.
    *
    * The iteration has these stages (gepa's `ReflectiveMutationProposer.propose` and `_run_reflective_batch`). Each
    * stage examines the tasks in task order:
    *   1. [[sampleTasks]] draws the parent and the minibatch of each task ([[GepaConfig.proposalSampling]]).
    *   1. [[evaluateParents]] evaluates the parents with traces, in one batch.
    *   1. [[prepareReflections]] makes the perfect-score check, selects the components, moves the pointer of the
    *      parent, and builds the reflective dataset of each task.
    *   1. [[proposeChildren]] calls the proposer for each task, on the thread of the engine.
    *   1. [[evaluateChildren]] evaluates the children in one batch, each child on the minibatch of its task.
    *   1. [[GepaConfig.proposalSelection]] selects the proposals that pass [[GepaConfig.acceptance]]. Of the selected
    *      proposals with equal candidates, only the first one stays.
    *   1. [[GepaEvalCache.scoredAll]] evaluates the selected candidates on the validation set, in one batch.
    *   1. The selected candidates go to the pool in order, after all pointer moves of the iteration. Thus a child takes
    *      the pointer of its parent after the moves of all tasks of that parent.
    *
    * The metric calls of the iteration are the minibatch of each parent evaluation, the minibatch of each child, and
    * the validation examples that are not in the cache. An iteration that adds no candidate still pays for its
    * minibatch evaluations (gepa counts the parent evaluation before it decides to skip). The sampler gives one example
    * at least, so each iteration decreases the budget and the loop of `search` ends.
    *
    * With one task ([[ProposalSampling.Single]]), each batch holds one evaluation, and the thread of the engine runs
    * it.
    */
  private def iterate(
      state    : GepaState,
      trainset : Vector[Example],
      valset   : Vector[Example],
      rng      : Random,
      sampler  : MinibatchSampler,
      cache    : GepaEvalCache[P],
      iteration: Int
  )(using RuntimeContext): Either[DspyError, (GepaState, Int)] =
    val tasks                      = sampleTasks(state, trainset, rng, sampler, iteration)
    val (parentEvals, parentCalls) = evaluateParents(tasks)
    for
      (moved, reflections) <- prepareReflections(state, tasks.zip(parentEvals))
      children             <- proposeChildren(reflections)
    yield
      val (proposals, childCalls) = evaluateChildren(children)
      // gepa removes the equal candidates after the selection, so no other proposal takes the place of a removed one.
      val selected    = config.proposalSelection.select(proposals, config.acceptance).distinctBy(_.candidate)
      val validations = cache.scoredAll(selected.map(_.candidate), valset)
      val minibatches = parentCalls + childCalls
      val charged     = moved.copy(totalMetricCalls = MetricCallCount.add(moved.totalMetricCalls, minibatches))
      val next        = selected.zip(validations).foldLeft(charged) { case (pool, (proposal, validation)) =>
        pool.add(
          proposal.candidate,
          validation.scores,
          parents = Vector(proposal.parent),
          metricCalls = validation.evaluated,
          objectiveScores = validation.objectiveScores
        )
      }
      (next, selected.size)

  /** Stage 1: the tasks of one iteration, in task order. Each parent draw of [[GepaConfig.proposalSampling]] calls the
    * candidate selector one time. Each task of that draw then takes the next minibatch of the sampler (gepa's
    * `sample_tasks`).
    */
  private def sampleTasks(
      state    : GepaState,
      trainset : Vector[Example],
      rng      : Random,
      sampler  : MinibatchSampler,
      iteration: Int
  ): Vector[ProposalTask] =
    val (draws, tasksForEachDraw) = config.proposalSampling.shape
    (0 until draws).toVector.flatMap { draw =>
      val parentIdx = config.candidateSelector.select(state, rng)
      (0 until tasksForEachDraw).toVector.map { mutation =>
        val ids = sampler.sample(trainset.size, iteration, callInIteration = draw * tasksForEachDraw + mutation)
        ProposalTask(parentIdx, state.candidates(parentIdx), ids.map(trainset))
      }
    }

  /** Stage 2: the evaluation of the parent of each task on the minibatch of the task, with traces, and the metric calls
    * of the stage. Tasks with equal parent candidates and equal minibatches share one evaluation, and the stage counts
    * the metric calls of that evaluation one time (gepa's `unique_keys`). The parents are equal by content, and not by
    * pool index. The minibatches are equal by the content and the order of their examples.
    */
  private def evaluateParents(tasks: Vector[ProposalTask])(using RuntimeContext): (Vector[EvaluationBatch], Int) =
    val pairs  = tasks.map(task => task.parent -> task.minibatch)
    val unique = pairs.distinct
    val evals  = unique.zip(adapter.batchEvaluate(unique, captureTraces = true)).toMap
    (pairs.map(evals), unique.map((_, minibatch) => minibatch.size).sum)

  /** Stage 3: the reflection of each task that has one, and the state with the pointers moved. A `Left` is an error
    * from the reflective dataset of the adapter.
    *
    * A task whose parent has the perfect score on each example of the minibatch has no reflection (nothing to learn),
    * and it does not move the pointer of its parent. The component selector runs after that check, as in gepa, with the
    * pointer that the earlier tasks of the iteration moved. Thus two tasks of one parent change different components
    * with the round-robin selector. Each task that the check does not skip moves the pointer, also when it makes no
    * proposal.
    *
    * The other skip check of gepa (no trajectories) does not apply here, because an evaluation with traces gives one
    * trajectory for each example.
    */
  private def prepareReflections(
      state    : GepaState,
      evaluated: Vector[(ProposalTask, EvaluationBatch)]
  )(using RuntimeContext): Either[DspyError, (GepaState, Vector[Reflection])] =
    evaluated.foldLeft[Either[DspyError, (GepaState, Vector[Reflection])]](Right((state, Vector.empty))) {
      case (Right((current, reflections)), (task, parentEval)) if !hasPerfectScores(parentEval) =>
        val allComponents             = task.parent.keys.toVector.sorted
        val (components, nextPointer) =
          config.componentSelector.select(allComponents, current.pointerOf(task.parentIdx))
        adapter.makeReflectiveDataset(task.parent, parentEval, components).map { records =>
          val reflection = Reflection(task, parentEval.scores, components, records)
          (current.withPointer(task.parentIdx, nextPointer), reflections :+ reflection)
        }
      case (done, _) => done
    }

  /** `true` when [[GepaConfig.skipPerfectScore]] is on and each score of the minibatch is perfect. */
  private def hasPerfectScores(parentEval: EvaluationBatch): Boolean =
    config.skipPerfectScore && parentEval.scores.nonEmpty && parentEval.scores.forall(_ >= config.perfectScore)

  /** Stage 4: the child of each reflection that gives one, in task order. The proposer gets its calls on the thread of
    * the engine, one task after the other. A `Left` stops the stage: the later tasks get no proposer call.
    */
  private def proposeChildren(reflections: Vector[Reflection])(using RuntimeContext): Either[DspyError, Vector[Child]] =
    reflections.foldLeft[Either[DspyError, Vector[Child]]](Right(Vector.empty)) { (done, reflection) =>
      done.flatMap(children => proposeChild(reflection).map(children ++ _))
    }

  /** The child of one reflection. A `Left` is a text of the proposer for a component that the parent does not hold.
    *
    * The proposer gets one call, with the parent, the selected components, and the records of each selected component
    * that has one record at least. After a `Left`, the proposer gets one more call.
    *
    * The task has no child in each of these cases, and it then costs the minibatch evaluation of its parent only:
    *   - The selected components have no reflective record. The proposer then gets no call.
    *   - The proposer gives no text.
    *   - The proposer gives a `Left` two times.
    */
  private def proposeChild(reflection: Reflection)(using RuntimeContext): Either[DspyError, Option[Child]] =
    val parent = reflection.task.parent
    // A component with no reflective record is not in the reflective dataset of the proposer (gepa leaves it out).
    // With no record at all, the proposer gets no call.
    val withRecords = reflection.records.filter((_, records) => records.nonEmpty)
    val newTexts    =
      if withRecords.isEmpty then Map.empty[OptimizableId, String]
      else proposeTexts(parent, withRecords, reflection.components).getOrElse(Map.empty)
    // gepa: `assert name in new_candidate`.
    val unknown = newTexts.keySet.diff(parent.keySet).toVector.sorted
    if unknown.nonEmpty then
      Left(
        ValidationError(
          "The instruction proposer gave a text for a component that the candidate does not hold: " +
            unknown.map(_.render).mkString(", ")
        )
      )
    // No new text: the candidate would be equal to its parent, so there is no proposal and no acceptance evaluation
    // (gepa: "Reflection returned no text updates; skipping proposal").
    else if newTexts.isEmpty then Right(None)
    else
      val candidate = newTexts.foldLeft(parent) { case (cand, (component, text)) =>
        cand.updated(component, Some(text))
      }
      Right(Some(Child(reflection.task, reflection.scoresBefore, candidate)))

  /** Stage 5: the proposal of each child, in task order, and the metric calls of the stage. The children are evaluated
    * in one batch with no traces, each one on the SAME minibatch as its parent. Equal children are not removed here, so
    * each child costs its minibatch (gepa removes equal candidates only after the selection).
    */
  private def evaluateChildren(children: Vector[Child])(using RuntimeContext): (Vector[Proposal], Int) =
    val evals =
      adapter.batchEvaluate(children.map(child => child.candidate -> child.task.minibatch), captureTraces = false)
    val proposals = children.zip(evals).map { (child, eval) =>
      Proposal(child.candidate, child.task.parentIdx, child.scoresBefore, eval.scores)
    }
    (proposals, children.map(_.task.minibatch.size).sum)

  /** The new texts of the proposer for one task. `None` when the proposal failed two times: after a `Left`, the engine
    * calls the proposer one more time (gepa's `_propose_texts_batch_safe`). A `Right` gets no second call, also when it
    * holds no text.
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

  /** Full validation scores for a candidate, via the shared eval cache: returns the per-instance scores, the
    * per-instance objective scores, and the number of ACTUAL (uncached) evaluations to charge against the budget.
    */
  private def fullEval(candidate: Candidate, valset: Vector[Example], cache: GepaEvalCache[P])(using
      RuntimeContext
  ): ScoredBatch =
    cache.scored(candidate, valset)

  /** `Left` when the frontier type of `state` needs objective scores and the seed candidate has none (gepa raises a
    * `ValueError` for this state). The check applies to a new state and to a state from a checkpoint.
    */
  private def requireObjectiveScores(state: GepaState): Either[DspyError, GepaState] =
    if state.frontierType == FrontierType.Instance || state.objectiveAggregates(0).nonEmpty then Right(state)
    else
      Left(
        ConfigurationError(
          s"The frontier type ${state.frontierType} needs objective scores from the metric, and the seed candidate " +
            "has no objective score. Use a metric that gives objective scores, or use the frontier type Instance."
        )
      )

  /** Validate a checkpoint against the current program/data topology and restore its already-accounted validation
    * scores and objective scores into the evaluation cache.
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
    state.candidates.indices.foreach { index =>
      cache
        .restore(state.candidates(index), valset, state.valSubscores(index), state.objectiveScoresOf(index))
        .fold(error => throw IllegalStateException(error), identity)
    }

object GepaEngine:

  /** One (parent, minibatch) task of a reflective iteration (gepa's `ProposalTask`): the pool index and the content of
    * the parent, and the minibatch.
    */
  private final case class ProposalTask(parentIdx: Int, parent: Candidate, minibatch: Vector[Example])

  /** A task that passed the perfect-score check: the scores of the parent on the minibatch, the components to change,
    * and the reflective records of each of them.
    */
  private final case class Reflection(
      task        : ProposalTask,
      scoresBefore: Vector[Double],
      components  : Vector[OptimizableId],
      records     : Map[OptimizableId, Vector[ReflectiveRecord]]
  )

  /** The candidate that the proposer made for one task, with the scores of the parent on the minibatch of the task. */
  private final case class Child(task: ProposalTask, scoresBefore: Vector[Double], candidate: Candidate)
