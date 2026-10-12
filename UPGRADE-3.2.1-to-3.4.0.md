# Porting checklist: dspy 3.2.1 → 3.4.0

Target upstream: **dspy 3.4.0 plus 25 commits** (`f1260ff14`, 2026-10-08). The declared parity target of dspy4s
is 3.2.1 (see [UPGRADE-3.1.3-to-3.2.1.md](UPGRADE-3.1.3-to-3.2.1.md)).

Source of truth: `git diff 3.2.1..f1260ff14 -- dspy` in `/Users/jpablo/GitHub/dspy`. The range has 282 commits
and 198 changed files (46,189 insertions, 1,565 deletions). The vendored `dspy/_vendor/lm15` client accounts for
33,906 of those lines. Approximately 12,000 changed lines remain in the code that dspy4s ports.

This document classifies the upstream changes. The gap and defect ledger is
[docs/port/PORT_GAPS.md](docs/port/PORT_GAPS.md), entries **G-23 to G-50**. Each item below gives the entry that
holds the details.

## Method and status (analysis of 2026-10-11)

Five review passes read the two codebases by area: LM clients, adapters and signatures, program modules,
interpreters and runtime, and optimizers. A second read of the dspy4s code then confirmed the items marked **C**.

- **C**: a second read of the dspy4s code confirmed the item.
- **R**: one review pass reported the item after a read of the two sides. No second read was done.
- **I**: the review pass inferred the item from one side.

No build and no test ran for this analysis. Each defect comes from a code read. Write a failing test before each
fix.

Checkbox legend: `[ ]` open, `[x]` done, `[-]` decided against.

## Implementation status

### Batch 1 (2026-10-11): eight defects that need no decision

Each correction started from a failing test. The full suite passes: 1,030 tests, 0 failures, 5 live tests skipped.

- **G-24** cache key covers the default options of the model.
- **G-25** stream wrapper forwards the capability flags and keeps the type of each failure (part of L2).
- **G-33** an error without an id does not desynchronize the sandbox session (I2); a host tool that throws gets an
  error reply.
- **G-35** an interpreter failure ends an RLM or CodeAct run (P1).
- **G-37** truncation keeps the last trajectory entry (P3).
- **G-40** the callback end event fires when the body is interrupted (part of I9).
- **G-44** GEPA selection and merge exclude dominated candidates.
- **G-46** an empty devset is a validation error (O2).

### Batch 2 (2026-10-11): decision D2 and what it unlocks

The full suite passes: 1,119 tests, 0 failures, 5 live tests skipped.

- **Foundation.** `PredictorKey` in each predictor trace entry, `PredictorTraces` (distinct keys, entries by leaf,
  missing key), and `TracedRun` (the trace of one run; the metric runs in the isolated context).
- **G-42** `BootstrapFewShot` follows upstream: demos for each predictor, the labeled pool, error counts, the trace
  for the metric, retry rounds, and teacher priming (O1).
- **G-44** the GEPA adapter finds the call of a leaf by its key, with the upstream selection, the failure option, and
  no proposal for empty reflective data.
- **API change.** `Gepa.compile` and `GepaEngine.optimize` return `Either[DspyError, GepaResult[P]]`.
- **Rule for leaf types.** The module of a leaf type must use `ModuleLifecycle.predictor` or
  `ModuleLifecycle.dynamicPredictor`. An optimizer returns an error for a leaf type that records no key.

Open points from batch 1, for the owner:

- The random search rejects an explicit empty validation set. Upstream uses the trainset.
- A `SubprocessPythonInterpreter` timeout now ends a CodeAct run.
- The sandbox setup read still skips a response with a different id. Upstream stops.
- A custom `LanguageModel` wrapper must forward `defaultOptions` and the capability flags by hand.

## TL;DR

- Upstream replaced its LM client with a native engine (`lm15`). The engine itself is not a port target. Its
  typed errors, retry classes, stream checks, and strict schemas are.
- Upstream fixed approximately 60 bugs in areas that dspy4s ports. Many do not apply, because immutable values and
  typed inputs prevent them. Approximately 25 apply.
