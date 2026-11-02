package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.collections.Traverse
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.TypeRef
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.lm.contracts.LmRequest
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.IdentifiedOptimizable
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.PredictorTraces
import dspy4s.programs.optimization.TracedRun
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.ProgramRunner

import zio.blocks.schema.DynamicValue

import scala.concurrent.duration.Duration
import scala.util.Random
import scala.util.Try

/** Bridges a dspy4s program into the GEPA engine — the analogue of Python's `DspyAdapter`. The engine drives the search
  * through this object: it applies a [[Candidate]] to the program, evaluates it, and builds the reflective dataset. The
  * [[InstructionProposer]] of the engine writes the new instructions. See PORT_GAPS G-12.
  *
  * @param program
  *   the student program GEPA optimizes (its predictors' instructions are the genome)
  * @param metric
  *   the feedback metric — scores examples AND yields the reflection feedback
  * @param failureScore
  *   the score for an example the program or the metric failed on; default `0.0`. It must be finite.
  * @param addFormatFailureAsFeedback
  *   when `true`, a predictor call that failed to parse gives the reflection LM a record (dspy's
  *   `add_format_failure_as_feedback`); default `false`, as upstream
  */
final class GepaAdapter[P](
    program                   : P,
    metric                    : FeedbackMetric,
    failureScore              : Double  = 0.0,
    addFormatFailureAsFeedback: Boolean = false
)(using ps: OptimizableStructure[P], runner: ProgramRunner[P]):
  import GepaAdapter.RunResult

  require(failureScore.isFinite, s"failureScore must be finite: $failureScore")

  /** The result of an example with no result of its own: the failure score, and no trajectory. */
  private val failedRun: RunResult = RunResult(failureScore, Map.empty, None)

  /** The optimizable leaves in read order. `replace` keeps the metadata, so their predictor keys hold for each
    * candidate.
    */
  private val leaves: Vector[IdentifiedOptimizable] = ps.readIdentified(program)

  /** Stable optimizable ID → position of the leaf in [[leaves]]. Display names never participate in lookup. */
  private val leafIndexById: Map[OptimizableId, Int] = leaves.iterator.map(_.id).zipWithIndex.toMap

  /** `Left` when two leaves have the same predictor key: a trace entry then cannot be assigned to one of them
    * (UPGRADE-3.2.1-to-3.4.0, decision D2). The engine reads this before any run.
    */
  val distinctKeys: Either[DspyError, Unit] = PredictorTraces.requireDistinctKeys(leaves)

  /** Apply `candidate` to the program and evaluate it over `batch`, returning the score of each example.
    *
    * Each example runs in an ISOLATED context with a fresh trace ([[TracedRun]]), through the executor of
    * [[GepaAdapter.evaluationExecutor]]. A run that fails is a result and not an error of the executor. A non-fatal
    * exception from a run or from the metric is an error of the executor for its example only, because the batch cannot
    * reach the error limit. The executor has no time limit. Thus each run that passes keeps its score, for any number
    * of runs that fail or throw, and for any duration of a run.
    *
    * The program-level feedback of the metric gives the score and the objective scores of an example. The metric gets
    * one call for each run that passes, with an empty trace on the two paths (upstream gives `trace=None` to each such
    * call). These examples have `failureScore` and no objective score:
    *   - A run that fails, or that throws an exception.
    *   - A metric that gives a `Left`, that throws an exception, or that gives a score that is not finite. Upstream
    *     keeps a float NaN. The port saves the state as JSON, and JSON has no such number.
    *   - An example with no input keys. It gets no run and no model call (upstream `Example.inputs` raises).
    *   - Each example of the batch, when the whole executor fails (for example a fatal error in a worker thread). The
    *     adapter then writes a warning with the cause.
    *
    * The adapter drops each objective score that is not finite, and it keeps the finite ones. In
    * [[EvaluationBatch.objectiveScores]], an example with no objective score has an empty map. The adapter does not
    * read the objective scores of a component-level feedback.
    *
    * With `captureTraces` (the reflective path), [[RuntimeContext.captureFailureTraces]] is on, so a parse failure
    * stays in the trace with the raw response (G-12 P-a/P-b). `addFormatFailureAsFeedback` decides if that failure
    * becomes reflection signal. The batch has one [[Trajectory]] for each run that passed, and for each run that failed
    * with a [[ParseError]] that has the raw response (upstream `bootstrap_trace_data` with
    * `capture_failed_parses=True`). Each other example has no trajectory, so [[EvaluationBatch.trajectories]] can be
    * shorter than the batch.
    *
    * When `captureTraces` is false (the acceptance/full-eval fast path), the batch has no trajectories.
    */
  def evaluate(batch: Vector[Example], candidate: Candidate, captureTraces: Boolean)(using
      RuntimeContext
  ): EvaluationBatch =
    val prog = applyCandidate(candidate)
    // The runs are independent, because `runOne` isolates the trace context of each example. They go through the
    // parallel executor. `runOne` gives a result for a run that fails, so only an exception counts as an error there.
    val runs = GepaAdapter.evaluationExecutor(batch.size)
      .execute(
        task = (example: Example) => runOne(prog, example, captureTraces)(using RuntimeEnvironment.current),
        data = batch
      ) match
      // An example that threw gets the failure score.
      case Right(outcome) => outcome.results.map(_.getOrElse(failedRun))
      // A failure of the whole executor: each example gets the failure score.
      case Left(error) =>
        RuntimeEnvironment.warn(
          "GepaAdapter",
          s"The evaluation of ${batch.size} examples failed, so each example has the failure score: ${error.message}"
        )
        batch.map(_ => failedRun)
    EvaluationBatch(
      scores = runs.map(_.score),
      trajectories = Option.when(captureTraces)(runs.flatMap(_.trajectory)),
      objectiveScores = runs.map(_.objectiveScores)
    )

  /** Evaluate each `(candidate, batch)` pair of `items` with [[evaluate]], with the pairs in parallel (dspy's
    * `batch_evaluate`). The results are in the order of `items`.
    *
    * The threads of the context ([[RuntimeContext.numThreads]], or the default of the [[ParallelExecutor]]) are divided
    * between the pairs ([[GepaAdapter.threadShares]]). Each pair runs in a copy of the context of the caller with its
    * share as the number of threads, so the language model and the callbacks of the caller stay visible. The pairs have
    * no time limit and no error limit of their own: the limits of [[evaluate]] apply to the examples of each pair.
    *
    * The number of workers is the smaller of the number of pairs and the number of threads. Each worker is a thread of
    * a [[ParallelExecutor]] that gets the context of the caller. Each pair runs to its end, also when another pair
    * throws an exception. Then the first exception from [[evaluate]], in the order of `items`, goes to the caller with
    * its class. A failure of the whole executor (for example a fatal error in a worker thread) goes to the caller as an
    * `IllegalStateException`.
    *
    * The engine uses this for the parents, the children, and the selected candidates of one iteration. The call
    * installs the context that it gets (UPGRADE-3.2.1-to-3.4.0, decision D8: the given context wins).
    */
  def batchEvaluate(
      items        : Vector[(Candidate, Vector[Example])],
      captureTraces: Boolean
  )(using ctx: RuntimeContext): Vector[EvaluationBatch] =
    RuntimeEnvironment.withInstalled(ctx)(effective ?=> batchEvaluateInstalled(items, captureTraces)(using effective))

  private def batchEvaluateInstalled(
      items        : Vector[(Candidate, Vector[Example])],
      captureTraces: Boolean
  )(using ctx: RuntimeContext): Vector[EvaluationBatch] =
    val total   = ctx.numThreads.getOrElse(ParallelExecutor.DefaultNumThreads)
    val shares  = GepaAdapter.threadShares(total, items.size)
    val workers = ThreadCount.applyUnsafe(math.max(1, math.min(items.size, total)))

    // The context that `evaluate` gets and `RuntimeEnvironment.current` are the same in the call.
    def evaluatePair(index: Int): EvaluationBatch =
      val (candidate, batch) = items(index)
      val share              = ThreadCount.applyUnsafe(shares(index))
      RuntimeEnvironment.withSetting(context => context.withConfig(context.config.copy(numThreads = Some(share)))) {
        evaluate(batch, candidate, captureTraces)(using RuntimeEnvironment.current)
      }

    // `Try` keeps the exception of a pair as the result of that pair, so `get` throws it with its class.
    new ParallelExecutor(numThreads = workers, timeout = Duration.Inf)
      .execute(task = (index: Int) => Try(evaluatePair(index)), data = items.indices.toVector)
      .flatMap(outcome => Traverse.either(outcome.results)(identity))
      .fold(error => throw IllegalStateException(error.message), _.map(_.get))

  /** The program with the instructions of `candidate`. The engine uses it to make the final result. */
  def applyCandidate(candidate: Candidate): P = Candidate.applyTo(program, candidate)

  /** Build the reflective dataset for each component to update: per trajectory, the input values and the output values
    * of that component plus the optimizable-level feedback (gepa's `make_reflective_dataset`). The
    * [[InstructionProposer]] gives these records to the reflection LM to rewrite the instruction of the component.
    *
    * Reads the trajectories of `evalBatch`, so `evalBatch` comes from [[evaluate]] with `captureTraces = true`.
    *
    * Finds the calls of a component by the predictor key of each trace entry ([[PredictorTraces.byLeaf]]), not by the
    * position of the entry. A trajectory gives one record for a component at most:
    *   - Without `addFormatFailureAsFeedback`, a call that failed does not count.
    *   - With `addFormatFailureAsFeedback`, a call that failed counts only when its trace entry has the raw response.
    *     Upstream has a failed trace entry only for a parse failure. The first such call gives the record, and the user
    *     metric gets no call for it. This holds also for a parse failure that a program caught, in a run that passed.
    *     Upstream has no such entry.
    *   - Otherwise, a run that failed gives no record. A run that passed gives the record of one call, selected with
    *     `random` (gepa's `self.rng.choice`). The engine gives each proposal task a source of its own
    *     ([[RandomStream.Reflection]]), so a resumed run makes the same selections.
    *
    * `Left` in two groups of cases. The engine reads the first group with [[requireTraceKeys]], and that group stops a
    * run. The second group skips a proposal task. A `Left` of the metric stops the dataset, as an exception of the
    * metric does.
    *   - Two leaves have the same predictor key, or a leaf ran but its module recorded no key.
    *   - The metric gives a `Left` for a component.
    */
  private[gepa] def makeReflectiveDataset(
      candidate : Candidate,
      evalBatch : EvaluationBatch,
      components: Vector[OptimizableId],
      random    : Random
  )(using RuntimeContext): Either[DspyError, Map[OptimizableId, Vector[ReflectiveRecord]]] =
    for
      _       <- distinctKeys
      runs    <- callsByLeaf(evalBatch.trajectories.getOrElse(Vector.empty))
      records <- Traverse.either(components)(component =>
                   recordsOf(component, candidate, runs, random).map(component -> _)
                 )
    yield records.toMap

  /** `Left` when two leaves have the same predictor key, or when a leaf ran but its module recorded no key (decision
    * D2): the adapter then cannot find the calls of each leaf in `evalBatch`. The engine reads this `Left` before
    * [[makeReflectiveDataset]], because it stops the run, and each other `Left` of the dataset only skips a task.
    */
  private[gepa] def requireTraceKeys(evalBatch: EvaluationBatch): Either[DspyError, Unit] =
    for
      _ <- distinctKeys
      _ <- callsByLeaf(evalBatch.trajectories.getOrElse(Vector.empty))
    yield ()

  /** The calls of each leaf in each trajectory. `Left` when a leaf ran but its module recorded no predictor key. */
  private def callsByLeaf(trajectories: Vector[Trajectory]) =
    Traverse.either(trajectories)(traj => PredictorTraces.byLeaf(leaves, traj.trace).map(traj -> _))

  /** The records of `component` for each of `runs`. A component that no leaf has gets no record. The first `Left` of
    * the metric is the result.
    */
  private def recordsOf(
      component: OptimizableId,
      candidate: Candidate,
      runs     : Vector[(Trajectory, Vector[Vector[TraceEntry]])],
      random   : Random
  )(using RuntimeContext): Either[DspyError, Vector[ReflectiveRecord]] =
    leafIndexById.get(component) match
      case None        => Right(Vector.empty)
      case Some(index) =>
        val leaf = leaves(index)
        // The layout that the candidate gives to this leaf (gepa's `build_program(candidate)`). With no instructions,
        // it has the default text that the leaf runs, not the default text of its structure.
        val instructions = candidate.getOrElse(component, leaf.parameters.instructions)
        val layout       = leaf.metadata.structure.withInstructions(instructions.orElse(leaf.view.defaultInstructions))
        Traverse.either(
          runs
        ) { case (traj, calls) => recordFor(leaf, layout, traj, calls(index), random) }.map(_.flatten)

  /** The record of one leaf for one trajectory, built from the call that [[selectEntry]] gives. `Left` when the metric
    * gives a `Left` for that call.
    */
  private def recordFor(
      leaf   : IdentifiedOptimizable,
      layout : SignatureLayout,
      traj   : Trajectory,
      entries: Vector[TraceEntry],
      random : Random
  )(using RuntimeContext): Either[DspyError, Option[ReflectiveRecord]] =
    selectEntry(entries, traj.failed, random) match
      case None        => Right(None)
      case Some(entry) =>
        val inputs = reflectiveInputs(entry.inputs, layout)
        rawResponse(entry) match
          case Some(raw) => Right(Some(failureRecord(inputs, raw, layout)))
          case None      => metric
              .feedback(
                traj.example,
                traj.prediction,
                traj.trace,
                component = Some(leaf.displayName),
                componentTrace = Vector(entry)
              )
              .map(scored => Some(ReflectiveRecord(inputs, entry.outputs, scored.feedback)))

  /** The inputs of one call in the form of a reflective record (gepa's `make_reflective_dataset`): the conversation
    * history of the call moves to the key [[ReflectiveRecord.ContextKey]], and that key is first. The value of each
    * input does not change.
    *
    * The conversation history is the first input field of `layout` with the type [[TypeRef.history]], as it is for an
    * adapter. Inputs with no such field do not change. An input with the name `Context` replaces the history, as
    * upstream.
    */
  private def reflectiveInputs(inputs: DynamicValue.Record, layout: SignatureLayout): DynamicValue.Record =
    val moved =
      for
        field   <- layout.inputFields.find(_.typeRef == TypeRef.history)
        history <- DynamicValues.recordGet(inputs, field.name)
      yield DynamicValues.mergeRecords(
        DynamicValues.record(ReflectiveRecord.ContextKey -> history),
        DynamicValues.recordFilterKeys(inputs, _ != field.name)
      )
    moved.getOrElse(inputs)

  /** Select the call of one leaf that the reflection LM sees for one trajectory (gepa's `make_reflective_dataset`).
    * `entries` are the calls of the leaf in that trajectory, in trace order.
    */
  private def selectEntry(entries: Vector[TraceEntry], runFailed: Boolean, random: Random): Option[TraceEntry] =
    val usable =
      if addFormatFailureAsFeedback then entries.filter(entry => entry.failure.isEmpty || rawResponse(entry).isDefined)
      else entries.filter(_.failure.isEmpty)
    usable.find(_.failure.isDefined).orElse {
      // A run that failed gives no record from a call that passed.
      if runFailed || usable.isEmpty then None
      else Some(usable(random.nextInt(usable.size)))
    }

  /** The raw response of the model in the trace entry of a call that failed to parse. `None` for a call that passed,
    * and for a call that failed with another error, for example an LM error. Upstream has no trace entry for such a
    * call.
    */
  private def rawResponse(entry: TraceEntry): Option[String] =
    entry.failure.flatMap(_ => DynamicValues.recordGet(entry.outputs, "raw_response").map(DynamicValues.renderText))

  /** The record of a call that failed to parse, with the raw response `raw`. The user metric gets no call, because the
    * call has no prediction to score. The two texts are those of gepa's `make_reflective_dataset`.
    */
  private def failureRecord(inputs: DynamicValue.Record, raw: String, layout: SignatureLayout)(using
      RuntimeContext
  ): ReflectiveRecord =
    ReflectiveRecord(
      inputs,
      DynamicValues.string(
        "Couldn't parse the output as per the expected output format. The model's raw response was:\n" +
          s"```\n$raw\n```\n\n"
      ),
      s"Your output failed to parse. Follow this structure:\n${structureInstruction(layout)}"
    )

  /** The output structure that the model must follow, as text: the chat prompt of `layout` with no demos and no inputs.
    * Upstream uses a `ChatAdapter` for this text, whichever adapter the program uses. Each message is its role, `": "`,
    * its content, and a line end (upstream `role + ": " + content + "\n"` in `make_reflective_dataset`).
    */
  private def structureInstruction(layout: SignatureLayout)(using RuntimeContext): String =
    ChatAdapter()
      .format(AdapterInvocation(layout, demos = Vector.empty, inputs = Example.empty, request = LmRequest(model = "")))
      .fold(
        _ => "",
        _.messages.map(m => s"${m.role.toString.toLowerCase}: ${m.text.getOrElse("")}\n").mkString
      )

  /** `candidate` with the text that each leaf runs in the place of each `None`, for the current instruction that the
    * proposer reads. dspy seeds from `pred.signature.instructions`, which has the default text. The genome keeps
    * `None`, so the child of a candidate comes from the candidate itself and not from this value.
    */
  private[gepa] def withEffectiveInstructions(candidate: Candidate): Candidate =
    candidate.map { (component, instructions) =>
      component -> instructions.orElse(leafIndexById.get(component).map(index => defaultText(leaves(index))))
    }

  /** The text that `leaf` runs with no instructions. */
  private def defaultText(leaf: IdentifiedOptimizable): String =
    leaf.view.copy(parameters = leaf.parameters.copy(instructions = None)).effectiveInstructions

  /** Run one example in an isolated context and assemble its result. With `captureTraces`, the context captures the
    * failures, and a run that passed or a parse failure has a [[Trajectory]] with the trace of the run. The metric gets
    * an empty trace on the two paths.
    */
  private def runOne(prog: P, example: Example, captureTraces: Boolean)(using RuntimeContext): RunResult =
    // Upstream `Example.inputs` raises for an example with no input keys, so the example gets the failure score.
    if example.inputKeys.isEmpty then failedRun
    else
      // The metric runs in the isolated context of the run, so the trace and the history of an LM-judged metric do not
      // reach the trajectory or the caller's context.
      TracedRun.runWith(prog, ProgramCall(example.inputs), captureFailures = captureTraces) { (result, trace) =>
        result match
          case Right(prediction) =>
            // `wrapped_metric` of upstream gets `trace=None`, because `Evaluate` calls it with two arguments.
            metric.feedback(example, prediction, Vector.empty, component = None, componentTrace = Vector.empty) match
              case Right(scored) if scored.score.isFinite =>
                val objectives = scored.objectiveScores.filter((_, value) => value.isFinite)
                val trajectory = Trajectory(example, prediction, trace, scored.score, objectiveScores = objectives)
                RunResult(scored.score, objectives, Option.when(captureTraces)(trajectory))
              case _ => failedRun
          // `bootstrap_trace_data` with `capture_failed_parses=True` keeps a parse failure, and it drops each other
          // failure. With `captureTraces`, the trace already holds the failure entry through captureFailureTraces.
          case Left(ParseError(_, _, Some(raw))) =>
            val trajectory = Trajectory(example, RawPrediction.empty, trace, failureScore, failure = Some(raw))
            RunResult(failureScore, Map.empty, Option.when(captureTraces)(trajectory))
          case Left(_) => failedRun
      }

