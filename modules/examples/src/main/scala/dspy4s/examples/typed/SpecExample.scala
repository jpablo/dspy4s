/**
 * Typed signatures — trait-as-spec surface.
 *
 * The trait-spec surface is the closest port of DSPy's Python
 * `class Foo(dspy.Signature)` style. A trait extending `Spec` declares
 * input/output fields as abstract method members tagged with
 * `InputField[T]` / `OutputField[T]`. The macro `Signature.of[T]`
 * walks the trait at compile time, validates each member, summons a
 * `FieldCodec[T]` for the wrapped type, and emits the runtime
 * `Signature`.
 *
 * `Signature.of[T]` exposes named-tuple input/output types, so callers
 * get typed construction and typed dot-access while keeping the DSPy-style
 * declaration as the source of truth.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.typed.{InputField, OutputField, Spec, Signature}
import kyo.Schema

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]   // Emotion is the enum from CaseClassExample.scala

trait QASpec extends Spec:
  def question: InputField[String]
  def context:  InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

case class Source(title: String, score: Double) derives Schema
case class CitedAnswer(answer: String, sources: List[Source]) derives Schema

trait CitedQASpec extends Spec:
  def question: InputField[String]
  def result:   OutputField[CitedAnswer]

object SpecExample:

  /** Spec-derived signature for the emotion-classification task. The
    * resulting `untyped` SignatureSchema is structurally identical to the one
    * produced by `CaseClassExample.signature.untyped` and to one built
    * via `Signature.builder("Emotion").input[String]("sentence")...`.
    * Cross-surface parity is proven by the typed module's test suite. */
  val emotion = Signature.of[EmotionSpec]

  /** A multi-field QA spec. Field order, names, types, normalized prefixes,
    * and enum metadata all flow from the trait declarations. */
  val qa = Signature.of[QASpec]

  /** A structured-output spec. Nested products, lists, and primitive
    * coercions are decoded through kyo-schema. */
  val citedQa = Signature.of[CitedQASpec]

  /** Illustrative call: with an LM and adapter configured, run the
    * spec-derived signature against a named-tuple input and read the
    * typed named-tuple output with dot syntax. */
  def callEmotion(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.TypedPredict
    TypedPredict(emotion).run((sentence = sentence)).map(_.output.sentiment)
