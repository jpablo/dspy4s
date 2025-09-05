# dspy4s Port Plan

Last updated: 2025-09-05

## Overview

- Goal: A faithful Scala 3 port of the Python `dspy` library, mirroring structure, names, and behavior where practical.
- Approach: Incremental delivery with a minimal viable core, followed by layered additions (predictors, adapters, evaluation, teleprompting, retrieval, streaming).
- Parity: Keep package layout and class names recognizable; prefer thin Scala-idiomatic wrappers to wholesale redesigns.

## Principles

- Structure parity: Mirror `dspy/*` folders under `src/main/scala/dspy/...`.
- Scala 3 features: Use `scala.Selectable` and structural types for dynamic-record like values (e.g., examples, parsed outputs) where helpful.
- Clear abstractions: Define stable traits first (Module, LM, Signature, Field) and build features on top.
- Deterministic IO: Prefer explicit parameters and typed errors over hidden globals; provide simple `Settings` to mirror Python defaults.
- Test-driven parity: Recreate selected upstream tutorials and expected shapes/behaviors as tests.

## MVP Scope (Iteration 1)

Packages to implement:

- `dspy.primitives`: `Module`, `BaseModule` (if needed), `Example`, `Prediction`.
- `dspy.signatures`: `Field`, `Signature`, `utils`.
- `dspy.predict`: core `Predict` (prompt rendering → LM call → parse to structured output).
- `dspy.clients`: `BaseLM`, `LM`, `OpenAI` (chat-completions), small in-memory `Cache`.
- `dspy.utils`: minimal `settings`, logging helpers, basic exceptions.

Out of scope for MVP (stub or defer): advanced predictors (`retry`, `aggregation`, `best_of_n`, `cot`, `refine`), adapters beyond chat/JSON, evaluation harness, retrieval backends, teleprompting, streaming, vendor-specific clients (Databricks, Weaviate), JS runner.

Acceptance criteria:

- A simple end-to-end example reproduces an upstream tutorial: define a `Signature`, create a `Predict` module, call OpenAI, parse response into a `Prediction` with typed fields.
- Unit tests for `Signature` building, prompt formatting, and JSON parsing; integration test for OpenAI path (behind env-guard and recorded fixtures).

## Post-MVP Phases

1) Predictors+: add `retry`, `aggregation`, `best_of_n`, `refine`, `chain_of_thought`, `parallel`.
2) Adapters: `base`, `chat`, `json` (with tool-call stubs), typed media (image/audio/code) as needed.
3) Evaluation: metrics and small harness to batch-run examples.
4) Retrieval I: embeddings + a minimal retriever; keep vendor backends optional.
5) Teleprompt I: `bootstrap`, `random_search` (subject to algo parity with upstream).
6) Streaming: listener interfaces and token-stream support.
7) Teleprompt II / Advanced: optimizers like `mipro`, `simbA`, `gepa` after foundations stabilize.
8) Datasets/DSP/Utils: port selectively to support examples/tests.

## Module Mapping (Upstream → Scala package)

- `dspy/primitives/*` → `dspy.primitives.*`
- `dspy/signatures/*` → `dspy.signatures.*`
- `dspy/predict/*` → `dspy.predict.*` (start with `predict.py`)
- `dspy/clients/{base_lm,lm,openai,cache,embedding,provider}` → `dspy.clients.*` (start with base + openai + cache)
- `dspy/adapters/{base,chat_adapter,json_adapter,...}` → `dspy.adapters.*` (defer non-core)
- `dspy/evaluate/*` → `dspy.evaluate.*` (basic metrics later)
- `dspy/retrievers/*` → `dspy.retrievers.*` (defer)
- `dspy/teleprompt/*` → `dspy.teleprompt.*` (defer)
- `dspy/streaming/*` → `dspy.streaming.*` (defer)
- `dspy/utils/*` → `dspy.utils.*` (port only pieces required by MVP)

## Design Decisions

- Dynamic records: Use `Selectable` and structural types for flexible outputs; offer typed case-class projections where feasible.
- Core API:
  - `trait Module { def forward(...): Prediction; final def apply(...): Prediction = forward(...) }`
  - `trait LM { def complete(prompt: Prompt, params: Params): F[Completion] }` (start with `F = scala.concurrent.Future` or `cats.effect.IO` — see Open Questions).
- Prompt rendering: Text-first with JSON output blocks for deterministic parsing; later add adapters for richer chat/tool schemas.
- Parsing: JSON decoding into structural records or typed case classes; surface parse errors as typed results.
- Settings/config: Central `Settings` (env vars, rate limits, model defaults) akin to Python’s `utils.settings`, but explicit in constructors where possible.
- Errors: `DspyError` hierarchy (ConfigError, HttpError, ParseError, ProviderError) to avoid throwing generic exceptions across module boundaries.

## sbt Layout (Proposed)

- Modules: `core` (primitives, signatures, utils), `predict`, `clients`, `adapters`, `evaluate`, `retrievers`, `teleprompt`, `streaming`, `examples`.
- Root aggregates modules; publish `core`, `predict`, `clients` first.
- Dependencies (tentative):
  - HTTP: `sttp-client3` (or Java 11 `HttpClient` initially for zero deps).
  - JSON: `circe` or `uPickle` (decision pending).
  - Tests: `munit` (+ `munit-cats-effect` if using IO).

## Testing Strategy

- Unit tests: per package targeting logic (prompt templates, field parsing, cache behavior).
- Golden tests: deterministic prompt→parse fixtures; avoid real network.
- Parity tests: mirror selected upstream tutorial flows and assert output shape/keys.
- Integration: optional OpenAI tests, gated by `OPENAI_API_KEY` and marked `it`.

## Milestones & Deliverables

M1 – MVP Core (2–4 days)
- Implement `primitives`, `signatures`, `predict(Predict)`, `clients(OpenAI)`, minimal `utils`.
- Example and tests pass locally; README usage snippet.

M2 – Predictors+ & Adapters (3–5 days)
- Add `retry`, `aggregation`, `best_of_n`, `refine`; `adapters` for chat/json.
- More examples; expand tests.

M3 – Eval & Retrieval (3–5 days)
- Basic metrics + harness; embeddings + simple retriever.

M4 – Teleprompt & Streaming (5–7 days)
- `bootstrap`, `random_search`; streaming listeners; docs polish.

## Open Questions / Decisions Needed

- Effects: Start with `Future` (stdlib) or `cats-effect IO` for composability and resource safety?
- JSON: `circe` vs `uPickle`? (Circe ecosystem vs smaller footprint.)
- HTTP: `sttp` vs JDK `HttpClient` for MVP?
- Package root: Keep `package dspy` for parity, or `package dspy4s.dspy`?
- Config: Env-only or also support a `~/.dspy4s/config` file?

## Workspace Notes

- The upstream Python repo is available under `upstream-dspy -> /Users/jpablo/GitHub/dspy`.
- To search both trees: `rg -n "pattern" src upstream-dspy/dspy`.

## Next Steps

1) Confirm decisions for Effects, JSON, HTTP, and package root naming.
2) Scaffold the sbt multi-module layout and wire baseline dependencies.
3) Implement MVP core and the first end-to-end example.

