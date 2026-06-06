/**
 * Examples in DSPy
 *
 * Source:   docs/docs/deep-dive/data-handling/examples.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/deep-dive/data-handling/examples.md
 * Status:   translated (the full `dspy.Example` API, snippets 1–6). The one shape difference: a dspy4s
 *           `Example` is an immutable `case class`, so Python's in-place `example.context = "..."`
 *           (snippet 5) becomes `withValue("context", ...)`, which returns an updated copy.
 *
 * `dspy.Example(question=...)` → `Example("question" := ...)`; `with_inputs` / `inputs()` / `labels()` /
 * `without(...)` carry over as `withInputs(Set(...))` / `inputs` / `labels` / `without(Set(...))`; iterating
 * `example.items()` is iterating the record's keys.
 */
package dspy4s.examples.deep_dive.data_handling

import dspy4s.core.contracts.{:=, DynamicValues, Example}

object Examples:

  // ── Snippet 1 (lines 19–25) — construct and read fields ──
  // | qa_pair = dspy.Example(question="This is a question?", answer="This is an answer.")
  // | print(qa_pair.question); print(qa_pair.answer)
  val qaPair: Example = Example("question" := "This is a question?", "answer" := "This is an answer.")
  def question: String = qaPair.get("question").map(DynamicValues.renderText).getOrElse("")
  def answer: String   = qaPair.get("answer").map(DynamicValues.renderText).getOrElse("")

  // ── Snippet 2 (lines 45–51) — mark inputs ──
  // | qa_pair.with_inputs("question")
  // | qa_pair.with_inputs("question", "answer")   # be careful marking labels as inputs
  val singleInput: Example    = qaPair.withInputs(Set("question"))
  val multipleInputs: Example = qaPair.withInputs(Set("question", "answer"))

  // ── Snippet 3 (lines 64–72) — split into inputs vs labels ──
  // | article_summary = dspy.Example(article="...", summary="...").with_inputs("article")
  // | input_key_only = article_summary.inputs(); non_input_key_only = article_summary.labels()
  val articleSummary: Example =
    Example("article" := "This is an article.", "summary" := "This is a summary.").withInputs(Set("article"))
  def inputKeyOnly  = articleSummary.inputs   // -> { article: "..." }
  def nonInputOnly  = articleSummary.labels   // -> { summary: "..." }

  // ── Snippet 4 (lines 82–86) — drop fields ──
  // | article_summary = dspy.Example(context=..., question=..., answer=..., rationale=...).with_inputs("context", "question")
  // | article_summary.without("answer", "rationale")
  val fullExample: Example = Example(
    "context"   := "This is an article.",
    "question"  := "This is a question?",
    "answer"    := "This is an answer.",
    "rationale" := "This is a rationale."
  ).withInputs(Set("context", "question"))
  def withoutAnswerRationale: Example = fullExample.without(Set("answer", "rationale"))

  // ── Snippet 5 (lines 95–97) — update a field (immutable copy, not in-place mutation) ──
  // | article_summary.context = "new context"
  def withNewContext: Example = fullExample.withRawValue("context", "new context")

  // ── Snippet 6 (lines 103–106) — iterate key/value pairs ──
  // | for k, v in article_summary.items(): print(f"{k} = {v}")
  def items(example: Example): Vector[(String, String)] =
    DynamicValues.recordKeys(example.values)
      .map(k => k -> example.get(k).map(DynamicValues.renderText).getOrElse(""))

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.deep_dive.data_handling.examplesMain"
@main def examplesMain(): Unit =
  println(s"qa_pair: question='${Examples.question}', answer='${Examples.answer}'")
  println(s"with_inputs('question'): ${Examples.singleInput.inputKeys}")
  println(s"inputs(): ${Examples.inputKeyOnly}")
  println(s"labels(): ${Examples.nonInputOnly}")
  println(s"without(answer, rationale): ${Examples.withoutAnswerRationale.values}")
  println("items:")
  Examples.items(Examples.fullExample).foreach((k, v) => println(s"  $k = $v"))
