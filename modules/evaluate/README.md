# dspy4s `evaluate`

Run a program over a labelled dev set, score each prediction with a metric, and aggregate the result. This is
the dspy4s port of `dspy.evaluate` — `Evaluate`, the builtin string-comparison metrics, the LM-judged
auto-evaluation metrics, and CSV/JSON persistence of the per-example results.

## The idea

You have:

- a **program** — any `Example => Either[DspyError, DynamicPrediction]`. This is just the call signature a
  dspy4s module exposes; the evaluator never sees module internals, only the function.
- a **dev set** — a `Vector[Example]`, each example a data bag of input fields plus the ground-truth fields.
- a **metric** — something that scores one prediction against its example and returns a `Double`.

`Evaluate` runs the program over every example (in parallel), feeds each `(example, prediction)` pair to the
metric, averages the scores, and hands back an `EvaluationResult` carrying the aggregate plus every
per-example outcome. The aggregate is reported as a **percentage** (`mean(score) * 100`), matching dspy.

```
            ┌──────────── ParallelExecutor (numThreads) ────────────┐
 devset ──► │  example ─► program ─► prediction ─► metric ─► score   │ ─► EvaluationResult
            └───────────────────────────────────────────────────────┘        (mean * 100)
```

## Mechanics

### `Evaluate`

`Evaluate(devset, metric, …)` builds an evaluator; calling it with a program runs the evaluation. The dev set
and metric can be fixed at construction and/or overridden per call.

```scala
import dspy4s.core.contracts.{Example, RuntimeContext, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.metrics.ExactMatch

val devset = Vector(
  Example("question" := "Capital of France?", "answer" := "Paris"),
  Example("question" := "Capital of Italy?",  "answer" := "Rome")
)

val evaluator = Evaluate(devset = devset, metric = new ExactMatch())

given RuntimeContext = RuntimeEnvironment.current

val result = evaluator() { example =>
  myProgram(example)   // Example => Either[DspyError, DynamicPrediction]
}

result.foreach { eval =>
  println(eval.score)        // aggregate, 0–100
  println(eval.metricName)   // "exact_match"
  eval.results.foreach(r => println(r.score))  // per-example
}
```

Key pieces of behaviour:

- **Parallelism.** Examples run concurrently through a `ParallelExecutor`. Thread count resolves from
  `numThreads` → the ambient `RuntimeContext.numThreads` → a default of `8`. Per-worker, the ambient context
  is restored into the thread-local so LM-judged metrics can reach the language model.
- **Error handling.** `maxErrors` (config → context → default `10`) caps tolerated failures before the run
  aborts. A failed example contributes `failureScore` (default `0.0`). With `provideTraceback = true`, the
  captured error message is attached to that example's `ExampleEvaluation.error`.
- **Empty dev set** short-circuits to a zero-score result rather than dividing by zero.
- **`callbackMetadata`** is threaded into the evaluation scope's `RuntimeContext.callbackMetadata`, so
  callbacks (and the program) firing during the run can read it. Mirrors Python's
  `Evaluate(callback_metadata=…)`. An empty record leaves the ambient context untouched.
- **Display & persistence.** `displayProgress` prints a one-line summary; `displayTable` renders a
  dependency-free fixed-width table (all rows, or a row limit); `saveAsCsv` / `saveAsJson` write the
  per-example results to disk.

Two call styles exist, mirroring dspy:

- `evaluator(metric?, devset?, …)(program)` — the flexible apply, overrides merged over the configured
  defaults.
- `evaluator.evaluate(predict, dataset, metric)` — the plain `Evaluator` trait method.

### `EvaluationResult`

```scala
final case class EvaluationResult(
  score: Double,                       // aggregate, mean(score) * 100
  results: Vector[ExampleEvaluation],  // one per dev-set example, in order
  metricName: String,
  metadata: Map[String, Any]           // num_threads, max_errors, devset_size
)
```

Each `ExampleEvaluation` holds the `example`, the `prediction`, its `score`, and an optional captured `error`.
`renderTable(limit)` produces a plain-text table whose columns are the union of example fields, prediction
fields (prefixed `pred:`), `score`, and `error` when present.

## Metrics

