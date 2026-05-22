# Phase 6 Progress

Phase 6 focuses on the evaluation runtime (metrics, threaded evaluator, result persistence).

## Implemented in this step

1. Text normalization utility
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/main/scala/dspy4s/eval/metrics/NormalizeText.scala`
- NFD normalization + combining-mark stripping (Unicode `\p{Mn}`)
- Lowercase + strip punctuation + remove articles (`a/an/the`) + collapse whitespace
- Added `dpr(...)` variant (keeps articles, used by `PassageMatch`)
- Parity with Python `dspy/evaluate/metrics.py:normalize_text`

2. Built-in metrics
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/main/scala/dspy4s/eval/metrics/BuiltinMetrics.scala`
- `ExactMatch(answerField)` — 0.0/1.0; accepts single string or Vector of reference strings
- `ContainsMatch(answerField)` — 0.0/1.0 if normalized prediction contains normalized reference
- `F1Score(answerField)` — token-level F1 over whitespace-paired normalized tokens
- `AnswerMatch(frac, answerField)` — EM when `frac >= 1.0`, else F1 threshold
- `PassageMatch(contextField, answerField)` — 0.0/1.0 if any DPR-normalized context passage contains any reference
- `FunctionMetric(name, fn)` — wraps `(Example, Prediction, trace) => Either[DspyError, Double]`
  - Convenience builders: `FunctionMetric.apply(name)(fn)` and `FunctionMetric.bool(name)(pred)`

3. Evaluate runner
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/main/scala/dspy4s/eval/Evaluate.scala`
- `Evaluate(devset, metric, numThreads, maxErrors, failureScore, displayProgress, timeout)` factory
- `apply(...)(program: Example => Either[DspyError, Prediction])` runs the program over the dataset
- Reuses `ParallelExecutor` from `programs` module — inherits thread isolation, `max_errors` cancellation, timeout
- Failure path: when a program invocation returns `Left`, the result is substituted with `(PredictionData.empty, failureScore)` (default 0.0)
- Aggregate score returned as a percentage (0-100), matching Python `Evaluate` semantics

4. Result persistence
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/main/scala/dspy4s/eval/EvaluationResultPersistence.scala`
- `saveAsJson(result, path)` — flat array of result objects, example fields prefixed `example_`, prediction fields as-is (or `pred_` on collision), metric column appended
- `saveAsCsv(result, path)` — same schema, with proper CSV escaping (quotes for commas/newlines, `""` doubling)
- `EvaluationResult` now carries `metricName` so persistence can name the score column

5. Contract refresh
- Updated `EvaluationResult` in `dspy4s.eval.contracts`:
  - field `score` (percentage 0-100) replaces `aggregateScore`
  - field `results: Vector[ExampleEvaluation]` replaces `evaluations`
  - `metricName: String` carried alongside for persistence/display
- `evalApi.contractsPhase = "phase-6"`

6. Build configuration
- Added `ujson % 4.0.2` and `munit` test dependency to `evaluation` subproject

7. Tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/test/scala/dspy4s/eval/metrics/NormalizeTextSuite.scala` (6 cases)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/test/scala/dspy4s/eval/metrics/BuiltinMetricsSuite.scala` (14 cases)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/test/scala/dspy4s/eval/EvaluateSuite.scala` (6 cases)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/eval/src/test/scala/dspy4s/eval/PersistenceSuite.scala` (4 cases)
- Coverage: normalization (NFD, punctuation, articles, whitespace, unicode), each metric against single/list/non-matching inputs, parallel execution order preservation, failure-score path, FunctionMetric wrapping, JSON/CSV save shape and collision handling

## Validation

- Ran full test suite on 2026-05-22.
- Result: all 175 tests pass (35 programs + 30 eval + 16 streaming + 42 core + 16 adapters + 36 lm — 2 of those are live-ignored).

## Remaining gaps

- `display_table` (console DataFrame rendering) — deferred; Python uses pandas, we'd need a minimal tabular formatter
- Straggler retry mechanism from Python `ParallelExecutor` (resubmit slow workers) — deferred
- `SemanticF1`, `CompleteAndGrounded` auto-evaluation metrics (LLM-judged) — requires a working LM provider path; defer to a later phase
- `ProvideTraceback` / `callback_metadata` options from Python `Evaluate.__call__` — not yet plumbed through
