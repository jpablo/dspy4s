package dspy4s.gepa

import dspy4s.core.data.RawPrediction
import dspy4s.core.data.Example
import dspy4s.core.contracts.TraceEntry

/** One example's captured trajectory — the unit GEPA reflects on: the program's per-predictor [[TraceEntry]] trace
  * (including failure entries via G-12 P-a) plus the score it earned. The reflective-dataset builder reads these.
  * `failed` is `true` when the run of the program returned an error (gepa's `FailedPrediction`). The `prediction` is
  * then empty. `objectiveScores` are the objective scores of the program-level feedback of the metric. A run that
  * failed has none.
  */
final case class Trajectory(
    example        : Example,
    prediction     : RawPrediction,
    trace          : Vector[TraceEntry],
    score          : Double,
    failed         : Boolean             = false,
    objectiveScores: Map[String, Double] = Map.empty
)

/** Result of evaluating a candidate over a batch (the analogue of gepa's `EvaluationBatch`). `trajectories` is `Some`
  * iff traces were captured (the reflective path) and `None` on the scores-only fast path. `outputs`, `scores`, and
  * (when present) `trajectories` are all aligned with the input batch order.
  *
  * `objectiveScores` holds the objective scores that the metric gave for each example
  * ([[dspy4s.gepa.contracts.ScoreWithFeedback.objectiveScores]]), aligned with the batch. An example with no objective
  * score has an empty map. The field is `None` when no example of the batch has an objective score, as upstream.
  */
final case class EvaluationBatch(
    outputs        : Vector[RawPrediction],
    scores         : Vector[Double],
    trajectories   : Option[Vector[Trajectory]],
    objectiveScores: Option[Vector[Map[String, Double]]] = None
)
