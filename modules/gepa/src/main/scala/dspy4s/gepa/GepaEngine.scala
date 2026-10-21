package dspy4s.gepa

import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.optimization.OptimizableId

import java.nio.file.Path
import scala.annotation.tailrec
import scala.util.Random
import scala.util.Try
import scala.util.control.NonFatal

/** Configuration for a GEPA run. Covers the reflective-mutation loop, merge crossover, the evaluation cache, the
  * objective-aware Pareto fronts ([[frontierType]]), and the proposals of one iteration ([[proposalSampling]],
  * [[proposalSelection]], [[acceptance]]; PORT_GAPS G-45).
  */
final case class GepaConfig(
    /** The budget in metric (evaluation) calls (gepa's `max_metric_calls`, `max_full_evals`, or `auto`). Reflection-LM
      * calls do NOT count, matching gepa. [[Gepa.compile]] resolves it with [[GepaBudget.resolve]]; the engine resolves
      * it with the validation set that it gets.
      */
    budget                 : GepaBudget,
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
      * budget is always an upper bound. A run that stops for this reason has the stop reason
      * [[GepaStopReason.PerfectScore]].
      */
    stopOnPerfectScore: Boolean = false,
    /** The seed of the random sources of the run: the candidate selector, the merge proposer, the minibatch sampler,
      * and the selection of one call of a predictor for the reflective dataset. Each source derives from the seed and
      * fixed coordinates, for example the iteration index, so a resumed run makes the same draws as a run with no stop.
      */
    seed: Long = 0L,
    /** The directory of the checkpoint of the run (gepa's `run_dir`, dspy's `log_dir`). With a directory, the engine
      * saves the state after the seed evaluation and after each iteration, and a run with a checkpoint in the directory
      * continues it: the same iteration index, random draws, pointers, and merge schedule, so the resumed run makes the
      * same draws as a run with no stop. `None` gives no checkpoint.
      *
      * A checkpoint that cannot be read, or that does not agree with the program, the validation set, or the
      * configuration, gives a `Left` before any LM call, and the file stays unchanged.
      */
    runDir: Option[Path] = None
)

/** The outcome of a GEPA run: the best candidate (by mean validation score) and the program it yields. `state` is the
  * final search state: each candidate, its scores and objective scores for each validation example, its parents, and
  * the Pareto fronts. The state also holds the iteration index, the merge schedule, the metric calls before the
  * validation of each candidate, and the number of failed proposals.
  */
final case class GepaResult[P](
    bestCandidate   : Candidate,
    bestProgram     : P,
    bestScore       : Double,
    numCandidates   : GepaCandidateCount,
    totalMetricCalls: MetricCallCount,
    state           : GepaState,
    /** The budget of the run in metric calls, after [[GepaBudget.resolve]]. */
    maxMetricCalls: MetricCallCount,
    /** The number of iterations of the run, a merge iteration included. A resumed run counts the iterations before the
      * stop too ([[GepaState.iteration]]).
      */
    iterations: Int,
    /** Why the run stopped. */
    stopReason: GepaStopReason
)

