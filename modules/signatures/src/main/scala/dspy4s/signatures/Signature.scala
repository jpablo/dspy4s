package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, SignatureLayout, SignatureOps, ValidationError}
import dspy4s.signatures.OutputAugmentation.PrependField
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

  // The field operations below change the layout and the shapes in one call, thus the two always agree on the names
  // and the order of the fields. A composite program (ChainOfThought, ReAct, MultiChainComparison) uses them. Each
  // operation keeps the effective instructions of this signature. The layout operations of `SignatureOps` have the
  // names of these methods, thus the calls name the object.

  /** This signature with the `String` input `field` after the inputs. The input is the pair of the base input and the
    * value of `field`. The input shape encodes the base value with the base input shape, and then adds `field`.
    *
    * @throws IllegalArgumentException
    *   when this signature has a field (an input or an output) with the name of `field`. A composite program adds the
    *   field, thus a name clash is a bug of the program.
    */
  def appendInput(field: FieldSpec): Signature[(I, String), O] =
    requireNewFields(Vector(field))
    Signature(SignatureOps.appendInput(layout)(field), Shape.pair(inputShape, Shape.string(field, name)), outputShape)

  /** This signature with the `String` inputs `fields` after the inputs, for a number of fields that is known only at
    * run time. The result holds the new signature and the type of its appended values. See
    * [[Signature.AppendedStringInputs]].
    *
    * @throws IllegalArgumentException
    *   when this signature has a field with the name of a field of `fields`, or when two fields of `fields` have the
    *   same name
    */
  def appendInputs(fields: Vector[FieldSpec]): Signature.AppendedStringInputs[I, O] =
    requireNewFields(fields)
    new Signature.AppendedStringInputs(this, fields)

  /** This signature with the `String` output `field` first. `fieldName` is the name of `field`, and its singleton type
    * is the label of the field in `Out`. The `PrependField` evidence gives the output type `Out`. Usually `Out` is the
    * named tuple `WithField[O, Name, String]` of [[OutputAugmentation]].
    *
    * When the outputs already have a field with this name, the layout, the output shape, and the output value stay the
    * same. Else the layout gets `field` first in the outputs, and the output shape puts `field` first. The JSON schema
    * of the output shape is the schema of the base output shape with the property of `field` first. The decode of an
    * output with no static fields (the `DynamicValue.Record` of [[fromStringDynamic]]) gives a `ValidationError`.
    *
    * @throws IllegalArgumentException
    *   when the name of `field` is not `fieldName`, or when this signature has an input with that name
    */
  def prependOutput[Name <: String & Singleton, Out](fieldName: Name, field: FieldSpec)(using
      PrependField.WithOutput[Name, String, O, Out]
  ): Signature[I, Out] =
    require(
      field.name == (fieldName: String),
      s"The field '${field.name}' does not have the name '$fieldName'"
    )
    require(
      !layout.inputFields.exists(_.name == field.name),
      s"The signature '$name' cannot prepend the output '${field.name}': it has an input with that name"
    )
    Signature(
      SignatureOps.prependOutput(layout)(field),
      inputShape,
      OutputAugmentation.prepended(outputShape, field, name)
    )

  /** This signature with the outputs `fields` and the output shape `shape` in place of its outputs. The inputs stay.
    * The loop step of `ReAct` uses it: the step has its own outputs, and the extractor gives the base outputs.
    *
    * @throws IllegalArgumentException
    *   when the names of `shape` are not the names of `fields` in the same order, when two fields of `fields` have the
    *   same name, or when this signature has an input with the name of a field of `fields`
    */
  def withOutputs[O2](fields: Vector[FieldSpec], shape: Shape[O2]): Signature[I, O2] =
    val names = fields.map(_.name)
    require(
      shape.fieldSpecs.map(_.name) == names,
      s"The output shape of '$name' has the fields ${shape.fieldSpecs.map(_.name).mkString(", ")}, not " +
        names.mkString(", ")
    )
    require(names.distinct.size == names.size, s"The signature '$name' cannot have two outputs with the same name")
    val inputNames = layout.inputFields.map(_.name).toSet
    val clashes    = names.filter(inputNames.contains)
    require(
      clashes.isEmpty,
      s"The signature '$name' cannot have the outputs ${clashes.mkString(", ")}: they are inputs"
    )
    Signature(SignatureOps.replaceOutputs(layout)(fields), inputShape, shape)

  /** Require that the names of `fields` are unique, and that this signature has no field with one of these names. */
  private def requireNewFields(fields: Vector[FieldSpec]): Unit =
    val taken   = (layout.fields ++ inputShape.fieldSpecs ++ outputShape.fieldSpecs).map(_.name).toSet
    val names   = fields.map(_.name)
    val clashes = names.filter(taken.contains)
    require(
      clashes.isEmpty,
      s"The signature '$name' cannot append the input fields ${clashes.mkString(", ")}: it has them"
    )
    require(
      names.distinct.size == names.size,
      s"The signature '$name' cannot append two input fields with the same name"
    )

object Signature:

  /** A signature with appended `String` inputs, for a number of inputs that is known only at run time.
    * [[Signature.appendInputs]] makes it. The value type [[Values]] of the appended inputs depends on this instance.
    * Only [[validate]] gives a [[Values]], thus the input shape of [[signature]] always gets one value for each
    * appended field.
    */
  final class AppendedStringInputs[I, O] private[Signature] (base: Signature[I, O], fields: Vector[FieldSpec]):
    opaque type Values = Vector[String]

    /** The values of the appended inputs, when `values` has one value for each appended field. */
    def validate(values: Vector[String]): Either[DspyError, Values] =
      if values.size == fields.size then Right(values)
      else
        Left(ValidationError(
          s"The signature '${base.name}' requires exactly ${fields.size} appended values, got ${values.size}"
        ))

    /** The base signature with the appended inputs after the inputs of the base. */
    val signature: Signature[(I, Values), O] = Signature(
      fields.foldLeft(base.layout)(SignatureOps.appendInput(_)(_)),
      Shape.pair(base.inputShape, Shape.strings(fields, base.name)),
      base.outputShape
    )

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
