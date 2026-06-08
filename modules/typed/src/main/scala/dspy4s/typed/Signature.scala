package dspy4s.typed

import dspy4s.core.contracts.{DspyError, FieldRole, SignatureLayout, TypeRef}
import zio.blocks.schema.{DynamicValue, Schema}

/** A signature with compile-time knowledge of its input (`I`) and output
  * (`O`) shapes. Wraps an untyped `SignatureLayout` for compatibility with the
  * existing `DynamicPredict` / adapter / runtime stack — adapters still see the
  * runtime `fields` vector and consume it as today; the typed layer
  * additionally carries `Shape[I]` / `Shape[O]` for input encoding and
  * output decoding.
  *
  * Phase 2 supports `I <: Product` / `O <: Product` (case classes). Phase 3
  * adds the builder API.
  */
final case class Signature[I, O](
    name: String,
    layout: SignatureLayout,
    inputShape: Shape[I],
    outputShape: Shape[O]
):

  /** SignatureLayout-level instructions carried into adapter prompt formatting.
    * This mirrors the underlying untyped `SignatureLayout` API while preserving
    * the typed input/output shapes.
    */
  def instructions: Option[String] = layout.instructions

  /** Replace signature-level instructions. Empty strings keep the current
    * untyped `SignatureLayout.withInstructions` behavior and leave the signature
    * unchanged.
    */
  def withInstructions(text: String): Signature[I, O] =
    copy(layout = layout.withInstructions(text))

  /** Replace or clear signature-level instructions. */
  def withInstructions(text: Option[String]): Signature[I, O] =
    copy(layout = layout.withInstructions(text))

  /** Mark the named OUTPUT field as the native tool-calls sink: sets its wire [[TypeRef]] to
    * [[TypeRef.toolCalls]]. An adapter with native function-calling enabled then fills that field from the
    * provider's `tool_calls` rather than asking the model to produce it as text. The typed decode of the field
    * (a `Vector[ToolCall]`) happens via `O`'s derived `Schema`, so this only adjusts the runtime layout — the
    * shapes are untouched. No-op if no output field has the given name. See PORT_GAPS G-7b.
    *
    * Needed because the typed derivation maps field types structurally (a `Vector[ToolCall]` becomes
    * `TypeRef.list`) and `zio-blocks` `Reflect` does not expose a type name to auto-detect the tool-calls type. */
  def markToolCalls(fieldName: String): Signature[I, O] =
    copy(layout = layout.withFields(layout.fields.map { field =>
      if field.role == FieldRole.Output && field.name == fieldName then field.copy(typeRef = TypeRef.toolCalls)
      else field
    }))