- The review also found parity defects that are older than 3.2.1. The largest are in `BootstrapFewShot`, in the
  GEPA engine, and in the LM cache key.
- Upstream marks four new features as experimental: decision types, ReAnchor, `dspy.Flex`, and ReActV2. Wait for
  them to become stable.
- Upstream deprecated `CodeAct` and `ProgramOfThought`. It removes them in 3.5 and names `RLM` as the replacement.

## Phase 0: decisions to make before the work

- [ ] **D1. `CodeAct` and `ProgramOfThought`.** Upstream deprecated them (99bb44bf4). Decide: add `@deprecated`, or
      keep them and record the decision in `PORT_DIFFERENCES.md`. The decision sets the priority of G-39 and of
      the shared-interpreter part of G-35.
- [x] **D2. Trace entry to optimizable leaf.** `BootstrapFewShot` (G-42) and the GEPA adapter (G-44) must find the
      trace entries of one predictor. Upstream uses object identity and signature equality. dspy4s needs one rule
      for the two optimizers.
      **Decided 2026-10-11: the metadata key.** A predictor writes its key into each trace entry: the module name
      and the signature structure, which is the data of `OptimizableMetadata`. The `frame` law keeps that data
      stable across candidates. The outermost predictor on a call path owns the key, so the `Predict` inside
      `ChainOfThought` writes none. Three rules follow:
      - Two leaves with the same key are an error. The message tells the user to give each predictor a name.
      - A leaf type that sets no key is an error, not a leaf to skip. The optimizer detects it when the module ran
        but no entry has its key.
      - Each optimizer keeps its upstream policy for a leaf that ran more than one time in a run.
      An injected leaf id (the exact identity that upstream Bootstrap gets from object identity) was rejected for
      now: each leaf type must then carry a label. The metadata key stays valid if that id comes later.
- [ ] **D3. Prompt parity policy.** The adapter prompts are not byte-equal to upstream (G-30, G-37). Decide: match
      upstream exactly, or record each difference as intended.
- [ ] **D4. ReAct default iteration limit.** dspy4s uses 5 and upstream uses 20 (G-37).
- [ ] **D5. Optional output fields.** `FieldSpec` has no optional flag, so the adapters cannot apply the upstream
      rule (G-29).
- [ ] **D6. Experimental tracks.** Confirm that decision types, ReAnchor, `dspy.Flex`, and ReActV2 wait (G-49,
      G-19).

## Phase 1: lm / clients

- [ ] **L1. Typed LM errors and retry classes** (e608bc024, 2cda3872d). Errors carry `status`, `provider_code`,
      `request_id`, and `retry_after`. Retries use transient classes only. → G-23 (C)
- [ ] **L2. Stream guard** (2cda3872d, 7fd361214, 661467b77). A stream without an end event is an error, and the
      typed error survives. → G-25 (C)
- [ ] **L3. One call path for streamed calls**: cache, retry before the first token, usage, and history. → G-25 (C)
- [ ] **L4. Strict response schema** (40a6e1689): `strict: true` with a schema shaped for strict mode. → G-26 (C)
- [ ] **L5. Capabilities as data**, declared for each server, with unknown treated as false. → G-26 (R)
- [ ] **L6. Timeouts** (7fd361214): separate connect and read limits, and an idle limit for streams. → G-26 (R)
- [ ] **L7. Responses request and output shapes** (0312f0da6, 891958702, 24ec85de4). → G-26 (R)
- [ ] **L8. Connection lifecycle**: `close` on the client, and one HTTP client for each configuration. → G-26 (R)
- [ ] **L9. Versioned plain-data cache record** in place of Java serialization. → G-24 (R)
- [ ] **L10. Advisory cost estimate** and nested cost roll-up (bb8ba59c0). → G-27 (R)

## Phase 2: adapters / signatures / streaming

- [ ] **A1. Optional and default output fields** (ba8a64958): a missing optional output takes its default. → G-29 (C)
- [ ] **A2. Fallback from ChatAdapter to JSONAdapter after a parse error only** (2cda3872d), and never after
      visible stream output. → G-29 (R)
