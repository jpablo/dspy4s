package dspy4s.typed

import dspy4s.core.contracts.{
  FieldRole, FieldSpec, Signature, SignatureSpec
}

/** Fluent, type-driven builder for runtime `Signature` values.
  *
  * The case-class derivation in `TypedSignature.derived[I, O]` is the
  * primary typed-I/O surface; this builder is the complementary
  * **programmatic** surface for callers that don't want to introduce a
  * case class per signature (REPL exploration, dynamic shapes assembled
  * from config, tests).
  *
  * Each `.input[T]` / `.output[T]` call summons a `FieldCodec[T]` to
  * derive the `TypeRef` and any well-known metadata (e.g. enum allowed
  * cases) for the resulting `FieldSpec`. The same primitives + Scala enum
  * support that case-class derivation gets in Phase 2 are available here
  * without writing a case class.
  *
  * Returns a plain `Signature` from `.build`; callers needing typed
  * `Predict.run` should use `TypedSignature.derived[I, O]` instead.
  */
final class SignatureBuilder private[typed] (
    private val sigName: String,
    private val inputs: Vector[FieldSpec],
    private val outputs: Vector[FieldSpec],
    private val instructionsText: Option[String]
):

  /** Append an input field typed `T`. Order of `.input` calls becomes the
    * input-field order in the resulting `Signature`. */
  def input[T](name: String)(using dec: FieldCodec[T]): SignatureBuilder =
    copy(inputs = inputs :+ fieldSpec(name, FieldRole.Input, dec))

  /** Append an output field typed `T`. Order of `.output` calls becomes the
    * output-field order in the resulting `Signature`. */
  def output[T](name: String)(using dec: FieldCodec[T]): SignatureBuilder =
    copy(outputs = outputs :+ fieldSpec(name, FieldRole.Output, dec))

  /** Replace the signature-level instructions. Empty strings become `None`. */
  def instructions(text: String): SignatureBuilder =
    copy(instructionsText = Option(text).filter(_.nonEmpty))

  /** Finalize the builder into a runtime `Signature`. The resulting fields
    * are all inputs followed by all outputs, matching the ordering used by
    * the rest of dspy4s.
    *
    * Routed through `SignatureSpec.create` so the resulting fields are
    * normalized (inferred prefix + description) and standard validations
    * apply (non-empty fields, unique names, valid identifiers). Invalid
    * input from the builder surfaces as `IllegalArgumentException` —
    * this is a programmer-error path, not user-input handling. */
  def build: Signature =
    SignatureSpec
      .create(
        name         = sigName,
        fields       = inputs ++ outputs,
        instructions = instructionsText
      )
      .fold(
        err => throw new IllegalArgumentException(s"Invalid signature '$sigName': ${err.message}"),
        identity
      )

  private def fieldSpec(name: String, role: FieldRole, dec: FieldCodec[?]): FieldSpec =
    FieldSpec(
      name     = name,
      role     = role,
      typeRef  = dec.typeRef,
      metadata = dec.metadata
    )

  private def copy(
      inputs: Vector[FieldSpec] = this.inputs,
      outputs: Vector[FieldSpec] = this.outputs,
      instructionsText: Option[String] = this.instructionsText
  ): SignatureBuilder =
    new SignatureBuilder(sigName, inputs, outputs, instructionsText)

object SignatureBuilder:
  /** Start a new builder. Prefer `TypedSignature.builder(name)` at call
    * sites — same factory, more discoverable from the typed namespace. */
  def apply(name: String): SignatureBuilder =
    new SignatureBuilder(name, Vector.empty, Vector.empty, None)
