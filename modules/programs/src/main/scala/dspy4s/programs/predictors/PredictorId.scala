package dspy4s.programs.predictors

import dspy4s.core.contracts.SignatureLayout
import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive0
import scala.compiletime.error

/** A non-negative position in the canonical predictor traversal. */
type PredictorOrdinal = PredictorOrdinal.T

object PredictorOrdinal extends RefinedSubtype[Int, Positive0]

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
final class PredictorId private (val ordinal: PredictorOrdinal) extends Ordered[PredictorId] derives CanEqual:

  /** Stable text form used at persistence and logging boundaries. */
  def render: String = s"predictor-$ordinal"

  override def compare(that: PredictorId): Int = ordinal.compare(that.ordinal)
  override def toString: String                = render
  override def equals(other: Any): Boolean = other match
    case that: PredictorId => ordinal == that.ordinal
    case _                 => false
  override def hashCode(): Int = ordinal.hashCode()

object PredictorId:
  private val Prefix = "predictor-"

  /** Construct a literal predictor id, rejecting negative ordinals at compile time. */
  inline def apply(inline ordinal: Int): PredictorId =
    inline if ordinal >= 0 then fromOrdinal(PredictorOrdinal.assume(ordinal))
    else error("PredictorId ordinal must be non-negative")

  /** Construct from an ordinal that has already crossed the refinement boundary. */
  def fromOrdinal(ordinal: PredictorOrdinal): PredictorId = new PredictorId(ordinal)

  /** Parse the representation produced by [[PredictorId.render]]. */
  def parse(value: String): Either[String, PredictorId] =
    if !value.startsWith(Prefix) then Left(s"Invalid predictor id '$value': expected '$Prefix<ordinal>'")
    else
      value.drop(Prefix.length).toIntOption match
        case Some(ordinal) =>
          PredictorOrdinal.either(ordinal).fold(
            _ => Left(s"Invalid predictor id '$value': ordinal must be a non-negative integer"),
            valid => Right(fromOrdinal(valid))
          )
        case None => Left(s"Invalid predictor id '$value': ordinal must be a non-negative integer")

/** One focus of a [[Predictors]] traversal: stable machine identity, structural display name, and a non-executable
  * snapshot of its read-only metadata plus writable state. */
final case class IdentifiedPredictor(id: PredictorId, displayName: String, view: PredictorView) derives CanEqual:
  def state: PredictorState       = view.state
  def metadata: PredictorMetadata = view.metadata
  def layout: SignatureLayout     = view.layout
  def moduleName: String          = view.moduleName
