package dspy4s.gepa

import dspy4s.core.data.RawPrediction
import dspy4s.core.data.Example
import dspy4s.core.contracts.TraceEntry

/** One example's captured trajectory — the unit GEPA reflects on: the program's per-predictor [[TraceEntry]] trace
  * (including failure entries via G-12 P-a) plus the score it earned. The reflective-dataset builder reads these.
  * `objectiveScores` are the objective scores of the program-level feedback of the metric. A run that failed has none.
  *
  * `failure` is the text of a run that failed, and `None` for a run that passed. Upstream gives a failed example a
  * `FailedPrediction` in the place of its prediction (`bootstrap_trace_data` in `dspy/teleprompt/bootstrap_trace.py`).
  * The text is the `completion_text` of that value: the raw response of the model when the run failed to parse it
  * (upstream `AdapterParseError.lm_response`). For another error, the text is the message of the error, for example the
  * message of an exception of the run or of the metric (upstream drops such an example). The `prediction` of a run that
  * failed is empty. A run that passed keeps its prediction, also when the metric gave a `Left`.
  */
final case class Trajectory(
    example        : Example,
    prediction     : RawPrediction,
    trace          : Vector[TraceEntry],
    score          : Double,
    failure        : Option[String]      = None,
    objectiveScores: Map[String, Double] = Map.empty
):

  /** `true` when the run of the program failed ([[failure]] is defined). */
  def failed: Boolean = failure.isDefined

/** Result of evaluating a candidate over a batch (the analogue of gepa's `EvaluationBatch`). `trajectories` is `Some`
  * iff traces were captured (the reflective path) and `None` on the scores-only fast path. `scores`, `objectiveScores`,
  * and (when present) `trajectories` are all aligned with the input batch order.
  *
  * `objectiveScores` holds the objective scores that the metric gave for each example
  * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]). An example with no objective score has an empty map.
  */
final case class EvaluationBatch(
    scores         : Vector[Double],
    trajectories   : Option[Vector[Trajectory]],
    objectiveScores: Vector[Map[String, Double]]
)
