package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, SignatureLayout, TypeRef}
import zio.blocks.schema.DynamicValue

/** A signature with the static types of its input (`I`) and its output (`O`). It holds the runtime `SignatureLayout`,
  * which the adapters and the runtime read, and the shapes that encode the input and decode the output.
  */
final case class Signature[I, O](
    layout     : SignatureLayout,
    inputShape : Shape[I],
    outputShape: Shape[O]
):

  /** The name of the signature: the name of its layout. */
  def name: String = layout.name

  /** SignatureLayout-level instructions carried into adapter prompt formatting. This mirrors the underlying runtime
    * `SignatureLayout` API while preserving the input/output shapes.
    */
  def instructions: Option[String] = layout.instructions

  /** Replace signature-level instructions. Empty strings keep the current runtime `SignatureLayout.withInstructions`
    * behavior and leave the signature unchanged.
    */
  def withInstructions(text: String): Signature[I, O] =
    copy(layout = layout.withInstructions(text))

  /** Replace or clear signature-level instructions. */
  def withInstructions(text: Option[String]): Signature[I, O] =
    copy(layout = layout.withInstructions(text))

  /** Add `text` after the instructions, with a blank line between them. See [[SignatureLayout.appendInstructions]]. */
  def appendInstructions(text: String): Signature[I, O] =
    copy(layout = layout.appendInstructions(text))

  /** Mark the output field `fieldName` as the field of the native tool calls: its wire [[TypeRef]] becomes
    * [[TypeRef.toolCalls]]. An adapter with native function calls then fills that field from the `tool_calls` of the
    * provider, and the model does not write it as text. The derived `Schema` of `O` decodes the field as a
    * `Vector[ToolCall]`, thus this function changes only the runtime layout, not the shapes. When no output field has
    * the name, the signature stays the same. See PORT_GAPS G-7b.
    *
    * The derivation gives the type of a field from its structure (a `Vector[ToolCall]` becomes `TypeRef.list`), and it
    * does not find the type of the tool calls. Thus this function is necessary.
    */
  def markToolCalls(fieldName: String): Signature[I, O] =
    copy(layout = layout.withOutputFields(layout.outputFields.map { field =>
      if field.name == fieldName then field.copy(typeRef = TypeRef.toolCalls)
      else field
    }))

