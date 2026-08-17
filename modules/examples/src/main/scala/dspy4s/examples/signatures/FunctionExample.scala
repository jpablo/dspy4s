/** Signatures — method/function surface.
  *
  * A Scala function type can declare a dspy4s signature without a throwaway method body. `Signature.fromType[F]`
  * inspects the function type at compile time and lowers it into the same runtime path used by trait specs and case
  * classes. Runtime name and instructions can be supplied when useful.
  *
  * If an implementation method already exists, `Signature.from(method)` can inspect that method's signature directly.
  *
  * Status: example
  */
package dspy4s.examples.signatures

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.{PredictionBackend, Program}
import dspy4s.signatures.Signature

object FunctionExample:

  /** Named-tuple output: signature string is `sentence -> sentiment`. */
  val emotion = Signature.fromType[(sentence: String) => (sentiment: Emotion)]

  /** Multi-output named tuple: signature string is `sentence -> sentiment, confidence`.
    */
  val scored = Signature.fromType[
    (sentence: String) => (sentiment: Emotion, confidence: Double)
  ]

  /** Anonymous input and scalar output: signature string is `input -> result`.
    */
  val anonymous = Signature.fromType[String => Emotion]

  val classifyProgram = Program.predict(emotion)

  def classify(sentence: String)(using PredictionBackend): Either[DspyError, Emotion] =
    Demo.run(classifyProgram, (sentence = sentence)).map(_.output.sentiment)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.signatures.functionMain"
@main def functionMain(): Unit =
  Demo.withLm {
    println("Function: " + FunctionExample.classify("i started feeling a little vulnerable"))
  }
