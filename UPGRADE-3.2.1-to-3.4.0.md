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

### Batch 3 (2026-10-11): typed LM errors, and decisions D1 and D4

The full suite passes: 1,151 tests, 0 failures, 6 live tests skipped. The count was 1,176 before the removal of the
two programs.

- **G-23** an LM failure has a class (`LmError` and 12 classes with the upstream codes) and details: model, provider,
  provider code, status, request id, and retry hint. One mapper serves the chat call, the streamed call, and the
  embedder (L1).
- **G-23** a retry policy retries the transient classes only, and waits for the `Retry-After` hint. This was the last
  defect of the first table that needed no decision.
- **G-37** the default of `ReAct.maxIterations` is 20 (decision D4).
- **G-39** `CodeAct` and `ProgramOfThought` are removed (decision D1), with `GeneratedPython`, their guides, their
  type class instances, and their tests: approximately 900 main lines, 1,000 test lines, and 1,200 document lines.
  Four tests of shared machinery moved to `RLM` and `ReAct`. P4 and P7 are closed.
- **Open after the removal.** `RLM` has no `Streamable` instance, so `Streamify` cannot run it. These public items
  have no user in main sources now: `SubprocessPythonInterpreter`, the plain `CodeInterpreter` trait, and the
  rejection branch of `InterpretedTrajectoryAgent` (`ActionPreparation.Rejected`).
- **Change in behavior.** A retry policy without an explicit predicate does not retry a `RuntimeError`. A custom
  `LanguageModel` must return a transient `LmError` to get a retry.
- **Live test guard.** The test of `OpenAiLiveSuite` for a wrong API key had no opt-in guard, so each test run sent
  a request to the OpenAI API. It now has the guard of the other live tests, and a plain `sbt test` makes no
  network request from this suite.
- **Format.** Four files of batch 2 had format defects, and they are corrected. `sbt fmtCheck` stops at two old
  failures and hides new ones. Use the scalafmt command-line tool with `--list` as the check.

### Batch 4 (2026-10-11): the sandbox runner, the session rules, and the interpreter factory

The full suite passes: 1,197 tests, 0 failures, 6 live tests skipped. The sandbox tests ran against Deno 2.7.4.

- **G-34** `runner.js` is the upstream file of f1260ff14, with three comment lines of dspy4s. The launch command has
  the isolation flags, the Deno version check, and the cache directory argument (I1, I5).
- **G-33** each request has a random id, one `execute` runs at a time, a session does not start again after a failure
  or after `close`, and mount names cannot collide (I3, I4).
- **G-35** the runtime context has an interpreter factory, and `RLM` selects its factory for each call (P2).
- **API changes.** `RLM.interpreterFactory` is `Option[RLM.InterpreterFactory]`. `DenoPyodideInterpreter.OutputField`
  is now `SandboxOutputField` in `core.contracts`.
- **Change in behavior.** The sandbox runs Pyodide 0.29.4, and sandboxed code cannot read the Deno cache directory.
- **A defect of batch 2, corrected.** The flat `RuntimeContext.copy` lost `scope.withinPredictor`. No library code
  used that path. A test failed first.
- **Not in this batch.** The runtime description of an interpreter (I7) waits for decision D3, because it changes the
  RLM prompt.

### Batch 5 (2026-10-11): tool call ids, the conversation history, and two GEPA engine rules

The full suite passes: 1,357 tests, 0 failures, 6 live tests skipped.

- **G-28** a tool call keeps its provider id, a request can carry tool calls and tool results, and a signature takes
  the conversation history as an input field of type `History`. The Chat, JSON, and XML adapters replay it as
  earlier turns (A3, A4).
- **G-44** a new GEPA candidate takes the largest round-robin pointer of its parents, a skipped iteration does not
  move the pointer, and the merge search makes 10 attempts of 10 pair draws with the overlap floor of 5.
- **G-45** a second read of the upstream GEPA code corrected the ledger: objective frontiers (O4) and more than one
  proposal (O5) are options upstream, and their defaults agree with dspy4s. Only the instruction proposer (O3)
  changes default behavior.
- **API changes.** `ToolCall` has a third field, `id`. `Message` has `toolCalls`, `toolCallId`, and `name`.
  `GepaConfig` has `mergeValOverlapFloor`.
- **Changes in behavior.** The typed decode gives `None` for an optional field that a record lacks. A GEPA run with a
  validation set of fewer than 5 examples makes no merge by default. The results of a GEPA run with more than one
  component change, because the pointer rule changed.
- **Not in this batch.** The instruction proposer (O3), the objective frontiers (O4), more than one proposal (O5),
  resume, the stop reason of a run, and the facade.

