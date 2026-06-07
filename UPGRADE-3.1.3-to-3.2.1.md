# Porting checklist: dspy 3.1.3 → 3.2.1

Target upstream: **dspy 3.2.1** (latest stable, tagged 2026-05-05). `3.3.0b1` is a
pre-release beta and deliberately **not** the target.

Source of truth: `git diff 3.1.3 3.2.1` in `/Users/jpablo/GitHub/dspy`.
67 files changed under `dspy/`. This document classifies **every** changed file.

## Implementation status (session 2026-06-06)

Done and verified (core 61 / adapters 67 / programs 84 tests green; full `Test/compile` clean):

- **P1** ✅ Extra-input warning added in `PredictEngine.warnOnExtraInputs`. ⚠️ Uses
  `Console.err.println` because dspy4s has no logging facility. **Open design question:** a
  structured `CallbackEvent` (e.g. `WarningEvent`) in `core` would be more idiomatic than stderr.
- **C1** ✅ Confirmed no-op — empty/null response already yields `ParseError`. Characterization
  tests added (single + multi-output, Chat + JSON).
- **A1** ✅ Confirmed no-op — ujson emits literal UTF-8. Round-trip tests added (café/naïve/日本語).
- **D2** ✅ `ContextWindowExceededError` added to `DspyError`. Match-site audit: no exhaustive
  match without catch-all, no ripple. (L1 — provider detection in `OpenAiClient` — deferred.)
- **P3** ✅ Resolved via **Option C (normalize in augment path)**, surgical form: CoT/PoT
  auxiliary fields wrapped in `FieldSpec.normalize` so prefixes are *derived* (no hardcoded
  literals). CoT → `Reasoning:` (true no-op). PoT → `Generated Code:` / `Final Generated Code:`
  (was a duplicated `Code:`), others unchanged. Done at the field-definition sites only —
  `append`/`insert`/`prepend` left untouched, so CodeAct/MultiChainComparison are unaffected.
  Discovery: `normalize` runs at `create`/`parse` but NOT in the mutation helpers; the default
  `ChatAdapter` ignores `prefix` (uses `[[ ## name ## ]]`), so this only affects JSON/XML markers.
- **O3** ✅ Investigated — no single upstream-target version constant to bump (the `3.1.3`
  strings in `docs/` are baseline records tied to a `release-3.1.3` reference clone). Left as-is.

Still open (decisions / deferred): **D1** type-mismatch warning (recommended skip — redundant on
typed surface); **L1** wire `ContextWindowExceededError` detection into `OpenAiClient`;
**P1 design** stderr vs callback-event warning channel.

## TL;DR

The 3.1.3 → 3.2.1 diff is dominated by non-actionable churn:

- **Docstrings** — `Example:` → `Examples:` heading rename across ~25 files; no behavior.
- **`LM` → `BaseLM`** type-hint migration (commit 940629124) — N/A: dspy4s has a single
  `LanguageModel` abstraction, no `LM`/`BaseLM` split.
- **litellm capability plumbing** (`supports_function_calling`, `supports_response_schema`,
  `supported_params`, `supports_reasoning`) — out of scope: the port does no provider
  capability gating and has no native function-calling path.
- **Unported subsystems** — BetterTogether, GEPA, MIPROv2, BootstrapFinetune, Optuna,
  KNN, Avatar, RLM, retrievers, propose, Embedder, fine-tuning, DummyLM, module
  state-persistence, `inspect_history` rendering, restricted-pickle cache. All changes
  to these are out of scope by absence.

**Net actionable work is ~6 items below**, plus one cross-cutting feature decision
(type-mismatch warning) and the version bump.

---

## Phase 0 — Decisions to make before coding

- [ ] **D1. Port the type-mismatch warning feature?** (spans `constants.py`, `field.py`,
      `settings.py`, consumed in `predict.py`). It's diagnostic-only (warns, never raises)
      and is **largely redundant** on the typed `Predict[I, O]` surface (types enforced at
      compile time). Only meaningfully applies to the untyped `DynamicPredict` record path.
      → Recommend **skip as a unit** unless `DynamicPredict` diagnostics are wanted. Half-porting
      (flag without warning, or vice versa) gives no value.
- [ ] **D2. Port `ContextWindowExceededError`?** Adding the typed error is cheap and correct,
      but its *value* depends on whether any adapter/module truncate-and-retry fallback that
      catches it is (or will be) ported. dspy4s adapters don't implement that fallback today.
      → Recommend **add the error type** (LM error fidelity) but treat the fallback consumer
      as a separate, later item.
