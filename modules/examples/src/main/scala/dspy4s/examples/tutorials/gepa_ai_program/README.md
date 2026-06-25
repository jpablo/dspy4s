# Reflective Prompt Evolution with GEPA

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/gepa_ai_program/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/gepa_ai_program/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

✅ **Optimizer ported.** GEPA (**Ge**netic-**Pa**reto) is a reflective prompt optimizer: it uses an LM to reflect
on a program's execution trajectory — what worked, what didn't, what to change — and evolves a pool of candidate
prompts, driven by rich textual *feedback* rather than only a scalar metric. In DSPy it's `dspy.GEPA` (introduced
in [*GEPA: Reflective Prompt Evolution Can Outperform Reinforcement Learning*](https://arxiv.org/abs/2507.19457),
wrapping [gepa-ai/gepa](https://github.com/gepa-ai/gepa)).

dspy4s ships a self-contained GEPA implementation in the **`dspy4s-gepa`** module
([`modules/gepa`](../../../../../../../../gepa)) — the engine is ported directly (upstream's is a thin adapter over
the external `gepa` package), so there is no external dependency. It covers reflective mutation + instance-Pareto
candidate selection, round-robin/all component selection, the epoch-shuffled minibatch sampler, merge (crossover)
proposals, an evaluation cache, run-dir resume, and an opt-in perfect-score early stop. Multi-objective frontiers
are deliberately not ported (DSPy's metric is a scalar `ScoreWithFeedback`). See `PORT_GAPS.md` **G-12**.

## What GEPA needs that other optimizers don't

Unlike `BootstrapFewShot*`/`COPRO`/`MIPROv2` (which take a plain `Metric`), GEPA needs:

- a **`FeedbackMetric`** — returns a score *plus* natural-language feedback. That feedback text is the "gradient":
  the reflection LM reads it to rewrite a predictor's instruction. Concrete, actionable feedback (what went wrong,
  the correct answer, a format error) is what makes the search work.
- a separate **`reflectionLm`** — usually a stronger model, used only to rewrite instructions (its calls don't
  count against the metric-call budget).

## Minimal usage

```scala
import dspy4s.gepa.{Gepa, GepaConfig}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}

val gepa = new Gepa[DynamicPredict](
  metric,                  // a FeedbackMetric (score + feedback)
  reflectionLm = lm,       // the model that rewrites instructions
  GepaConfig(maxMetricCalls = 60, reflectionMinibatchSize = 3, seed = 0L)
)
val result = gepa.compile(student, trainset = trainset, valset = valset)
// result.bestProgram     — the program with the evolved instructions
// result.bestCandidate   — Map[componentName -> instruction]
// result.bestScore       — mean validation score
```

## Runnable end-to-end example

The upstream tutorials on this page (AIME math, enterprise extraction, PAPILLON, code-backdoor classification)
are notebooks with heavy datasets and aren't ported as runnable examples. A self-contained, live-model smoke
harness exercises the whole tower — `FeedbackMetric` → failure-aware traces → reflective dataset → reflection LM
→ Pareto loop — against a real model:

```
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"
```

See [`examples/verify/GepaSmokeTest.scala`](../../verify/GepaSmokeTest.scala): a vague baseline instruction
("Answer the question.") is evolved into a precise HAS_NUM/NO_NUM classifier, lifting the held-out validation
score to 1.0.

For the few-shot/instruction optimizers, see [`learn/optimization`](../../learn/optimization/README.md)
(`BootstrapFewShot*`, `COPRO`, `MIPROv2`).
