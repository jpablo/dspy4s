# Milestone — 3.2.1 upgrade + gap-closing + the optimizer arc (2026-06)

Branch `3.2.1`, **34 commits** ahead of `main` (83 files, +6178 / −196). Full suite
**green: 563 tests** across all 8 modules (5 live-LM suites skipped — no API key in CI).
This doc is the reviewer's map; the per-gap detail lives in
[`PORT_GAPS.md`](PORT_GAPS.md), per-symbol status in [`PORT_MAP.md`](PORT_MAP.md).

## Headline

The port went from **"blocked on G-1"** to a **verified, real-LM-validated optimizer
stack** with 8 tracked gaps closed. The keystone was G-1 (typed predictor introspection);
everything downstream — composite save/load, the Refine feedback loop, and the COPRO /
MIPROv2 optimizer family — builds on it.

## 1. Upstream catch-up: dspy 3.1.3 → 3.2.1

- `360aa30` — ported the actionable 3.1.3→3.2.1 deltas (extra-input warning, empty-response
  parse error, non-ASCII output, `ContextWindowExceededError` type, CoT/PoT prefix
  normalization). The bulk of upstream's diff was docstrings / Python-only machinery / unported
  subsystems; see [`UPGRADE-3.1.3-to-3.2.1.md`](../../UPGRADE-3.1.3-to-3.2.1.md).
- `b01c627` — quick wins: `ContextWindowExceededError` wired into `OpenAiClient`; **Ensemble**
  optimizer; Evaluate `display_table` + `provideTraceback`; LM capability flags
  (`supportsFunctionCalling`/`ResponseSchema`/`Reasoning`); `inspect_history`.

## 2. G-1 — typed predictor introspection (the keystone), P1–P6

`Predictors[P]` / `Predictor[P]` typeclasses with Scala 3 Mirror derivation: enumerate and
immutably replace the learnable predictors of an arbitrary composite (`replace(p,read(p))==p`).

- `30420f3` P1 typeclasses + derivation · `25d7e8d` P2 retarget optimizers + drop
  `LabeledSampleProgram` · `9bcf99f` P3 hoist ReAct/CodeAct/MCC sub-predicts to fields ·
  `dd466be` P4 leaf instances for typed `Predict`/`ChainOfThought` · `c459d07` P5 **`Runnable`
  spine unification** (optimizers now target typed programs/user composites end-to-end) ·
  `1657f9c` P6 remove `PredictOps` (`Predictors` is the sole introspection typeclass).

## 3. Tier-0 state gaps (built on G-1)

- **G-3** `b85fe27` / `b2d0096` — per-module `config` (merged under per-call) + bound LM
  (`withLm`/`boundLm`, the immutable `set_lm`/`get_lm`).
- **G-4** `9c5a6db` — program `save`/`load` + `dumpState`/`loadState` (JSON) via
  `ProgramPersistence`, walking a composite through `Predictors` (single Predict + composites
  share one path).
- **Instruction-editing enabler** `50dd00e` — `Predictor.set` now writes back demos + config +
  instructions (the COPRO/MIPRO unlock).

## 4. The optimizer family

- **COPRO** `f2c24f7` — coordinate-ascent instruction optimizer.
- **GroundedProposer** `c27760c` (MIPRO Phase A) — LM instruction proposer grounded in a
  dataset summary + demos (the `propose` package).
- **MIPROv2** `9f51db8` (Phase B) — instruction+demo joint optimizer = BootstrapFewShot +
  GroundedProposer + search. (Random search vs Optuna and other deltas documented in-code.)

## 5. More gaps

- **G-6** `95adeb5` — `Metric.score` now takes `RuntimeContext`; `SemanticF1` /
  `CompleteAndGrounded` (LLM-judged metrics) ported.
- **G-5** `ddecaf2` — `Refine` is no longer a best-of-N alias: real `OfferFeedback` loop
  (advice from the trace → injected as a `hint_` field on retry). v1 = single uniform advice;
  per-module advice is the follow-up.
- **G-9** `d8c80de` — field-constraint rendering (`FieldSpec.constraints` + `FieldConstraints`
  matching `PYDANTIC_CONSTRAINT_MAP`; rendered by ChatAdapter).
- **G-7 v1** `ed2c69f` — native structured outputs: `FormattedPrompt.requestOptions` seam +
  `JSONAdapter` `response_format`. Native function-calling (G-7b) reuses the seam, deferred.

## 6. Verification (the part offline tests can't give)

- **Adversarial review** (`/workflows` find→refute→synthesize): 10 findings, 8 confirmed.
  Fixed 7 — incl. a **HIGH silent-correctness bug** (`391456c`): leaf `Predictor` instances
  were out of implicit scope, so a real user composite would silently optimize **zero**
  predictors with no error. Every unit test had hidden it via one import. Plus 5 med/low fixes
  (`e98d6fe`). 2 findings correctly refuted.
- **Real-LM smoke harness** `825fc48` / `e4d89ae` — runs COPRO + MIPROv2 against a live model
  with deterministic eval (temperature=0) + a live progress callback. Validated: **MIPROv2 0% →
  100%** on a real model (it discovered the label scheme from the data and attached demos). A
  reusable observability hook (`CallbackHandler`) ships with it.

## What's deferred (next, heavier tracks)

- **G-7b** native function-calling (inject `tools`, parse `tool_calls`, rewire ReAct).
- **Retrievers / Embedder → KNN** (needs an embeddings client + vector search).
- **Heavy optimizers** — GEPA (external engine), SIMBA/GRPO/BetterTogether (training stack),
  Optuna (no JVM analog), InferRules.
- **Follow-ups** — Refine per-module advice; G-9 typed-`Schema` constraint derivation; XML/JSON
  constraint embedding.

## Notes for the reviewer

- Commits are one-per-gap/phase by design — not squashed.
- Every phase was test-first and left the full build green; ledgers (`PORT_GAPS.md`,
  `PORT_MAP.md`, `PORT_BACKLOG.md`) were kept current as gaps closed.
- `7300ced` (Scala 3.8.4 upgrade) is an interleaved change from the repo owner; the rest builds
  cleanly on it.
