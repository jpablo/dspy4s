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

### Batch 8 (2026-10-12): more than one GEPA proposal in an iteration

The full suite passes: 1,484 tests, 0 failures, 6 live tests skipped.

- **G-45** an iteration can have more than one proposal task (O5): `GepaConfig.proposalSampling` (`Single`,
  `SameParent(n)`, `Independent(n)`, `PxN(p, n)`), `proposalSelection` (`AllImprovements`, `BestImprovement`,
  `TopK(k)`), and `acceptance` (`StrictImprovement`, `ImprovementOrEqual`). The defaults are those of upstream: one
  task, strict acceptance, all improvements.
- The engine evaluates the parents, the children, and the selected candidates of one iteration in batches.
  `GepaAdapter.batchEvaluate` runs the candidates in parallel and divides the threads of the context between them.
- **API changes.** `GepaConfig` has three new fields with defaults. `MinibatchSampler.sample` has a third parameter
  with a default. `ProposalSampling`, `ProposalSelection`, `ProposalAcceptance`, `Proposal`, and `ProposalCount` are
  new.
- **Changes in behavior.** None with the default configuration: one task gives the same random draws and the same
  metric call counts as before.
- **Deliberate differences from upstream.** The budget stays a hard limit: an iteration starts only when its largest
  possible cost fits. No traces are captured for the children and for the validations.
- **Open points, for the owner.**
  - The engine evaluates in the context that is installed on the thread (`RuntimeEnvironment.current`), and the
    proposer call uses the `using RuntimeContext` value. A caller that passes a context that is not installed gets
    evaluations with no language model. This behavior is old.
  - The limit of 120 seconds for one example of an evaluation still applies in each candidate of a batch.
- **Not in this batch.** Resume, the stop reason of a run, and the facade.

### Batch 9 (2026-10-13): GEPA resume, stop reason, and facade

The full suite passes: 1,506 tests, 0 failures, 6 live tests skipped.

- **G-44** a resumed run continues the iteration index, the pointers, and the merge schedule, and its random draws
  come from the seed and the iteration index. A resumed run thus makes the same draws as a run with no stop.
  `GepaConfig.runDir` is the run directory, also for `Gepa.compile`.
- **G-44** `GepaResult` has `stopReason`, `iterations`, and `maxMetricCalls`. `GepaState.failedProposals` counts the
  proposal tasks whose proposer failed two times.
- **G-45** `GepaConfig.budget` is `MetricCalls(n)`, `FullEvals(n)`, or `Auto(level)` with the upstream formula.
  `Gepa.compile(student, trainset)` uses the trainset as the validation set.
- **Decisions of the owner.** The six decisions of 2026-10-13: `runDir` in `GepaConfig`; random sources from the seed
  and the iteration; the merge schedule in the checkpoint; each checkpoint error as a `Left`; the stop reason in the
  result; and the facade with an optional validation set and a budget of three forms.
- **API changes.** `GepaConfig.maxMetricCalls` is now `GepaConfig.budget`: write
  `budget = GepaBudget.MetricCalls(MetricCallCount(n))`. `GepaEngine.optimize` has no `runDir` parameter.
  `GepaResult` has three new fields. `GepaState` has `iteration`, `merge`, `discoveryCalls`, and `failedProposals`.
  `GepaBudget`, `AutoBudget`, `GepaStopReason`, `MergeSchedule`, and `FullEvalCount` are new.
- **Changes in behavior.**
  - The seeded results of a run change, because the random sources come from the seed and the iteration index.
  - A checkpoint error, a budget below the seed cost, and an empty trainset give a `Left`. Before, they threw.
  - An empty validation set for `Gepa.compile` selects the trainset. Before, the run returned the seed.
  - In a run of the engine, the random sources come from `GepaConfig.seed`. The `seed` parameter of `GepaAdapter`
    now has an effect only on a direct call of `makeReflectiveDataset`. `Gepa.compile` gives the two the same value.
- **Open point, for the owner.** The checkpoint does not keep the cached scores of a merge subsample that the run
  rejected. When a later candidate is equal to that merged candidate, a resumed run evaluates those examples again,
  so its metric call count can differ from a run with no stop. The draws stay the same. This case is rare.

### Batch 10 (2026-10-13): the output rules of the adapters

The full suite passes: 1,537 tests, 0 failures, 6 live tests skipped.

- **G-29** optional and default outputs (A1, decision D5), the fallback from `ChatAdapter` to `JSONAdapter` after a
  parse error (A2), parse errors for values of the wrong type, the first header of a field, the upstream rule for
  incomplete demos, and `tool_calls` as a text field when native function calling is off. This closes the last
  defect of the first table.
- **Decisions of the owner.** The six decisions of 2026-10-13, as listed in G-29.
- **API changes.** `FieldSpec` has `optional`. `Adapter` has `fallback`. `ChatAdapter` has `useJsonAdapterFallback`
  (default `true`). `AdapterDemos` is new.
- **Changes in behavior.**
  - A chat response that does not parse gives one more LM call, in the JSON format.
  - A value of the wrong type is a `ParseError` with the field name. Before, it was a `ValidationError`.
  - An `Option` output that the response does not have is `None`. Before, the call failed.
  - A duplicate header keeps its first value. Before, the values were joined.
  - The prompts of programs with incomplete demos change, and a field default no longer fills a demo.
  - With native function calling off, the prompt asks for `tool_calls`, and the parser reads it from the text.
    Before, the field was always empty. The JSON adapter also accepts a JSON string that has such a list.
  - With native function calling active and no provider calls, the JSON adapter gives an empty `tool_calls`. Before,
    it was a parse error.
  - On a tool turn, a missing text field takes its `defaultValue`, else a null. Before, it was always a null.
  - A demo with no value for the history field of a signature is an incomplete demo, as upstream. The demo rule uses
    all fields of the signature.
  - In a GEPA run, a predictor call that fails to parse gets one more call with the JSON format. The failure record
    has the raw response of that call, and the metric call count of such a run grows. `GepaFailureFeedbackSuite`
    has the new counts.
  - The saved state of a signature has the key `optional`. A state with no such key loads as not optional.
  - `Refine` gives the fallback of the base adapter the same advice, as upstream `WrapperAdapter`.
- **Open points, for the owner.**
  - The XML adapter keeps `tool_calls` as text, and its prompt asks for no format. Upstream parses a list.
  - A typed `Option[Int]` field has the prompt type `str`, because `Option` is a variant. `Optional[int]` in a string
    signature gives `int`.
  - The string syntax splits fields on `,`. Thus `Union[int, None]` and `Optional[dict[str, int]]` do not parse.
  - `Streamify` builds its stream state from the configured adapter, so a JSON fallback call streams through the
    chat stream state.
  - The extraction step of `TwoStepAdapter` gets no JSON fallback. Upstream gets one through `ChatAdapter.__call__`.
  - `Adapter.execute` does not use the fallback. No main code calls it.
  - The JSON string `"3"` for an `int` field is a parse error. Upstream `parse_value` accepts it.
  - An incomplete demo does not show its null inputs, and a null output shows the "Not supplied" text. Upstream
    shows `None`.

### Batch 11 (2026-10-14): the stream guard and one call path for streamed calls

The full suite passes: 1,561 tests, 0 failures, 6 live tests skipped.

- **G-25** the stream guard (L2) and one call path for streamed calls (L3). This closes G-25.
- **Decisions of the owner.** The six decisions of 2026-10-14, all as upstream:
  - A stream is complete when a chunk has a finish reason, a chunk has usage, or `[DONE]` arrives. A stream
    that stops before that is an `LmStreamAssemblyError`, with no retry and no cache entry.
  - A `data:` line that is not valid JSON is an `LmStreamAssemblyError`. A JSON value that is not an object is
    skipped.
  - A mid-stream error event gives the typed error of its code, with the upstream table.
  - A cache hit on a streamed call gives the cached response, and the listeners get no token.
  - A retry is possible only before the first chunk with text or a tool call.
  - Each streamed call has one history entry, and the usage of its chunks is recorded, also after a failure.
- **API changes.** `LmStreamAssemblyError` is new (code `stream_assembly`). `LmChunk` has `cachedResponse`, and
  `LmChunk.cached` builds the chunk of a cache hit.
