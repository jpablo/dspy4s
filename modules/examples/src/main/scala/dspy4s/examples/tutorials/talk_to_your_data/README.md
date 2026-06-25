# Talk to Your Data

A type-safe analytics agent. Ask questions about a dataset in plain English and get answers, with the arithmetic done in code and then independently re-checked. Built on dspy4s.

```
Q: Which product category brings in the most money?
  plan:
    Aggregation: SUM of column `total`.
    Group by: category.
    Sort by value descending. Take the top 1.
    Answer with the group LABEL (e.g. the category/region name).
  answer:  Electronics
  method:  Parsed the CSV, summed `total` per category, took the max.
  verify:  JVM cross-check AGREED  (after 1 attempt)
```

## The idea

LLMs are good at understanding a question and unreliable at computing the answer; they tend to make numbers up. This example keeps those two jobs separate and makes each step checkable.

| Stage | What happens | Feature |
|---|---|---|
| **Plan** | The English question becomes a typed [`QueryPlan`](Schemas.scala), a validated Scala value with enums and nested lists. | Typed signatures |
| **Act** | The dataset (10k rows of CSV, too large to put in a prompt) goes into a sandboxed Python REPL, and the model writes code to compute the answer. | RLM |
| **Verify** | The same plan runs again on the JVM in a pure-Scala engine. The two engines have to agree, otherwise the agent refines and retries. | trust |
| **Optimize** | [GEPA](Optimize.scala) evolves the planner's instruction against a grounded metric until it maps questions to the right query reliably. | GEPA |

The answer is computed twice, once by Python in a sandbox and once by Scala on the JVM, and it is only trusted when the two match.

## Why the types matter

The model's intent is a `QueryPlan`, so the rest of the program pattern-matches an enum instead of parsing free text and hoping for valid JSON:

```scala
enum Agg derives Schema, CanEqual:
  case Count, Sum, Average, Min, Max, Median

final case class QueryPlan(agg: Agg, column: Option[String], groupBy: List[String],
  filters: List[Filter], timeRange: Option[TimeRange], sort: Option[Sort],
  limit: Option[Int], answerKind: AnswerKind) derives Schema
```

`derives Schema` gives the codec for free. The same type constrains the LM's output, decodes its reply, drives the optimizer's metric, and is what the JVM verifier executes. Add a field and the compiler points you at every place that needs to change.

## Run it

```bash
# Full demo (GEPA optimization, then the live agent). Needs an OpenAI-compatible key; the agent stage needs Deno.
OPENAI_API_KEY=sk-... DSPY_MODEL=gpt-4o-mini \
  sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.talkToYourDataMain"

# Offline foundation check (no LM, no Deno). Proves the dataset, engine, and gold answers are self-consistent.
sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
```

The GEPA half runs without Deno, because its metric is the Scala engine rather than the sandbox. Only the live agent's act stage needs Deno for the Python REPL. Tune the optimizer with `GEPA_METRIC_CALLS` and `GEPA_MINIBATCH`.

## Files

- [`Schemas.scala`](Schemas.scala): the typed `QueryPlan`, `AnalysisResult`, and `Verdict` (with their enums).
- [`Dataset.scala`](Dataset.scala): a deterministic synthetic e-commerce dataset (about 10k orders) and a 24-question gold set whose answers the engine computes, so the answer key is correct by construction.
- [`QueryEngine.scala`](QueryEngine.scala): the pure-Scala plan evaluator. It serves as both the GEPA oracle and the verify-stage cross-check.
- [`Agent.scala`](Agent.scala): the plan, act, verify, refine loop.
- [`Optimize.scala`](Optimize.scala): the grounded GEPA metric and the optimization driver.
- [`TalkToYourData.scala`](TalkToYourData.scala): the runnable `@main`.
- [`SelfCheck.scala`](SelfCheck.scala): the offline foundation check.

## Notes on scope

- The data is synthetic and deterministic (seeded), so it is license-clean to ship and every gold answer is exact. That is what makes the GEPA before/after a real, reproducible number rather than an LLM-judge estimate.
- The demo's questions reduce to a structured `QueryPlan`, which is why the JVM can re-verify them. RLM is the general executor and can compute things the structured vocabulary cannot express; the structured cases are the ones a second independent engine can hold honest. Verify proves the computation is correct (both engines agree). GEPA's accuracy measures whether the interpretation is right.
- Swap the CSV for a JDBC query and the same agent talks to a real database. The dataset is just text that the act stage crunches.
