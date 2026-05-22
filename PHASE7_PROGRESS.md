# Phase 7 Progress

Phase 7 focuses on the first optimizers.

## Implemented in this step

1. Core types
- `ExampleData` extended with `augmented: Boolean = false` flag in `dspy4s.core.contracts`
  - `withAugmented(flag)` mutator on `Example` trait
  - Traces produced by bootstrap are marked `augmented=true`, preserving Python parity
- `OptimizeApi.contractsPhase = "phase-7"`
- `munit` added as test dependency for `optimize` subproject

2. `PredictOps[P]` typeclass
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/optimize/src/main/scala/dspy4s/optimize/PredictOps.scala`
- Reads/writes demos, signature, program name for optimizer programs
- Built-in `given` instances for `Predict` and `ChainOfThought`

3. `LabeledFewShot` optimizer
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/optimize/src/main/scala/dspy4s/optimize/LabeledFewShot.scala`
- `LabeledFewShotConfig(k, sample, seed)`
- Selects `min(k, trainset.size)` examples via seeded shuffle (deterministic under same seed)
- `sample=false` preserves input order

4. `BootstrapFewShot` optimizer
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/optimize/src/main/scala/dspy4s/optimize/BootstrapFewShot.scala`
- `BootstrapFewShotConfig(metric, metricThreshold, maxBootstrappedDemos, maxLabeledDemos, maxRounds, maxErrors, seed)`
- Phases:
  1. Runs teacher on each trainset example
  2. Keeps `(inputs ∪ prediction_outputs, augmented=true)` demo when metric passes or metric is None
  3. Stops bootstrap at `maxBootstrappedDemos`
  4. Fills remaining slots (up to `maxLabeledDemos`) from examples that didn't bootstrap successfully
- Catches `NonFatal` per round; increments per-round `rollout_id` for cache-busting on retry rounds
- Fails fast with `RuntimeError` if total error count ≥ `maxErrors`
- Uses `scala.util.boundary`/`break` for non-local exits (no deprecation warnings)

5. `BootstrapFewShotWithRandomSearch` optimizer
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/optimize/src/main/scala/dspy4s/optimize/BootstrapFewShotWithRandomSearch.scala`
- `RandomSearchConfig(metric, numCandidates, maxBootstrappedDemos, maxLabeledDemos, maxRounds, numThreads, maxErrors, stopAtScore, metricThreshold, seed)`
- Generates candidate programs from seeds:
  - seed −3: zero-shot (empty demos)
  - seed −2: `LabeledFewShot(k = maxLabeledDemos)`
  - seed −1: unshuffled `BootstrapFewShot`
  - seed ≥ 0: shuffled-subset `BootstrapFewShot` with random subset size
- Each candidate evaluated against `valset` (falls back to `trainset`) via `dspy4s.eval.Evaluate`
- Early exit when any candidate reaches `stopAtScore`
- Returns `OptimizationReport` with `candidates` sorted by descending score and `bestProgram`

6. Tests (13 new cases)
- `LabeledFewShotSuite`: 5 tests — determinism, empty trainset, input-order slicing, cap at trainset size, signature preservation
- `BootstrapFewShotSuite`: 5 tests — empty trainset, successful bootstrapping, metric filtering, maxErrors cutoff, labeled fill from failed pool
- `BootstrapFewShotWithRandomSearchSuite`: 3 tests — candidate generation + ranking, `stopAtScore` early exit, empty trainset
- All tests use a demo-aware scripted student/teacher pair for end-to-end verification of the random-search loop

## Validation

- Ran full test suite on 2026-05-22.
- Result: all 188 tests pass (13 optimize + 30 eval + 35 programs + 16 streaming + 42 core + 16 adapters + 36 lm — 2 live tests skipped).

## Remaining gaps (Phase 7 continued)

- `Ensemble` optimizer (per-input voting/majority over multiple compiled programs)
- `KNNFewShot` optimizer (k-NN retriever over an Embedder-backed vectorizer, bootstrap per-inference)
- Support for composite programs (multi-predictor students); current `BootstrapFewShot` handles a single predictor per program via `PredictOps`
- LLM-judged auto-evaluation metrics (`SemanticF1`, `CompleteAndGrounded`) as metric implementations for bootstrap scoring
- Straggler retry mechanism from Python `ParallelExecutor` (resubmit slow workers)

## Known deltas from Python

- Python's `compile()` returns the module directly; Scala returns `OptimizationReport[P]` wrapping the module. This is more explicit and carries metadata (candidates + scores).
- Python's `BootstrapFewShotWithRandomSearch` attaches `candidate_programs` as a dynamic attribute; Scala carries it inside the report.
- Python seeds random-shuffled subsets via `seed` directly; Scala uses `(seed + baseSeed)` to allow reproducible-but-independent runs. The seed discipline is documented but can be simplified.
- Python's `KNNFewShot` monkey-patches the student's `forward`; Scala has no equivalent — deferred.
