# Talk to Your Data

A type-safe analytics agent. Ask questions about a dataset in plain English and get answers, with the arithmetic usually done in code and then computed again on the JVM. Built on dspy4s.

```
Q: Which product category brings in the most money?
  plan:
    Aggregation: SUM of column `total`.
    Group by: category.
    Sort the groups by the aggregate value, descending.
    Take the top 1.
    Answer with the group LABEL (e.g. the category/region name).
  answer:  Electronics
  method:  Parsed the CSV, summed `total` per category, took the max.
  verify:  JVM cross-check AGREED  (after 1 attempt(s))
```

## The idea

LLMs are good at understanding a question and unreliable at computing the answer; they tend to make numbers up. This example keeps those two jobs separate and makes each step checkable.

| Stage | What happens | Feature |
|---|---|---|
| **Plan** | The English question becomes a [`QueryPlan`](Schemas.scala), a validated Scala value with enums and nested lists. | Signatures |
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

# Offline foundation check (no LM, no Deno).
sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
```

The foundation check does these checks: the totals of the engine agree with each other, four gold answers agree with a direct computation from the orders, each gold plan has no problem, and a JSON reply decodes to a `QueryPlan` and an `AnalysisResult`. It does not check the other gold answers against a direct computation, the model, or the sandbox.

The GEPA half runs without Deno, because its metric is the Scala engine rather than the sandbox. Only the live agent's act stage needs Deno for the Python REPL. Tune the optimizer with `GEPA_METRIC_CALLS` and `GEPA_MINIBATCH`. For another OpenAI-compatible endpoint, set `OPENAI_BASE_URL`.

## Files

- [`Schemas.scala`](Schemas.scala): the `QueryPlan`, `AnalysisResult`, and `Verdict` (with their enums).
- [`Dataset.scala`](Dataset.scala): a deterministic synthetic e-commerce dataset (about 10k orders) and a 24-question gold set. The answer key comes from hand-written plans and the same engine that scores a planner, and `SelfCheck` compares some answers with a direct computation.
- [`QueryEngine.scala`](QueryEngine.scala): the pure-Scala plan evaluator. It serves as both the GEPA oracle and the verify-stage cross-check. It gives a problem that names each bad column of a plan.
- [`Agent.scala`](Agent.scala): the plan, act, verify, refine loop.
- [`Optimize.scala`](Optimize.scala): the grounded GEPA metric and the optimization driver.
- [`TalkToYourData.scala`](TalkToYourData.scala): the runnable `@main`.
- [`SelfCheck.scala`](SelfCheck.scala): the offline foundation check.

## Notes on scope

- The data is synthetic and deterministic (seeded), so it is license-clean to ship and each gold answer is reproducible. The score of a plan comes from code, not from an LLM judge. GEPA uses the val split to select the best candidate, so the accuracy on that split is not a measure on held-out data.
- The demo's questions reduce to a structured `QueryPlan`, which is why the JVM can compute them again. RLM is the general executor and can compute things that the structured vocabulary cannot express; a second engine can check only the structured cases. Verify shows that two computations agree. It does not show that the plan is correct, because the two computations use the same plan. GEPA's accuracy measures whether the plan is correct.
- At the iteration limit, RLM asks the model for the outputs with no more code, so the model can then write the values itself. Verify still compares these values with the JVM answer.
- To use a database in place of the CSV, change the text that the act stage gets (`Dataset.csv`), and also change `Agent.verify` and the gold answers of `Optimize`, which read `Dataset.orders`.
