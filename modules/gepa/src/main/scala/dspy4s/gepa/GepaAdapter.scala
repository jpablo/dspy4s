package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.Evaluate
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

import scala.util.Random

/** Bridges a dspy4s program into the GEPA engine — the analogue of Python's `DspyAdapter`. The engine drives the search
  * through this object: it applies a [[Candidate]] to the program, evaluates it, and (next) builds the reflective
  * dataset + proposes new instructions. v0 covers [[evaluate]]. See PORT_GAPS G-12.
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
  *   seeds the random source that selects one call of a predictor that ran more than one time in a run (dspy's `rng`)
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

  /** Selects one call of a predictor that ran more than one time in a run (gepa's `self.rng.choice`). The seed is mixed
    * first, because `java.util.Random` gives the same first values for near seeds such as 0 to 15.
    */
  private val rng = new Random(GepaAdapter.mixSeed(seed))

  /** One trajectory with the calls of each leaf in it. The calls are aligned with [[leaves]]. */
  private type LeafCalls = (Trajectory, Vector[Vector[TraceEntry]])

  /** `Left` when two leaves have the same predictor key: a trace entry then cannot be assigned to one of them
    * (UPGRADE-3.2.1-to-3.4.0, decision D2). The engine reads this before any run.
    */
  val distinctKeys: Either[DspyError, Unit] = PredictorTraces.requireDistinctKeys(leaves)

  /** Apply `candidate` to the program and evaluate it over `batch`, returning per-example outputs + scores.
    *
    * When `captureTraces` is set (the reflective path), each example is run in an ISOLATED context — fresh trace and
    * [[RuntimeContext.captureFailureTraces]] on — so its trajectory is exactly its own and a parse failure stays in the
    * trace (the raw response is captured, G-12 P-a/P-b). `addFormatFailureAsFeedback` decides if that failure becomes
    * reflection signal. When `captureTraces` is false (the acceptance/full-eval fast path) it runs through [[Evaluate]]
    * for scores only.
    */
  def evaluate(batch: Vector[Example], candidate: Candidate, captureTraces: Boolean)(using
      RuntimeContext
  ): EvaluationBatch =
    val prog = applyCandidate(candidate)
    if captureTraces then withTraces(prog, batch) else scoresOnly(prog, batch)

  /** The program with `candidate`'s instructions applied — the engine uses this to materialize the final result. */
  def applyCandidate(candidate: Candidate): P = Candidate.applyTo(program, candidate)

  private def scoresOnly(prog: P, batch: Vector[Example])(using RuntimeContext): EvaluationBatch =
    // `Evaluate` rejects an empty devset. An empty batch has no output and no score, so there is nothing to run.
    if batch.isEmpty then return EvaluationBatch(Vector.empty, Vector.empty, trajectories = None)
    Evaluate(devset = batch, metric = metric, failureScore = failureScore)()((ex: Example) =>
      runner.run(prog, ex.inputs)
    ) match
      case Right(result) =>
        EvaluationBatch(result.results.map(_.prediction), result.results.map(_.score), trajectories = None)
      case Left(_) =>
        // Whole-batch eval failure (timeout / max-errors): degrade to per-example failure scores.
        EvaluationBatch(batch.map(_ => RawPrediction.empty), batch.map(_ => failureScore), trajectories = None)

  private def withTraces(prog: P, batch: Vector[Example])(using RuntimeContext): EvaluationBatch =
    // Reflective runs are independent — runOne isolates its trace context per example — so they go through the
    // same parallel executor as the scores-only Evaluate path instead of serializing every minibatch run.
    val trajectories = ParallelExecutor.fromSettings()
      .execute(
        task = (example: Example) => runOne(prog, example)(using RuntimeEnvironment.current),
        data = batch
      ) match
      case Right(outcome) => batch.indices.toVector.map(i => outcome.results(i).getOrElse(failedRun(batch(i))))
      case Left(_)        =>
        // Whole-batch failure (timeout / max-errors): degrade to failure trajectories, mirroring scoresOnly.
        batch.map(failedRun)
    EvaluationBatch(
      outputs = trajectories.map(_.prediction),
      scores = trajectories.map(_.score),
      trajectories = Some(trajectories)
    )

  /** Build the reflective dataset for each component to update: per trajectory, that component's rendered I/O plus the
    * optimizable-level feedback (gepa's `make_reflective_dataset`). The reflection LM reads these to rewrite the
    * component's instruction.
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
    for
      _    <- distinctKeys
      runs <- callsByLeaf(evalBatch.trajectories.getOrElse(Vector.empty))
    yield components.iterator.map { component =>
      component -> leafIndexById.get(component).toVector.flatMap { index =>
        val leaf = leaves(index)
        // The layout that the candidate gives to this leaf (gepa's `build_program(candidate)`).
        val layout =
          leaf.metadata.structure.withInstructions(candidate.getOrElse(component, leaf.parameters.instructions))
        runs.flatMap { case (traj, calls) => recordFor(leaf, layout, traj, calls(index)) }
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
      entries: Vector[TraceEntry]
  )(using RuntimeContext): Option[ReflectiveRecord] =
    selectEntry(entries, traj.failed).map { entry =>
      val inputs = DynamicValues.renderText(entry.inputs)
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
          ReflectiveRecord(inputs, DynamicValues.renderText(entry.outputs), feedback)
    }

  /** Select the call of one leaf that the reflection LM sees for one trajectory (gepa's `make_reflective_dataset`).
    * `entries` are the calls of the leaf in that trajectory, in trace order.
    */
  private def selectEntry(entries: Vector[TraceEntry], runFailed: Boolean): Option[TraceEntry] =
    val usable = if addFormatFailureAsFeedback then entries else entries.filter(_.failure.isEmpty)
    usable.find(_.failure.isDefined).orElse {
      // A run that failed gives no record from a call that passed.
      if runFailed || usable.isEmpty then None
      else Some(usable(rng.nextInt(usable.size)))
    }

  /** The record of a call that failed. The user metric gets no call, because the call has no prediction to score. The
    * two texts for a parse failure are those of gepa's `make_reflective_dataset`.
    */
  private def failureRecord(inputs: String, entry: TraceEntry, message: String, layout: SignatureLayout)(using
      RuntimeContext
  ): ReflectiveRecord =
    DynamicValues.recordGet(entry.outputs, "raw_response").map(DynamicValues.renderText) match
      case Some(raw) => ReflectiveRecord(
          inputs,
          "Couldn't parse the output as per the expected output format. The model's raw response was:\n" +
            s"```\n$raw\n```",
          s"Your output failed to parse. Follow this structure:\n${structureInstruction(layout)}"
        )
      case None =>
        // The call failed and recorded no model response (for example an LM error). Upstream has no such trace entry.
        ReflectiveRecord(inputs, "(no output)", s"The call failed with this error: $message")

  /** The output structure that the model must follow, as text: the chat prompt of `layout` with no demos and no inputs.
    * Upstream uses a `ChatAdapter` for this text, whichever adapter the program uses.
    */
  private def structureInstruction(layout: SignatureLayout)(using RuntimeContext): String =
    ChatAdapter()
      .format(AdapterInvocation(layout, demos = Vector.empty, inputs = Example.empty, request = LmRequest(model = "")))
      .fold(
        _ => "",
        _.messages.map(m => s"${m.role.toString.toLowerCase}: ${m.text.getOrElse("").strip}").mkString("\n")
      )

  /** Run one example in an isolated, failure-capturing context and assemble its [[Trajectory]]. */
  private def runOne(prog: P, example: Example)(using RuntimeContext): Trajectory =
    // The metric runs in the isolated context of the run, so the trace and the history of an LM-judged metric do not
    // reach the trajectory or the caller's context.
    TracedRun.runWith(prog, ProgramCall(example.inputs), captureFailures = true) { (result, trace) =>
      result match
        case Right(prediction) =>
          val score = metric.feedback(example, prediction, trace, component = None, componentTrace = Vector.empty)
            .map(_.score)
            .getOrElse(failureScore)
          Trajectory(example, prediction, trace, score)
        case Left(_) =>
          // The trace of the run already holds the failure entry (raw response) via captureFailureTraces.
          Trajectory(example, RawPrediction.empty, trace, failureScore, failed = true)
    }

  /** The trajectory of an example that has no result, because its run did not complete. */
  private def failedRun(example: Example): Trajectory =
    Trajectory(example, RawPrediction.empty, Vector.empty, failureScore, failed = true)

object GepaAdapter:

  /** The SplitMix64 output for `seed`. It changes about half of the 64 bits when one bit of `seed` changes, so near
    * seeds give unrelated random sequences. The result is the same for the same seed.
    */
  private def mixSeed(seed: Long): Long =
    val a = seed + 0x9e3779b97f4a7c15L
    val b = (a ^ (a >>> 30)) * 0xbf58476d1ce4e5b9L
    val c = (b ^ (b >>> 27)) * 0x94d049bb133111ebL
    c ^ (c >>> 31)
