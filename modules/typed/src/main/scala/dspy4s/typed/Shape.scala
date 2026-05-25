package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, FieldRole, FieldSpec, NotFoundError, ValidationError
}
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

/** A schema-aware view of a user type `A`, used as the input or output of a
  * `TypedSignature`. Lists fields in declaration order, converts each to a
  * `FieldSpec` for the untyped `Signature`, encodes typed values into the
  * `Map[String, Any]` carried through `ProgramCall`, and decodes a raw
  * `Map[String, Any]` produced by an adapter back into the typed `A`.
  *
  * Phase 2 supports only `A <: Product` (case classes). Phase 3 will add the
  * builder API for non-case-class shapes.
  */
trait Shape[A]:
  def fieldSpecs: Vector[FieldSpec]
  def encode(value: A): Map[String, Any]
  def decode(raw: Map[String, Any]): Either[DspyError, A]

object Shape:

  /** Derives a `Shape[A]` from any case class whose fields all have a
    * `ValueDecoder` in scope. Compile error if any field lacks a decoder. */
  inline def derived[A <: Product](using m: Mirror.ProductOf[A]): Shape[A] =
    derivedWithRole[A](FieldRole.Output)

  /** Derives a `Shape[A]` and stamps every field with the given role. Use
    * `FieldRole.Input` for input case classes, `FieldRole.Output` for
    * output case classes. `derived` defaults to `Output`; `TypedSignature`
    * builders that need inputs invoke this explicitly. */
  inline def derivedWithRole[A <: Product](role: FieldRole)(using
      m: Mirror.ProductOf[A]
  ): Shape[A] =
    val names    = summonLabels[m.MirroredElemLabels]
    val decoders = summonDecoders[m.MirroredElemTypes]
    new MirrorShape[A](m, names, decoders, role)

  // ── Internal: Mirror-backed Shape implementation ─────────────────────────

  private[typed] final class MirrorShape[A <: Product](
      m: Mirror.ProductOf[A],
      names: List[String],
      decoders: List[ValueDecoder[Any]],
      role: FieldRole
  ) extends Shape[A]:

    val fieldSpecs: Vector[FieldSpec] =
      names.lazyZip(decoders).map { (name, dec) =>
        FieldSpec(
          name     = name,
          role     = role,
          typeRef  = dec.typeRef,
          metadata = dec.metadata
        )
      }.toVector

    def encode(value: A): Map[String, Any] =
      val iter = value.productIterator
      names
        .lazyZip(decoders)
        .map { (name, dec) => name -> dec.encode(iter.next()) }
        .toMap

    def decode(raw: Map[String, Any]): Either[DspyError, A] =
      // Decode each field, short-circuit on first failure.
      val builder = Array.newBuilder[Any]
      builder.sizeHint(names.size)
      val it = names.lazyZip(decoders).iterator
      while it.hasNext do
        val (name, dec) = it.next()
        raw.get(name) match
          case None =>
            return Left(NotFoundError(
              resource = "prediction_field",
              message  = s"Required field '$name' is missing from the raw prediction"
            ))
          case Some(value) =>
            dec.decode(value) match
              case Right(decoded) => builder += decoded
              case Left(err) =>
                return Left(ValidationError(
                  s"Field '$name': ${err.message}"
                ))
      val tuple = Tuple.fromArray(builder.result()).asInstanceOf[m.MirroredElemTypes]
      Right(m.fromProduct(tuple))

  // ── Compile-time summon helpers ──────────────────────────────────────────

  private inline def summonLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h & String] :: summonLabels[t]

  private inline def summonDecoders[T <: Tuple]: List[ValueDecoder[Any]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        summonInline[ValueDecoder[h]].asInstanceOf[ValueDecoder[Any]]
          :: summonDecoders[t]