- **Changes in behavior.**
  - A streamed call through `ManagedLanguageModel` uses the cache, the retry policy, the history, and the usage
    tracker. Before, it used none of them.
  - Under `streamify`, a call that the cache answers gives no token.
  - A stream that stops before its end is an error. Before, the partial text was a success.
  - A `data:` line that is not JSON is an error. Before, the client dropped it.
  - A mid-stream error event is a typed error. Before, it was an empty chunk.
  - An OpenAI stream that ends with `[DONE]` or a usage chunk, but has no chunk with a finish reason, gets a last
    chunk with the finish reason `stop`. Upstream gives the end event the same default.
  - A retry also stops after a chunk with a finish reason, as upstream `_retryable` (`state.completed`).
  - When the last attempt fails before its first chunk with text or a tool call, the caller gets only the failure
    chunk.
  - The stream wrapper gives no token for a cached chunk. `LmChunk.reportedFailure` replaces the private failure
    helpers of the wrapper.
- **Open points, for the owner.**
  - A caller that closes a managed stream before its end gets no history entry, no usage record, and no cache entry.
  - The upstream rule that no event can come after the end event is not enforced.
  - The usage of an attempt that failed and got a retry is not recorded.
  - When a `ManagedLanguageModel` wraps another one, the outer model reads a cached chunk of the inner model as a
    new response: it stores it, and its history entry has `cache_hit = false`.
  - An OpenAI chunk that the codec cannot read, with no `error` object, is skipped. Upstream reads its fields
    leniently.
  - `StreamingLanguageModelWrapper.stream` does not give a `ClosableIterator`. This is older than the batch.
  - An exception from the stream of the delegate gives no history entry. `call` has the same behavior.

### Batch 12 (2026-10-14): the strict response schema

The full suite passes: 1,579 tests, 0 failures, 6 live tests skipped.

From this batch on, the owner is offline, and Claude continues the work. A choice that the owner did not make is
marked **decided by Claude, review**. Each such choice takes the upstream behavior where upstream has one.

- **L4** (G-26, strict flag). The JSON adapter sends a strict structured output, as upstream
  `JSONAdapter._prepare_response_format`.
- **Decisions of the owner.** The six decisions of 2026-10-14:
  - `strict: true`, with the upstream shaping (`_strict_json_schema`). The constraints of the port stay.
  - An optional field permits `null` in the strict schema.
  - JSON mode (`{"type": "json_object"}`) for an open map, a text `tool_calls` field, or a schema that cannot be
    built. No `response_format` when the model does not support a response schema.
  - A string signature gets a schema built from its field types.
  - With native function calling active, the schema has no `tool_calls` field.
  - The schema name is `DSPyProgramOutputs`.
- **Decided by Claude, review.** A `oneOf` goes out as an `anyOf`. zio-blocks writes `oneOf` for a sealed trait whose
  cases have fields, and OpenAI strict mode does not take `oneOf`. Upstream does not handle `oneOf`. Without this
  rule, such a typed output gets a request error now that `strict` is `true`.
- **API changes.** `StrictJsonSchema` (internal) is new. `JSONAdapterPrompt.sanitizeSchemaName` is gone.
- **Changes in behavior.**
  - The request has `strict: true` and the name `DSPyProgramOutputs`. Before, `strict` was `false`, and the name was
    the signature name.
  - A string signature (`DynamicPredict`) now sends a `response_format`. Before, it sent none.
  - An open map, a text `tool_calls` field, a type with no schema, or a typed schema that does not parse gives JSON
    mode. Before, the first three sent a lenient schema or none, and the last sent none.
  - An object whose `type` is a list with `object` (zio-blocks writes `["object", "null"]`) gets the strict shape.
    Upstream checks only `type == "object"`.
  - A `$ref` in a cycle gives JSON mode. Upstream recurses with no end.
- **Open points, for the owner.**
  - `ZioSchemaCodec.jsonSchemaForModel` does not return for a recursive case class (`children: List[Node]`). A
    second probe confirmed it (no result in 7 minutes). This batch does not cause it. A separate task is flagged.
  - Upstream logs a warning when it goes back to JSON mode. The port does not log.

### Batch 13 (2026-10-14): stream whitespace, `appendInstructions`, and the RLM checks

The full suite passes: 1,595 tests, 0 failures, 6 live tests skipped.

- **A5** (G-31). The chat and XML stream chunks of a field have no boundary whitespace, as upstream
  `_default_handle_stream_chunk`. The joined chunks of a field equal the parsed value for each split of the response.
- **The chat parser strips each value at both ends** (decided by Claude, review). Upstream `ChatAdapter.parse` does
  `"\n".join(v).strip()`. The port stripped only the end. The A5 test found this difference.
- **A7** (G-48). `SignatureLayout.appendInstructions` and `Signature.appendInstructions`, as upstream
  `append_instructions`. With no instructions, the text comes after the default instructions.
- **P5** (G-38). The RLM constructor rejects the namespaces that upstream `_validate_namespace` rejects. The input
  name `interpreter_factory` is not reserved, because it has no meaning in the port (decided by Claude, review).
- **P6** (G-38). The RLM truncation keeps exactly the limit for an odd limit.
- The RLM iteration input says `max_iters`, as upstream.
- **API changes.** `appendInstructions` on `SignatureLayout` and `Signature`. `RLM.ReservedResultNames` and
  `RLM.isReservedSandboxName`. `PythonNames` (the Python 3.12 keywords and the identifier rule) is new in
  `dspy4s.core.runtime`; `DenoPyodideVariables` uses it.
- **Changes in behavior.**
  - A chat value loses its leading spaces. Before, only the trailing whitespace went away.
  - A chat field whose last text is only whitespace gets an empty last chunk with `isLast = true`, as upstream.
  - An RLM with a duplicate tool, a tool name that is not a Python identifier, or a reserved input or output name
    now throws at construction.
- **Open points, for the owner.**
  - The identifier rule accepts letters, digits, and `_`. Python also accepts a few other Unicode characters.
  - With one output field and no marker, the chat stream and the parser can differ for rare whitespace characters,
    and the stream sends the text before a later marker as a chunk of the field.
  - A repeated chat header streams each section, but the parser keeps the first.

### Batch 14 (2026-10-14): the JSON disk cache record

The full suite passes: 1,603 tests, 0 failures, 6 live tests skipped.

- **L9** (G-24). `DiskLmCache` writes a versioned JSON record in place of Java serialization, as upstream
  `CallResult.dump` and `CACHE_FORMAT`.
- **Decided by Claude, review.**
  - The record: `<key>.json` with `_dspy4s_format` = `dspy4s-lm-result-v1`, `outputs` (`text`, `tool_calls` of
    `{name, args, id?}`, `metadata`), `usage` (`total_tokens`, `prompt_tokens`, `completion_tokens`, `extras`) or
    `null`, and `model_name` or `null`. The port has its own format name, because its record is not upstream's.
  - A legacy `.bin` entry stays readable, as upstream keeps legacy records readable, but only through a strict
    `ObjectInputFilter`. A valid legacy entry is a hit and moves to the JSON record. A rejected one is a miss and is
    removed. The alternative was to drop all legacy entries.
- **Changes in behavior.**
  - The cache directory gets `.json` files. Old `.bin` files move to `.json` when `get` reads them.
  - A `.bin` file with a class outside the old record no longer runs the code of that class.
- **Open points, for the owner.**
  - Each `put` still lists and sorts the directory for the eviction.
  - A deeply nested `args` value can overflow the stack in the JSON encoder, as before.
  - Upstream also stores the provider responses, the cost, and the raw response. The port stores only the fields of
    `LmResponse`.

### Batch 15 (2026-10-14): the content of the LM history and `inspectHistory`

The full suite passes: 1,613 tests, 0 failures, 6 live tests skipped.

- **I11** (G-27, history content). An LM history entry holds the messages, the options, the outputs, the usage, the
  model of the response, and a UUID, as upstream `finalize`. `inspectHistory` shows only the LM entries, in the
  layout of upstream `pretty_print_history`, with the tool calls of the messages and of the response.
- **Decided by Claude, review.**
  - The keys and their order follow upstream: `model`, `model_type`, `cache_hit`, `messages`, `kwargs`, `outputs`,
    `usage`, `response_model`, `uuid`. A tool call is `{name, args, id?}`, the shape of the port, not the OpenAI wire
    shape. A message part is `{kind, payload}`.
  - A failed call keeps its entry, with `error_code` and `error_message`. Upstream records no failed call. The render
    shows it as `Error (<code>): <message>`.
  - The render has no colors. A part shows `<kind: n characters>`. The arguments of a tool call are compact JSON.
- **API changes.** `LmHistoryRecord` is new in `dspy4s.lm.runtime`. `HistoryRenderer.render` has the new layout.
- **Changes in behavior.**
  - `inspectHistory(n)` counts only the LM entries. Before, a module entry could fill the view.
  - An LM entry holds the full messages and outputs. A long run keeps more data in memory; the cap of 10,000 entries
    stays.
  - The old keys `outputs` (a count) and `mode` are gone.
