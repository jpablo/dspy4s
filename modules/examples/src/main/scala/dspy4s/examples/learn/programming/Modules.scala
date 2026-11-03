/** Modules
  *
  * Source: docs/docs/learn/programming/modules.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/modules.md Status: translated (3 of the 14
  * Python blocks: lines 22–33, 45–56, and 66–69).
  *
  * This file leaves out the other 11 blocks: the tabs Math (line 102), Retrieval-Augmented Generation (117),
  * Classification (139), Information Extraction (164), and Agents (184); the `Hop` module (211); and the five blocks
  * that track the LM usage (246, 252, 258, 286, 311). tutorials/cache shows `UsageTracking`.
  *
  * Translation rule (see Signatures.scala): string-DSL Python signatures become `Signature.fromString("…")` (parsed to
  * a NamedTuple at compile time); each snippet is a self-contained example object exposing a `call(...)` that wires the
  * program.
  */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.strategies.{ChainOfThought, Predict}
import dspy4s.signatures.Signature

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
      classify((sentence = sentence)).map(_.output.sentiment)

  // ── Snippets 2 + 3 (lines 45–69) ────────────────
  // | question = "What's something great about the ColBERT retrieval model?"
  // |
  // | # 1) Declare with a signature, and pass some config.
  // | classify = dspy.ChainOfThought('question -> answer', temperature=0.7)
  // |
  // | # 2) Call with input argument.
  // | response = classify(question=question)
  // |
  // | # 3) Access the output.
  // | response.answer
  // |
  // | print(f"Reasoning: {response.reasoning}")
  // | print(f"Answer: {response.answer}")
  //
  // The Scala leaves out `temperature`, because a model can reject the value.
  // --8<-- [start:chain-of-thought]
  object QaReasoningExample:
    val classify = ChainOfThought(Signature.fromString("question -> answer"))

    /** Returns the reasoning and the answer. `ChainOfThought` prepends the `reasoning` output. */
    def call(question: String)(using RuntimeContext): Either[DspyError, (String, String)] =
      classify((question = question)).map(p => (p.output.reasoning, p.output.answer))
  // --8<-- [end:chain-of-thought]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
@main def modulesMain(): Unit =
  Demo.withLm {
    println("Sentiment: " + Modules.SentimentExample.call("it's a charming and often affecting journey."))
    println("QA:        " +
      Modules.QaReasoningExample.call("What's something great about the ColBERT retrieval model?"))
  }
