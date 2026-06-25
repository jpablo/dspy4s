/**
 * Data
 *
 * Source:   docs/docs/learn/evaluation/data.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/data.md
 * Status:   translated (Example construction + inputs/labels, snippets 1–4). The `DataLoader`
 *           snippets (5–8: from_csv / from_json / from_parquet / from_huggingface) are not portable —
 *           dspy4s has no `datasets` module yet; build `Vector[Example]` directly instead.
 *
 * `dspy.Example(question=..., answer=...)` maps to `Example("question" := ..., "answer" := ...)`; the
 * `with_inputs` / `inputs()` / `labels()` API carries over as `withInputs` / `inputs` / `labels`.
 */
package dspy4s.examples.learn.evaluation

import dspy4s.core.contracts.{:=, DynamicValues, Example}

object Data:

  // ── Snippet 1 (lines 18–24) ────────────────────
  // | qa_pair = dspy.Example(question="This is a question?", answer="This is an answer.")
  // | print(qa_pair.question); print(qa_pair.answer)
  // --8<-- [start:example-basic]
  val qaPair: Example = Example("question" := "This is a question?", "answer" := "This is an answer.")
  // --8<-- [end:example-basic]

  def question: String = qaPair.get("question").map(DynamicValues.renderText).getOrElse("")
  def answer: String   = qaPair.get("answer").map(DynamicValues.renderText).getOrElse("")

  // ── Snippet 2 (lines 40–42) — a trainset is just a Vector[Example] ──
  // | trainset = [dspy.Example(report="LONG REPORT 1", summary="short summary 1"), ...]
  // --8<-- [start:trainset]
  val trainset: Vector[Example] = Vector(
    Example("report" := "LONG REPORT 1", "summary" := "short summary 1").withInputs(Set("report")),
    Example("report" := "LONG REPORT 2", "summary" := "short summary 2").withInputs(Set("report"))
  )
  // --8<-- [end:trainset]

  // ── Snippet 3 (lines 51–57) — mark which fields are inputs ──
  // | qa_pair.with_inputs("question")
  // | qa_pair.with_inputs("question", "answer")   # be careful marking labels as inputs
  val singleInput: Example    = qaPair.withInputs(Set("question"))
  val multipleInputs: Example = qaPair.withInputs(Set("question", "answer"))

  // ── Snippet 4 (lines 63–71) — split a row into inputs vs labels ──
  // | article_summary = dspy.Example(article="...", summary="...").with_inputs("article")
  // | input_key_only = article_summary.inputs(); non_input_key_only = article_summary.labels()
  // --8<-- [start:inputs-labels]
  val articleSummary: Example =
    Example("article" := "This is an article.", "summary" := "This is a summary.").withInputs(Set("article"))
  def inputKeyOnly = articleSummary.inputs   // -> { article: "..." }
  def nonInputOnly = articleSummary.labels   // -> { summary: "..." }
  // --8<-- [end:inputs-labels]

  // ── Snippets 5–8 (lines 83–138) — DataLoader / HuggingFace / splits ──
  // Not portable: dspy4s has no `datasets` / `DataLoader` (`from_csv`, `from_json`, `from_parquet`,
  // `from_pandas`, `from_huggingface`) and no built-in split helpers. Construct `Vector[Example]`
  // directly (as in snippet 2) and slice with ordinary Scala (`xs.take(75)` / `xs.drop(75)`).

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.learn.evaluation.dataMain"
@main def dataMain(): Unit =
  println(s"qa_pair: question='${Data.question}', answer='${Data.answer}'")
  println(s"with_inputs('question','answer') keys: ${Data.multipleInputs.inputKeys}")
  println(s"article inputs: ${Data.inputKeyOnly}")
  println(s"article labels: ${Data.nonInputOnly}")
  println(s"trainset size: ${Data.trainset.size}")
