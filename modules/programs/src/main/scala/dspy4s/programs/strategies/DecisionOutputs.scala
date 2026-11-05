package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, SignatureLayout}
import dspy4s.core.decisions.{DecisionPlan, DecisionSettings}
import zio.blocks.schema.DynamicValue

/** Experimental. The settings operations of the decision outputs of a predictor: [[Predict]], [[DynamicPredict]], and
  * [[ChainOfThought]] (upstream `Predict.fields`, `get_criteria`, and `set_criteria`). This trait adds no module, thus
  * no module event.
  *
  * @tparam Self
  *   the type of the predictor
  */
trait DecisionOutputs[Self]:

  /** The explicit settings of the decision outputs, by output name. */
  def decisions: Map[String, DecisionSettings]

  /** The layout that holds the decision outputs. */
  protected def decisionLayout: SignatureLayout

  /** This predictor with `updated` as its explicit settings. */
  protected def withDecisions(updated: Map[String, DecisionSettings]): Self

  /** Experimental. This predictor with the explicit `settings` of the decision output `field` (upstream
    * `Predict.fields[field]`). The settings replace the earlier settings of the field. A `Left` comes when the plan of
    * the layout rejects the settings ([[dspy4s.core.decisions.DecisionPlan.of]]).
    */
  def withDecision(field: String, settings: DecisionSettings): Either[DspyError, Self] =
    DecisionPlan.withDecision(decisionLayout, decisions, field, settings).map(withDecisions)

  /** Experimental. The effective criteria of the decision output `field` (upstream `get_criteria`): the explicit
    * criteria, else the default of its kind. A `Left` comes when `field` is not a decision output.
    */
  def criteria(field: String): Either[DspyError, Option[DynamicValue]] =
    DecisionPlan.criteria(decisionLayout, decisions, field)

  /** Experimental. This predictor with the criteria of the decision output `field` set to `criteria` (upstream
    * `set_criteria`). The other settings of the field stay. A `Left` comes when the plan rejects the criteria.
    */
  def withCriteria(field: String, criteria: DynamicValue): Either[DspyError, Self] =
    DecisionPlan.withCriteria(decisionLayout, decisions, field, criteria).map(withDecisions)