### Batch 6 (2026-10-12): the GEPA instruction proposer

The full suite passes: 1,408 tests, 0 failures, 6 live tests skipped.

- **G-45** `InstructionProposer` is a trait, and `DefaultInstructionProposer` is the default proposer of `Gepa` and
  `GepaEngine` (O3). It runs a predictor over the upstream signature, with its own JSON adapter, and reads the new
  instruction from the field `new_instruction`. The options are `additionalInstructions`, `baseInstructions`,
  `maxChars`, and `adapter`.
- **G-44** the engine calls the proposer one more time after a failure. A second failure gives no proposal.
- **Decisions of the owner.** The proposer writes the JSON text of the records itself. The first version has no
  `skills` and no `truncate_history_outputs`. The budget stays a hard limit.
- **API changes.** `ReflectiveRecord` holds values (`inputs` is a record, `generatedOutputs` is a value).
  `InstructionProposer` is a trait, and the function `InstructionProposer.propose(currentInstruction, records, lm)`
  is gone. `Gepa` and `GepaEngine` have one more parameter with a default. `InstructionCharLimit` is new.
- **Changes in behavior.**
  - The reflection LM gets another prompt, and its response must be a JSON object. The results of a GEPA run thus
    change.
  - A response with no instruction gives no child. Before, the engine evaluated a child with an empty instruction.
  - A reflection call that fails gets one more call.
  - With more than one selected component, the failure of one component drops the texts of all of them.
  - The text of a parse failure in a reflective record ends with two line ends, as upstream.
  - A reflection call is a predictor call. A callback handler of the context thus gets its module events and its LM
    events, with the names `gepa_propose_instruction` and `gepa_compress_instruction`.
- **Open points, for the owner.**
  - A response `{"new_instruction": null}` gives the instruction `null`, because the JSON adapter gives the JSON text
    of a value that is not a string. Upstream fails there. The correction is in the adapter, and it changes each
    program (decision D5).
  - The JSON adapter shows no field description (G-37), so the descriptions of the proposal signature do not reach
    the reflection LM with the default adapter. They do with `adapter = ChatAdapter()`.
  - An exception from a proposer of the caller goes to the caller. Only a `Left` gets the second call.
- **Not in this batch.** The objective frontiers (O4), more than one proposal (O5), resume, the stop reason of a
  run, and the facade.

### Batch 7 (2026-10-12): the GEPA objective frontiers

The full suite passes: 1,457 tests, 0 failures, 6 live tests skipped.

- **G-45** a metric can give objective scores (`ScoreWithFeedback.objectiveScores`), and GEPA keeps them for each
  candidate and each validation example (O4). `GepaConfig.frontierType` selects the Pareto fronts for candidate
  selection and merge: `Instance` (default, as upstream), `Objective`, `Hybrid`, or `Cartesian`.
- **G-44** an evaluation with no traces keeps the score of each run that passes. Before, more than 10 failed runs in
  one batch gave the failure score to each example of the batch. A test failed first.
- **API changes.** `ScoreWithFeedback`, `Trajectory`, and `EvaluationBatch` have an `objectiveScores` field with a
  default. `GepaState` has `valObjectiveScores` and `frontierType`. `GepaConfig` has `frontierType`. `GepaResult`
  has `state`, the final search state. `FrontierType`, `FrontierKey`, and `ScoredBatch` are new.
- **Changes in behavior.** The default frontier type gives the same candidate selection as before. The scores-only
  evaluation no longer runs through `Evaluate`: each example runs in an isolated context, and the error limit of
  the context does not apply.
- **Deliberate differences from upstream.** The objectives of a front are in the order of their names. A later
  candidate with no objective score stays in the pool; upstream stops the run.
- **Open point, for the owner.** A run or a metric that throws an exception still counts for the error limit of the
  executor (10 by default), on the two evaluation paths. Ten exceptions in one batch thus give each example the
  failure score. A failure that is a `Left` does not count.
- **Not in this batch.** More than one proposal (O5), resume, the stop reason of a run, and the facade.

Open points from batch 1, for the owner:

- The random search rejects an explicit empty validation set. Upstream uses the trainset.
- A `SubprocessPythonInterpreter` timeout ended a CodeAct run. This point is closed: batch 3 removed `CodeAct`.
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
  dspy4s removed them in batch 3 (decision D1).

## Phase 0: decisions to make before the work

