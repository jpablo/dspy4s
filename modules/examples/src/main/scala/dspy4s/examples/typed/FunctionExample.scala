/**
 * Typed signatures — method/function surface.
 *
 * A Scala method signature can declare a dspy4s typed signature. The method
 * body is not called; `TypedSignature.from(method)` inspects the method at
 * compile time and lowers it into the same typed runtime path used by trait
 * specs and case classes.
 *
 * Status: example
 */
package dspy4s.examples.typed

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.typed.TypedSignature

def emotionFromSentence(sentence: String): (sentiment: Emotion) = ???

def scoredEmotion(sentence: String): (sentiment: Emotion, confidence: Double) = ???

def anonymousEmotion(sentence: String): Emotion = ???

object FunctionExample:

  /** Named-tuple output: signature string is `sentence -> sentiment`. */
  val emotion = TypedSignature.from(emotionFromSentence)

  /** Multi-output named tuple: signature string is
    * `sentence -> sentiment, confidence`. */
  val scored = TypedSignature.from(scoredEmotion)

  /** Scalar output: signature string is `sentence -> result`. */
  val anonymous = TypedSignature.from(anonymousEmotion)

  def classify(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.TypedPredict
    TypedPredict(emotion)
      .run((sentence = sentence))
      .map(_.output.sentiment)