- [ ] **A3. Tool call ids, `ToolCallResults`, and native tool history** (2ec31b3aa, a0fcc1e37). → G-28 (R)
- [ ] **A4. `dspy.History` with output-format reminders in replay** (f207d35cf). → G-28 (R)
- [ ] **A5. Boundary whitespace in ChatAdapter stream chunks** (f4a428853). → G-31 (R)
- [ ] **A6. Nested XML output** (33aaa19e0). → G-32 (R)
- [ ] **A7. `append_instructions` on signatures** (498760149). → G-48 (R)
- [ ] **A8. Decision types `Noul`, `Score`, `Choice`** (e83978c08, 4c6448e03). Experimental. → G-49 (I)

## Phase 3: programs

- [x] **P1. Interpreter failure is terminal** (89696e0cc). → G-35 (C)
- [ ] **P2. One interpreter for each invocation, from a factory** (623360078, 6fb74fcf4, 430af8d87). → G-35 (R)
- [x] **P3. ReAct truncation with one tool call** (7eb5acce2). → G-37 (C)
- [ ] **P4. Echo for a single-line assignment in `_parse_code`** (fe75476cd). → G-39 (C)
- [ ] **P5. RLM namespace checks** (74194bfab). → G-38 (R)
- [ ] **P6. RLM truncation with an odd limit** (35ef21f16). → G-38 (R)
- [ ] **P7. Deprecation of `CodeAct` and `ProgramOfThought`** (99bb44bf4). → D1, G-39 (C)
- [ ] **P8. Sandbox `dspy` facade and sub-agents in RLM** (ba3f9198e). Large. → G-38 (I)
- [ ] **P9. `dspy.Flex`** (e4e97aae2, f70d08a5b, 573cfe707). Experimental. → G-49 (I)
- [ ] **P10. ReActV2 changes** (b3897e271, ca54a855a, 05728f6ad): reserved outputs, an error when the forced
      submit gives no outputs, and an async path. It is still experimental. → G-19 (R)

## Phase 4: interpreters / runtime

- [ ] **I1. Vendor the current `runner.js`** (80b118e52, ab8a7658d, 07a0f7aa6): Pyodide version pin, frozen
      globals, a private control exception, and no read access to the Deno cache. → G-34 (C)
- [x] **I2. Unsolicited sandbox error** (6334c2ba5). → G-33 (C)
- [ ] **I3. Request ids and recursion guard** (80b118e52). → G-33 (R)
- [ ] **I4. Mount collisions** (072c72164). → G-33 (R)
- [ ] **I5. Launch flags, Deno version check, probe timeout** (07a0f7aa6). → G-34 (R)
- [ ] **I6. Persistent local Python REPL** (d58c3007e, 453823209). → G-36 (R)
- [ ] **I7. `execution_instructions`** (0b93fb148, 750017192). → G-35 (R)
- [ ] **I8. `SandboxSerializable`** (ed4d479a1). → G-38 (R)
- [ ] **I9. Callback events**: interpreter lifecycle (b11cfe874), optimizer compile (80553206b), and the end event
      for base exceptions (970f72900). → G-40 (C for the end event, R for the others)
- [ ] **I10. MCP**: SDK v2 (32bcd36ce) and structured results (ca0a088bd). → G-47 (R)
- [ ] **I11. `inspect_history` renders tool calls** (571171227). → G-27 (R)

## Phase 5: optimize / gepa / evaluate

- [x] **O1. Labeled demos sampled for each predictor** (1bc87de15). It needs per-predictor demos first. → G-42 (C)
- [x] **O2. Empty devset is an error** (c7ee4462a). → G-46 (C)
- [ ] **O3. GEPA `InstructionProposer` as the default proposer** (b6c8aaf1e). → G-45 (R)
- [ ] **O4. GEPA objective-aware frontiers** (638e155cf). → G-45 (R)
- [ ] **O5. GEPA multiple proposals with parallel evaluation** (aa9d2d053, 822f39319). → G-45 (R)
- [ ] **O6. ReAnchor calibration optimizer** (e8ec009c6). Experimental. → G-49 (I)

