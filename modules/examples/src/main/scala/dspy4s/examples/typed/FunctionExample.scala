/**
 * Typed signatures — method/function surface.
 *
 * A Scala function type can declare a dspy4s typed signature without a
 * throwaway method body. `TypedSignature.fromType[F](name)` inspects the
 * function type at compile time and lowers it into the same typed runtime
 * path used by trait specs and case classes.
 *
 * If an implementation method already exists, `TypedSignature.from(method)`
 * can inspect that method's signature directly.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.typed.TypedSignature

object FunctionExample:

  /** Named-tuple output: signature string is `sentence -> sentiment`. */
  val emotion =
    TypedSignature.fromType[(sentence: String) => (sentiment: Emotion)]("Emotion")

  /** Multi-output named tuple: signature string is
    * `sentence -> sentiment, confidence`. */
  val scored =
    TypedSignature.fromType[
      (sentence: String) => (sentiment: Emotion, confidence: Double)
    ]("ScoredEmotion")

  /** Anonymous input and scalar output: signature string is
    * `input -> result`. */
  val anonymous =
    TypedSignature.fromType[String => Emotion]("AnonymousEmotion")

  def classify(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.TypedPredict
    TypedPredict(emotion)
      .run((sentence = sentence))
      .map(_.output.sentiment)
