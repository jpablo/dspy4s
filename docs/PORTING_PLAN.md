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
  - `trait LM { def complete(prompt: Prompt, params: Params): Future[Completion] }`.
- Prompt rendering: Text-first with JSON output blocks for deterministic parsing; later add adapters for richer chat/tool schemas.
- Parsing: JSON decoding into structural records or typed case classes; on failure, complete the `Future` with `DspyError.ParseError` including raw text.
- Settings/config: Central `Settings` (env vars, rate limits, model defaults) akin to Python’s `utils.settings`, but explicit in constructors where possible.
- Errors: `DspyError` hierarchy (ConfigError, HttpError, ParseError, ProviderError) to avoid throwing generic exceptions across module boundaries.

## sbt Layout (Proposed)

- Modules: `core` (primitives, signatures, utils), `predict`, `clients`, `adapters`, `evaluate`, `retrievers`, `teleprompt`, `streaming`, `examples`.
- Root aggregates modules; publish `core`, `predict`, `clients` first.
- Dependencies:
  - HTTP: `sttp-client4` (JDK `httpclient` backend).
  - JSON: `uPickle`.
  - Tests: `munit`.

## Testing Strategy

- Unit tests: per package targeting logic (prompt templates, field parsing, cache behavior).
- Golden tests: deterministic prompt→parse fixtures; avoid real network.
- Parity tests: mirror selected upstream tutorial flows and assert output shape/keys.
- Integration: optional OpenAI tests, gated by `OPENAI_API_KEY` and marked `it`.
- Test framework: `munit` (no cats-effect); for async, use `Await.result` only in ITs or small helper utilities.

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

- Package root: Keep `package dspy` for parity, or `package dspy4s.dspy`? (default: `dspy`).
- Config: Env-only or also support a `~/.dspy4s/config` file? (default: env + optional config file).

## Recommended Decisions (For Sign-Off)

- Effects: Use stdlib `Future` for async, limited to LM/network boundaries; prefer sync APIs elsewhere. Accept an implicit `ExecutionContext` in clients/modules.
- JSON: Use `uPickle` for a small, fast dependency and simple AST; revisit `circe` only if needed.
- HTTP: Use `sttp-client4` with the JDK `httpclient` backend.
- Package root: Publish as `dspy` (top-level) for parity; the repo name remains `dspy4s`.
- Config: Support explicit params > env vars > system props; add optional `~/.dspy4s/config` (TOML/JSON) reader behind a tiny module.
- Scala/JDK: Target Scala 3.7.2 and JDK 17+; CI matrix for 17 and 21.

Rationale: These choices minimize friction for MVP while keeping a clean path to streaming, retries, and richer adapters without redesign.

## Non-Goals (For Now)

- Full parity of every upstream module; we will port only what’s needed to run core tutorials.
- Multi-provider clients beyond OpenAI; vendor-specific adapters and retrievers are deferred.
- Python interop, codegen-based macros for Signatures, or a JS/Scala.js target.
- Distributed caching, persistence layers, or external stores.

## Risks & Mitigations

- Ambiguity in upstream behaviors: add golden tests against frozen fixtures; document divergences.
- API churn during MVP: keep traits stable and hide impl details; start at version `0.x`.
- Rate limits / flaky networks: centralize retries/backoff in `clients` with `sttp` request timeouts and simple retry policy (no cats).
- Parsing fragility: prefer JSON blocks in prompts; maintain tolerant decoders and surface `ParseError` with raw text.

## Conventions

- Error model: sealed `DspyError` with typed cases; fail returned `Future` with `DspyError` at boundaries.
- Async: Accept an implicit `ExecutionContext` in constructors; default to `global` only in examples/tests.
- Naming: mirror Python class and file names where sensible; Scala package `dspy.*`.
- Logging: minimal SLF4J facade; redact secrets; structured context for LM calls (model, tokens, latency).
- Config precedence: constructor args > env > system props > config file.
- Source style: add `scalafmt` with a pinned config; enable `-Xfatal-warnings` once code stabilizes.

## Tooling & CI

- GitHub Actions: build + test on JDK 17/21; cache Coursier; run fmt check.
- Static checks: `scalafmt`, optional `scalafix` later; doc generation via `sbt doc`.
- Test layout: `unit`, `golden`, and `it` (integration) tags; OpenAI tests gated by `OPENAI_API_KEY`.

## Security & Privacy

- Never log API keys or raw prompts/responses unless explicitly enabled; default to redacted logs.
- Opt-in on-disk cache with TTL; default in-memory cache only, no persistence.
- Respect env-only secrets; config file must support `chmod 600` style checks.

## Publishing & Versioning

- Versioning: `0.x` until APIs settle; follow SemVer post-1.0.
- Artifacts: publish `core`, `predict`, `clients` first; others later.

## Workspace Notes

- The upstream Python repo is available under `upstream-dspy -> /Users/jpablo/GitHub/dspy`.
- To search both trees: `rg -n "pattern" src upstream-dspy/dspy`.

## Next Steps

1) Confirm decisions for package root naming and config file.
2) Scaffold the sbt multi-module layout and wire baseline dependencies.
3) Implement MVP core and the first end-to-end example.

## Task Breakdown (MVP)

- Decide stack: package root + config file shape. (sign-off)
- Create sbt modules: `core`, `predict`, `clients`, `examples` (aggregate root).
- Add deps: `sttp-client4` (httpclient backend), `upickle`, `munit`.
- Add `scalafmt` and CI workflow; enable fmt check in CI.
- `core.primitives`: `Module`, `Example`, `Prediction`, `DspyError`.
- `signatures`: `Field`, `Signature`, helpers for rendering/validation.
- `utils`: `Settings` (env reading), logging façade, exceptions.
- `clients.base`: `LM` trait (`complete`), request/response models, error mapping.
- `clients.openai`: minimal chat completions path; key injection; timeouts and retries.
- `clients.cache`: in-memory map with TTL and request-key hashing.
- `predict`: `Predict` module: template → LM → JSON parse → `Prediction`.
- Prompt rendering: text-first with fenced JSON block; parser tolerant to string/int/bool.
- Tests: unit (signatures, render, parse, cache); golden fixtures; optional OpenAI integration test.
- Example: small tutorial reproduction in `examples` and README snippet.

Exit criteria: build green, example runs locally (with key), and golden tests pass without network.