- **Open points, for the owner.**
  - L10, the cost estimate: upstream reads prices from a model catalog. The port needs a decision: a catalog, prices
    that the user declares, or no cost.
  - `kwargs` keeps all the request options. Upstream removes the keys that start with `api_`. No port code puts such
    keys in the options.

### Batch 16 (2026-10-14): callback events for evaluation, compile, and the interpreter

The full suite passes: 1,622 tests, 0 failures, 6 live tests skipped.

- **I9** (G-40, new events). `Evaluate` runs, optimizer `compile` calls, and code-interpreter operations are callback
  scopes, as upstream `on_evaluate_*`, `on_compile_*` (80553206b), and `on_interpreter_*` (b11cfe874).
- **Decided by Claude, review.**
  - New sealed cases of `CallbackEvent`: `EvaluateStartEvent`/`EvaluateEndEvent`, `CompileStartEvent`/
    `CompileEndEvent`, and `InterpreterStartEvent`/`InterpreterEndEvent` with an `InterpreterOperation` (`Execute`,
    `ToolCall`, `Startup`, `Shutdown`). A typed field gives the operation, not a key in the inputs. An exhaustive match
    on `CallbackEvent` in user code needs the new cases.
  - The inputs are sizes, not objects: `devset_size` and `num_threads` for an evaluation, `trainset_size` (and
    `valset_size`) for a compile, `programs_size` for `Ensemble`.
  - Only the outermost `compile` of one optimizer instance on a thread opens a scope, as upstream. A `compile` of
    another instance nests: the random search shows `LabeledFewShot` and `BootstrapFewShot` scopes inside its own, as
    upstream. `InferRules` calls the inner `BootstrapFewShot` run with no scope, as upstream `super().compile`.
  - `optimizerName` is the simple class name (`Gepa`, where upstream says `GEPA`).
  - The Deno interpreter has no public `start`, so a `Startup` scope occurs only inside the first `Execute`. The
    subprocess interpreter has `Execute` and `Shutdown` scopes only.
- **API changes.** The new events and `InterpreterOperation`. `CallbackDispatcher.withEvaluate`, `withCompile`, and
  `withInterpreter`. `Teleprompter.withCompileCallbacks`.
- **Changes in behavior.**
  - A callback handler gets the new events. An evaluation inside a compile nests under the compile scope: `Evaluate`
    keeps the open call scopes of the thread.
  - `SandboxProtocolSuite` filters the tool events, because the interpreter now emits its own events too.

### Batch 17 (2026-10-14): the time limits of the transport

The full suite passes: 1,630 tests, 0 failures, 6 live tests skipped.

- **L6** (G-26, timeouts). `HttpTimeouts` gives a connect limit and a read limit, with the upstream lm15 defaults
  (10 s and 600 s). The read limit bounds the wait for the headers and, for a stream, the wait for each next line.
- **Decided by Claude, review.**
  - The defaults change from one 60 s value to the upstream 10 s connect and 600 s read limits.
  - A stream with no new line in the read limit ends with an `LmTimeoutError`. A connection that fails during a
    stream ends it with an `LmTransportError`. `HttpStreamResponse.failure` carries the reason, and the OpenAI client
    reports it in place of the stream assembly error. An interrupt of the consumer, or a reader failure that is not an
    IO error, gives an `LmUnexpectedError` (not transient, so no retry).
  - The limit bounds the wait for each line. Upstream bounds the wait for each byte.
- **API changes.** `HttpTimeouts` is new. `HttpTransport.jdk(timeouts)` and `JdkHttpTransport(timeouts)` replace the
  `timeoutMillis` parameter. `HttpStreamResponse` has `failure`.
- **Changes in behavior.**
  - A plain call can wait 600 s for the headers. Before, it waited 60 s.
  - A broken stream gives a typed error. Before, the line iterator threw an `UncheckedIOException`.
  - Each open stream has one reader thread.
- **Open points, for the owner.**
  - `sendJson` has no limit on the body after the headers: the JDK client stops its timer when the headers arrive.
  - A consumer that never reads a stream to its end and never closes it keeps the reader thread and the connection.

### Batch 18 (2026-10-14): value and syntax fixes from the open points of batch 10

The full suite passes: 1,639 tests, 0 failures, 6 live tests skipped.

Three open points of batch 10, closed with the upstream behavior (decided by Claude, review):

- A JSON string for a field that is not a string is read from its text, as upstream `parse_value` reads a string
  with `json_repair`: `"3"` for an `int` field is `3`, and `"[1, 2]"` for a `list` field is a list. A string that does
  not fit is still a parse error with the field name. `AdapterTextSupport.outputTextValue` is the one rule for a text
  value in the chat, JSON, and XML formats.
- The XML adapter reads a text `tool_calls` field as the JSON list of calls, as the chat and JSON formats do.
- The string signature syntax splits fields at commas outside brackets, reads `Union[T, None]` and `Union[None, T]`
  as an optional `T`, and reads a `dict[...]` type as `json`. Thus `scores: dict[str, int]` and
  `note: Optional[dict[str, int]]` parse.
- **Changes in behavior.**
  - A JSON string for a `json` field that is not JSON is a parse error. Before, it was a string value.
  - An optional `tool_calls` field with the text `null` is now a parse error in the XML format too. Before, XML gave a
    null. Upstream gives `None` in each format; this is an open point.
- **Open points, for the owner.**
  - A union of more than one type and `None` gives an opaque type, for example `int | str`.
  - The other three open points of this batch are closed in batch 19.

### Batch 19 (2026-10-14): the text rules of `parse_value`

The full suite passes: 1,642 tests, 0 failures, 6 live tests skipped.

Three open points of batch 18, closed with the upstream behavior (decided by Claude, review). Upstream
`parse_value` reads a text with `json_repair`, then pydantic validates it in lax mode.

- The text of an `int` field may have spaces, and a float text with no fraction (`3.0`) is an `int`. The text of a
  `float` field may have spaces.
- A `list[T]` field reads the JSON list in its text. A text that is not a JSON list is a parse error.
- The text `null` or `None` of an optional `tool_calls` field is a null, as for the other optional fields.
- Tests: `TextValueRulesSuite`.

### Batch 20 (2026-10-14): the Responses API request and output shapes

The full suite passes: 1,655 tests, 0 failures, 6 live tests skipped.

- **L7** (G-26, Responses). `OpenAiLanguageModel(mode = LmMode.Responses)` sends the request to `/responses` with the
  body of upstream `chat_to_responses` (`ResponsesWire.request`), and reads one output with upstream
  `responses_outputs` (`ResponsesWire.outputs`): the joined text, the tool calls with their `call_id`, the reasoning
  under the metadata key `reasoning_content`, and the citations under `citations`.
- **Decided by Claude, review.**
  - The mode of the provider selects the endpoint, the body, and the parse, not the mode of the request.
  - A `json_schema` response format is flattened into `text.format`, the form that the Responses API takes. Upstream
    builds that form from a pydantic model; the port sends a record.
  - A streamed call in the Responses mode gives one final chunk from a plain call: the port has no parser for the
    Responses stream events yet.
- **Changes in behavior.**
  - A Responses reply gives one output. Before, each output item gave one output (a reasoning item, a message, and a
    function call gave three outputs).
  - A Responses body with no `output` array is a parse error. Before, the parser tried the chat shape.
  - A Responses text is not trimmed, as upstream. The parts with no text type do not count, as upstream.
  - `ProviderLanguageModelSuite` expects the upstream join of the text parts (no separator).
- **Open points, for the owner.**
  - No parser for the Responses stream events; `OpenAiClient.stream` always uses the chat endpoint.
  - Function-call arguments that are not JSON decode as on the chat path (`{input: raw}`); upstream gives `{}`.
  - A tool message with a list content joins only its text blocks; a `file` block stays as it is.
  - `ProviderLanguageModel` (the generic runtime provider) parses with the mode of the request.

### Batch 21 (2026-10-14): the persistent local Python interpreter

The full suite passes: 1,666 tests, 0 failures, 6 live tests skipped.

- **I6** (G-36). `LocalPythonInterpreter` runs Python in a persistent local subprocess, as upstream
  `LocalInterpreter`: state across calls, variables, host tools, `SUBMIT`, a time limit, and the callback scopes of
  batch 16. The worker is the upstream `local_interpreter_worker.py`, vendored as a resource. RLM can use it through
  `LocalPythonInterpreter.factory()` (`RuntimeContext.interpreterFactory`), so RLM no longer needs Deno.
