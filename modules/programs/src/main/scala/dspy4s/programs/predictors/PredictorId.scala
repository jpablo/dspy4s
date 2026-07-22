package dspy4s.programs.predictors

import dspy4s.core.contracts.SignatureLayout

/** Stable identity of a learnable predictor within a program's [[Predictors]] traversal.
  *
  * The identity is the predictor's zero-based ordinal in [[Predictors.read]] order. That order is the canonical
  * optimizer-facing structure: composition concatenates child traversals, while parameter-free structure contributes no
  * entries. Consequently IDs are unique within one program and remain unchanged by `replace`, category identity nodes,
  * and reassociation of ordered composition.
  *
  * A [[PredictorId]] is deliberately not a display path. Human-readable paths come from [[Predictors.inspectNamed]] and
  * may reflect the current case-class/combinator syntax. Nor does an ID survive an arbitrary schema edit that inserts,
  * removes, or reorders predictors; such a change defines a different traversal.
  */
final case class PredictorId(ordinal: Int) extends Ordered[PredictorId] derives CanEqual:
  require(ordinal >= 0, s"PredictorId ordinal must be non-negative, got $ordinal")

  /** Stable text form used at persistence and logging boundaries. */
  def render: String = s"predictor-$ordinal"

  override def compare(that: PredictorId): Int = ordinal.compare(that.ordinal)
  override def toString: String                = render

object PredictorId:
  private val Prefix = "predictor-"

  /** Parse the representation produced by [[PredictorId.render]]. */
  def parse(value: String): Either[String, PredictorId] =
    if !value.startsWith(Prefix) then Left(s"Invalid predictor id '$value': expected '$Prefix<ordinal>'")
    else
      value.drop(Prefix.length).toIntOption match
        case Some(ordinal) if ordinal >= 0 => Right(PredictorId(ordinal))
        case _ => Left(s"Invalid predictor id '$value': ordinal must be a non-negative integer")

/** One focus of a [[Predictors]] traversal: stable machine identity, structural display name, and a non-executable
  * snapshot of its read-only metadata plus writable state. */
final case class IdentifiedPredictor(id: PredictorId, displayName: String, view: PredictorView) derives CanEqual:
  def state: PredictorState       = view.state
  def metadata: PredictorMetadata = view.metadata
  def layout: SignatureLayout     = view.layout
  def moduleName: String          = view.moduleName
