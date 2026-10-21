package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.TypeRef
import dspy4s.core.runtime.ContextPropagation
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

import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.util.Random

/** Bridges a dspy4s program into the GEPA engine — the analogue of Python's `DspyAdapter`. The engine drives the search
  * through this object: it applies a [[Candidate]] to the program, evaluates it, and builds the reflective dataset. The
  * [[InstructionProposer]] of the engine writes the new instructions. See PORT_GAPS G-12.
  *
  * @param program
  *   the student program GEPA optimizes (its predictors' instructions are the genome)
  * @param metric
  *   the feedback metric — scores examples AND yields the reflection feedback
  * @param failureScore
  *   the score for an example the program failed on (parse/runtime error); default `0.0`
  * @param addFormatFailureAsFeedback
  *   when `true`, a predictor call that failed gives the reflection LM a record (dspy's
  *   `add_format_failure_as_feedback`); default `false`, as upstream
  * @param seed
  *   seeds the random source that selects one call of a predictor that ran more than one time in a run (dspy's `rng`).
  *   The [[GepaEngine]] does not use this source: it gives each proposal task a source of its own.
  */
final class GepaAdapter[P](
    val program               : P,
    val metric                : FeedbackMetric,
    val failureScore          : Double  = 0.0,
    addFormatFailureAsFeedback: Boolean = false,
    seed                      : Long    = 0L
)(using ps: OptimizableStructure[P], runner: ProgramRunner[P]):

  /** The optimizable leaves in read order. `replace` keeps the metadata, so their predictor keys hold for each
    * candidate.
    */
  private val leaves: Vector[IdentifiedOptimizable] = ps.readIdentified(program)

  /** Stable optimizable ID → position of the leaf in [[leaves]]. Display names never participate in lookup. */
  private val leafIndexById: Map[OptimizableId, Int] = leaves.iterator.map(_.id).zipWithIndex.toMap

  /** Selects one call of a predictor that ran more than one time in a run (gepa's `self.rng.choice`), for a call of
    * [[makeReflectiveDataset]] with no source. The seed is mixed first, because `java.util.Random` gives the same first
    * values for near seeds such as 0 to 15.
    */
  private val rng = new Random(GepaRandom.mix(seed))

  /** One trajectory with the calls of each leaf in it. The calls are aligned with [[leaves]]. */
  private type LeafCalls = (Trajectory, Vector[Vector[TraceEntry]])

  /** `Left` when two leaves have the same predictor key: a trace entry then cannot be assigned to one of them
    * (UPGRADE-3.2.1-to-3.4.0, decision D2). The engine reads this before any run.
    */
  val distinctKeys: Either[DspyError, Unit] = PredictorTraces.requireDistinctKeys(leaves)

  /** Apply `candidate` to the program and evaluate it over `batch`, returning per-example outputs + scores.
    *
    * Each example runs in an ISOLATED context with a fresh trace ([[TracedRun]]), through the executor of
    * [[GepaAdapter.evaluationExecutor]]. A run that fails is a result and not an error of the executor. A non-fatal
    * exception from a run or from the metric is an error of the executor for its example only, because the batch cannot
    * reach the error limit. The executor has no time limit. Thus each run that passes keeps its score, for any number
    * of runs that fail or throw, and for any duration of a run.
    *
    * The program-level feedback of the metric gives the score and the objective scores of an example. The metric gets
    * one call for each run that passes. A run that fails, a metric that gives a `Left`, or an exception has
    * `failureScore` and no objective score. A failure of the whole executor (for example a fatal error in a worker
    * thread) gives each example `failureScore`. The trajectory of a run that fails, or of an example whose run or
    * metric throws, has the text of the failure ([[Trajectory.failure]]). [[EvaluationBatch.objectiveScores]] is `None`
    * when no example of the batch has an objective score. The adapter does not read the objective scores of a
    * component-level feedback.
    *
    * With `captureTraces` (the reflective path), [[RuntimeContext.captureFailureTraces]] is on, so a parse failure
    * stays in the trace with the raw response (G-12 P-a/P-b). `addFormatFailureAsFeedback` decides if that failure
    * becomes reflection signal. The metric gets the trace of the run, and the batch has one [[Trajectory]] for each
    * example.
    *
    * When `captureTraces` is false (the acceptance/full-eval fast path), the batch has no trajectories, and the metric
    * gets an empty trace. The output of an example whose metric gives a `Left` is then empty, as it is for a run that
    * fails. With `captureTraces`, the trajectory of such an example keeps the prediction.
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
      // An example that threw has no result, and it gets the failure score and the error of the executor.
      case Right(outcome) => batch.indices.toVector.map(i =>
          outcome.results(i).getOrElse(failedRun(batch(i), outcome.errors.getOrElse(i, GepaAdapter.NotCompleted)))
        )
      // A failure of the whole executor: each example gets the failure score.
      case Left(error) => batch.map(failedRun(_, error))
    EvaluationBatch(
      outputs = runs.map(_.prediction),
      scores = runs.map(_.score),
      trajectories = Option.when(captureTraces)(runs),
      // gepa_utils `_make_evaluation_batch`: `objective_scores if any(objective_scores) else None`.
      objectiveScores = Option.when(runs.exists(_.objectiveScores.nonEmpty))(runs.map(_.objectiveScores))
    )

  /** Evaluate each `(candidate, batch)` pair of `items` with [[evaluate]], with the pairs in parallel (dspy's
    * `batch_evaluate`). The results are in the order of `items`.
    *
    * The threads of the context ([[RuntimeContext.numThreads]], or the default of the [[ParallelExecutor]]) are divided
    * between the pairs ([[GepaAdapter.threadShares]]). Each pair runs in a copy of the context of the caller with its
    * share as the number of threads, so the language model and the callbacks of the caller stay visible. The pairs have
    * no time limit and no error limit of their own: the limits of [[evaluate]] apply to the examples of each pair.
    *
    * The number of workers is the smaller of the number of pairs and the number of threads. With two workers or more,
    * each worker is a thread that gets the context of the caller. With one worker, the thread of the caller evaluates
    * the pairs in order, and no thread starts. An exception from [[evaluate]] goes to the caller.
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
    val workers = math.min(items.size, total)

    // The context that `evaluate` gets and `RuntimeEnvironment.current` are the same in the call.
    def evaluatePair(index: Int): EvaluationBatch =
      val (candidate, batch) = items(index)
      val share              = ThreadCount.applyUnsafe(shares(index))
      RuntimeEnvironment.withSetting(context => context.withConfig(context.config.copy(numThreads = Some(share)))) {
        evaluate(batch, candidate, captureTraces)(using RuntimeEnvironment.current)
      }

    // One worker evaluates the pairs in order, so the thread of the caller does that work. With no pair, no work
    // starts.
    if workers <= 1 then items.indices.toVector.map(evaluatePair)
    else
      // The context of the caller goes to each worker thread, as it does in the `ParallelExecutor`.
      val captured                                    = ContextPropagation.captureAll
      def task(index: Int): Callable[EvaluationBatch] =
        () =>
          captured.run(RuntimeEnvironment.withGeneratedAsyncTask("gepa-batch-evaluate")(evaluatePair(index)))
      val pool = Executors.newFixedThreadPool(workers)
      try
        val pending = items.indices.toVector.map(index => pool.submit(task(index)))
        // `get` has no time limit. An exception of a pair goes to the caller, as it does with one worker.
        pending.map { result =>
          try result.get()
          catch case error: ExecutionException => throw Option(error.getCause).getOrElse(error)
        }
      finally
        val _ = pool.shutdownNow()

  /** The program with the instructions of `candidate`. The engine uses it to make the final result. */
  def applyCandidate(candidate: Candidate): P = Candidate.applyTo(program, candidate)

  /** Build the reflective dataset for each component to update: per trajectory, the input values and the output values
    * of that component plus the optimizable-level feedback (gepa's `make_reflective_dataset`). The
    * [[InstructionProposer]] gives these records to the reflection LM to rewrite the instruction of the component.
    *
    * Requires `evalBatch` to carry trajectories (i.e. it came from [[evaluate]] with `captureTraces = true`).
    *
    * Finds the calls of a component by the predictor key of each trace entry ([[PredictorTraces.byLeaf]]), not by the
    * position of the entry. A trajectory gives one record for a component at most:
    *   - Without `addFormatFailureAsFeedback`, a call that failed does not count.
    *   - The first call that failed gives the record. The user metric gets no call for it.
    *   - Otherwise, a run that failed gives no record. A run that passed gives the record of one call, selected at
    *     random with the seeded source.
    *
    * `Left` when two leaves have the same predictor key, or when a leaf ran but its module recorded no key.
    */
  def makeReflectiveDataset(
      candidate : Candidate,
      evalBatch : EvaluationBatch,
      components: Vector[OptimizableId]
  )(using RuntimeContext): Either[DspyError, Map[OptimizableId, Vector[ReflectiveRecord]]] =
    makeReflectiveDataset(candidate, evalBatch, components, rng)

  /** [[makeReflectiveDataset]] with `random` as the source that selects one call of a leaf. The engine gives each
    * proposal task a source of its own ([[RandomStream.Reflection]]), so a resumed run makes the same selections.
    */
  private[gepa] def makeReflectiveDataset(
      candidate : Candidate,
      evalBatch : EvaluationBatch,
      components: Vector[OptimizableId],
      random    : Random
  )(using RuntimeContext): Either[DspyError, Map[OptimizableId, Vector[ReflectiveRecord]]] =
    for
      _    <- distinctKeys
      runs <- callsByLeaf(evalBatch.trajectories.getOrElse(Vector.empty))
    yield components.iterator.map { component =>
      component -> leafIndexById.get(component).toVector.flatMap { index =>
        val leaf = leaves(index)
        // The layout that the candidate gives to this leaf (gepa's `build_program(candidate)`).
        val layout =
          leaf.metadata.structure.withInstructions(candidate.getOrElse(component, leaf.parameters.instructions))
        runs.flatMap { case (traj, calls) => recordFor(leaf, layout, traj, calls(index), random) }
      }
    }.toMap

  /** The calls of each leaf in each trajectory. `Left` when a leaf ran but its module recorded no predictor key. */
  private def callsByLeaf(trajectories: Vector[Trajectory]): Either[DspyError, Vector[LeafCalls]] =
    trajectories.foldLeft[Either[DspyError, Vector[LeafCalls]]](Right(Vector.empty)) { (done, traj) =>
      for
        runs  <- done
        calls <- PredictorTraces.byLeaf(leaves, traj.trace)
      yield runs :+ (traj -> calls)
    }

  /** The record of one leaf for one trajectory, built from the call that [[selectEntry]] gives. */
  private def recordFor(
      leaf   : IdentifiedOptimizable,
      layout : SignatureLayout,
      traj   : Trajectory,
      entries: Vector[TraceEntry],
      random : Random
  )(using RuntimeContext): Option[ReflectiveRecord] =
    selectEntry(entries, traj.failed, random).map { entry =>
      val inputs = reflectiveInputs(entry.inputs, layout)
      entry.failure match
        case Some(message) => failureRecord(inputs, entry, message, layout)
        case None          =>
          val feedback = metric
            .feedback(
              traj.example,
              traj.prediction,
              traj.trace,
              component = Some(leaf.displayName),
              componentTrace = Vector(entry)
            )
            .map(_.feedback)
            .getOrElse(FeedbackMetric.defaultFeedback(traj.score))
          ReflectiveRecord(inputs, entry.outputs, feedback)
    }

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
    val usable = if addFormatFailureAsFeedback then entries else entries.filter(_.failure.isEmpty)
    usable.find(_.failure.isDefined).orElse {
      // A run that failed gives no record from a call that passed.
      if runFailed || usable.isEmpty then None
      else Some(usable(random.nextInt(usable.size)))
    }

  /** The record of a call that failed. The user metric gets no call, because the call has no prediction to score. The
    * two texts for a parse failure are those of gepa's `make_reflective_dataset`.
    */
  private def failureRecord(
      inputs : DynamicValue.Record,
      entry  : TraceEntry,
      message: String,
      layout : SignatureLayout
  )(using RuntimeContext): ReflectiveRecord =
    DynamicValues.recordGet(entry.outputs, "raw_response").map(DynamicValues.renderText) match
      case Some(raw) => ReflectiveRecord(
          inputs,
          text(
            "Couldn't parse the output as per the expected output format. The model's raw response was:\n" +
              s"```\n$raw\n```\n\n"
          ),
          s"Your output failed to parse. Follow this structure:\n${structureInstruction(layout)}"
        )
      case None =>
        // The call failed and recorded no model response (for example an LM error). Upstream has no such trace entry.
        ReflectiveRecord(inputs, text("(no output)"), s"The call failed with this error: $message")

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  /** The output structure that the model must follow, as text: the chat prompt of `layout` with no demos and no inputs.
    * Upstream uses a `ChatAdapter` for this text, whichever adapter the program uses. Each message is its role, `": "`,
    * its content, and a line end (upstream `role + ": " + content + "\n"` in `make_reflective_dataset`).
    */
  private def structureInstruction(layout: SignatureLayout)(using RuntimeContext): String =
    ChatAdapter()
      .format(AdapterInvocation(layout, demos = Vector.empty, inputs = Example.empty, request = LmRequest(model = "")))
      .fold(
        _ => "",
        _.messages.map(m => s"${m.role.toString.toLowerCase}: ${m.text.getOrElse("").strip}\n").mkString
      )

  /** Run one example in an isolated context and assemble its [[Trajectory]]. With `captureTraces`, the context captures
    * the failures, and the trajectory and the metric get the trace of the run. Without it, the two get an empty trace:
    * the caller reads the output, the score, and the objective scores only.
    */
  private def runOne(prog: P, example: Example, captureTraces: Boolean)(using RuntimeContext): Trajectory =
    // The metric runs in the isolated context of the run, so the trace and the history of an LM-judged metric do not
    // reach the trajectory or the caller's context.
    TracedRun.runWith(prog, ProgramCall(example.inputs), captureFailures = captureTraces) { (result, runTrace) =>
      val trace = if captureTraces then runTrace else Vector.empty
      result match
        case Right(prediction) =>
          metric.feedback(example, prediction, trace, component = None, componentTrace = Vector.empty) match
            case Right(scored) =>
              Trajectory(example, prediction, trace, scored.score, objectiveScores = scored.objectiveScores)
            case Left(_) =>
              // The scores-only path gives an empty output for each example with no score, as `Evaluate` does
              // (upstream uses `Evaluate` for this path). The reflective path keeps the prediction.
              Trajectory(example, if captureTraces then prediction else RawPrediction.empty, trace, failureScore)
        case Left(error) =>
          // With `captureTraces`, the trace already holds the failure entry (raw response) via captureFailureTraces.
          Trajectory(example, RawPrediction.empty, trace, failureScore, failure = Some(GepaAdapter.failureText(error)))
    }

  /** The trajectory of an example that has no result, because its run did not complete. `error` is the error of the
    * executor for the example, for example the error of an exception of the run or of the metric.
    */
  private def failedRun(example: Example, error: DspyError): Trajectory =
    Trajectory(example, RawPrediction.empty, Vector.empty, failureScore, failure = Some(GepaAdapter.failureText(error)))

object GepaAdapter:

  /** The text of a run that failed with `error` ([[Trajectory.failure]]): the raw response of the model for a parse
    * error that has it (upstream `FailedPrediction(completion_text=e.lm_response)` in `bootstrap_trace_data`), else the
    * message of the error.
    */
  private def failureText(error: DspyError): String =
    error match
      case ParseError(_, _, Some(raw)) => raw
      case other                       => other.message

  /** The error of an example that the executor did not run, because it stopped the batch first. */
  private val NotCompleted: DspyError = RuntimeError("gepa_adapter", "The run of the example did not complete")

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
  def threadShares(total: Int, items: Int): Vector[Int] =
    require(total > 0, s"total must be positive: $total")
    if items <= 0 then Vector.empty
    else
      val workers = math.min(items, total)
      Vector.tabulate(items)(index => total / workers + (if index < total % workers then 1 else 0))
