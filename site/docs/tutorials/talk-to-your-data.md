# Talk to your data

A type-safe analytics agent that answers questions about a dataset and gets the
arithmetic right. It threads three ideas into one workflow: typed signatures, a
code-writing agent, and prompt optimization with a grounded metric.

## The model's intent is a typed value

The agent never lets the model emit a free-form answer. It first asks the model
to produce a `QueryPlan`: a validated Scala value describing the computation.
The same type constrains the model's output, decodes its reply, drives the
optimizer's metric, and renders the report.

```scala
--8<-- "tutorials/talk_to_your_data/Schemas.scala:query-plan"
```

Fields like `agg` are enums, so the model can only pick a defined operation. The
prompt cannot drift between "avg", "mean", and "average":

```scala
--8<-- "tutorials/talk_to_your_data/Schemas.scala:agg-enum"
```

## Plan, then compute in code

The dataset is thousands of CSV rows, too large to put in a prompt. So the agent
runs in two stages. **Plan** asks the model for the typed `QueryPlan`. **Act**
hands the plan and the data to an `RLM`, which writes and runs Python in a
sandbox to compute the answer. The arithmetic happens in code, not in the
model's head:

```scala
--8<-- "tutorials/talk_to_your_data/Agent.scala:plan-act"
```

A third stage, **verify**, re-computes the same plan independently on the JVM
with a plain Scala query engine and requires the two answers to agree. A
mismatch triggers a refine attempt.

## Optimization with a grounded metric

The planner's instruction starts vague. `GEPA` evolves it against a metric that
is the deterministic Scala engine compared to a by-construction answer key, so
the optimizer has a trustworthy signal with no human labels. This turns a weak
baseline planner into a reliable one.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.talkToYourDataMain"
```

GEPA optimization needs only a language model. The full plan-act-verify agent
also needs [Deno](https://deno.com) on the path for the sandbox. An offline
foundation check (`tytdSelfCheckMain`) runs with neither.

## Notes

This is a multi-file example: typed schemas, the dataset and query engine, the
agent, and the optimization setup each live in their own file. Full source:
[talk_to_your_data](https://github.com/jpablo/dspy4s/tree/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/talk_to_your_data).
