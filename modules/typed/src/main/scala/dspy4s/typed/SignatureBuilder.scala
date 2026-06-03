package dspy4s.typed

import dspy4s.core.contracts.{
  FieldRole, FieldSpec, SignatureLayout
}
import zio.blocks.schema.Schema

/** Fluent, type-driven builder for runtime `SignatureLayout` values.
  *
  * The case-class derivation in `Signature.derived[I, O]` is the
  * primary typed-I/O surface; this builder is the complementary
  * **programmatic** surface for callers that don't want to introduce a
  * case class per signature (REPL exploration, dynamic shapes assembled
  * from config, tests).
  *
  * Each `.input[T]` / `.output[T]` call summons a `zio.blocks.schema.Schema[T]` and derives the field's wire
  * `TypeRef` from it (via [[ZioSchemaCodec.typeRefForSchema]]) — the same mapping case-class derivation applies
  * per record field. Any type with a `Schema` (primitives, Scala enums, collections, nested products) works
  * here without writing a case class.
  *
  * Returns a plain `SignatureLayout` from `.build`; callers needing typed
  * `DynamicPredict.apply` should use `Signature.derived[I, O]` instead.
  */
final class SignatureBuilder private[typed] (
    private val sigName: String,
    private val inputs: Vector[FieldSpec],
    private val outputs: Vector[FieldSpec],
    private val instructionsText: Option[String]
):

  /** Append an input field typed `T`. Order of `.input` calls becomes the
    * input-field order in the resulting `SignatureLayout`. */
  def input[T](name: String)(using Schema[T]): SignatureBuilder =
    copy(inputs = inputs :+ fieldSpec(name, FieldRole.Input))

  /** Append an output field typed `T`. Order of `.output` calls becomes the
    * output-field order in the resulting `SignatureLayout`. */
  def output[T](name: String)(using Schema[T]): SignatureBuilder =
    copy(outputs = outputs :+ fieldSpec(name, FieldRole.Output))

  /** Replace the signature-level instructions. Empty strings become `None`. */
  def instructions(text: String): SignatureBuilder =
    copy(instructionsText = Option(text).filter(_.nonEmpty))

  /** Finalize the builder into a runtime `SignatureLayout`. The resulting fields
    * are all inputs followed by all outputs, matching the ordering used by
    * the rest of dspy4s.
    *
    * Routed through `SignatureLayout.create` so the resulting fields are
    * normalized (inferred prefix + description) and standard validations
    * apply (non-empty fields, unique names, valid identifiers). Invalid
    * input from the builder surfaces as `IllegalArgumentException` —
    * this is a programmer-error path, not user-input handling. */
  def build: SignatureLayout =
    SignatureLayout
      .create(
        name         = sigName,
        fields       = inputs ++ outputs,
        instructions = instructionsText
      )
      .fold(
        err => throw new IllegalArgumentException(s"Invalid signature '$sigName': ${err.message}"),
        identity
      )

  private def fieldSpec[T](name: String, role: FieldRole)(using Schema[T]): FieldSpec =
    FieldSpec(
      name    = name,
      role    = role,
      typeRef = ZioSchemaCodec.typeRefForSchema[T]
    )

  private def copy(
      inputs: Vector[FieldSpec] = this.inputs,
      outputs: Vector[FieldSpec] = this.outputs,
      instructionsText: Option[String] = this.instructionsText
  ): SignatureBuilder =
    new SignatureBuilder(sigName, inputs, outputs, instructionsText)

object SignatureBuilder:
  /** Start a new builder. Prefer `Signature.builder(name)` at call
    * sites — same factory, more discoverable from the typed namespace. */
  def apply(name: String): SignatureBuilder =
    new SignatureBuilder(name, Vector.empty, Vector.empty, None)
