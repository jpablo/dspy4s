/**
 * Typed signatures — trait-as-spec surface.
 *
 * The trait-spec surface is the closest port of DSPy's Python
 * `class Foo(dspy.Signature)` style. A trait extending `Spec` declares
 * input/output fields as abstract method members tagged with
 * `InputField[T]` / `OutputField[T]`. The macro `TypedSignature.of[T]`
 * walks the trait at compile time, validates each member, summons a
 * `ValueDecoder[T]` for the wrapped type, and emits the runtime
 * `TypedSignature`.
 *
 * Phase 5 MVP: `I` / `O` are `Map[String, Any]`; typed dot-access on
 * outputs (`result.sentiment`) requires synthesizing case classes from
 * the trait and is deferred. Use this surface for declarative *signature
 * authoring*; use the case-class API when typed I/O matters more than
 * declarative shape.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.typed.{InputField, OutputField, Spec, TypedSignature}

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]   // Emotion is the enum from CaseClassExample.scala

trait QASpec extends Spec:
  def question: InputField[String]
  def context:  InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

object SpecExample:

  /** Spec-derived signature for the emotion-classification task. The
    * resulting `untyped` Signature is structurally identical to the one
    * produced by `CaseClassExample.signature.untyped` and to one built
    * via `TypedSignature.builder("Emotion").input[String]("sentence")...`.
    * Cross-surface parity is proven by the typed module's test suite. */
  val emotion = TypedSignature.of[EmotionSpec]

  /** A multi-field QA spec. Field order, names, types, normalized prefixes,
    * and enum metadata all flow from the trait declarations. */
  val qa = TypedSignature.of[QASpec]

  /** Illustrative call: with an LM and adapter configured, run the
    * spec-derived signature against the raw input map and read the output
    * map. (Typed dot-access on outputs is the Phase 5 follow-up.) */
  def callEmotion(sentence: String)(using RuntimeContext)
      : Either[DspyError, Map[String, Any]] =
    import dspy4s.programs.TypedPredict
    TypedPredict(emotion).run(Map("sentence" -> sentence)).map(_.output)
