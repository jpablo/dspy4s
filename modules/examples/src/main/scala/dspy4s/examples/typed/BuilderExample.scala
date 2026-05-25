/**
 * Typed signatures — programmatic builder surface.
 *
 * The builder is the right choice when a case class per signature is
 * overkill (REPL exploration, dynamic shapes assembled from config,
 * tests). It produces a runtime `SignatureSchema` directly; users who want
 * typed `DynamicPredict.run` should use the trait-spec, method, or case-class
 * APIs instead.
 *
 * Each `.input[T]` / `.output[T]` call summons a `FieldCodec[T]` so the
 * resulting `FieldSpec` carries the right `TypeRef` and any well-known
 * metadata (enum allowed cases, display name, etc.).
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.SignatureSchema
import dspy4s.typed.TypedSignature

object BuilderExample:

  /** A simple toxicity check signature, built fluently. */
  val toxicity: SignatureSchema =
    TypedSignature
      .builder("Toxicity")
      .input[String]("comment")
      .output[Boolean]("toxic")
      .output[Double]("confidence")
      .instructions(
        "Mark `toxic` as true when the comment includes insults, harassment, " +
        "or derogatory remarks. Report the confidence as a number in [0.0, 1.0]."
      )
      .build

  /** Reusing an enum that has a `FieldCodec` (see CaseClassExample's
    * `Emotion`) gives the builder enum metadata for free — adapters can
    * read `FieldSpec.metadata(FieldMetadata.EnumCases)` to render the
    * allowed values into the prompt. */
  val classifyEmotion: SignatureSchema =
    TypedSignature
      .builder("Emotion")
      .input[String]("sentence")
      .output[Emotion]("sentiment")
      .instructions("Classify emotion in the given sentence.")
      .build
