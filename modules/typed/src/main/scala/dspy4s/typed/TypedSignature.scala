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
    * else in dspy4s).
    *
    * Routed through `SignatureSpec.create` so fields are normalized
    * (inferred prefix + description) and standard validations apply.
    * Case-class field names are always valid Scala identifiers, so the
    * `.fold(throw _, identity)` is a defensive measure -- it should
    * never fire for a well-formed case class. */
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
    val sig = SignatureSpec
      .create(
        name = name,
        fields = fields,
        instructions = Option(instructions).filter(_.nonEmpty)
      )
      .fold(
        err => throw new IllegalStateException(
          s"Internal error: case-class-derived signature '$name' failed validation: ${err.message}"
        ),
        identity
      )
    TypedSignature(name, sig, inShape, outShape)

  /** Programmatic builder for callers that don't want a case class per
    * signature. Returns a plain `Signature` — see `SignatureBuilder`. */
  def builder(name: String): SignatureBuilder = SignatureBuilder(name)

  /** Function/method macro entry. Inspects a method reference at compile
    * time and materializes a `TypedSignature` whose inputs come from the
    * method parameters and whose outputs come from the method return type.
    *
    * Output rules:
    *   - named tuple returns keep their names, e.g. `(sentiment: Emotion)`
    *   - case-class / product returns keep their product field names
    *   - scalar returns become a single output field named `result`
    */
  transparent inline def from[F](inline fn: F) =
    ${ internal.FunctionMacro.fromImpl[F]('fn) }

  /** Trait-as-spec macro entry. Inspects an abstract `Spec` trait at
    * compile time and materializes a `TypedSignature` whose untyped
    * `Signature` reflects the trait's `InputField` / `OutputField` members.
    *
    * The precise input and output types are named tuples. That lets users
    * construct inputs with named-tuple syntax and read outputs with typed
    * dot-access while the runtime still flows through the same `Shape` /
    * `Predict` / adapter pipeline as case-class signatures. */
  transparent inline def of[T <: Spec] =
    ${ internal.SpecMacro.ofImpl[T] }