- [x] **D1. `CodeAct` and `ProgramOfThought`.** Upstream deprecated them (99bb44bf4) and removes them in 3.5.
      **Decided 2026-10-11: remove them now.** The owner chose deprecation and asked to consider an early removal,
      so that no more work goes into the two programs. The removal costs less than a deprecation: `RLM` does not
      use the two classes, the version is `0.1.0-SNAPSHOT`, and a `@deprecated` annotation needs a `-Wconf` rule or
      `@nowarn` at each use under `-Werror`. `PORT_DIFFERENCES.md` section 15 records the difference. The decision
      closes G-39, P4, and P7, and the part of G-35 about one shared interpreter instance.
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
- [x] **D4. ReAct default iteration limit.** dspy4s used 5 and upstream uses 20 (G-37).
      **Decided 2026-10-11: 20, as upstream.** The default of `ReAct.maxIterations` is `IterationLimit(20)`.
- [ ] **D5. Optional output fields.** `FieldSpec` has no optional flag, so the adapters cannot apply the upstream
      rule (G-29).
- [ ] **D6. Experimental tracks.** Confirm that decision types, ReAnchor, `dspy.Flex`, and ReActV2 wait (G-49,
      G-19).

## Phase 1: lm / clients

- [x] **L1. Typed LM errors and retry classes** (e608bc024, 2cda3872d). Errors carry `status`, `provider_code`,
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
- [x] **A3. Tool call ids, `ToolCallResults`, and native tool history** (2ec31b3aa, a0fcc1e37). → G-28 (R)
- [x] **A4. `dspy.History` with output-format reminders in replay** (f207d35cf). → G-28 (R)
- [ ] **A5. Boundary whitespace in ChatAdapter stream chunks** (f4a428853). → G-31 (R)
- [ ] **A6. Nested XML output** (33aaa19e0). → G-32 (R)
- [ ] **A7. `append_instructions` on signatures** (498760149). → G-48 (R)
- [ ] **A8. Decision types `Noul`, `Score`, `Choice`** (e83978c08, 4c6448e03). Experimental. → G-49 (I)

## Phase 3: programs

- [x] **P1. Interpreter failure is terminal** (89696e0cc). → G-35 (C)
- [x] **P2. One interpreter for each invocation, from a factory** (623360078, 6fb74fcf4, 430af8d87). `RLM` selects
      the factory of the program, then of the runtime context, then the Deno sandbox. → G-35 (C)
- [x] **P3. ReAct truncation with one tool call** (7eb5acce2). → G-37 (C)
- [-] **P4. Echo for a single-line assignment in `_parse_code`** (fe75476cd). The parser belonged to
      `ProgramOfThought`, which is removed (D1). → G-39 (C)
- [ ] **P5. RLM namespace checks** (74194bfab). → G-38 (R)
- [ ] **P6. RLM truncation with an odd limit** (35ef21f16). → G-38 (R)
- [x] **P7. Deprecation of `CodeAct` and `ProgramOfThought`** (99bb44bf4). dspy4s removed them (D1). → G-39 (C)
- [ ] **P8. Sandbox `dspy` facade and sub-agents in RLM** (ba3f9198e). Large. → G-38 (I)
- [ ] **P9. `dspy.Flex`** (e4e97aae2, f70d08a5b, 573cfe707). Experimental. → G-49 (I)
- [ ] **P10. ReActV2 changes** (b3897e271, ca54a855a, 05728f6ad): reserved outputs, an error when the forced
      submit gives no outputs, and an async path. It is still experimental. → G-19 (R)

## Phase 4: interpreters / runtime

- [x] **I1. Vendor the current `runner.js`** (80b118e52, ab8a7658d, 07a0f7aa6): Pyodide version pin, frozen
      globals, a private control exception, and no read access to the Deno cache. → G-34 (C)
- [x] **I2. Unsolicited sandbox error** (6334c2ba5). → G-33 (C)
- [x] **I3. Request ids and recursion guard** (80b118e52). → G-33 (R)
- [x] **I4. Mount collisions** (072c72164). → G-33 (R)
- [x] **I5. Launch flags, Deno version check, probe timeout** (07a0f7aa6). → G-34 (R)
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
- [x] **O3. GEPA `InstructionProposer` as the default proposer** (b6c8aaf1e). No `skills` and no
  `truncate_history_outputs`. → G-45 (C)
- [x] **O4. GEPA objective-aware frontiers** (638e155cf). → G-45 (C)
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
- `PORT_MAP.md` recorded the caller-owned interpreter of `CodeAct` as intended. Upstream changed to one interpreter
  for each invocation (P2). Batch 3 removed `CodeAct`, and `PORT_MAP.md` now says so.
- `PORT_GAPS.md` G-22 names LiteLLM as the Anthropic reference. Upstream now has a native Anthropic adapter in
  `dspy/_vendor/lm15/providers/anthropic.py`.
