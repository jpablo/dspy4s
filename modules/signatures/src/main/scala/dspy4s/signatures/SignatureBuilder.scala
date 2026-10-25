package dspy4s.signatures

import dspy4s.core.contracts.{FieldSpec, SignatureLayout}
import zio.blocks.schema.Schema

/** A fluent builder of runtime `SignatureLayout` values, one field at a time.
  *
  * Use it when a static signature type is not necessary, for example in the REPL, for a layout from a configuration, or
  * in a test. For a typed `Signature[I, O]`, use `Signature.derived`, `Signature.of`, `Signature.from`,
  * `Signature.fromType`, or `Signature.fromString`.
  *
  * Each `.input[T]` or `.output[T]` call gets a `zio.blocks.schema.Schema[T]`, and makes the field spec from it with
  * [[ZioSchemaCodec.fieldSpecOf]]: the wire `TypeRef`, the enum values, and the optional flag. The typed signatures use
  * the same rules for each field of a record. Each type with a `Schema` (a primitive, a Scala enum, a collection, or a
  * nested product) can be a field.
  */
final class SignatureBuilder private[signatures] (
    private val sigName         : String,
    private val inputs          : Vector[FieldSpec],
    private val outputs         : Vector[FieldSpec],
    private val instructionsText: String
):

  /** Append an input field of type `T`. The order of the `.input` calls is the order of the input fields in the
    * `SignatureLayout`.
    */
  def input[T](name: String)(using Schema[T]): SignatureBuilder =
    copy(inputs = inputs :+ fieldSpec(name))

  /** Append an output field of type `T`. The order of the `.output` calls is the order of the output fields in the
    * `SignatureLayout`.
    */
  def output[T](name: String)(using Schema[T]): SignatureBuilder =
    copy(outputs = outputs :+ fieldSpec(name))

  /** Replace the instructions of the signature. An empty text means no instructions. */
  def instructions(text: String): SignatureBuilder =
    copy(instructionsText = text)

  /** The runtime `SignatureLayout`: the input fields, and then the output fields.
    *
    * `SignatureLayout.create` normalizes the fields (the prefix and the description) and validates them: the layout
    * must have a field, the names must be unique, and each name must be a valid identifier. An invalid layout is an
    * error of the programmer, thus it throws an `IllegalArgumentException`.
    */
  def build: SignatureLayout =
    Signature.layoutOf(sigName, instructionsText, inputs, outputs)

  private def fieldSpec[T](name: String)(using schema: Schema[T]): FieldSpec =
    ZioSchemaCodec.fieldSpecOf(name, schema.reflect)

  private def copy(
      inputs          : Vector[FieldSpec] = this.inputs,
      outputs         : Vector[FieldSpec] = this.outputs,
      instructionsText: String            = this.instructionsText
  ): SignatureBuilder =
    new SignatureBuilder(sigName, inputs, outputs, instructionsText)

object SignatureBuilder:
  /** Start a new builder. `Signature.builder(name)` is the same factory, and it is easier to find. */
  def apply(name: String): SignatureBuilder =
    new SignatureBuilder(name, Vector.empty, Vector.empty, "")