/** The GEPA engine: genetic-Pareto reflective prompt evolution. Each iteration selects a parent candidate from the
  * Pareto frontier, reflects on its failures over a train minibatch to propose a better instruction, accepts the
  * mutation iff it improves the minibatch, then full-evaluates the accepted candidate on the validation set and folds
  * it into the Pareto frontier. After an accepted mutation it schedules a [[MergeProposer]] crossover, attempted first
  * the next iteration. A [[GepaEvalCache]] memoizes scores-only evals so repeated `(candidate, example)` pairs are
  * free. A faithful port of the external `gepa` engine. See PORT_GAPS G-12.
  *
  * The budget is a hard limit ([[GepaConfig.budget]], resolved with [[GepaBudget.resolve]]). The seed evaluation and
  * each iteration are atomic: an iteration starts only when its largest possible cost fits in the budget that remains.
  * The run stops for one of the reasons of [[GepaStopReason]]: no metric call remains, the next reflective iteration
  * does not fit, or the best candidate has the perfect score ([[GepaConfig.stopOnPerfectScore]]).
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
  * The state counts the iterations ([[GepaState.iteration]]). One pass of the loop is one iteration, a merge iteration
  * included. Each random source of an iteration derives from [[GepaConfig.seed]] and the iteration index, and the state
  * holds the merge schedule ([[GepaState.merge]]). With [[GepaConfig.runDir]], the engine saves the state after the
  * seed evaluation and after each iteration. A resumed run thus makes the same draws as a run with no stop.
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

  /** The minibatch sampler. It has no state, so each run can use it. */
  private val sampler = MinibatchSampler.of(config.batchSampler, config.reflectionMinibatchSize, config.seed)

  /** Run the search. A `Left` stops the search. It has one of these causes:
    *   - The trainset is empty (`ConfigurationError`). The search stops before any LM call.
    *   - The budget cannot hold the seed evaluation (`ConfigurationError`). The search stops before any LM call.
    *   - The checkpoint in [[GepaConfig.runDir]] cannot be read, or it does not agree with the run: its frontier type
    *     is not [[GepaConfig.frontierType]], its candidates have other component ids, its validation rows have another
    *     size, or its scores conflict in the evaluation cache (`ConfigurationError`). The search stops before any LM
    *     call, and the file stays unchanged.
    *   - The engine cannot write the checkpoint (`RuntimeError`).
    *   - The adapter cannot find the trace entries of each leaf (UPGRADE-3.2.1-to-3.4.0, decision D2): two leaves have
    *     the same predictor key (found before any run), or a leaf ran but its module recorded no key (found when a
    *     reflective iteration reads the traces).
    *   - The proposer gives a text for a component that the candidate does not hold.
    *   - [[GepaConfig.frontierType]] is not `Instance`, and the seed candidate has no objective score. The search stops
    *     after the seed evaluation, before any iteration.
    */
  def optimize(
      seedCandidate: Candidate,
      trainset     : Vector[Example],
      valset       : Vector[Example]
  )(using
      ctx: RuntimeContext
  ): Either[DspyError, GepaResult[P]] =
    // The run installs the given context (decision D8: the given context wins), as `Evaluate` does. The evaluations
    // read the installed context, and the proposer gets the given context, so the two use the same language model and
    // the same callbacks (upstream has one ambient context, `dspy.settings`). The run keeps the open call scopes of
    // this thread, for example a compile scope. The trace and the history of the run reach the context of the
    // caller, also when the run throws.
    RuntimeEnvironment.withInstalled(ctx) { effective ?=>
      given RuntimeContext = effective
      for
        _      <- requireTrainset(trainset)
        _      <- adapter.distinctKeys
        result <- search(seedCandidate, trainset, valset)
      yield result
    }

  private def search(
      seedCandidate: Candidate,
      trainset     : Vector[Example],
      valset       : Vector[Example]
  )(using
      RuntimeContext
  ): Either[DspyError, GepaResult[P]] =
    val maxMetricCalls = config.budget.resolve(seedCandidate.size, trainset.size, Some(valset.size))
    val distinct       = valset.distinct.size.toLong
    val taskCount      = config.proposalSampling.taskCount
    val run            = Run(
      trainset,
      valset,
      new GepaEvalCache(adapter),
      maxMetricCalls,
      // A reflective iteration evaluates the parent and the proposal of each task on one minibatch, then may validate
      // every distinct example for each candidate that the selection can give. The loop reserves this atomic worst
      // case, so the budget stays a hard bound. With one task, this is two minibatches and one validation.
      reflectiveCost = 2L * taskCount * config.reflectionMinibatchSize +
        config.proposalSelection.limit(taskCount).toLong * distinct,
      // A merge's subsample is drawn from valset and shares the cache with its possible full eval, so at most one call
      // per distinct validation example is needed for the merged candidate.
      mergeCost = distinct
    )
    for
      start           <- startState(seedCandidate, run)
      _               <- checkpoint(start)
      (state, reason) <- loop(start, run)
    yield
      val best = state.bestIndex
      GepaResult(
        bestCandidate = state.candidates(best),
        bestProgram = adapter.applyCandidate(state.candidates(best)),
        bestScore = state.aggregateScore(best),
        numCandidates = GepaCandidateCount.assume(state.candidates.size),
        totalMetricCalls = state.totalMetricCalls,
        state = state,
        maxMetricCalls = maxMetricCalls,
        iterations = state.iteration,
        stopReason = reason
      )

  /** The iterations from `state` to the stop, with a checkpoint after each iteration. Gives the state after the last
    * iteration, and the stop reason. A pass that stops changes nothing, so a resumed run makes that pass again.
    */
  @tailrec
  private def loop(state: GepaState, run: Run[P])(using
      RuntimeContext
  ): Either[DspyError, (GepaState, GepaStopReason)] =
    step(state, run).flatMap(saved) match
      case Left(error)                => Left(error)
      case Right(Step.Stop(reason))   => Right((state, reason))
      case Right(Step.Continue(next)) => loop(next, run)

  /** `pass` after the checkpoint of the state that it continues with. A stop writes no checkpoint. */
  private def saved(pass: Step): Either[DspyError, Step] =
    pass match
      case Step.Continue(next) => checkpoint(next).map(_ => pass)
      case Step.Stop(_)        => Right(pass)

  /** One pass of the loop: the iteration `state.iteration`, or the reason to stop before it.
    *
    * A merge comes first if one is scheduled and the last iteration produced a new program (the order of gepa). A merge
    * proposal (accepted or rejected) consumes the iteration; only a "no triplet found" falls through to a reflective
    * iteration, with the same random source. Each candidate that a reflective iteration adds schedules a future merge.
    */
  private def step(state: GepaState, run: Run[P])(using RuntimeContext): Either[DspyError, Step] =
    val remaining = run.maxMetricCalls.toLong - state.totalMetricCalls
    if converged(state) then Right(Step.Stop(GepaStopReason.PerfectScore))
    else if remaining <= 0 then Right(Step.Stop(GepaStopReason.BudgetExhausted))
    else
      val rng    = GepaRandom.source(config.seed, RandomStream.Engine, state.iteration.toLong)
      val merger = Option.when(config.useMerge)(mergeProposer(run, rng, state.merge))
      // The state after the iteration has the next iteration index and the merge schedule of the proposer.
      def finish(next: GepaState): Step =
        Step.Continue(next.copy(iteration = state.iteration + 1, merge = merger.fold(next.merge)(_.schedule)))
      val merged =
        merger.filter(mp => mp.shouldAttempt && remaining >= run.mergeCost).flatMap(mp => attemptMerge(state, mp, run))
      merged match
        case Some(next)                             => Right(finish(next))
        case None if remaining < run.reflectiveCost =>
          Right(Step.Stop(GepaStopReason.IterationDoesNotFit(run.reflectiveCost, remaining)))
        case None =>
          merger.foreach(_.clearNewProgram())
          iterate(state, run, rng).map { (next, added) =>
            merger.foreach(mp => (0 until added).foreach(_ => mp.onReflectiveAccepted()))
            finish(next)
          }

  /** `true` when [[GepaConfig.stopOnPerfectScore]] is on and the best candidate has the perfect mean validation score.
    * Later iterations can only find that candidate again, and they use the budget on `skipPerfectScore` minibatches.
    * The option is off by default, as upstream.
    */
  private def converged(state: GepaState): Boolean =
    config.stopOnPerfectScore && state.aggregateScore(state.bestIndex) >= config.perfectScore

  /** The merge proposer of one iteration. It continues `schedule` with the random source of the iteration. */
  private def mergeProposer(run: Run[P], rng: Random, schedule: MergeSchedule): MergeProposer[P] =
    new MergeProposer(
      run.valset,
      config.maxMergeInvocations,
      rng,
      run.cache,
      valOverlapFloor = config.mergeValOverlapFloor,
      start = schedule
    )

  /** One merge attempt. `None` when the proposer finds no triplet. Otherwise the state with the subsample calls of the
    * merge, and for an accepted merge, the merged candidate with its validation. The subsample calls come first, so the
    * discovery count of the merged candidate includes them.
    */
  private def attemptMerge(state: GepaState, merger: MergeProposer[P], run: Run[P])(using
      RuntimeContext
  ): Option[GepaState] =
    val proposal = merger.propose(state)
    merger.clearNewProgram()
    proposal.map { merge =>
      val charged = charge(state, merge.metricCalls)
      if merge.accepted then
        val validation = fullEval(merge.candidate, run.valset, run.cache)
        merger.onMergeAccepted()
        charged.add(merge.candidate, validation.scores, merge.parents, validation.evaluated, validation.objectiveScores)
      else charged
    }

  /** One reflective-mutation iteration: returns the new state (with the iteration's metric calls accrued, the
    * round-robin pointers of the parents moved, the failed proposals counted, and the selected candidates appended),
    * and the number of candidates that the iteration added (each one schedules a merge). A `Left` is an error from the
    * reflective dataset of the adapter, or a text of the proposer for a component that the parent does not hold.
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
    * at least, so each iteration decreases the budget and the loop of `search` ends. The state gets the minibatch calls
    * before the first candidate, so the discovery count of a candidate is the total before its validation.
    *
    * With one task ([[ProposalSampling.Single]]), each batch holds one evaluation, and the thread of the engine runs
    * it.
    */
  private def iterate(state: GepaState, run: Run[P], rng: Random)(using
      RuntimeContext
  ): Either[DspyError, (GepaState, Int)] =
    val tasks                      = sampleTasks(state, run.trainset, rng)
    val (parentEvals, parentCalls) = evaluateParents(tasks)
    for
      (moved, reflections) <- prepareReflections(state, tasks.zip(parentEvals))
      (children, failed)   <- proposeChildren(reflections)
    yield
      val (proposals, childCalls) = evaluateChildren(children)
      // gepa removes the equal candidates after the selection, so no other proposal takes the place of a removed one.
      val selected    = config.proposalSelection.select(proposals, config.acceptance).distinctBy(_.candidate)
      val validations = run.cache.scoredAll(selected.map(_.candidate), run.valset)
      val charged     = charge(moved, parentCalls + childCalls).copy(failedProposals = moved.failedProposals + failed)
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
    * `sample_tasks`). The index of a task is its position in the iteration, which is also its call of the sampler.
    */
  private def sampleTasks(state: GepaState, trainset: Vector[Example], rng: Random): Vector[ProposalTask] =
    val (draws, tasksForEachDraw) = config.proposalSampling.shape
    (0 until draws).toVector.flatMap { draw =>
      val parentIdx = config.candidateSelector.select(state, rng)
      (0 until tasksForEachDraw).toVector.map { mutation =>
        val index = draw * tasksForEachDraw + mutation
        val ids   = sampler.sample(trainset.size, state.iteration, callInIteration = index)
        ProposalTask(index, parentIdx, state.candidates(parentIdx), ids.map(trainset))
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
    * The reflective dataset of each task has a random source of its own ([[RandomStream.Reflection]]), from the seed,
    * the iteration index, and the index of the task.
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
        val random = GepaRandom.source(config.seed, RandomStream.Reflection, state.iteration.toLong, task.index.toLong)
        adapter.makeReflectiveDataset(task.parent, parentEval, components, random).map { records =>
          val reflection = Reflection(task, parentEval.scores, components, records)
          (current.withPointer(task.parentIdx, nextPointer), reflections :+ reflection)
        }
      case (done, _) => done
    }

  /** `true` when [[GepaConfig.skipPerfectScore]] is on and each score of the minibatch is perfect. */
  private def hasPerfectScores(parentEval: EvaluationBatch): Boolean =
    config.skipPerfectScore && parentEval.scores.nonEmpty && parentEval.scores.forall(_ >= config.perfectScore)

  /** Stage 4: the child of each reflection that gives one, in task order, and the number of tasks for which the
    * proposer failed two times. The proposer gets its calls on the thread of the engine, one task after the other. A
    * `Left` stops the stage: the later tasks get no proposer call.
    */
  private def proposeChildren(reflections: Vector[Reflection])(using
      RuntimeContext
  ): Either[DspyError, (Vector[Child], Int)] =
    reflections.foldLeft[Either[DspyError, (Vector[Child], Int)]](Right((Vector.empty, 0))) { (done, reflection) =>
      for
        (children, failed) <- done
        outcome            <- proposeChild(reflection)
      yield outcome match
        case Outcome.Proposed(child) => (children :+ child, failed)
        case Outcome.NoChange        => (children, failed)
        case Outcome.Failed          => (children, failed + 1)
    }

  /** The outcome of the proposer for one reflection. A `Left` is a text of the proposer for a component that the parent
    * does not hold.
    *
    * The proposer gets one call, with the parent, the selected components, and the records of each selected component
    * that has one record at least. After a `Left` or a non-fatal exception, the proposer gets one more call.
    *
    * The task has no child in each of these cases, and it then costs the minibatch evaluation of its parent only:
    *   - The selected components have no reflective record. The proposer then gets no call.
    *   - The proposer gives no text.
    *   - The proposer fails two times, with a `Left` or a non-fatal exception. The outcome is then [[Outcome.Failed]].
    */
  private def proposeChild(reflection: Reflection)(using RuntimeContext): Either[DspyError, Outcome] =
    val parent = reflection.task.parent
    // A component with no reflective record is not in the reflective dataset of the proposer (gepa leaves it out).
    // With no record at all, the proposer gets no call.
    val withRecords = reflection.records.filter((_, records) => records.nonEmpty)
    val proposed    =
      if withRecords.isEmpty then Some(Map.empty[OptimizableId, String])
      else proposeTexts(parent, withRecords, reflection.components)
    val newTexts = proposed.getOrElse(Map.empty)
    // gepa: `assert name in new_candidate`.
    val unknown = newTexts.keySet.diff(parent.keySet).toVector.sorted
    if unknown.nonEmpty then
      Left(
        ValidationError(
          "The instruction proposer gave a text for a component that the candidate does not hold: " +
            unknown.map(_.render).mkString(", ")
        )
      )
    else if proposed.isEmpty then Right(Outcome.Failed)
    // No new text: the candidate would be equal to its parent, so there is no proposal and no acceptance evaluation
    // (gepa: "Reflection returned no text updates; skipping proposal").
    else if newTexts.isEmpty then Right(Outcome.NoChange)
    else
      val candidate = newTexts.foldLeft(parent) { case (cand, (component, text)) =>
        cand.updated(component, Some(text))
      }
      Right(Outcome.Proposed(Child(reflection.task, reflection.scoresBefore, candidate)))

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

  /** The new texts of the proposer for one task. `None` when the proposal failed two times: after a `Left` or a
    * non-fatal exception ([[proposeInReflectionContext]]), the engine calls the proposer one more time (gepa's
    * `_propose_texts_batch_safe`). A `Right` gets no second call, also when it holds no text.
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
    * A non-fatal exception of the proposer gives a `Left` with a [[RuntimeError]], so [[proposeTexts]] calls the
    * proposer one more time (gepa's `_propose_texts_batch_safe` catches each exception of the reflection). A fatal
    * error, for example an `InterruptedException`, goes up to the caller.
    *
    * The call has a trace and a history of its own. The trace entries do not reach the caller. The history entries go
    * to the context of the caller, also for a call that gives a `Left` or that throws a non-fatal exception.
    */
  private def proposeInReflectionContext(
      parent    : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId]
  )(using ctx: RuntimeContext): Either[DspyError, Map[OptimizableId, String]] =
    val reflection = ctx.withServices(ctx.services.copy(lm = Some(reflectionLm)))
    val executed   = RuntimeEnvironment.isolatedAttempt(reflection) {
      try proposer.propose(parent, reflective, components)
      catch
        case NonFatal(error) =>
          Left(RuntimeError("instruction_proposer", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    }
    RuntimeEnvironment.propagate(executed.delta.copy(trace = Vector.empty))
    executed.value

  /** Full validation scores for a candidate, via the shared eval cache: returns the per-instance scores, the
    * per-instance objective scores, and the number of ACTUAL (uncached) evaluations to charge against the budget.
    */
  private def fullEval(candidate: Candidate, valset: Vector[Example], cache: GepaEvalCache[P])(using
      RuntimeContext
  ): ScoredBatch =
    cache.scored(candidate, valset)

  /** The state at the start of the search: the state of the checkpoint in [[GepaConfig.runDir]], or else the seed
    * candidate with its validation. A `Left` comes before any LM call, except the `Left` of [[requireObjectiveScores]]
    * for a new state.
    */
  private def startState(seedCandidate: Candidate, run: Run[P])(using RuntimeContext): Either[DspyError, GepaState] =
    config.runDir
      .fold[Either[DspyError, Option[GepaState]]](Right(None))(loadCheckpoint)
      .flatMap {
        case Some(saved) => resume(saved, seedCandidate, run)
        case None        => seedState(seedCandidate, run)
      }
      .flatMap(requireObjectiveScores)

  /** The state in `dir`, or `None` when the directory has no checkpoint. */
  private def loadCheckpoint(dir: Path): Either[DspyError, Option[GepaState]] =
    GepaStatePersistence.load(dir).left.map(error => ConfigurationError(s"Invalid GEPA checkpoint in '$dir': $error"))

  /** The state of a checkpoint, after the checks of [[validateAndWarm]] and of the frontier type. */
  private def resume(saved: GepaState, seedCandidate: Candidate, run: Run[P]): Either[DspyError, GepaState] =
    if saved.frontierType != config.frontierType then
      Left(
        ConfigurationError(
          s"The GEPA checkpoint${config.runDir.fold("")(dir => s" in '$dir'")} has the frontier type " +
            s"${saved.frontierType}, and the configuration has the frontier type ${config.frontierType}. " +
            "Use the frontier type of the checkpoint, or a new run directory."
        )
      )
    else validateAndWarm(saved, seedCandidate, run.valset, run.cache).map(_ => saved)

  /** The seed candidate with its validation. The seed evaluation is atomic, so a budget that cannot hold it is a `Left`
    * before any LM call, and the budget stays a hard bound.
    */
  private def seedState(seedCandidate: Candidate, run: Run[P])(using RuntimeContext): Either[DspyError, GepaState] =
    val seedCost = run.cache.uncachedCount(seedCandidate, run.valset)
    if seedCost > run.maxMetricCalls then
      Left(
        ConfigurationError(
          s"The budget of ${run.maxMetricCalls} metric calls cannot cover the seed validation cost of $seedCost " +
            "calls. Use a larger budget or a smaller validation set."
        )
      )
    else
      val seed = fullEval(seedCandidate, run.valset, run.cache)
      Right(
        GepaState.seed(
          seedCandidate,
          seed.scores,
          metricCalls = seed.evaluated,
          objectiveScores = seed.objectiveScores,
          frontierType = config.frontierType
        )
      )

  /** Write `state` to [[GepaConfig.runDir]], when the configuration has one. */
  private def checkpoint(state: GepaState): Either[DspyError, Unit] =
    config.runDir.fold[Either[DspyError, Unit]](Right(())) { dir =>
      Try(GepaStatePersistence.save(dir, state)).toEither.left.map { error =>
        RuntimeError(
          "gepa_checkpoint",
          s"Could not write the GEPA checkpoint in '$dir': ${Option(error.getMessage).getOrElse(error.toString)}"
        )
      }
    }

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
    * scores and objective scores into the evaluation cache. A `Left` when the candidates have other component ids, when
    * a validation row has another size than `valset`, or when two scores of the checkpoint conflict in the cache.
    */
  private def validateAndWarm(
      state        : GepaState,
      seedCandidate: Candidate,
      valset       : Vector[Example],
      cache        : GepaEvalCache[P]
  ): Either[DspyError, Unit] =
    val expectedIds = seedCandidate.keySet
    val mismatched  = state.candidates.indices.filter(index => state.candidates(index).keySet != expectedIds)
    val wrongRows   = state.valSubscores.indices.filter(index => state.valSubscores(index).size != valset.size)
    if mismatched.nonEmpty then
      Left(
        ConfigurationError(
          s"GEPA checkpoint optimizable ids do not match the current program at candidates ${mismatched.mkString(", ")}"
        )
      )
    else if wrongRows.nonEmpty then
      Left(
        ConfigurationError(
          s"GEPA checkpoint validation rows do not match the current valset size ${valset.size}: " +
            wrongRows.mkString(", ")
        )
      )
    else
      state.candidates.indices.foldLeft[Either[DspyError, Unit]](Right(())) { (done, index) =>
        done.flatMap { _ =>
          cache
            .restore(state.candidates(index), valset, state.valSubscores(index), state.objectiveScoresOf(index))
            .left
            .map(ConfigurationError(_))
        }
      }

object GepaEngine:

  /** The data of one search: the two example sets, the evaluation cache, the budget, and the largest cost of a
    * reflective iteration and of a merge iteration.
    */
  private final case class Run[P](
      trainset      : Vector[Example],
      valset        : Vector[Example],
      cache         : GepaEvalCache[P],
      maxMetricCalls: MetricCallCount,
      reflectiveCost: Long,
      mergeCost     : Long
  )

  /** The result of one pass of the loop: the state after the iteration, or the reason to stop before it. */
  private enum Step derives CanEqual:
    case Continue(next: GepaState)
    case Stop(reason: GepaStopReason)

  /** `Left` when the trainset is empty: the reflective minibatches come from it (dspy asserts a trainset that is not
    * empty).
    */
  private def requireTrainset(trainset: Vector[Example]): Either[DspyError, Unit] =
    if trainset.nonEmpty then Right(())
    else Left(ConfigurationError("GEPA needs a trainset with one example at least: the minibatches come from it."))

  /** `state` with `metricCalls` more metric calls. */
  private def charge(state: GepaState, metricCalls: Int): GepaState =
    state.copy(totalMetricCalls = MetricCallCount.add(state.totalMetricCalls, metricCalls))

  /** One (parent, minibatch) task of a reflective iteration (gepa's `ProposalTask`): the position of the task in the
    * iteration, the pool index and the content of the parent, and the minibatch.
    */
  private final case class ProposalTask(index: Int, parentIdx: Int, parent: Candidate, minibatch: Vector[Example])

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

  /** The outcome of the proposer for one task. */
  private enum Outcome derives CanEqual:
    /** The proposer gave new texts, so the task has a child. */
    case Proposed(child: Child)

    /** The task has no child: the selected components have no record, or the proposer gave no text. */
    case NoChange

    /** The proposer gave a `Left` two times ([[GepaState.failedProposals]]). */
    case Failed
