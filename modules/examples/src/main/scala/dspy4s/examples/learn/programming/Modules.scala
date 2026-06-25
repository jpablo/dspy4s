/**
 * Modules
 *
 * Source:   docs/docs/learn/programming/modules.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/modules.md
 * Status:   translated (portable snippets; multi-completion `n=5` access noted as unsupported)
 *
 * Translation rule (see Signatures.scala): string-DSL Python signatures become typed
 * `Signature.fromString("…")` (parsed to a NamedTuple at compile time); each snippet is a
 * self-contained example object exposing a `call(...)` that wires the program.
 */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.{ChainOfThought, Predict}
import dspy4s.typed.Signature

object Modules:

  // ── Snippet 1 (lines 22–33) ────────────────────
  // | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
  // |
  // | # 1) Declare with a signature.
  // | classify = dspy.Predict('sentence -> sentiment: bool')
  // |
  // | # 2) Call with input argument(s).
  // | response = classify(sentence=sentence)
  // |
  // | # 3) Access the output.
  // | print(response.sentiment)
  object SentimentExample:
    val classify = Predict(Signature.fromString("sentence -> sentiment: bool"))

    def call(sentence: String)(using RuntimeContext): Either[DspyError, Boolean] =
      classify.apply((sentence = sentence)).map(_.output.sentiment)

  // ── Snippets 2 + 3 (lines 45–73) ────────────────
  // | question = "What's something great about the ColBERT retrieval model?"
  // |
  // | # 1) Declare with a signature, and pass some config.
  // | classify = dspy.ChainOfThought('question -> answer', n=5)
  // |
  // | # 2) Call with input argument.
  // | response = classify(question=question)
  // |
  // | # 3) Access the outputs.
  // | response.completions.answer
  // | print(f"Reasoning: {response.reasoning}")
  // | print(f"Answer: {response.answer}")
  //
  // Note: Python's `n=5` samples multiple completions and exposes them via
  // `response.completions.answer` / `response.completions[3]`. dspy4s's typed
  // ChainOfThought returns a single prediction; multi-completion access (snippet 4) is
  // not part of the typed surface. The single-completion shape is shown here.
  // --8<-- [start:chain-of-thought]
  object QaReasoningExample:
    val classify = ChainOfThought(Signature.fromString("question -> answer"))

    /** Returns the corrected reasoning and the answer (ChainOfThought prepends `reasoning`). */
    def call(question: String)(using RuntimeContext): Either[DspyError, (String, String)] =
      classify.apply((question = question)).map(p => (p.output.reasoning, p.output.answer))
  // --8<-- [end:chain-of-thought]

  // ── Snippet 4 (lines 84–86) ────────────────────
  // | response.completions[3].reasoning == response.completions.reasoning[3]
  // Not portable: dspy4s has no typed multi-completion (`n`) surface — a typed prediction
  // carries a single decoded output. The raw completions remain on `prediction.raw.completions`.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
@main def modulesMain(): Unit = Demo.withLm {
  println("Sentiment: " + Modules.SentimentExample.call("it's a charming and often affecting journey."))
  println("QA:        " + Modules.QaReasoningExample.call("What's something great about the ColBERT retrieval model?"))
}
