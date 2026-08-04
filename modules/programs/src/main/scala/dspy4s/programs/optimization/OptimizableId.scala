package dspy4s.programs.optimization

import dspy4s.core.contracts.SignatureLayout
import io.github.iltotore.iron.RefinedSubtype
import io.github.iltotore.iron.constraint.numeric.Positive0
import scala.compiletime.error

/** A non-negative position in the canonical optimizable traversal. */
type OptimizableOrdinal = OptimizableOrdinal.T

object OptimizableOrdinal extends RefinedSubtype[Int, Positive0]

/** Stable identity of an optimizable leaf within a program's [[OptimizableTraversal]].
  *
  * The identity is the leaf's zero-based ordinal in [[OptimizableTraversal.read]] order. That order is the canonical
  * optimizer-facing structure: composition concatenates child traversals, while parameter-free structure contributes no
  * entries. Consequently IDs are unique within one program and remain unchanged by `replace`, category identity nodes,
  * and reassociation of ordered composition.
  *
  * A [[OptimizableId]] is deliberately not a display path. Human-readable paths come from
  * [[OptimizableTraversal.inspectNamed]] and may reflect the current case-class/combinator syntax. Nor does an ID
  * survive an arbitrary schema edit that inserts, removes, or reorders leaves; such a change defines a different
  * traversal.
  */
final class OptimizableId private (val ordinal: OptimizableOrdinal) extends Ordered[OptimizableId] derives CanEqual:

  /** Stable text form used at persistence and logging boundaries. */
  def render: String = s"optimizable-$ordinal"

  override def compare(that: OptimizableId): Int = ordinal.compare(that.ordinal)
  override def toString: String                  = render
  override def equals(other: Any): Boolean       = other match
    case that: OptimizableId => ordinal == that.ordinal
    case _                   => false
  override def hashCode(): Int = ordinal.hashCode()

object OptimizableId:
  private val Prefix = "optimizable-"

  /** Construct a literal optimizable id, rejecting negative ordinals at compile time. */
  inline def apply(inline ordinal: Int): OptimizableId =
    inline if ordinal >= 0 then fromOrdinal(OptimizableOrdinal.assume(ordinal))
    else error("OptimizableId ordinal must be non-negative")

  /** Construct from an ordinal that has already crossed the refinement boundary. */
  def fromOrdinal(ordinal: OptimizableOrdinal): OptimizableId = new OptimizableId(ordinal)

  /** Parse the representation produced by [[OptimizableId.render]]. */
  def parse(value: String): Either[String, OptimizableId] =
    if !value.startsWith(Prefix) then Left(s"Invalid optimizable id '$value': expected '$Prefix<ordinal>'")
    else
      value.drop(Prefix.length).toIntOption match
        case Some(ordinal) =>
          OptimizableOrdinal.either(ordinal).fold(
            _ => Left(s"Invalid optimizable id '$value': ordinal must be a non-negative integer"),
            valid => Right(fromOrdinal(valid))
          )
        case None => Left(s"Invalid optimizable id '$value': ordinal must be a non-negative integer")

/** One focus of a [[OptimizableTraversal]] traversal: stable machine identity, structural display name, and a
  * non-executable snapshot of its read-only metadata plus optimizable parameters.
  */
final case class IdentifiedOptimizable(id: OptimizableId, displayName: String, view: OptimizableView) derives CanEqual:
  def parameters: OptimizableParameters = view.parameters
  def metadata: OptimizableMetadata     = view.metadata
  def layout: SignatureLayout           = view.layout
  def moduleName: String                = view.moduleName
