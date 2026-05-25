package dspy4s.typed

import dspy4s.core.contracts.{FieldRole, Signature, SignatureSpec}

/** A signature with compile-time knowledge of its input (`I`) and output
  * (`O`) shapes. Wraps an untyped `Signature` for compatibility with the
  * existing `Predict` / adapter / runtime stack — adapters still see the
  * runtime `fields` vector and consume it as today; the typed layer
  * additionally carries `Shape[I]` / `Shape[O]` for input encoding and
  * output decoding.
  *
  * Phase 2 supports `I <: Product` / `O <: Product` (case classes). Phase 3
  * adds the builder API.
  */
final case class TypedSignature[I, O](
    name: String,
    untyped: Signature,
    inputShape: Shape[I],
    outputShape: Shape[O]
)

object TypedSignature:

  /** Derives a `TypedSignature[I, O]` from two case classes. The resulting
    * untyped signature has all input fields followed by all output fields
    * (matching the `inputFields ++ outputFields` ordering used everywhere
    * else in dspy4s). */
  inline def derived[I <: Product, O <: Product](
      name: String,
      instructions: String = ""
  )(using
      mi: scala.deriving.Mirror.ProductOf[I],
      mo: scala.deriving.Mirror.ProductOf[O]
  ): TypedSignature[I, O] =
    val inShape  = Shape.derivedWithRole[I](FieldRole.Input)
    val outShape = Shape.derivedWithRole[O](FieldRole.Output)
    val fields   = inShape.fieldSpecs ++ outShape.fieldSpecs
    val sig = SignatureSpec(
      name = name,
      fields = fields,
      instructions = Option(instructions).filter(_.nonEmpty)
    )
    TypedSignature(name, sig, inShape, outShape)

  /** Programmatic builder for callers that don't want a case class per
    * signature. Returns a plain `Signature` — see `SignatureBuilder`. */
  def builder(name: String): SignatureBuilder = SignatureBuilder(name)