object GepaAdapter:

  /** The result of one example of a batch: its score, its objective scores, and its trajectory. Only a run that passed
    * and a parse failure have a trajectory, and only with `captureTraces`.
    */
  private final case class RunResult(
      score          : Double,
      objectiveScores: Map[String, Double],
      trajectory     : Option[Trajectory]
  )

  /** The executor of one evaluation of `batchSize` examples ([[GepaAdapter.evaluate]]). The number of threads comes
    * from the context, or else it is [[ParallelExecutor.DefaultNumThreads]].
    *
    * The error limit is 100 times the batch size, as upstream (`max_errors=len(batch) * 100` in dspy `gepa_utils.py`
    * `DspyAdapter.evaluate`). The error limit of the context does not apply. Each example gives one error at most, so
    * the batch cannot reach the limit.
    *
    * The executor has no time limit (`Duration.Inf`). The `timeout` of upstream never fails the batch: it only submits
    * a slow example again (dspy `utils/parallelizer.py`).
    */
  private[gepa] def evaluationExecutor(batchSize: Int)(using RuntimeContext): ParallelExecutor =
    // The limit stays in the range of an `Int`.
    val maxErrors = math.min(math.max(batchSize, 1).toLong * 100L, Int.MaxValue.toLong).toInt
    ParallelExecutor.fromSettings(timeout = Duration.Inf, maxErrors = Some(ErrorLimit.applyUnsafe(maxErrors)))

  /** The number of threads of each of `items` evaluations that share `total` threads (dspy's `batch_evaluate`). At most
    * `total` evaluations run at the same time. With `workers = min(items, total)`, each evaluation gets `total /
    * workers` threads, and the first `total % workers` evaluations get one more. Examples: 8 threads and 3 items give
    * 3, 3, 2; 4 and 2 give 2, 2; 2 and 3 give 1, 1, 1. `total` must be positive.
    */
  private[gepa] def threadShares(total: Int, items: Int): Vector[Int] =
    require(total > 0, s"total must be positive: $total")
    if items <= 0 then Vector.empty
    else
      val workers = math.min(items, total)
      Vector.tabulate(items)(index => total / workers + (if index < total % workers then 1 else 0))