object Signature:

  /** Derives a `Signature[I, O]` from two case classes. The resulting
    * untyped signature has all input fields followed by all output fields
    * (matching the `inputFields ++ outputFields` ordering used everywhere
    * else in dspy4s).
    *
    * Routed through `SignatureLayout.create` so fields are normalized
    * (inferred prefix + description) and standard validations apply.
    * Case-class field names are always valid Scala identifiers, so the
    * `.fold(throw _, identity)` is a defensive measure -- it should
    * never fire for a well-formed case class. */
  inline def derived[I <: Product, O <: Product](
      name: String,
      instructions: String = ""
  )(using
      mi: scala.deriving.Mirror.ProductOf[I],
      mo: scala.deriving.Mirror.ProductOf[O],
      inputSchema: Schema[I],
      outputSchema: Schema[O]
  ): Signature[I, O] =
    val inShape  = Shape.derivedWithRole[I](FieldRole.Input)
    val outShape = Shape.derivedWithRole[O](FieldRole.Output)
    val fields   = inShape.fieldSpecs ++ outShape.fieldSpecs
    val sig = SignatureLayout
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
    Signature(name, sig, inShape, outShape)

  /** Programmatic builder for callers that don't want a case class per
    * signature. Returns a plain `SignatureLayout` — see `SignatureBuilder`. */
  def builder(name: String): SignatureBuilder = SignatureBuilder(name)

  /** Parse a DSPy-style string DSL **literal** (`"question -> answer"`) into a fully typed `Signature` at compile
    * time. The string is parsed during macro expansion (reusing [[SignatureLayout.parse]]) and each field becomes
    * a named-tuple element: untyped fields default to `String`; `int` / `float` / `bool` map to the matching
    * scalar. So `Signature.fromString("question -> answer: bool")` is a
    * `Signature[(question: String), (answer: Boolean)]` with typed dot-access on predictions.
    *
    * The DSL must be a compile-time literal and is validated at compile time (an invalid string, or a field type
    * outside `str` / `int` / `float` / `bool`, is a compile error). For a signature built from a **runtime**
    * string (no static types, `DynamicValue.Record` I/O), use [[fromStringDynamic]]. */
  transparent inline def fromString(inline dsl: String, inline instructions: String = "") =
    ${ internal.FunctionMacro.fromStringImpl('dsl, 'instructions) }

  /** Parse a DSPy-style string DSL into a `Signature` whose input/output shapes are the raw spine type
    * `DynamicValue.Record`. Unlike [[fromString]], this accepts a **runtime** string (the DSL carries no static
    * schema, so I/O stays at the spine type). Use `DynamicValues.recordFromEntries(...)` to build inputs.
    *
    * For static type-safe inputs and outputs, prefer the compile-time [[fromString]], `Signature.fromType[F]`,
    * `Signature.from(method)`, or `Signature.of[T <: Spec]`. */
  def fromStringDynamic(
      dsl: String,
      instructions: String = ""
  ): Either[DspyError, Signature[DynamicValue.Record, DynamicValue.Record]] =
    SignatureLayout.parse(dsl, instructions).map { layout =>
      val inShape  = Shape.MapShape(layout.inputFields)
      val outShape = Shape.MapShape(layout.outputFields)
      Signature(layout.name, layout, inShape, outShape)
    }

  /** Function/method macro entry. Inspects a method reference at compile
    * time and materializes a `Signature` whose inputs come from the
    * method parameters and whose outputs come from the method return type.
    *
    * Output rules:
    *   - named tuple returns keep their names, e.g. `(sentiment: Emotion)`
    *   - case-class / product returns keep their product field names
    *   - scalar returns become a single output field named `result`
    */
  transparent inline def from[F](inline fn: F) =
    ${ internal.FunctionMacro.fromImpl[F]('fn) }

  /** Anonymous type-only function signature macro. This is the
    * declaration-only companion to `from(method)`: no throwaway `???`
    * method is needed.
    *
    * Input rules:
    *   - named function parameters keep their names,
    *     e.g. `(sentence: String) => Emotion`
    *   - one anonymous input becomes `input`, e.g. `String => Emotion`
    *   - multiple anonymous inputs become `input1`, `input2`, ...
    *
    * Output rules match `from(method)`.
    */
  transparent inline def fromType[F] =
    ${ internal.FunctionMacro.fromTypeImpl[F]('{ "Signature" }, '{ "" }) }

  /** Type-only function signature macro with optional runtime name and
    * instructions. The name defaults to `"Signature"` for DSPy-style
    * anonymous signatures.
    */
  transparent inline def fromType[F](
      inline name: String = "Signature",
      inline instructions: String = ""
  ) =
    ${ internal.FunctionMacro.fromTypeImpl[F]('name, 'instructions) }

  /** Trait-as-spec macro entry. Inspects an abstract `Spec` trait at
    * compile time and materializes a `Signature` whose untyped
    * `SignatureLayout` reflects the trait's `InputField` / `OutputField` members.
    *
    * The precise input and output types are named tuples. That lets users
    * construct inputs with named-tuple syntax and read outputs with typed
    * dot-access while the runtime still flows through the same `Shape` /
    * `DynamicPredict` / adapter pipeline as case-class signatures. */
  transparent inline def of[T <: Spec] =
    ${ internal.SpecMacro.ofImpl[T]('{ "" }, '{ "" }) }

  /** Trait-as-spec macro entry with optional runtime name and instructions.
    * The name defaults to the spec trait name when empty.
    */
  transparent inline def of[T <: Spec](
      inline name: String = "",
      inline instructions: String = ""
  ) =
    ${ internal.SpecMacro.ofImpl[T]('name, 'instructions) }
