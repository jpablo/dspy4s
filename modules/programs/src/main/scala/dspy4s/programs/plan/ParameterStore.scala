package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, NotFoundError, ValidationError}
import dspy4s.programs.optimization.{OptimizableMetadata, OptimizableParameters}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

/** Stable identity for one optimizer-writable parameter slot.
  *
  * IDs are explicit program data. They do not depend on tree position, so reassociation does not change parameter
  * identity. Reusing one ID deliberately shares one parameter slot.
  */
opaque type ParameterId = String

object ParameterId:
  def apply(value: String): ParameterId =
    either(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  def either(value: String): Either[DspyError, ParameterId] =
    val normalized = value.trim
    if normalized.nonEmpty then Right(normalized)
    else Left(ValidationError("ParameterId must not be empty"))

  extension (id: ParameterId) def value: String = id

  given CanEqual[ParameterId, ParameterId] = CanEqual.derived

/** One declared parameter slot and its current value. */
final case class ParameterBinding(
    id      : ParameterId,
    metadata: OptimizableMetadata,
    value   : OptimizableParameters
) derives CanEqual

/** Immutable parameter values for one program plan.
  *
  * Bindings retain declaration order for deterministic optimizer presentation. Lookup and replacement use stable
  * [[ParameterId]] values rather than structural paths or vector offsets.
  */
final class ParameterStore private (private val bindings: Vector[ParameterBinding]) derives CanEqual:

  def all: Vector[ParameterBinding] = bindings

  def size: Int = bindings.size

  def get(id: ParameterId): Option[OptimizableParameters] =
    bindings.find(_.id == id).map(_.value)

  def binding(id: ParameterId): Option[ParameterBinding] =
    bindings.find(_.id == id)

  def updated(id: ParameterId, value: OptimizableParameters): Either[DspyError, ParameterStore] =
    val index = bindings.indexWhere(_.id == id)
    if index < 0 then Left(NotFoundError("program_parameter", s"Unknown parameter id '${id.value}'"))
    else Right(ParameterStore.fromBindings(bindings.updated(index, bindings(index).copy(value = value))))

  /** Replace a complete parameter set. Missing and extra IDs are errors. */
  def replace(values: Map[ParameterId, OptimizableParameters]): Either[DspyError, ParameterStore] =
    val expected = bindings.map(_.id).toSet
    val actual   = values.keySet
    if expected != actual then
      val missing = (expected -- actual).toVector.map(_.value).sorted
      val extra   = (actual -- expected).toVector.map(_.value).sorted
      Left(ValidationError(
        s"Parameter replacement IDs do not match; missing=[${missing.mkString(", ")}], extra=[${extra.mkString(", ")}]"
      ))
    else
      Right(ParameterStore.fromBindings(bindings.map(binding => binding.copy(value = values(binding.id)))))

  /** Save only optimizer-writable values, keyed by stable identity. Syntax, metadata, and services are not state. */
  def dumpState: DynamicValue.Record =
    DynamicValue.Record(Chunk.from(bindings.map(binding => binding.id.value -> binding.value.dumpState)))

  /** Load values into this program's declared slots. Unknown, missing, duplicate, or malformed entries fail. */
  def loadState(state: DynamicValue.Record): Either[DspyError, ParameterStore] =
    val names = state.fields.map(_._1)
    if names.distinct.size != names.size then
      Left(ValidationError("Parameter state contains duplicate IDs"))
    else
      state.fields.iterator.foldLeft[Either[DspyError, Map[ParameterId, OptimizableParameters]]](Right(Map.empty)) {
        case (acc, (rawId, rawValue)) =>
          for
            values <- acc
            id     <- ParameterId.either(rawId)
            record <- rawValue match
                        case value: DynamicValue.Record => Right(value)
                        case _                          => Left(ValidationError(
                            s"Parameter state '${id.value}' must be a record"
                          ))
            value <- OptimizableParameters.fromState(record)
          yield values.updated(id, value)
      }.flatMap(replace)

  private[plan] def merge(that: ParameterStore): ParameterStore =
    that.bindings.foldLeft(this) { (current, incoming) =>
      current.binding(incoming.id) match
        case None           => ParameterStore.fromBindings(current.bindings :+ incoming)
        case Some(existing) =>
          require(
            existing == incoming,
            s"Parameter id '${incoming.id.value}' was composed with different metadata or values"
          )
          current
    }

  override def equals(other: Any): Boolean =
    other match
      case that: ParameterStore => bindings == that.bindings
      case _                    => false

  override def hashCode(): Int = bindings.hashCode()

  override def toString: String = s"ParameterStore(${bindings.mkString(", ")})"

object ParameterStore:
  val empty: ParameterStore = fromBindings(Vector.empty)

  def single(binding: ParameterBinding): ParameterStore = fromBindings(Vector(binding))

  private[plan] def fromBindings(bindings: Vector[ParameterBinding]): ParameterStore =
    new ParameterStore(bindings)