object Signature:

  /** Derives a `Signature[I, O]` from two case classes. The runtime layout has the input fields, and then the output
    * fields. The derivation is structural and closed: an ambient `Schema[I]` or `Schema[O]` cannot change the meaning
    * of a type at the boundary.
    */
  inline def derived[I <: Product, O <: Product](
      name        : String,
      instructions: String = ""
  )(using
      mi: scala.deriving.Mirror.ProductOf[I],
      mo: scala.deriving.Mirror.ProductOf[O]
  ): Signature[I, O] =
    fromShapes(name, instructions, Shape.canonicalDerived[I], Shape.canonicalDerived[O])

  /** The signature `name` with the inputs of `in` and the outputs of `out`. An empty `instructions` means no
    * instructions. The layout comes from `SignatureLayout.create`, which normalizes the fields (the prefix and the
    * description) and validates them. Each signature macro expands to a call of this function in the code of the
    * caller, thus the function is public.
    *
    * @throws IllegalArgumentException
    *   when the layout is not valid, for example when an input and an output have the same name
    */
  def fromShapes[I, O](name: String, instructions: String, in: Shape[I], out: Shape[O]): Signature[I, O] =
    fromFieldSpecs(name, instructions, in.fieldSpecs, out.fieldSpecs, in, out)

  /** The signature `name` with the layout fields `inputs` and `outputs`, and the codec of `in` and `out`. The layout
    * comes from `SignatureLayout.create`, as in [[fromShapes]]. `Signature.fromString` expands to a call of this
    * function: the layout keeps the field specs of the DSL text (for example the enum values of a `Literal` field), and
    * the shapes only encode and decode. Each spec in `inputs` and `outputs` must agree with the field of the same name
    * in the shape (the same name, type, and optional flag).
    *
    * @throws IllegalArgumentException
    *   when the layout is not valid
    */
  def fromFieldSpecs[I, O](
      name        : String,
      instructions: String,
      inputs      : Vector[FieldSpec],
      outputs     : Vector[FieldSpec],
      in          : Shape[I],
      out         : Shape[O]
  ): Signature[I, O] =
    Signature(layoutOf(name, instructions, inputs, outputs), in, out)

  /** The layout `name` with `inputs`, `outputs`, and `instructions`, as [[fromFieldSpecs]] makes it. */
  private[signatures] def layoutOf(
      name        : String,
      instructions: String,
      inputs      : Vector[FieldSpec],
      outputs     : Vector[FieldSpec]
  ): SignatureLayout =
    SignatureLayout
      .create(name, inputs, outputs, Option(instructions).filter(_.nonEmpty))
      .fold(err => throw new IllegalArgumentException(s"Invalid signature '$name': ${err.message}"), identity)

  /** A builder of a runtime `SignatureLayout`, for a caller that does not want a case class for each signature. See
    * [[SignatureBuilder]].
    */
  def builder(name: String): SignatureBuilder = SignatureBuilder(name)

  /** Parse a DSPy-style string DSL **literal** (`"question -> answer"`) into a `Signature` whose I/O types are inferred
    * at compile time. The string is parsed during macro expansion (reusing [[SignatureLayout.parse]]) and each field
    * becomes a named-tuple element: fields without an annotation default to `String`; `int` / `float` / `bool` map to
    * the matching scalar. So `Signature.fromString("question -> answer: bool")` is a
    * `Signature[(question: String), (answer: Boolean)]` with direct dot access on predictions. An input field with the
    * type `History` (`"question, history: History -> answer"`) is a `dspy4s.core.contracts.History` element. An
    * optional field (`"note: Optional[str]"` or `"note: str | None"`) is an `Option` element, and a `Literal['a', 'b']`
    * field is a `String` element. The layout keeps the attributes of the DSL, for example the enum values of a
    * `Literal`.
    *
    * The DSL must be a compile-time literal and is validated at compile time (an invalid string, or a field type
    * outside `str` / `int` / `float` / `bool` and `History` for an input field, is a compile error). For a signature
    * built from a **runtime** string (no static types, `DynamicValue.Record` I/O), use [[fromStringDynamic]].
    */
  transparent inline def fromString(inline dsl: String, inline instructions: String = "") =
    ${ internal.FunctionMacro.fromStringImpl('dsl, 'instructions) }

  /** Parse a DSPy-style string DSL into a `Signature` whose input/output shapes are the raw `DynamicValue.Record`.
    * Unlike [[fromString]], this accepts a **runtime** string (the DSL carries no static schema, so I/O stays
    * `DynamicValue.Record`). Use `DynamicValues.recordFromEntries(...)` to build inputs.
    *
    * For static type-safe inputs and outputs, prefer the compile-time [[fromString]], `Signature.fromType[F]`,
    * `Signature.from(method)`, or `Signature.of[T <: Spec]`.
    */
  def fromStringDynamic(
      dsl         : String,
      instructions: String = ""
  ): Either[DspyError, Signature[DynamicValue.Record, DynamicValue.Record]] =
    SignatureLayout.parse(dsl, instructions).map { layout =>
      Signature(layout, Shape.MapShape(layout.inputFields), Shape.MapShape(layout.outputFields))
    }

  /** Function/method macro entry. Inspects a method reference at compile time and materializes a `Signature` whose
    * inputs come from the method parameters and whose outputs come from the method return type.
    *
    * Output rules:
    *   - named tuple returns keep their names, e.g. `(sentiment: Emotion)`
    *   - case-class / product returns keep their product field names
    *   - scalar returns, decision types (`Noul`, `Score`, `Choice`), and `History` become a single output field named
    *     `result`
    *
    * An input and an output with the same name are a compile error.
    */
  transparent inline def from[F](inline fn: F) =
    ${ internal.FunctionMacro.fromImpl[F]('fn) }

  /** Anonymous type-only function signature macro. This is the declaration-only companion to `from(method)`: no
    * throwaway `???` method is needed.
    *
    * Input rules:
    *   - named function parameters keep their names, e.g. `(sentence: String) => Emotion`
    *   - one anonymous input becomes `input`, e.g. `String => Emotion`
    *   - multiple anonymous inputs become `input1`, `input2`, ...
    *
    * Output rules match `from(method)`.
    */
  transparent inline def fromType[F] =
    ${ internal.FunctionMacro.fromTypeImpl[F]('{ "Signature" }, '{ "" }) }

  /** Type-only function signature macro with optional runtime name and instructions. The name defaults to `"Signature"`
    * for DSPy-style anonymous signatures.
    */
  transparent inline def fromType[F](
      inline name        : String = "Signature",
      inline instructions: String = ""
  ) =
    ${ internal.FunctionMacro.fromTypeImpl[F]('name, 'instructions) }

  /** Trait-as-spec macro entry. Inspects an abstract `Spec` trait at compile time and materializes a `Signature` whose
    * runtime `SignatureLayout` reflects the trait's `InputField` / `OutputField` members.
    *
    * The precise input and output types are named tuples. That lets users construct inputs with named-tuple syntax and
    * read outputs with direct dot access while the runtime still flows through the same `Shape` / `DynamicPredict` /
    * adapter pipeline as case-class signatures.
    */
  transparent inline def of[T <: Spec] =
    ${ internal.SpecMacro.ofImpl[T]('{ "" }, '{ "" }) }

  /** Trait-as-spec macro entry with optional runtime name and instructions. The name defaults to the spec trait name
    * when empty.
    */
  transparent inline def of[T <: Spec](
      inline name        : String = "",
      inline instructions: String = ""
  ) =
    ${ internal.SpecMacro.ofImpl[T]('name, 'instructions) }