- **Decided by Claude, review.**
  - It is not a security sandbox, as upstream says: the worker has the files, the network, and the credentials of the
    user.
  - The JVM cannot start a process in its own process group, so a time limit kills the worker and its descendants.
  - The checks of the tool names and the output fields run at each `execute` and give a `Left`; upstream checks them
    in the constructor.
  - An error result keeps the captured output in `stdout`; upstream discards it.
- **API changes.** `LocalPythonInterpreter` and `LocalPythonInterpreter.factory` are new in `dspy4s.core.runtime`.
- **Open points, for the owner.**
  - `execution_instructions` (I7, the text that tells the model how the interpreter runs) waits for D3.
  - A process that the worker started, and whose parent exited before the kill, keeps running.
  - The stderr of the worker is discarded, so a worker that cannot start (for example on Python before 3.10) gives
    only "Python worker exited unexpectedly".

### Batch 22 (2026-10-14): `close` on the models and the transport

The full suite passes: 1,671 tests, 0 failures, 6 live tests skipped.

- **L8** (G-26, connection lifecycle). `LanguageModel.close` releases the resources of a model, as upstream
  `LM.close`, and the model stays usable: the JDK transport starts its client at the first request, `close` closes it,
  and the next request starts a new one. `OpenAiClient`, `OpenAiLanguageModel`, and `OpenAiEmbedder` close their
  transport; `ManagedLanguageModel` closes its delegate. The stream wrapper of `streamify` does not close the
  configured model, because it does not own it.