- [ ] **D3. Message-parity for duplicate-field / empty-response errors?** dspy4s already
      *rejects* these cases but with generic messages. Decide whether exact upstream message
      text matters (it generally doesn't for a port).

---

## Phase 1 — core / signatures / settings

- [ ] **C1. [verify, likely no-op] Empty/null LM response → parse error.**
      Upstream `adapters/base.py` (commit b833bc559): empty/null LM output now raises
      `AdapterParseError("The LM returned an empty or null response.")` instead of silently
      null-filling output fields.
      - dspy4s already errors: `ChatAdapter.scala:73-76` and `JSONAdapter.scala:78-92` return
        `Left(ParseError(...))`; there is no null-fill path to remove.
      - **Action:** per bug-fixing workflow, write a reproducing test first — confirm the
        **multi-output** empty-text case (`ChatAdapter.scala:78-85`, `JSONAdapter.scala:96-112`)
        returns a parse error. Optionally unify the message to mirror upstream (see D3).

- [ ] **C2. [out of scope] `IS_TYPE_UNDEFINED` constant** (new `dspy/utils/constants.py`).
      Only consumer is the type-mismatch warning (D1). If D1 is taken, model it as a typed
      `FieldSpec.typeUndefined: Boolean` set by `SignatureParser.parseField`
      (`SignatureParser.scala:43-46`) — **do not** introduce a magic-string key (per the
      "no magic string keys" convention).

- [ ] **C3. [out of scope] `warn_on_type_mismatch` default flag** (`dsp/utils/settings.py`).
      If D1 is taken: add `warnOnTypeMismatch: Option[Boolean]` to `RuntimeContext`
      (`Runtime.scala:73-89`) + thread through `fillFrom` (`Runtime.scala:102-115`).

- [ ] **C4. [add error type] `ContextWindowExceededError`** (`dspy/utils/exceptions.py`).
      If D2 is taken: add a case to the `DspyError` sealed trait (`Errors.scala:3-20`), e.g.
      `final case class ContextWindowExceededError(model: Option[String], message: String =
      "Context window exceeded") extends DspyError`. (Note: `LanguageModels.scala:139` already
      references it in a commented example.)

- [ ] **C5. [no action] Signature duplicate-field check, `Settings.load` allow_pickle,
      docstring/typo fixes, Python 3.14 cloudpickle guards, `dsp/utils/utils.py`,
      `utils/hasher.py`.** All either already-matching behavior, Python-specific, or
      docstring-only. dspy4s has no unsafe pickle loader to gate (uses JSON `dumpState`/
      `fromState`); `Settings.load`'s `allow_pickle` security fix has no analog.

- [ ] **C6. [note, do not port] Deprecated field args.** `field.py` adds deprecation warnings
      for `prefix`/`format`/`parser` on `InputField`/`OutputField`. dspy4s's typed field
      wrappers take none of these — nothing to warn about. **Forward note:** if a user-facing
      field-metadata API is ever added, do NOT add `prefix`/`format`/`parser`.

---

## Phase 2 — lm / clients

- [ ] **L1. [if D2] Detect context-window overflow in the provider.**
      Upstream `clients/lm.py`/`base_lm.py`: catch the provider's native context-window error
      and re-raise `dspy.ContextWindowExceededError(model=...)`.
      - **Action:** in `OpenAiClient.statusError`/`invoke` (`OpenAiClient.scala:107`), detect the
        OpenAI 400 with body `code: "context_length_exceeded"` and return
        `Left(ContextWindowExceededError(model))` instead of a generic `RuntimeError`.

- [ ] **L2. [already matches — no action] Defensive usage parsing, usage-tracking guard,
      cache get/promote refactor, BaseLM callback dispatch.**
      - Usage: `ProviderResponseParser.parseUsage` already returns `None` when absent
        (`ProviderLanguageModel.scala:133`); `ManagedLanguageModel.trackUsage` already guards
        `usageEnabled && !response.cacheHit` (`ManagedLanguageModel.scala:190`).
      - Cache: `CompositeLmCache.get` already does memory→disk lookup with disk-hit promotion
        and delete-on-failure (`CacheRuntime.scala:324`, `185-188`).
      - Callbacks: `CallbackDispatcher.withLm` already emits LM events for every
        `LanguageModel` (`CallbackDispatcher.scala:63`).

- [ ] **L3. [out of scope] Capability properties** (`supports_function_calling`,
      `supports_reasoning`, `supports_response_schema`, `supported_params`).
      Inert in 3.2.1 base classes; no consumer in the port. Becomes a prerequisite only if
      native function calling / `response_format` negotiation is later ported — at which point
      decide where to surface these on `LanguageModel`.

- [ ] **L4. [no action] Restricted-pickle cache, `disk_serialization.py` (new),
      `configure_cache(restrict_pickle, safe_types)`, fine-tuning (`provider.py`,
      `utils_finetune.py`), `Embedder`, `parallelizer` sequential fast-path,
      `inspect_history` file/colors.** All Python-pickle-specific, unported subsystems, or
      docstring-only. dspy4s's `DiskLmCache` reads a fixed `Persisted*` shape via Java
      serialization — no allowlist surface.
      - **Forward notes** (carry the *fixed* semantics if ever ported): Embedder `caching`
        truthiness now treats explicit `False` as opt-out; parallelizer adds a `numThreads==1`
        branch; `lm.py` responses-conversion is now one `input` item per message.

---

## Phase 3 — adapters

- [ ] **A1. [verify, likely already matches] `ensure_ascii=False` in JSON output.**
      Upstream `json_adapter.py` (commit a3874e343): `json.dumps(..., ensure_ascii=False)` so
      non-ASCII (e.g. "café", "naïve") is emitted literally, not `\uXXXX`.
      - dspy4s uses `ujson.write` (`JSONAdapter.scala:57`) / `value.render()` (`:177`).
        ujson/upickle write UTF-8 literally by default → **likely already matches**.
      - **Action:** add a diacritics round-trip test to `JSONAdapterSuite.scala`. If ujson
        escapes, set a non-ASCII-preserving write option (then this becomes PORT NEEDED).

- [ ] **A2. [by construction — no code change] `_annotation_is_subclass` guard**
      (`adapters/utils.py`, commit 35613ab51). Guards Python `issubclass` against non-class
      annotations. dspy4s dispatches over the closed `TypeRef` ADT (`ChatAdapter.scala:217-235`,
      `JSONAdapter.scala:158-172`) with total `case _ =>` fallbacks — no hazard.
      - **Action:** audit that `TypeRef` handles tool-arg / non-class shapes gracefully (it does).

- [ ] **A3. [no action] Everything else in adapters.** `LM`→`BaseLM` hints (single abstraction),
      litellm capability checks (no gating), native function-calling refactor (unported),
      `ContextWindowExceededError` import swap (no litellm coupling), and the unported custom
      types — `Citations`, `Document`, `File`, `History`, `Image`, `Reasoning`, `Code`, `Tool`
      (all changes there are docstring-only **except** Image's new `verify` SSL flag, relevant
      only if `Image` is ported). `TwoStepAdapter` is not ported.

---

## Phase 4 — programs (primitives / predict)

- [ ] **P1. [PORT — real new behavior] Extra-field warning in Predict.**
      Upstream `predict.py` `_forward_preprocess`: warn when inputs contain keys not in
      `signature.input_fields` (extra fields are ignored). dspy4s silently drops them.
      - **Action:** in `PredictEngine.buildInvocation` (`PredictEngine.scala:74-87`), when
        `call.inputs.keys -- layout.inputFields.map(_.name)` is non-empty, log a warning via the
        runtime/callback. (`Predict.forward` `Predict.scala:61-68` validates *missing* inputs but
        not *extra* ones.)

- [ ] **P2. [if D1] Type-mismatch warning.** Relevant only to the untyped `DynamicPredict`
      record path (typed `Predict[I, O]` enforces at compile time). Requires C2 + C3.

- [ ] **P3. [cosmetic alignment] Drop hardcoded reasoning/code prefixes.**
      Upstream `chain_of_thought.py` and `program_of_thought.py` removed explicit
      `prefix=`/`format=str`, letting the adapter derive name-based markers.
      - **CoT:** `ChainOfThought.scala:150-156` hardcodes `prefix = Some("Reasoning:")`.
        Setting `prefix = None` makes `SignatureLayout.normalize` (`:113-122`) derive the same
        `"Reasoning:"` marker → effectively a **no-op on output**, aligns intent.
      - **PoT:** `ProgramOfThought.scala:67-101` hardcodes `"Code:"`/`"Previous Code:"`/etc.
        Dropping them **does** change on-wire markers (port field names differ from Python's old
        prefixes). Verify against `ProgramOfThoughtSuite` for pinned strings before changing.
      - `ProgramOfThought._parse_code` simplification already matches (`extractCode`
        `ProgramOfThought.scala:238-248` only does fence extraction).

- [ ] **P4. [no action] `example.py` (+291), `module.py` (+137).** Despite the line counts,
      **every** code-bearing change is docstrings. No behavioral change.

- [ ] **P5. [no action / deliberately not mirrored]** `load_state`/`allow_unsafe_lm_state`
      / `_sanitize_lm_state` (no module state persistence in port), `inspect_history(file=)`
      (no history buffer), `react.py` import swap (no context-window truncation in port),
      RLM + `repl_types.py` + `python_interpreter.py` + `runner.js` (RLM unported; port uses
      a subprocess interpreter by design), `best_of_n`/`refine`/`parallel`/`code_act`/`knn`
      (docstring-only; KNN unported).

---

## Phase 5 — evaluate

- [ ] **E1. [no action] All evaluate changes.** `auto_evaluation.py` (`SemanticF1`,
      `CompleteAndGrounded` now return `Prediction(score=...)`) — these LLM-judge metrics are
      **not ported** (blocked: `Metric.score` `EvaluateContracts.scala:14` has no
      `RuntimeContext`, so a metric can't call an LM). `metrics.py` — docstring-only;
      `BuiltinMetrics.scala`/`NormalizeText.scala` unaffected. `dummies.py` (`DummyLM`) — no
      analog in the port; the new structured-response contract already matches dspy4s's design
      (runtime owns bookkeeping, `LanguageModel.call` returns a typed `LmResponse`).

---

## Phase 6 — optimize / teleprompt / retrievers / streaming / propose / misc

- [ ] **O1. [no action] All teleprompt changes.** Ported optimizers (`LabeledFewShot`,
      `BootstrapFewShot`, `BootstrapFewShotWithRandomSearch`) saw only:
      - `bootstrap.py` — docstring clarification of `max_rounds` rollout/temperature; **code
        unchanged** in 3.2.1.
      - `utils.py` `create_n_fewshot_demo_sets` `metric_threshold` fix — **already matches**
        (`BootstrapFewShotWithRandomSearch.scala:67,93` already thread `metricThreshold`).
      Everything else (BetterTogether +695 full rewrite, GEPA, MIPROv2, BootstrapFinetune,
      Optuna, KNNFewShot, grounded_proposer) is **unported**.

- [ ] **O2. [no action] retrievers / streaming / propose / colbert.** No retrievers or propose
      module in the port. Streaming changes (`messages.py`, `streamify.py`) are docstring-only.

- [ ] **O3. [version bump] Update the upstream-target version string to `3.2.1`** wherever it's
      tracked (branch is already named after the version per convention).

---

## Deliberately NOT mirrored (document in the upgrade PR)

These 3.2.1 additions target machinery the port intentionally omits — list them so they're
not mistaken for regressions:

- Module state-persistence (`load_state`/`dump_state`, `allow_unsafe_lm_state`, `allow_pickle`).
- `inspect_history` console rendering (`file=`, color toggles) — history lives in
  `RuntimeContext`/`RuntimeEnvironment` as structured records.
- `DummyLM` test utility (suites use ad-hoc `LanguageModel` stubs).
- Restricted-pickle disk cache / `disk_serialization.py` (Java-serialization, fixed shape).
- litellm capability metadata on the LM.

## Pre-existing gaps surfaced (NOT 3.1.3→3.2.1 items — track separately)

- Unported: KNN, Avatar, RLM, BetterTogether, GEPA, MIPROv2, BootstrapFinetune,
  retrievers, propose, Embedder, TwoStepAdapter, custom adapter types (Citations/Document/
  File/History/Image/Reasoning/Code/Tool-as-type).
- `ReAct` lacks context-window-overflow trajectory truncation.
- `BootstrapFewShot` rounds don't vary `temperature=1.0`/`rollout_id` per round (3.2.1 now
  documents this as intended upstream behavior).
- **Carry-forward bug fixes if those modules are later ported:** MIPROv2
  `raw_chosen_params` (`instruction_idx` → `demos_idx`); Embedder `caching` truthiness;
  parallelizer `numThreads==1` path; `lm.py` per-message responses conversion.

## New public API symbols in 3.2.1

- `ContextWindowExceededError` — newly exported at `dspy.*` (see C4/D2).
- `EmbeddingsWithScores` — from `dspy.retrievers` (only relevant if retrievers are ported).
