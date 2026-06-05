/**
 * Typed signatures — programmatic builder surface.
 *
 * The builder is the right choice when a case class per signature is
 * overkill (REPL exploration, dynamic shapes assembled from config,
 * tests). It produces a runtime `SignatureLayout` directly; users who want
 * typed `DynamicPredict.apply` should use the trait-spec, method, or case-class
 * APIs instead.
 *
 * Each `.input[T]` / `.output[T]` call summons a `Schema[T]` and derives the
 * resulting `FieldSpec`'s wire `TypeRef` from it.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.SignatureLayout
import dspy4s.typed.Signature

object BuilderExample:

  /** A simple toxicity check signature, built fluently. */
  val toxicity: SignatureLayout =
    Signature
      .builder("Toxicity")
      .input[String]("comment")
      .output[Boolean]("toxic")
      .output[Double]("confidence")
      .instructions(
        "Mark `toxic` as true when the comment includes insults, harassment, " +
        "or derogatory remarks. Report the confidence as a number in [0.0, 1.0]."
      )
      .build

  /** Reusing an enum that has a `Schema` (see CaseClassExample's `Emotion`) gives the builder a
    * `TypeRef.string` field; enum allowed-values reach the LM through the typed Predict path's
    * `Shape.jsonSchemaString` (inlined by `JSONAdapter`). */
  val classifyEmotion: SignatureLayout =
    Signature
      .builder("Emotion")
      .input[String]("sentence")
      .output[Emotion]("sentiment")
      .instructions("Classify emotion in the given sentence.")
      .build

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.typed.builderMain"
@main def builderMain(): Unit =
  println("toxicity outputs:  " + BuilderExample.toxicity.outputFields.map(_.name))
  println("emotion outputs:   " + BuilderExample.classifyEmotion.outputFields.map(_.name))
