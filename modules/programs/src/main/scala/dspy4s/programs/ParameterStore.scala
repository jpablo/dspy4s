package dspy4s.programs

import dspy4s.core.contracts.{DspyError, NotFoundError, ValidationError}
import dspy4s.programs.optimization.{OptimizableMetadata, OptimizableParameters}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Public identity of one optimizer-writable parameter slot.
  *
  * Most programs do not construct this value. Anonymous prediction declarations receive deterministic ordinal IDs in
  * program declaration order. A [[PredictionDef]] supplies a stable semantic ID when identity must not depend on that
  * order or another operation must address one prediction directly.
  */
opaque type ParameterId = String

object ParameterId:
  private val AnonymousPrefix = "auto/"
  private val ReservedPrefix  = "$dspy4s/"

  /** Construct an explicit stable parameter ID. */
  def apply(value: String): ParameterId =
    either(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  def either(value: String): Either[DspyError, ParameterId] =
    val normalized = value.trim
    if normalized.isEmpty then Left(ValidationError("ParameterId must not be empty"))
    else if normalized.startsWith(AnonymousPrefix) then
      Left(ValidationError(s"ParameterId prefix '$AnonymousPrefix' is reserved for anonymous predictions"))
    else if normalized.startsWith(ReservedPrefix) then
      Left(ValidationError(s"ParameterId prefix '$ReservedPrefix' is reserved for program state"))
    else Right(normalized)

  private[programs] def anonymous(ordinal: Int): ParameterId =
    require(ordinal >= 0, "Anonymous parameter ordinal must not be negative")
    s"$AnonymousPrefix$ordinal"

  private[programs] def stored(value: String): Either[DspyError, ParameterId] =
    val normalized = value.trim
    if normalized.isEmpty then Left(ValidationError("Stored ParameterId must not be empty"))
    else if normalized.startsWith(AnonymousPrefix) then
      normalized.drop(AnonymousPrefix.length).toIntOption match
        case Some(ordinal) if ordinal >= 0 => Right(normalized)
        case _                             => Left(ValidationError(s"Invalid anonymous ParameterId '$normalized'"))
    else Right(normalized)

  extension (id: ParameterId)
    def value: String        = id
    def isAnonymous: Boolean = id.startsWith(AnonymousPrefix)

  given CanEqual[ParameterId, ParameterId] = CanEqual.derived

/** A stable reference to one named prediction declaration. */
trait ParameterRef:
  def id: ParameterId

/** Private identity used by prediction syntax.
  *
  * An anonymous key uses reference equality. Reusing one program value therefore shares its parameters. Two separate
  * declarations remain independent, even when they have equal signatures and defaults. A stable key uses semantic
  * equality, so two declarations with the same stable ID share one slot when their metadata and values agree.
  */
private[programs] sealed trait ParameterKey

private[programs] object ParameterKey:
  final case class Stable(id: ParameterId)       extends ParameterKey
  final class Anonymous private[ParameterKey] () extends ParameterKey

  def stable(id: ParameterId): ParameterKey = Stable(id)
  def anonymous(): ParameterKey             = new Anonymous()

  given CanEqual[ParameterKey, ParameterKey] = CanEqual.derived

/** One declared parameter slot and its current public identity. */
final case class ParameterBinding(
    id      : ParameterId,
    metadata: OptimizableMetadata,
    value   : OptimizableParameters
) derives CanEqual

private final case class StoredParameterBinding(
    key     : ParameterKey,
    metadata: OptimizableMetadata,
    value   : OptimizableParameters
)

/** Immutable parameter values for one program plan.
  *
  * Bindings retain declaration order. Stable keys keep their semantic IDs. Anonymous keys receive ordinal IDs from this
  * order. This is the small elaboration step between private syntax identity and optimizer-facing identity.
  */
final class ParameterStore private (private val bindings: Vector[StoredParameterBinding]):

  private val StateShapeKey = "$dspy4s/parameter-shape"

  def all: Vector[ParameterBinding] =
    bindings.zipWithIndex.map { case (binding, index) => publicBinding(binding, index) }

  def size: Int = bindings.size

  def get(id: ParameterId): Option[OptimizableParameters] =
    bindingIndex(id).map(bindings(_).value)

  def get(ref: ParameterRef): Option[OptimizableParameters] = get(ref.id)

  def binding(id: ParameterId): Option[ParameterBinding] =
    bindingIndex(id).map(index => publicBinding(bindings(index), index))

  def binding(ref: ParameterRef): Option[ParameterBinding] = binding(ref.id)

  private[programs] def binding(key: ParameterKey): Option[ParameterBinding] =
    bindings.indexWhere(_.key == key) match
      case -1    => None
      case index => Some(publicBinding(bindings(index), index))

  private[programs] def idOf(key: ParameterKey): Option[ParameterId] =
    bindings.indexWhere(_.key == key) match
      case -1    => None
      case index => Some(publicId(bindings(index), index))

  def updated(id: ParameterId, value: OptimizableParameters): Either[DspyError, ParameterStore] =
    bindingIndex(id) match
      case None        => Left(NotFoundError("program_parameter", s"Unknown parameter id '${id.value}'"))
      case Some(index) =>
        Right(ParameterStore.fromBindings(bindings.updated(index, bindings(index).copy(value = value))))

  /** Replace a complete parameter set. Missing and extra IDs are errors. */
  def replace(values: Map[ParameterId, OptimizableParameters]): Either[DspyError, ParameterStore] =
    val expected = all.map(_.id).toSet
    val actual   = values.keySet
    if expected != actual then
      val missing = (expected -- actual).toVector.map(_.value).sorted
      val extra   = (actual -- expected).toVector.map(_.value).sorted
      Left(ValidationError(
        s"Parameter replacement IDs do not match; missing=[${missing.mkString(", ")}], extra=[${extra.mkString(", ")}]"
      ))
    else
      Right(ParameterStore.fromBindings(bindings.zipWithIndex.map { case (binding, index) =>
        binding.copy(value = values(publicId(binding, index)))
      }))

  /** Save optimizer-writable values with a deterministic declaration-shape fingerprint. */
  def dumpState: DynamicValue.Record =
    val shape: (String, DynamicValue) = StateShapeKey -> DynamicValue.Primitive(
      PrimitiveValue.String(shapeFingerprint)
    )
    val values: Vector[(String, DynamicValue)] = all.map(binding => binding.id.value -> binding.value.dumpState)
    DynamicValue.Record(Chunk.from(shape +: values))

  /** Load values into this program's declared slots. Unknown, missing, duplicate, or malformed entries fail. */
  def loadState(state: DynamicValue.Record): Either[DspyError, ParameterStore] =
    val names = state.fields.map(_._1)
    if names.distinct.size != names.size then
      Left(ValidationError("Parameter state contains duplicate IDs"))
    else
      for
        storedShape <- state.fields.find(_._1 == StateShapeKey).map(_._2) match
                         case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) => Right(value)
                         case Some(_)                                                    => Left(ValidationError(s"Parameter state '$StateShapeKey' must be a string"))
                         case None                                                       => Left(ValidationError(s"Parameter state is missing '$StateShapeKey'"))
        _ <- Either.cond(
               storedShape == shapeFingerprint,
               (),
               ValidationError("Parameter state does not match this program's prediction declarations")
             )
        values <- state.fields.iterator
                    .filter(_._1 != StateShapeKey)
                    .foldLeft[Either[DspyError, Map[ParameterId, OptimizableParameters]]](Right(Map.empty)) {
                      case (acc, (rawId, rawValue)) =>
                        for
                          values <- acc
                          id     <- ParameterId.stored(rawId)
                          record <- rawValue match
                                      case value: DynamicValue.Record => Right(value)
                                      case _                          => Left(ValidationError(
                                          s"Parameter state '${id.value}' must be a record"
                                        ))
                          value <- OptimizableParameters.fromState(record)
                        yield values.updated(id, value)
                    }
        loaded <- replace(values)
      yield loaded

  private[programs] def merge(that: ParameterStore): ParameterStore =
    that.bindings.foldLeft(this) { (current, incoming) =>
      current.bindings.find(_.key == incoming.key) match
        case None           => ParameterStore.fromBindings(current.bindings :+ incoming)
        case Some(existing) =>
          val id = incoming.key match
            case ParameterKey.Stable(value) => value.value
            case _: ParameterKey.Anonymous  => current.idOf(incoming.key).fold("anonymous")(_.value)
          require(
            existing.metadata == incoming.metadata && existing.value == incoming.value,
            s"Parameter id '$id' was composed with different metadata or values"
          )
          current
    }

  override def equals(other: Any): Boolean =
    other match
      case that: ParameterStore => all == that.all
      case _                    => false

  override def hashCode(): Int = all.hashCode()

  override def toString: String = s"ParameterStore(${all.mkString(", ")})"

  private def bindingIndex(id: ParameterId): Option[Int] =
    bindings.indices.find(index => publicId(bindings(index), index) == id)

  private def publicBinding(binding: StoredParameterBinding, index: Int): ParameterBinding =
    ParameterBinding(publicId(binding, index), binding.metadata, binding.value)

  private def publicId(binding: StoredParameterBinding, index: Int): ParameterId =
    binding.key match
      case ParameterKey.Stable(id)   => id
      case _: ParameterKey.Anonymous => ParameterId.anonymous(index)

  private def shapeFingerprint: String =
    val declaration = all
      .sortBy(_.id.value)
      .map(binding =>
        s"${binding.id.value}\u0000${binding.metadata.moduleName}\u0000${binding.metadata.structure.dumpJson}"
      )
      .mkString("\u0001")
    MessageDigest
      .getInstance("SHA-256")
      .digest(declaration.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

object ParameterStore:
  val empty: ParameterStore = fromBindings(Vector.empty)

  private[programs] def single(
      key     : ParameterKey,
      metadata: OptimizableMetadata,
      value   : OptimizableParameters
  ): ParameterStore = fromBindings(Vector(StoredParameterBinding(key, metadata, value)))

  private def fromBindings(bindings: Vector[StoredParameterBinding]): ParameterStore =
    new ParameterStore(bindings)