## Checked and already correct in dspy4s

These upstream fixes need no work. Immutable values and typed inputs prevent the defect, or the port already has
the behavior.

| Upstream commit | Subject | Why no work |
|---|---|---|
| 59ce7601e, 735b2d6a0 | `Example` hash and copy | `Example` is an immutable case class. |
| c88b27907 | Transactional `load_state` | `ProgramPersistence` builds a new value or fails. |
| 485ce4454 | `None` results in `ParallelExecutor` | Results are `Option` values by position. |
| 96bae53d4 | Call lineage in parallel workers | `ContextPropagation` carries `RuntimeScope.callStack`. |
| bb8ba59c0 | Nested usage roll-up | Nested trackers record each call immediately. |
| faa99d1f7 | `None` usage from the Responses API | `ProviderLanguageModel` handles an absent usage. |
| 3f06959eb | GEPA trace alignment when the program fails | `GepaAdapter` indexes by batch position. |
| 6a4afda97 | Typed ReAct context-window error | `ReAct` returns `ContextWindowExceededError`. |
| 587cf8e0c | Context in batched RLM subqueries | `RLMSandboxTools` passes the context explicitly. |
| 579e6a2f9 | RLM tools through `dspy.Tool` | `DenoPyodideInterpreter` wraps each host tool call in `CallbackDispatcher.withTool`. |
| b175ef87e | Symlinks in Deno read paths | `DenoPyodideLaunch` resolves the real path. |
| b51596e9e | Stale `tool_choice` | `format` returns the request options as a value. |
| 67bf5eb48, 88673867a | Tool argument schema | `ToolParameterSpec.required` is explicit. |
| 17928f815, 7db82cd28 | Input warnings and type checks | Typed inputs are checked by the compiler. |
| eaac7ed03 | Forward guard cost | `forward` is protected and `apply` is final. |

## Deliberately not mirrored

- The `lm15` engine, `backend_selection.py`, the legacy engines, and the LiteLLM fallback: Python client
  architecture. dspy4s keeps one `LanguageModel` trait and one HTTP transport.
- Lazy imports and lazy package initialization (3c584233b, 46c88455f, b04a07335, 621c3a614, da1f0871e).
- Dependency changes: xxhash to sha256 (856857195), asyncer removal (732355437), typeguard removal (7db82cd28),
  Pydantic 2.11 (03641a2ee).
- LM state save and load (492c5597a, 0aa9eeb95) and `pickle_by_value.py`: dspy4s constructs models in code and
  saves JSON parameters only.
- Async tools in the interpreter (62f5c43ec, 079ef05aa): dspy4s tools are synchronous.
- Fixes for types that dspy4s does not have (c69136b29, 564d24cd8, b28682b97, 1c9798f5e). See G-32.
- Fixes for unported optimizers: `BootstrapFinetune` and `BetterTogether` (bc8af7ed3, 6a6ff5561), the Optuna
  helper (d97ba839f), Avatar (99427f8e2). G-13 to G-17 are unchanged.
- `restrict` validation in random search (965a39594) and optional `eval_kwargs` in COPRO (a1fd9261b): dspy4s has
  no such parameter.
- Model metadata refresh commits and documentation commits.

## Documents that were out of date before this analysis

- `PORT_GAPS.md` G-12 excludes multi-objective frontiers because the dspy surface did not use them. Upstream now
  uses them (O4).
- `PORT_GAPS.md` lists `inspect_history` as resolved. The history payload holds no prompt and no completion (G-27).
- `PORT_MAP.md` says that `llm_query_batched` is sequential. The code is parallel.
- `PORT_MAP.md` records the caller-owned interpreter of `CodeAct` as intended. Upstream changed to one interpreter
  for each invocation (P2).
- `PORT_GAPS.md` G-22 names LiteLLM as the Anthropic reference. Upstream now has a native Anthropic adapter in
  `dspy/_vendor/lm15/providers/anthropic.py`.