A metric is:

```scala
trait Metric:
  def name: String
  def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry] = Vector.empty)(using
      RuntimeContext): Either[DspyError, Double]
```

The `RuntimeContext` is what lets a metric call a language model during scoring; the `trace` is the program's
per-call trace — empty during evaluation, non-empty during bootstrapping (mirroring dspy's `trace is None`
branch).

### Builtin (string-comparison) metrics

These ignore the `RuntimeContext`. All normalise text first (lowercase, strip punctuation/articles/accents)
via `NormalizeText`, mirroring dspy's SQuAD-style normalisation. The compared field defaults to `answer`.

| Metric | Score |
|--------|-------|
| `ExactMatch` | `1.0` if normalised prediction equals any reference |
| `ContainsMatch` | `1.0` if the prediction contains any normalised reference |
| `F1Score` | token-overlap F1 against the best-matching reference |
| `AnswerMatch(frac)` | exact match when `frac >= 1.0`, else `F1 >= frac` |
| `PassageMatch` | `1.0` if any retrieved passage (DPR-normalised) contains an answer |

`FunctionMetric` wraps an arbitrary scoring function so you can supply your own:

```scala
import dspy4s.evaluate.metrics.FunctionMetric

val lenient = FunctionMetric.bool("nonempty") { (example, prediction) =>
  prediction.get("answer").exists(_ != null)
}
```

### Auto-evaluation (LM-judged) metrics

`SemanticF1` and `CompleteAndGrounded` are the metrics that **call a language model** during scoring. Each
runs a `ChainOfThought`-style judge sub-program (built from a runtime `SignatureLayout` over a
`DynamicPredict`, with a leading `reasoning` field) and reads back numeric judgements, resolving the
LM/adapter from the ambient `RuntimeContext`.

- **`SemanticF1`** — an LM judges `recall` and `precision` of the system response against the ground truth,
  returning `f1_score(precision, recall)`. The `decompositional = true` variant first enumerates key ideas in
  each response and discusses their overlap before scoring.
- **`CompleteAndGrounded`** — combines an `AnswerCompleteness` judgement (response vs ground truth) with an
  `AnswerGroundedness` judgement (response vs retrieved context), returning
  `f1_score(groundedness, completeness)`. The groundedness half needs a context field on the prediction
  (dspy4s has no retriever, so the program under evaluation must supply it).

Field names are configurable; defaults follow upstream (`question` / `response`). Because dspy4s has no
attribute access, values are pulled by string key from the `Example` / `DynamicPrediction` records.

## Persistence

`EvaluationResultPersistence` serialises the per-example results:

- **JSON** — an array of objects, each flattening `example_*` fields, prediction fields (prediction fields
  that collide with an example key get a `pred_` prefix), and the metric score under its `metricName`.
- **CSV** — the same flattened columns. Driven by `saveAsJson` / `saveAsCsv` on `EvaluateConfig`, or callable
  directly.

## Source layout

| File | Contents |
|------|----------|
| `Evaluate.scala` | `Evaluate`, `EvaluateConfig`, the run loop and parallel execution |
| `contracts/EvaluateContracts.scala` | `Metric`, `Evaluator`, `EvaluationResult`, `ExampleEvaluation`, table rendering |
| `metrics/BuiltinMetrics.scala` | string-comparison metrics + `FunctionMetric` |
| `metrics/AutoEvaluation.scala` | `SemanticF1`, `CompleteAndGrounded` (LM-judged) |
| `metrics/NormalizeText.scala` | SQuAD-style and DPR text normalisation |
| `EvaluationResultPersistence.scala` | CSV / JSON serialisation |

## Relation to dspy

This module ports `dspy/evaluate/` (dspy 3.1.3). The shape follows the [module-purity
principle](../../README.md): metrics and programs are pure `(in) => Either[err, out]`; parallelism, error
budgeting, callback metadata, and tracing live in the `RuntimeContext`/`RuntimeEnvironment`, not on the
instances. The main deltas — string-keyed field access instead of Python attribute access, dynamic
`SignatureLayout`-driven judges, and always returning a `Double` from metrics — are documented inline in
`AutoEvaluation.scala`.
