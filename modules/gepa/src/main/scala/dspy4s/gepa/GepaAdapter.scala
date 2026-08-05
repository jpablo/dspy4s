package dspy4s.gepa

import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.Evaluate
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.programs.optimization.IdentifiedOptimizable
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.ProgramRunner

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
  */
final class GepaAdapter[P](
    val program     : P,
    val metric      : FeedbackMetric,
    val failureScore: Double = 0.0
)(using ps: OptimizableStructure[P], runner: ProgramRunner[P]):

  /** Stable optimizable ID → its structure entry and trace index. Display names never participate in lookup. */
  private val componentsById: Map[OptimizableId, (IdentifiedOptimizable, Int)] =
    ps.readIdentified(program).zipWithIndex.map { case (entry, index) => entry.id -> (entry -> index) }.toMap

  /** Apply `candidate` to the program and evaluate it over `batch`, returning per-example outputs + scores.
    *
    * When `captureTraces` is set (the reflective path), each example is run in an ISOLATED context — fresh trace and
    * [[RuntimeContext.captureFailureTraces]] on — so its trajectory is exactly its own and a parse failure becomes
    * reflection signal (the raw response is captured, G-12 P-a/P-b). When `captureTraces` is false (the
    * acceptance/full-eval fast path) it runs through [[Evaluate]] for scores only.
    */
  def evaluate(batch: Vector[Example], candidate: Candidate, captureTraces: Boolean)(using
      RuntimeContext
  ): EvaluationBatch =
    val prog = applyCandidate(candidate)
    if captureTraces then withTraces(prog, batch) else scoresOnly(prog, batch)

  /** The program with `candidate`'s instructions applied — the engine uses this to materialize the final result. */
  def applyCandidate(candidate: Candidate): P = Candidate.applyTo(program, candidate)

  private def scoresOnly(prog: P, batch: Vector[Example])(using RuntimeContext): EvaluationBatch =
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
      case Right(outcome) => batch.indices.toVector.map { i =>
          outcome.results(i).getOrElse(Trajectory(batch(i), RawPrediction.empty, Vector.empty, failureScore))
        }
      case Left(_) =>
        // Whole-batch failure (timeout / max-errors): degrade to failure trajectories, mirroring scoresOnly.
        batch.map(example => Trajectory(example, RawPrediction.empty, Vector.empty, failureScore))
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
    * Locates a component's trace entry by stable ID → leaf index → trace position. Exact for a single-predictor program
    * and for sequential composites; non-sequential execution (a predictor called multiple times, or reordered) is a
    * documented refinement.
    */
  def makeReflectiveDataset(
      @scala.annotation.unused candidate: Candidate, // kept for engine-contract parity
      evalBatch                         : EvaluationBatch,
      components                        : Vector[OptimizableId]
  )(using RuntimeContext): Map[OptimizableId, Vector[ReflectiveRecord]] =
    val trajectories = evalBatch.trajectories.getOrElse(Vector.empty)
    components.iterator.map(component => component -> trajectories.flatMap(traj => recordFor(component, traj))).toMap

  private def recordFor(component: OptimizableId, traj: Trajectory)(using RuntimeContext): Option[ReflectiveRecord] =
    // Positionally locate the component's trace entry (component index in read order). Exact for a
    // single-predictor program and for sequential composites; non-sequential matching is a refinement.
    componentsById.get(component).flatMap { case (identified, index) =>
      traj.trace.lift(index).map(identified -> _)
    }.map { case (identified, entry) =>
      val inputs           = DynamicValues.renderText(entry.inputs)
      val generatedOutputs = entry.failure match
        case Some(_) =>
          DynamicValues.recordGet(entry.outputs, "raw_response").map(DynamicValues.renderText).getOrElse("(no output)")
        case None => DynamicValues.renderText(entry.outputs)
      val feedback = metric
        .feedback(
          traj.example,
          traj.prediction,
          traj.trace,
          component = Some(identified.displayName),
          componentTrace = Vector(entry)
        )
        .map(_.feedback)
        .getOrElse(FeedbackMetric.defaultFeedback(traj.score))
      ReflectiveRecord(inputs, generatedOutputs, feedback)
    }

  /** Run one example in an isolated, failure-capturing context and assemble its [[Trajectory]]. */
  private def runOne(prog: P, example: Example)(using RuntimeContext): Trajectory =
    val base     = summon[RuntimeContext]
    val isolated = base
      .withConfig(base.config.copy(captureFailureTraces = true))
      .withDelta(base.delta.copy(trace = Vector.empty))
    RuntimeEnvironment.withContext(isolated) {
      given RuntimeContext = RuntimeEnvironment.current
      runner.run(prog, example.inputs) match
        case Right(prediction) =>
          val trace = RuntimeEnvironment.current.trace
          val score = metric.feedback(example, prediction, trace, component = None, componentTrace = Vector.empty)
            .map(_.score)
            .getOrElse(failureScore)
          Trajectory(example, prediction, trace, score)
        case Left(_) =>
          // The isolated trace already holds the failure entry (raw response) via captureFailureTraces.
          Trajectory(example, RawPrediction.empty, RuntimeEnvironment.current.trace, failureScore)
    }