- **Decided by Claude, review.**
  - A copy of a model shares its client, so a `close` of one copy releases it for each copy, as upstream ("copies
    share owned pools").
  - The port closes a transport that the caller gave to `OpenAiClient`. Upstream does not close a custom engine.
  - `close` uses the JDK `HttpClient.close`, which waits for the requests that run.
- **API changes.** `LanguageModel.close` and `HttpTransport.close` (both no-ops by default), `OpenAiClient.close`,
  `OpenAiEmbedder.close`.
- **Open points, for the owner.**
  - `close` blocks while a stream is open and nobody reads it, for example when the thread that calls `close` holds an
    open stream. Closing the open streams of the transport first would avoid this, but it differs from upstream.
  - A request that starts just before `close` can fail with `LmTransportError`, as upstream.

### Batch 23 (2026-10-18): capabilities as data

The owner made five decisions on 2026-10-18: D3 (with the boundary rule), L5, L10, A6 (not mirrored), and I10 (in
scope). Phase 0 to phase 4 below record them.

- **L5** (G-26). `LmCapabilities` holds what a model can do: native tool calls, a JSON response schema, and
  reasoning output (upstream `Capabilities`). A capability that nobody declares is `false`, as upstream.
  `OpenAiLanguageModel` takes the OpenAI capabilities (tool calls and a response schema) for the official address
  only (`LmCapabilities.forBaseUrl`, which compares the host with `api.openai.com`). An other server has no capability
  until the user declares it with `withCapabilities`.
- **Changes in behavior.**
  - A model at an other address (Ollama, vLLM, LM Studio, OpenRouter, Azure OpenAI) has no capability by default.
    The JSON adapter then sends no `response_format`, and an adapter with native function calling uses the text form
    of the tool calls. Before, the port claimed both capabilities for each server, and a server without them failed
    the request.
  - A user of a server with these capabilities declares them, for example
    `OpenAiLanguageModel.local(model, baseUrl).withCapabilities(LmCapabilities.openAi)`.
- **API changes.** `LmCapabilities` (`none`, `openAi`, `forBaseUrl`) in `dspy4s.lm.contracts`;
  `OpenAiLanguageModel.capabilities`, `effectiveCapabilities`, and `withCapabilities`.
- **Test.** `LmCapabilitiesSuite`.

### Batch 24 (2026-10-18): the cost estimate

- **L10** (G-27). The user declares the price of a model on the managed model:
  `ManagedLanguageModel(delegate, price = Some(LmPrice(inputPerMillion = 0.15, outputPerMillion = 0.60)))`. The prices
  are in US dollars for one million tokens. A cached token and a reasoning token can have their own price; else they
  have the input price and the output price, as upstream.
- **The history entry.** Each entry of a success has `cost` and `cost_details` after `usage`, as upstream `finalize`:
  - `cost` is the estimate in US dollars, or `null`;
  - `cost_details` is `{"kind": "estimate", "currency": "USD", "model", "source": "declared"}`, or
    `{"kind": "unknown", "reason"}` with the upstream reason text ("input or output usage was not reported", "usage
    dimensions cannot be separated safely") or "no price is declared for the model".
  - A failure entry has no cost.
- **The rule** (`LmCost.of`, upstream `_estimate_cost` for an OpenAI usage). The cached tokens are a part of the
  prompt tokens, and the reasoning tokens are a part of the completion tokens. A cached count above the prompt count,
  or a reasoning count above the completion count, gives no estimate.
- **Nested roll-up.** The port already records the usage of a call in each active `UsageTracker`, so a nested tracker
  rolls up into its parent, as upstream bb8ba59c0. A test now checks it.
- **Decided by Claude, review.**
  - The price is on `ManagedLanguageModel`, because the managed model writes the history and the usage.
  - A cache hit has no usage, as upstream, and its cost comes from the usage that the cache keeps. To make this
    possible, `LmCache.get` now returns the stored usage, and `ManagedLanguageModel` removes the usage of a hit.
    Upstream stores the cost in the cache record; the port computes it again, so a price change between the call and
    the hit changes the cost of the hit.
  - `cost_details` names `source: declared` in place of the catalog metadata of upstream.
- **API changes.** `LmPrice` and `LmCost` in `dspy4s.lm.contracts`; `ManagedLanguageModel.price`;
  `LmHistoryRecord.success` takes the cost. `InMemoryLmCache.get` and `DiskLmCache.get` keep the usage of the
  stored response.
- **Tests.** `LmCostSuite`; `LmHistoryEntrySuite`, `LmCacheSuite`, and `DiskCacheRecordSuite` changed for the new
  keys and the new `get` rule.

### Batch 25 (2026-10-18): the adapter prompts (decision D3)

The full suite passes: 1,743 tests, 0 failures, 6 live tests skipped (before the I10 and batch 26 tests).

- **D3, the adapters** (G-30). The Chat, JSON, XML, and two-step adapters build each prompt as upstream does, inside
  the boundary rule of phase 0. Two new internal objects of the adapters hold the rules:
  - `PromptText`: the type name, the structure note, the reminder hint, the field list (upstream
    `get_field_description_string`), the system message, the objective, and `format_field_value`;
  - `PythonText`: the Python form of a value (`str`, the `repr` of a float, `json.dumps` with and without
    `indent=2`), and the text helpers that upstream uses (`strip`, `splitlines`, `textwrap.dedent`).
- **Golden tests.** `scripts/golden/adapter_prompts.py` writes 28 cases from upstream (four adapters, with demos,
  the history, constraints, values, and tool calls), and the extraction prompts of the two-step adapter.
  `TypedPromptParitySuite` checks a typed signature. `PortTypeTextSuite` and `StructuredTypeTextSuite` check the type
  text of the port for the structured types.
- **Two fixes from the new tests.**
  - An `Option` field of a typed signature had the type `string`, so an `Option[Int]` output showed as a text field.
    It now has the type and the enum values of its element (`OptionTypeRefSuite`).
  - A `Literal['a', "b"]` type of a string signature gives a `str` field with the enum values, also inside
    `Optional[...]`.
- **Upstream rules that the golden data showed.**
  - Each line of the field list except the last ends with `: ` and a space; a `Constraints:` line follows the field.
  - The objective comes from the default instructions of the full signature.
  - An incomplete demo prints a null value as `None` (`null` in JSON); only a missing key gets "Not supplied for this
    particular example."
  - XML escapes `&` and `<` in a plain `str` output only.
  - The two-step extractor text keeps 13 spaces from a line continuation of the upstream source.
- **Changes in behavior.**
  - The prompts change for each program. The port-only texts are gone: the output schema block of the chat adapter,
    the JSON key instructions, and the history note.
  - The XML parser reads a reply with more than one top-level tag inside a root element, as upstream (the new prompt
    asks for one tag for each field).
  - When the strict parse of an optional field fails, the parse tries the lenient forms of the typed decode: an empty
    text, `null`, `none`, or `n/a` gives a null, and a number can have a currency symbol and commas. A string
    signature `Optional[int]` now reads `N/A` as null.
- **API changes.** Removed: `ChatAdapter.displayTypeName`, `reminderHint`, and `structureHint`;
  `NativeFunctionCalling.TextFormat`; `contracts.AdapterConstraints`; `AdapterTextSupport.renderFields` and
  `fewShotMessages`.
- **Decided by Claude, review.**
  - A field with enum values shows the note of a `Literal` ("must exactly match (no extra characters) one of"), also
    for a Scala enum, because the parse needs an exact label.
  - The two-step cases of the golden data have scalar values only, because upstream prints a list there with Python
    `repr`.
- **Open points, for the owner.**
  - History replay of a partial message: the port shows only the fields that a message has. Upstream shows each
    output field, with `None` for a missing one, and gives an assistant turn for a message with inputs only.
  - The two-step extraction prompt has no output schema, so a structured field gets the bare list or dict schema.

### Batch 26 (2026-10-18): MCP tools

- **I10** (G-47). The new module `dspy4s-mcp` converts the tools of an MCP server into dspy4s tools, on the MCP Java
  SDK 2.1.0:
  - `McpToolClient.fromSync(client)` wraps a `McpSyncClient`. The caller owns the client: it initializes the client
    and closes it. An exception of the SDK becomes a `Left`.
  - `McpTools.fromClient(client)` and `McpTools.convert(client, tool)` give a `ToolFunction` for ReAct and RLM, with
    the name, the description, and the input schema of the MCP tool (upstream `convert_mcp_tool`).
  - `McpTools.toolSpec(tool)` gives a `ToolSpec` for native function calling. The model gets the full input schema.
  - The value of a result follows upstream `_convert_mcp_tool_result`: the text of one text part, the list of the
    texts of more than one, else the list of the other parts. An error result is a `Left`.
    `McpResultMode.Structured` gives the structured content when the server sends one (ca0a088bd).
- **Contract changes.** `ToolFunction.inputSchema` and `ToolSpec.inputSchema`: the JSON schema of the arguments, both
  optional. `ToolSchemaBridge` sends it as the `parameters` of the function when it is set.
- **Examples.** `learn/programming/Mcp.scala` (5 of 5 snippets) and `tutorials/mcp/Mcp.scala` (10 of 10) are
  translated. The agent pages use `ReAct`, because `ReActV2` is not ported (G-19).
- **Dependencies.** The SDK brings 13 jars, approximately 6 MB: Jackson 3, reactor-core, the JSON schema validator,
  and slf4j-api. The module has no SLF4J backend, so a JVM prints one "No SLF4J providers" warning.
- **Decided by Claude, review.**
  - `tools/list` reads each page and stops with a `Left` when a cursor comes back (the SDK loop would not end).
  - An integer that does not fit a `Long` becomes a `BigInt`.
  - A `$ref` to a definition that is not in `$defs` gives the type `Any`; upstream raises `KeyError`.
  - The error text of a result with more than one text part is a JSON array; upstream writes the Python list.
  - The input schema goes to the model with no change. Upstream expands each `$ref` and marks each argument with no
    default as required.
  - A `structuredContent` of `null` falls back to the text, because the Java SDK cannot tell `null` from an absent
    value. Upstream gives `None`.
- **Open points, for the owner.**
  - Only `McpSyncClient` is supported. `McpAsyncClient` is not.
  - The SSE and the streamable HTTP transports compile in the examples, but no test runs them.
- **Tests.** `McpToolsSuite` (15), `McpSdkClientSuite` (7, a server of the SDK in a child JVM over stdio),
  `McpSdkPagingSuite` (4, a fake transport), and one more test in `ToolSchemaBridgeSuite`.

### Batch 27 (2026-10-18): the prompts of ChainOfThought, ReAct, and RLM, and I7

The full suite passes: 1,814 tests, 0 failures, 6 live tests skipped.

- **D3, the programs** (G-30, G-37, G-38). `scripts/golden/program_prompts.py` runs upstream ChainOfThought, ReAct,
  and RLM with a scripted model and records each LM call. `ProgramPromptParitySuite` runs the same programs of the
  port with the same replies and compares each request.
  - **The instructions of a derived signature.** `SignatureOps.prependOutput`, `appendInput`, and `replaceOutputs`
    keep the instructions that the layout had, as upstream `Signature.prepend` and `append`: a layout with no
    instructions keeps the default instructions of its own fields (`SignatureLayout.effectiveInstructions`). Before,
    the objective of ChainOfThought named `reasoning`. The rule also corrects the ReAct extractor,
    MultiChainComparison, the `hint_` field of Refine, and the AutoEvaluation judge. Three laws state it (L7a to L7c).
  - **ReAct.** The upstream instructions, tool list, and finish tool; the four fields with no descriptions;
    `next_tool_name` with enum values; the trajectory as the user content of the adapter of the run; an error
    observation `Execution error in <name>: <message>`.
  - **RLM.** The 3.4.0 template, the execution text of the interpreter, the output lines with the type text of the
    port, the type name `REPLHistory`, the numbered list of the variables, and the extract instructions. No sub-agent
    text (P8).
- **I7** (G-35). `ReplInterpreterFactory` is a trait with `executionInstructions`. The local Python factory and the
  Deno factory have the upstream texts. RLM uses the text of the factory of each call, and replaces only its own
  fragment of the instructions.
- **Upstream rules that the runs showed.**
  - ReAct keeps one tool for each name, as a Python dict: a later tool takes the place of an earlier one, so a user
    tool named `finish` gives its place to the injected tool.
  - In the RLM tool docs, one newline comes before "Additional tools". An argument type is the JSON `type`, else
    `Any`.
  - The RLM template fills its placeholders in one pass, as `str.format`.
- **API changes, for the review of the owner.**
  - `Adapter.formatUserMessageContent` is a new public method (the user content of a turn; ReAct writes its
    trajectory with it). The default is the chat form; the XML, two-step, and Refine hint adapters override it.
  - `TrajectoryAgent.renderTrajectory` takes a `RuntimeContext`.
  - `RLMModel.ActionInputs.variables_info` and `ExtractInputs.variables_info` are `Vector[String]`, not `String`.
  - The instructions of the MultiChainComparison predictor, the ReAct extractor, and the judge are now the default
    text, not `None`, as upstream. An optimizer sees that text.
  - `ReplInterpreterFactory` is a trait, not a function type.
- **Changes in behavior.**
  - The Refine hint adapter ignores the instructions when it matches a layout. Without this, the advice did not
    reach a ChainOfThought whose base signature has no instructions.
- **Open points, for the owner.**
  - A tool name that the model writes and that is not a tool gives the port observation, and the loop continues.
    Upstream ends the loop with a parse error.
  - With the TwoStepAdapter, ReAct shows a list observation as a numbered list. Upstream shows the Python form.
  - A `copy` of an RLM resolves its factory again. Upstream keeps the factory of the first construction.
- **Tests.** `ProgramPromptParitySuite` (7), `ExecutionInstructionsSuite` (2), `RLMInstructionsSuite`,
  `ToolTextSuite`, `UserMessageContentSuite`, laws L7a to L7c in `SignatureOpsLawSuite`, and new tests in
  `ChainOfThoughtSuite`, `ReActSuite`, and `RefinePerModuleAdviceSuite`. `ReActSuite`, `GepaReActTraceSuite`, and
  `InterpretedTrajectoryAgentLawSuite` changed for the new trajectory text.

### Batch 28 (2026-10-18): the decision types (A8, part 1)

The full suite passes: 1,839 tests, 0 failures, 6 live tests skipped.

- **The values** (package `dspy4s.core.decisions`, experimental). `Noul`, `Score[L]`, and `Choice[O]` hold the
  value, the confidence, and the evidence of a decision output. `L` and `O` are Scala enums: their cases are the
  levels of a Score, in order, and the options of a Choice (decision 1). `Score.levelIn(L.values)` gives the level as
  a case of the enum.
- **The rules.** `DecisionPlan` holds the rules of upstream `DecisionState`: the decision outputs of a layout, the
  defaults, the validation of the settings, the criteria, the question for the backend, the evidence of an answer, and
  the decoding (the threshold of a Noul, the cuts of a Score, the weights of a Choice). `DecisionSettings` holds the
  explicit settings of one output, with the JSON form of the upstream `fields` entry. The plan is pure: the next
  batch connects it to `Predict`.
- **The fields.** `TypeRef.noul`, `score`, and `choice` mark a rich decision output, with the levels or the options
  in `enumValues`. A typed field of type `Noul`, `Score[L]`, or `Choice[O]` gets them from its schema: the schema of
  `Score[L]` carries the case names of `L` as `Modifier.config` entries. A `Boolean` field, and a field of a Scala
  enum, can be a native decision output.
- **Decided by Claude, review.**
  - An optional field is not a decision output, as upstream `decision_type` gives `None` for `Optional[...]`.
  - The JSON form writes the weights in label order, because a Scala `Map` has no order.
  - The evidence of a Score answer accepts only the string keys `"0"` to `"N-1"` of a JSON object.
  - `Shape.StructuralSchema` is a new public type, so that `Signature.derived` gives a Score field its levels.
- **Tests.** `DecisionPlanSuite` (20 tests: the cases of upstream `test_decision_parameters.py`) and
  `DecisionFieldSuite` (6 tests).
- **A defect found, for the next fix.** A field with a default value (for example `count: Int = 3`) gets the type
  `json`, so the prompt shows it as a `dict`. `DefaultFieldTypeRefSuite` reproduces it.

### Batch 29 (2026-10-19): the evidence path of a generative model (A8, part 2)

The full suite passes: 1,865 tests, 0 failures, 6 live tests skipped.

- **`DecisionAdapter`** (upstream `DecisionAdapter` for a generative model). It wraps the Chat, JSON, or XML adapter
  of the call. Each decision output becomes an evidence field (`NoulEvidence`, `ScoreEvidence`, or
  `ChoiceEvidence`) with the question as indented JSON in its description, and the demos go into the instructions as
  "Task examples (labels or evidence)" (`json.dumps` with ASCII escapes). The parse reads the evidence and decodes it
  with the settings. The text follows the upstream snapshot, except the evidence schema, which has the port form
  (decision 5; PORT_DIFFERENCES.md section 16). The JSON adapter sends the evidence schemas in its
  `response_format`.
- **The settings** (decision 3). `Predict`, `DynamicPredict`, and `ChainOfThought` have `decisions`, with
  `withDecision`, `criteria`, and `withCriteria` (upstream `fields`, `get_criteria`, `set_criteria`). Each call
  validates the settings and the questions before the request. The settings are a part of `OptimizableParameters`,
  so the program state saves them, and an optimizer can write them.
- **Scope limits** (decision 7). A call that streams to a listener and has decision outputs fails before the request.
  A typed output with a decision value inside a list, a map, or an `Option` gives the upstream warning. `RLM` with
  decision outputs gives a warning at construction, as upstream.
- **Decided by Claude, review.**
  - A typed signature has no field descriptions, so a typed decision output takes its question text from the
    `instructions` of its settings. Upstream takes the `desc` of the `OutputField`.
  - A malformed evidence value is a parse error, so the JSON fallback runs. A decode error (for example a total
    probability of zero) is not a parse error and gets no fallback, as upstream decodes after the adapter call.
  - Loading a state checks the JSON form of the settings; the check against the layout comes at the next call.
    Upstream checks at load time.
  - `StreamingLanguageModel.streamsToListener` tells the engine that a call streams to a listener of this predictor.
- **Tests.** `DecisionAdapterSuite` (9), `PredictDecisionSuite` (6), `DecisionProgramSuite` (5),
  `DecisionPersistenceSuite` (3), `StreamingDecisionSuite` (2).

### Batch 30 (2026-10-19): the Jev client and the System One path (A8, part 3)

The full suite passes: 1,904 tests, 0 failures, 6 live tests skipped.

- **`TypeSafeModel`** (upstream `dspy.experimental.TypeSafe`, decision 4). An HTTP client of `POST /v1/systemone`
  with a Bearer key, and no SDK. `TypeSafeModel.fromEnv` reads `TYPESAFE_API_KEY`, `TYPESAFE_DEFAULT_MODEL`, and
  `TYPESAFE_BASE_URL`. The model accepts only decision requests (`DecisionRequests`), checks each reply with the
  wire rules of the vendored lm15 provider, and maps each error status as upstream `normalize_error` and
  `map_http_error`. The key goes only to the `Authorization` header.
- **The System One path.** `LanguageModel.supportsDecisionRequests` marks a System One model. `Predict` then uses no
  adapter: it sends the state (`instructions`, `input_fields`, `inputs`, and `demos` when the call has demos) and the
  question of each decision output. The threshold, the cuts, and the weights stay local, so a cached answer serves
  new settings. Each output must be a decision output (a native `Boolean` or enum output needs no settings entry).
  Generation settings and a stream to a listener fail before the request (decision 7).
- **Wrappers.** `ManagedLanguageModel` and the stream wrapper forward the new flag. `JdkHttpTransport` lets a header
  of the caller replace a default header with the same name.
- **A defect fixed** (`fa969940`, found in batch 29). The output shape of `ChainOfThought`, the ReAct extractor, and
  `MultiChainComparison` gave the JSON schema of the base output, so the strict schema of the JSON adapter had no
  `reasoning` or `rationale` property. The shape now puts the added property first. The decision adapter lost the
  other properties of a recursive output type; it now replaces the root `$ref` first.
- **Tests.** `TypeSafeModelSuite` (8), `PredictSystemOneSuite` (7), `PredictTypeSafeSuite` (3),
  `StreamingSystemOneSuite` (2), `AugmentedResponseFormatSuite` (1), `AugmentedSchemaPathsSuite` (5), and 5 new
  tests in `OutputAugmentationSuite`.
- **Open points, for review.**
  - The request keeps the JSON form of the values, so the wire sends `0` where upstream sends `0.0`. The numbers are
    equal.
  - The `rolloutId` of a call changes only the cache key. Upstream passes `rollout_id` in the config, so a TypeSafe
    call with it fails with the generation-settings error.
  - The cache key of a TypeSafe call does not include the base URL. The upstream TypeSafe key does
    (`test_cache_identity_and_controls`). The OpenAI provider matches upstream: `dspy.LM` leaves `api_base` and
    `base_url` out of its key (`ignored_args_for_cache_key`).
  - The client has no `dump_state`, `load_state`, or `copy`, and reads the key only at construction.

### Batch 31 (2026-10-19): ReAnchor (O6) and the evidence log

The full suite passes: 1,944 tests, 0 failures, 6 live tests skipped.

- **`ReAnchor`** (package `dspy4s.optimize`, experimental; upstream `dspy.experimental.ReAnchor`). It fits the
  threshold of each Noul output, the cuts of each Score output, and the weights of each Choice output against the
  metric (decision 6). It keeps a candidate only when the total training score is strictly higher and a fold check
  passes, and it keeps the fitted behavior only when it beats the original settings. The result is the `decisions`
  entries of the predictors, so the program state saves them. `ReAnchorConfig.requireCache` makes `compile` fail
  unless the model of the context has a cache. `compile` puts the `ReAnchorReport` in the metadata of the result,
  and writes `report.json` when `logDir` is set.
- **`ReAnchorSearch`** holds the search rules and the Python number rules that the search needs: `math.fsum`,
  `round` (half-even on the exact binary value), and a port of `random.Random(0).shuffle`. The folds and the fitted
  values thus match upstream. Six scenarios gave the same values as upstream `calibrate` in the dspy venv.
- **`DecisionEvidenceLog`** (package `dspy4s.core.decisions`). It is the scoped log of the evidence of each call. The
  scope travels to worker threads as a `ContextPropagation.Carrier`. `PredictEngine` records the evidence on the
  generative path (the new `ParsedOutput.evidence`) and on the System One path.
- **The key of the predictor that runs.** `RuntimeScope.predictor: Option[PredictorKey]` replaces the flag
  `withinPredictor`, which is now a method. The evidence of the `Predict` inside `ChainOfThought` thus belongs to the
  `ChainOfThought` leaf, as its trace entry does.
- **Decided by Claude, review.**
  - One pass with the log gives both the evidence and the start scores. Upstream runs one more pass for the start
    scores. The result is the same for a deterministic program.
  - A failed pass gives the first error of the program or the metric. Upstream gives the "Execution cancelled" error
    of its executor.
  - A Choice with one label gives no flip points. Upstream raises an error there.
  - On the generative path, the evidence goes to the log after the parse, because the decode is a part of the parse.
    A decode error fails the pass, so the fit does not change.
  - The `score` of the candidate in the `OptimizationReport` is the mean training score from 0 to 1.
  - Removing the `RuntimeScope` field changes the source form of `RuntimeScope(withinPredictor = ...)`.
- **Tests.** `ReAnchorSuite` (15) and `ReAnchorUpstreamSuite` (25): the cases of `test_reanchor_calibrate.py` and
  `test_reanchor.py`, with the upstream values. Not ported: the per-predictor cache switch, the log lines, the
  `_compiled` mark, and the per-call model (the port has none of these), and a metric that returns a `Prediction`
  (the port metric returns a `Double`). The inner part of the nested-scope test cannot be ported, because
  `recording` gives the entries only when the body completes.
- **Open point.** The cache check reads only the model of the runtime context. `OptimizableStructure` does not show
  a model that a predictor binds itself.

### Batch 32 (2026-10-19): ReActV2 (P10)

The full suite passes: 1,962 tests, 0 failures, 6 live tests skipped.

- **`ReActV2[I, O]`** (package `dspy4s.programs.strategies`, experimental; upstream `dspy.ReActV2`). It follows the
  six points of P10. Each turn runs the step predictor `react`, which gets the pending inputs, the `History` of the
  run, and the tools, and gives `next_thought` and `tool_calls`. The tool calls run in order. An unknown tool and a
  tool error are error results that go back to the model, and a `submit` call that succeeds ends the run. After
  `maxIterations` turns, no tool calls, a parse error, or a full context window, the forced submit asks for the
  `submit` tool. The result has `history` and `termination_reason` as raw values; `ReActV2.history` and
  `ReActV2.terminationReason` read them. `ReAct` does not change.
- **The adapters** (upstream `Adapter._call_preprocess`). With native function calling, the prompt shows neither the
  tools input (the new `TypeRef.tools`) nor the tool calls output. Without it, `FormattedPrompt.removedOptions`
  removes `tools`, `tool_choice`, and `parallel_tool_calls` from the request, also when the call config has them.
  `ToolCall.fromDynamic` reads the other forms of `ToolCalls.validate_input`: the `{"tool_calls": [...]}` object and
  one call with `args`, `arguments`, or `function`.
- **Prompt parity.** The golden data has a ReActV2 run from upstream (`react_v2` in
  `scripts/golden/program_prompts.py`), and the port sends the same messages. The port form of the data: the
  arguments of a tool are JSON text, an optional input shows `str`, and `next_thought` has no type hint, because it
  is a `str` field (point 4).
- **Decided by Claude, review.**
  - The `submit` argument of a plain `str`, `int`, `float`, or `bool` output is the JSON type of its `TypeRef` (for
    example `{"type": "integer"}`, as the pydantic schema of upstream). Each other output uses its property in the
    output schema of the signature.
  - A failed run is a `RuntimeError("react_v2", ...)` with the upstream message, and the warnings use
    `RuntimeEnvironment.warn`.
  - The history keeps the results of the tool calls of a turn under `tool_call_results` (the form of G-28). Upstream
    keeps them inside its `ToolCalls` value.
  - The XML and the two-step adapters always remove the tool options, because they have no native mode.
- **Tests.** `ReActV2Suite` (17) and one case of `ProgramPromptParitySuite`. Not ported: the tests of the async path
  (point 6), the `reasoning_effort` checks (point 4), the per-call `max_iters` (point 5), and the history check of a
  failed run (the port gives back no history with an error).
- **Open points.**
  - Upstream also sets `reasoning_effort` to `None` in the forced submit. The port does not send it, because a JSON
    null could go to the provider.
  - Fixed in `08732202`: an exception that a tool throws ended the run, also in `ReAct`. Now `ToolExecutor.run`
    turns a non-fatal exception into an error result with the text `<ClassName>: <message>`, as upstream turns each
    exception into an error result. A fatal error still goes up to the caller.
  - The result has no total LM usage of the run, as `RLM`.

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
- Upstream marks four new features as experimental: decision types, ReAnchor, `dspy.Flex`, and ReActV2. Decision
  D6 ports the decision types with a Jev client and ReAnchor (batches 28 to 31), and ReActV2 (batch 32).
  `dspy.Flex` waits.
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
- [x] **D3. Prompt parity policy.** The adapter prompts are not byte-equal to upstream (G-30, G-37). Decide: match
      upstream exactly, or record each difference as intended.
      **Decided 2026-10-18: match upstream within a boundary rule.** A first design put a Python view of each type
      (`FieldSpec.pythonType`, with a copy of the pydantic schema form) into the core contracts. The owner rejected
      it: it couples the data model to Python. The rule:
      - The prompt matches upstream exactly where the text is fixed, or where the adapters can compute it from the
        data of the port: `TypeRef`, the enum values, the optional flag, the descriptions, and the constraints. This
        covers the frame of each prompt, the type names `str`, `int`, `float`, and `bool`, the notes of these types
        and of enums, the constraint lines, the demos, the history turns, and the output reminder.
      - A structured type (a list with an item type, a map, a record, a union) shows the type name of the port and the
        JSON schema of the port. The port does not write Python generic names or the pydantic schema form. Each such
        place is an intended difference.
      - No Python data goes into the core contracts. The type text stays in the adapters.
      - A value in an input or a demo prints as upstream prints it: `True`, `False`, `None`, the Python form of a
        float, and JSON with the separators `", "` and `": "`.
      - `scripts/golden` writes the expected prompts from upstream. The golden tests cover the cases inside the rule.
- [x] **D4. ReAct default iteration limit.** dspy4s used 5 and upstream uses 20 (G-37).
      **Decided 2026-10-11: 20, as upstream.** The default of `ReAct.maxIterations` is `IterationLimit(20)`.
- [x] **D5. Optional output fields.** `FieldSpec` has no optional flag, so the adapters cannot apply the upstream
      rule (G-29). **Decided 2026-10-13:** `FieldSpec.optional`, set by `Option[T]` and by `Optional[T]`; a missing
      output takes its default, else a null when optional, else a parse error.
- [x] **D6. Experimental tracks.** Confirm that decision types, ReAnchor, `dspy.Flex`, and ReActV2 wait (G-49,
      G-19).
      **Decided 2026-10-18: port three of the four.** The decision types (A8) serve TypeSafe Jev, a System One model
      that gives probabilities and no text; the owner wants them, with a Jev client and ReAnchor (O6). ReActV2 (P10)
      comes after them: upstream makes it the implementation of `dspy.ReAct` in 3.5. `dspy.Flex` (P9) waits, because
      it needs P8. The seven design decisions of the decision types (2026-10-18):
      1. A typed field `Noul`, `Score[L]`, or `Choice[O]`, where `L` and `O` are Scala enums, gives the answer space. A
         native `Boolean` or enum output opts in with a settings entry. The layout API has `TypeRef.noul`, `score`,
         and `choice`, with the options in `enumValues`, for option sets from data.
      2. The result values `Noul`, `Score[L]`, and `Choice[O]` are in the package `dspy4s.core.decisions`, marked
         experimental in the scaladoc.
      3. An immutable map of `DecisionSettings` on `Predict` and `DynamicPredict` holds the threshold, cuts, weights,
         instructions, and criteria. It is validated as upstream, saved with the program, and an optimizable
         parameter.
      4. `TypeSafeModel` is a `LanguageModel` in the LM module that accepts only decision requests, with a capability
         flag. It is an HTTP client for `POST /v1/systemone` with a Bearer token (`TYPESAFE_API_KEY`), and no SDK.
      5. A generative model gets the evidence form of upstream: an evidence object for each decision output, and the
         demos as task examples in the instructions. The prompt text follows the boundary rule of D3.
      6. ReAnchor is in the `optimize` module. A scoped recorder of the runtime logs the evidence of each call.
      7. Scope limits as upstream: no async path; a stream with decision outputs is an error; a nested decision output
         gives a warning and no evidence decoding; RLM decision outputs give a warning; with Jev, each output must be a
         decision output.
- [x] **D7. Values outside the allowed set.** A string-signature output with allowed values (`Literal[...]`) took any
      text in the port; upstream `parse_value` rejects other text (open point X.2). ReAct `next_tool_name` has the tool
      names as allowed values, so an invented tool name fails the upstream call; the port gave an observation (B27.1).
      **Decided 2026-10-21: reject, as upstream.** A value outside the allowed set is a parse error: the chat adapter
      then tries the JSON fallback, and the call fails when that also fails. String signatures thus act as typed
      enums, and ReAct fails on an invented tool name, as upstream.
- [x] **D8. The runtime context.** The port keeps the context in two places: the `using RuntimeContext` of each call,
      and the context installed on the thread. Callbacks, parallel workers, and parts of GEPA read the installed one
      (G-50). **Decided 2026-10-21: the given context wins.** Each public entry point (a program call, `compile`,
      `evaluate`) installs the context that it gets for the duration of the call, as `Evaluate` and GEPA (b36)
      already do. The API does not change.

## Phase 1: lm / clients

- [x] **L1. Typed LM errors and retry classes** (e608bc024, 2cda3872d). Errors carry `status`, `provider_code`,
      `request_id`, and `retry_after`. Retries use transient classes only. → G-23 (C)
- [x] **L2. Stream guard** (2cda3872d, 7fd361214, 661467b77). A stream without an end event is an error, and the
      typed error survives. → G-25 (C)
- [x] **L3. One call path for streamed calls**: cache, retry before the first token, usage, and history. → G-25 (C)
- [x] **L4. Strict response schema** (40a6e1689): `strict: true` with a schema shaped for strict mode. → G-26 (C)
- [x] **L5. Capabilities as data**, declared for each server, with unknown treated as false. → G-26 (R)
      **Decided 2026-10-18:** the OpenAI capabilities hold for the official address (`api.openai.com`) only. Each
      other server has no capability until the user declares one. Done in batch 23.
- [x] **L6. Timeouts** (7fd361214): separate connect and read limits, and an idle limit for streams. → G-26 (R)
- [x] **L7. Responses request and output shapes** (0312f0da6, 891958702, 24ec85de4). → G-26 (R)
- [x] **L8. Connection lifecycle**: `close` on the client, and one HTTP client for each configuration. → G-26 (R)
- [x] **L9. Versioned plain-data cache record** in place of Java serialization. → G-24 (R)
- [x] **L10. Advisory cost estimate** and nested cost roll-up (bb8ba59c0). → G-27 (R)
      **Decided 2026-10-18:** the user declares the prices of each model. An unknown price gives no cost, not zero,
      as upstream. Done in batch 24.

## Phase 2: adapters / signatures / streaming

- [x] **A1. Optional and default output fields** (ba8a64958): a missing optional output takes its default. → G-29 (C)
- [x] **A2. Fallback from ChatAdapter to JSONAdapter after a parse error only** (2cda3872d), and never after
      visible stream output. → G-29 (R)
- [x] **A3. Tool call ids, `ToolCallResults`, and native tool history** (2ec31b3aa, a0fcc1e37). → G-28 (R)
- [x] **A4. `dspy.History` with output-format reminders in replay** (f207d35cf). → G-28 (R)
- [x] **A5. Boundary whitespace in ChatAdapter stream chunks** (f4a428853). → G-31 (R)
- [-] **A6. Nested XML output** (33aaa19e0). → G-32 (R)
      **Decided 2026-10-18: not mirrored.** The XML adapter keeps a JSON value inside the tag of a list, dict, or
      model output. The prompt asks for JSON text there. See "Deliberately not mirrored".
- [x] **A7. `append_instructions` on signatures** (498760149). → G-48 (R)
- [x] **A8. Decision types `Noul`, `Score`, `Choice`** (e83978c08, 4c6448e03). Experimental. → G-49 (I)
      Done: the types and the decoder (batch 28), the evidence path for a generative model and the settings on
      `Predict` (batch 29), the Jev client with the System One path (batch 30), and the evidence log (batch 31).

## Phase 3: programs

- [x] **P1. Interpreter failure is terminal** (89696e0cc). → G-35 (C)
- [x] **P2. One interpreter for each invocation, from a factory** (623360078, 6fb74fcf4, 430af8d87). `RLM` selects
      the factory of the program, then of the runtime context, then the Deno sandbox. → G-35 (C)
- [x] **P3. ReAct truncation with one tool call** (7eb5acce2). → G-37 (C)
- [-] **P4. Echo for a single-line assignment in `_parse_code`** (fe75476cd). The parser belonged to
      `ProgramOfThought`, which is removed (D1). → G-39 (C)
- [x] **P5. RLM namespace checks** (74194bfab). → G-38 (R)
- [x] **P6. RLM truncation with an odd limit** (35ef21f16). → G-38 (R)
- [x] **P7. Deprecation of `CodeAct` and `ProgramOfThought`** (99bb44bf4). dspy4s removed them (D1). → G-39 (C)
- [ ] **P8. Sandbox `dspy` facade and sub-agents in RLM** (ba3f9198e). Large. → G-38 (I)
      **Decided 2026-10-18: wait.**
- [ ] **P9. `dspy.Flex`** (e4e97aae2, f70d08a5b, 573cfe707). Experimental. → G-49 (I)
      **Decided 2026-10-18: wait** (it needs P8).
- [x] **P10. ReActV2 changes** (b3897e271, ca54a855a, 05728f6ad): reserved outputs, an error when the forced
      submit gives no outputs, and an async path. It is still experimental. → G-19 (R)
      **Decided 2026-10-19: port ReActV2, with these six points.**
      1. `ReActV2[I, O]` is a new typed program, from a typed signature and a list of `ToolFunction` values (MCP tools
         too). `ReAct` does not change (G-7b). The step predictor `react` is the part that an optimizer can change.
      2. The result is `Prediction[O]`. The `history` (a `History`) and the `termination_reason` are raw values with
         the upstream keys. Helper methods read them, and the reason is an enum (`Submit`, `ForcedSubmit`). An output
         with the name `history` or `termination_reason` fails at construction.
      3. The tool calls use the adapter path of G-7b and G-28: native function calling when the model supports it,
         else the text `tool_calls` field. The forced submit sets `tool_choice` to `submit` in the call config.
      4. `next_thought` is a string field. Upstream types it as `dspy.Reasoning`; the port has no `Reasoning` type,
         and it is a separate item.
      5. An input field of the type `History` in the signature gives the start history. `maxIterations` is fixed at
         construction.
      6. A failed forced submit is a `Left` with the upstream message. A tool error and an unknown tool are error
         results that go back to the model. A parse error or a full context window starts the forced submit. No
         separate async path: `applyAsync` covers it, and the upstream tests of the async path only are not ported.
      Done in batch 32.

## Phase 4: interpreters / runtime

- [x] **I1. Vendor the current `runner.js`** (80b118e52, ab8a7658d, 07a0f7aa6): Pyodide version pin, frozen
      globals, a private control exception, and no read access to the Deno cache. → G-34 (C)
- [x] **I2. Unsolicited sandbox error** (6334c2ba5). → G-33 (C)
- [x] **I3. Request ids and recursion guard** (80b118e52). → G-33 (R)
- [x] **I4. Mount collisions** (072c72164). → G-33 (R)
- [x] **I5. Launch flags, Deno version check, probe timeout** (07a0f7aa6). → G-34 (R)
- [x] **I6. Persistent local Python REPL** (d58c3007e, 453823209). → G-36 (R)
- [x] **I7. `execution_instructions`** (0b93fb148, 750017192). → G-35 (R) Done in batch 27.
- [ ] **I8. `SandboxSerializable`** (ed4d479a1). → G-38 (R)
      **Decided 2026-10-18: wait.**
- [x] **I9. Callback events**: interpreter lifecycle (b11cfe874), optimizer compile (80553206b), and the end event
      for base exceptions (970f72900). → G-40 (C for the end event, R for the others)
- [x] **I10. MCP**: SDK v2 (32bcd36ce) and structured results (ca0a088bd). → G-47 (R)
      **Decided 2026-10-18:** MCP is in scope, and the port can take a new dependency for it. Done in batch 26.
- [x] **I11. `inspect_history` renders tool calls** (571171227). → G-27 (R)

## Phase 5: optimize / gepa / evaluate

- [x] **O1. Labeled demos sampled for each predictor** (1bc87de15). It needs per-predictor demos first. → G-42 (C)
- [x] **O2. Empty devset is an error** (c7ee4462a). → G-46 (C)
- [x] **O3. GEPA `InstructionProposer` as the default proposer** (b6c8aaf1e). No `skills` and no
  `truncate_history_outputs`. → G-45 (C)
- [x] **O4. GEPA objective-aware frontiers** (638e155cf). → G-45 (C)
- [x] **O5. GEPA multiple proposals with parallel evaluation** (aa9d2d053, 822f39319). → G-45 (C)
- [x] **O6. ReAnchor calibration optimizer** (e8ec009c6). Experimental. → G-49 (I)
      Done in batch 31.

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
- Nested XML output (A6, 33aaa19e0): the XML adapter of dspy4s asks for a list, dict, or model output as JSON text
  inside the tag, and parses it as JSON. Upstream asks for nested elements and parses them with the JSON schema of the
  field. Decided 2026-10-18.

## Documents that were out of date before this analysis

- `PORT_GAPS.md` G-12 excludes multi-objective frontiers because the dspy surface did not use them. Upstream now
  uses them (O4).
- `PORT_GAPS.md` lists `inspect_history` as resolved. The history payload holds no prompt and no completion (G-27).
- `PORT_MAP.md` says that `llm_query_batched` is sequential. The code is parallel.
- `PORT_MAP.md` recorded the caller-owned interpreter of `CodeAct` as intended. Upstream changed to one interpreter
  for each invocation (P2). Batch 3 removed `CodeAct`, and `PORT_MAP.md` now says so.
- `PORT_GAPS.md` G-22 names LiteLLM as the Anthropic reference. Upstream now has a native Anthropic adapter in
  `dspy/_vendor/lm15/providers/anthropic.py`.
