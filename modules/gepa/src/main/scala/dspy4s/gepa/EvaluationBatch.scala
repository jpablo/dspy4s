package dspy4s.gepa

import dspy4s.core.data.RawPrediction
import dspy4s.core.data.Example
import dspy4s.core.contracts.TraceEntry

/** One example's captured trajectory, the unit that GEPA reflects on: the program's per-predictor [[TraceEntry]] trace
  * (including failure entries via G-12 P-a) plus the score it earned. The reflective-dataset builder reads these.
  * `objectiveScores` are the objective scores of the program-level feedback of the metric. A parse failure has none.
  *
  * Only a run that passed and a parse failure have a trajectory (upstream `bootstrap_trace_data` with
  * `capture_failed_parses=True`, in `dspy/teleprompt/bootstrap_trace.py`).
  *
  * `failure` is the raw response of the model for a parse failure, and `None` for a run that passed. Upstream gives
  * such an example a `FailedPrediction` in the place of its prediction, and its `completion_text` is the raw response
  * (upstream `AdapterParseError.lm_response`). The `prediction` of a parse failure is empty.
  */
final case class Trajectory(
    example        : Example,
    prediction     : RawPrediction,
    trace          : Vector[TraceEntry],
    score          : Double,
    failure        : Option[String]      = None,
    objectiveScores: Map[String, Double] = Map.empty
):

  /** `true` when the run of the program failed to parse ([[failure]] is defined). */
  def failed: Boolean = failure.isDefined

/** Result of evaluating a candidate over a batch (the analogue of gepa's `EvaluationBatch`). `scores` and
  * `objectiveScores` are aligned with the input batch order.
  *
  * `trajectories` is `Some` iff traces were captured (the reflective path) and `None` on the scores-only fast path. It
  * holds only the runs that passed and the parse failures, in the input batch order, so it can be shorter than the
  * batch and it is not aligned with it. Each [[Trajectory]] names its example.
  *
  * `objectiveScores` holds the objective scores that the metric gave for each example
  * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]). An example with no objective score has an empty map.
  */
final case class EvaluationBatch(
    scores         : Vector[Double],
    trajectories   : Option[Vector[Trajectory]],
    objectiveScores: Vector[Map[String, Double]]
)
