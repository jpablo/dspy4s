# Talk to Your Data

A type-safe analytics agent: ask questions about a dataset in plain English, and get answers with the **arithmetic done in code, not guessed** — then independently re-checked. Built on dspy4s.

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

LLMs are great at *understanding* a question and terrible at *computing* the answer — they hallucinate numbers. This example separates the two, and makes each step trustworthy:

| Stage | What happens | Feature |
|---|---|---|
| **Plan** | The English question becomes a typed [`QueryPlan`](Schemas.scala) — a validated Scala value (enums, nested lists), not a string. | **Typed signatures** |
| **Act** | The dataset (10k rows of CSV — too big to put in a prompt) is injected into a sandboxed Python REPL; the model **writes code** to compute the answer. | **RLM** |
| **Verify** | The *same* plan is re-executed independently on the JVM by a pure-Scala engine. The two engines must agree, or the agent refines and retries. | (trust) |
| **Optimize** | The planner's instruction is evolved by **GEPA** against a grounded metric until it reliably maps questions to the right query. | **GEPA** |

The headline: the answer is computed twice, by two independent engines — Python in a sandbox and Scala on the JVM — and only trusted when they match.

## Why the types matter (the quiet part)

The model's *intent* is a `QueryPlan`, so the rest of the program isn't parsing free text and hoping for valid JSON — it's pattern-matching an enum:

```scala
enum Agg derives Schema, CanEqual:
  case Count, Sum, Average, Min, Max, Median

final case class QueryPlan(agg: Agg, column: Option[String], groupBy: List[String],
  filters: List[Filter], timeRange: Option[TimeRange], sort: Option[Sort],
  limit: Option[Int], answerKind: AnswerKind) derives Schema
```

`derives Schema` means zero hand-written codec; the same type constrains the LM's output, decodes its reply, drives the optimizer's metric, **and** is what the JVM verifier executes. Add a field and the compiler walks you through every place that needs to change. That's not a feature you demo — it's just how the code reads.

## Run it

```bash
# Full demo (GEPA optimization + the live agent). Needs an OpenAI-compatible key; the agent stage needs Deno.
OPENAI_API_KEY=sk-... DSPY_MODEL=gpt-4o-mini \
  sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.talkToYourDataMain"

# Offline foundation check — no LM, no Deno. Proves the dataset + engine + gold answers are self-consistent.
sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
```

The **GEPA half runs without Deno** (its metric is the Scala engine, not the sandbox); only the live agent's act stage needs Deno for the Python REPL. Tune the optimizer with `GEPA_METRIC_CALLS` and `GEPA_MINIBATCH`.

## Files

- [`Schemas.scala`](Schemas.scala) — the typed `QueryPlan`/`AnalysisResult`/`Verdict` (+ enums).
- [`Dataset.scala`](Dataset.scala) — a deterministic synthetic e-commerce dataset (~10k orders) and a 24-question gold set whose answers are computed by the engine (ground truth by construction).
- [`QueryEngine.scala`](QueryEngine.scala) — the pure-Scala plan evaluator; it's both the GEPA oracle and the verify-stage cross-check.
- [`Agent.scala`](Agent.scala) — the plan → act → verify → refine loop.
- [`Optimize.scala`](Optimize.scala) — the grounded GEPA metric and the optimization driver.
- [`TalkToYourData.scala`](TalkToYourData.scala) — the runnable `@main`.
- [`SelfCheck.scala`](SelfCheck.scala) — the offline foundation check.

## Honest notes

- The data is **synthetic and deterministic** (seeded), so it's license-clean to ship and every gold answer is exact — which is what makes the GEPA before/after a real, reproducible number rather than an LLM-judge guess.
- The demo's questions reduce to a structured `QueryPlan`, which is precisely why the JVM can re-verify them. RLM is the *general* executor (it can compute things the structured vocabulary can't express); the structured cases are the ones where a second, independent engine can hold it honest. Verify proves the **computation** is correct (both engines agree); GEPA's accuracy measures whether the **interpretation** is right.
- Swap the CSV for a JDBC query and the same agent talks to a real database — the dataset is just text the act stage crunches.
