/**
 * Typed signatures — method/function surface.
 *
 * A Scala function type can declare a dspy4s typed signature without a
 * throwaway method body. `Signature.fromType[F]` inspects the
 * function type at compile time and lowers it into the same typed runtime
 * path used by trait specs and case classes. Runtime name and instructions
 * can be supplied when useful.
 *
 * If an implementation method already exists, `Signature.from(method)`
 * can inspect that method's signature directly.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.typed.Signature

object FunctionExample:

  /** Named-tuple output: signature string is `sentence -> sentiment`. */
  val emotion =
    Signature.fromType[(sentence: String) => (sentiment: Emotion)]

  /** Multi-output named tuple: signature string is
    * `sentence -> sentiment, confidence`. */
  val scored =
    Signature.fromType[
      (sentence: String) => (sentiment: Emotion, confidence: Double)
    ]

  /** Anonymous input and scalar output: signature string is
    * `input -> result`. */
  val anonymous =
    Signature.fromType[String => Emotion]

  def classify(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.TypedPredict
    TypedPredict(emotion)
      .run((sentence = sentence))
      .map(_.output.sentiment)
