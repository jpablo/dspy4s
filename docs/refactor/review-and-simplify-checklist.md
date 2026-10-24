# Review and simplify checklist

This checklist plans the `/simplify` and `/code-review` passes, one module or file group at a time.

- **Base:** commit `6f6b9f84` (origin/main, 2026-10-21). All line numbers refer to this commit. They move when the code changes.
- **Method:** five read-only agents read the largest files of each module and skimmed the rest. They gave each module two scores from 0 to 10. I added the size and the commit history.
- **Status of the findings:** the findings are agent reports. They are leads, not proven bugs. I checked three of them in the source, and they are marked "(checked)".

## How to use this checklist

1. Select the next module from the rank tables.
2. Run `/simplify` on one file group. Give the module path or the file list as the target, because this branch has no diff.
3. Run `/code-review` on the same file group. The review then also checks the simplify changes.
4. Tick the boxes of the pass and of each finding that the pass fixed.
5. Update the line numbers in this file if a pass moves the code.

Simplify core before lm and adapters. The same DynamicValue helpers (`string`, `record`, `isNull`) are in all three modules, and the shared copy belongs in core.

## Rank tables

| Rank | /simplify | Score | | /code-review | Score |
|---|---|---|---|---|---|
| 1 | programs | 7 | | core | 7 |
| 2 | optimize | 7 | | signatures | 7 |
| 3 | core | 6 | | adapters | 6 |
| 4 | adapters | 6 | | programs | 6 |
| 5 | lm | 5 | | optimize | 6 |
| 6 | examples | 6 | | evaluate | 6 |
| 7 | signatures | 5 | | streaming | 6 |
| 8 | gepa | 5 | | lm | 5 |
| 9 | evaluate | 5 | | gepa | 4 |
| 10 | streaming | 4 | | mcp | 3 |
| 11 | algebra | 3 | | examples | 3 |
| 12 | mcp | 2 | | algebra | 1 |

Notes on the order:

- For equal scores, a module comes first when its bugs have a larger effect, or when it has more findings per line.
- examples is below lm in the simplify list, because it is demo code.

The module sections below are in the order of the sbt dependencies.

## algebra

Source root: `modules/algebra/src/main/scala/dspy4s/algebra/`. The paths below are relative to it.
Simplify 3 (rank 11). Code-review 1 (rank 12). 992 main lines, 213 test lines.

### Passes

- (a) The whole module, 992 lines.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `CopyDiscardCategory.scala:9-147`: the Monoidal, Symmetric, CopyDiscard, Markov, and Cartesian traits have no production instance. Only tests use them. `Isomorphism.scala` exists only for these traits. To remove them is a design decision, not a cleanup.
- [ ] `NaturalTransformation.scala:75-131`: no code outside the module calls `leftWhisker` or `rightWhisker`. They only state the Monad laws (`Monad.scala:27-48`).
- [ ] `Monad.scala:92-115`: `fromComponents` builds three wrapper objects only to get `pure` and `flatMap`.
- [ ] `MonoidAction.scala:13` (`functor`) and `NatGradedCategory.scala:39-50` (`underlyingCategory`, `forgetGrade`): only tests use them.

### Code-review findings

- [ ] `Kleisli.scala:16` and `Category.scala:34` put closures in closures. A very long chain can overflow the stack.

## core

Source root: `modules/core/src/main/scala/dspy4s/core/`. The paths below are relative to it.
Simplify 6 (rank 3). Code-review 7 (rank 1). 6352 main lines, 4494 test lines.

### Passes

- (a) The interpreters, about 2030 lines: `runtime/LocalPythonInterpreter.scala`, `runtime/DenoPyodideInterpreter.scala`, `runtime/DenoPyodideLaunch.scala`, `runtime/DenoPyodideProtocol.scala`, `runtime/DenoPyodideVariables.scala`, `runtime/SubprocessPythonInterpreter.scala`, `runtime/PythonNames.scala`, `contracts/CodeInterpreter.scala`. The simplify pass added `runtime/InterpreterSupport.scala`.
  - [x] /simplify
  - [x] /code-review
- (b) The context and the callbacks, about 1300 lines: `runtime/RuntimeEnvironment.scala`, `runtime/ContextPropagation.scala`, `runtime/CallbackDispatcher.scala`, `runtime/UsageScope.scala`, `runtime/StreamProgress.scala`, `runtime/ActivePredictContext.scala`, `contracts/RuntimeContext.scala`, `contracts/Callbacks.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (c) The decisions, about 800 lines: `decisions/`.
  - [ ] /simplify
  - [ ] /code-review
- (d) The other contracts and the data, about 2200 lines: the other files in `contracts/`, and `data/`, `signatures/`, `collections/`.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [x] Three copies of the code that kills a process and its descendants, in LocalPython and in the Deno launcher. Fixed: `InterpreterSupport.killTree`.
- [x] Two copies of the probe with a temp file and a time limit, in LocalPython and in the Deno launcher. Fixed: `InterpreterSupport.probe`.
- [x] `runtime/DenoPyodideProtocol.scala`: `field`, `asString`, and `asLong` only called DynamicValues, and `isNull` had two copies. LocalPython got its JSON helpers from an object with a Deno name. Fixed: `DynamicValues.encodeJson` and `DynamicValues.isNull`, and the protocol object keeps only the JSON-RPC code.
- [ ] `contracts/RuntimeContext.scala:96`, `:134`, `:193`: a compatibility surface (a flat `apply`, `copy`, and 16 accessors).
- [ ] `contracts/SignatureLayoutStateCodec.scala` and `Constraint.dumpState`/`fromState` are hand-written codecs that only tests call. The number read occurs three times: `DynamicValues.asLong`, `decisions/Decisions.scala:683`, and `contracts/Constraint.scala:106`.
- [ ] Five copies of the ThreadLocal set-and-restore pattern: `runtime/UsageScope.scala:65`, `decisions/DecisionEvidenceLog.scala:54`, `runtime/StreamProgress.scala:29`, `runtime/ActivePredictContext.scala:49`, `runtime/CallbackDispatcher.scala:134`.
- [ ] `runtime/RuntimeEnvironment.scala:336` does not use its own `warn`.

Findings of the group (a) simplify pass that the pass did not fix:

- [ ] LocalPython and Deno each have their own line transport (the process, the reader, the time limit) and their own session state machine. A shared `LineProcess` and `InterpreterSession` can replace them. Skipped: it is a design change, and it changes how Deno reads its output. Look at it with the Deno hang findings in the group (a) code review.
- [ ] Efficiency, skipped because each fix adds more code than it removes:
  - Deno copies and JSON-escapes the cached variable prelude again on each `execute`. A cache of the escaped prelude can remove this work.
  - LocalPython has no cache for the variables, so each `execute` encodes them again.
  - Deno computes the canonical paths again on each startup and on each file sync, and it runs its two Deno probes one after the other.
- [ ] `runtime/DenoPyodideVariables.scala:10` accepts ASCII names only, but `PythonNames.isBindable` accepts Unicode names, as upstream `str.isidentifier` does. Skipped: a change of behavior.

### Code-review findings

- [x] DenoPyodideInterpreter: nothing read the stderr pipe until a failure, so more than about 64 KB of stderr stopped the sandbox forever. Fixed: a daemon thread reads the standard error into a bounded tail.
- [ ] `runtime/DenoPyodideInterpreter.scala`, `readLine`: no time limit, so a `while True` in user code hangs `execute`. Skipped: the interpreter has no execution timeout, as upstream. A timeout is a new feature.
- [x] SubprocessPythonInterpreter: after a normal exit, `join()` had no limit, a timeout did not kill descendants, and an interrupt escaped and left the process alive. Fixed: a 1-second limit after the exit, `killTree` on a timeout, and a `Left` on an interrupt.
- [ ] `contracts/Constraint.scala:106-111`: `numberOf` ignores `BigDecimal`, but the JSON codec decodes decimals as `BigDecimal` (`decisions/Decisions.scala:680`). `SignatureLayout.fromJson` then drops `gt(0.5)` with no error. The tests use only whole numbers.
- [ ] `runtime/LocalPythonInterpreter.scala`, the tool threads: no test covers the fatal rethrow, a timeout in a tool call, an interrupt, or `close` from another thread. The inbound queue is not cleared between calls, so a line that arrives after a reply can become the reply of the next call. Skipped: only code that breaks the documented rule (all threads finish before the code returns) sends such a line.
- [x] DenoPyodideInterpreter and DenoPyodideLaunch: `Files.createFile` threw out of `execute`, the runner script was never written again after a removal, and `spawn` reported each IOException as "Deno not found". Fixed: a `Left` with the cause, `InterpreterSupport.ResourceFile`, and the cause in the message.
- [x] A value with a lone UTF-16 surrogate made the JSON writer throw out of `execute` (LocalPython and the Deno prelude). Fixed: `InterpreterSupport.tryEncodeJson` and a `Left` that names the variable.
- [ ] Gaps of the lone-surrogate fix: in DenoPyodideInterpreter, code text with a lone surrogate still throws from `DenoPyodideProtocol.encodeRequest`, and a tool exception with a lone surrogate in its message throws from `encodeError` in `handleToolCall`.
- [ ] `runtime/DenoPyodideLaunch.scala`, `denoCacheDir`: when `deno info --json` gives no directory, one launch probes again two times, each with a 10-second limit. Skipped: a new probe on each launch is deliberate, so that a Deno that the user installs later is found.

## signatures

Source root: `modules/signatures/src/main/scala/dspy4s/signatures/`. The paths below are relative to it.
Simplify 5 (rank 7). Code-review 7 (rank 2). 1969 main lines, 1958 test lines.

### Passes

One simplify pass fits the module. Split the review in two.

- [ ] /simplify on the whole module, 1969 lines.
- (a) The codec, about 1120 lines: `ZioSchemaCodec.scala`, `Shape.scala`, `OutputAugmentation.scala`, `InputAugmentation.scala`, `SchemaInterop.scala`.
  - [ ] /code-review
- (b) The macros, about 850 lines: `internal/`, `Signature.scala`, `SignatureBuilder.scala`, `Spec.scala`.
  - [ ] /code-review

### Simplify findings

- [ ] `internal/FunctionMacro.scala:62-70` and `:169-177` hold the same `validateSchemas`. `internal/SpecMacro.scala:90-95` is a third copy.
- [ ] `internal/FunctionMacro.scala:124-159` and `:238-267` repeat the choice between a scalar, a tuple, and a product output. The quote `new Shape.SchemaTupleShape[x](Shape.canonicalSchema[x])` occurs 9 times in the macros.
- [ ] `Shape.scala:181-189` and `ZioSchemaCodec.scala:535-545` hold the same missing-field check. `Shape.scala:199-207` (`SchemaTupleShape`) only passes each call to `derivedFromZioSchema`.
- [ ] `ZioSchemaCodec.scala:67-68` calls `optionSomeValueReflect` two times. The default case already covers `:308` and `:506`. The doc at `:54` says that the codec drops unknown fields, but `:112` keeps them.
- [ ] `OutputAugmentation.scala:107-121`: no production code calls `decodeAugmented` with its `readField` and `hook` parameters. Only tests use them.

### Code-review findings

- [ ] `OutputAugmentation.scala:183-185`: `encode` uses `DynamicValues.fromAny`, not the Schema of the base type. An `Option` becomes the text `"Some(3)"`, and a nested case class becomes its `toString`. In programs, `DynamicSignature.scala:146` gives this encode to the next program. No test covers it, and it breaks the single-codec rule. (checked: the `fromAny` call is there)
- [ ] `ZioSchemaCodec.scala:174-184`, `:535-545`: the derived FieldSpecs never carry a case-class default as `defaultValue`. A response without the field `count: Int = 3` fails with `NotFoundError`. This is against the FieldSpec contract (core `contracts/FieldSpec.scala:15-23`).
- [ ] `internal/SpecMacro.scala:40`: `declaredMethods` does not see the fields of a parent Spec trait. A Spec that inherits fields loses them with no error. No test covers inheritance.
- [ ] `internal/FunctionMacro.scala:149-157`, `:259-265`: `Noul`, `Score`, `Choice`, and `History` are case classes. A method that returns one of these types gets one output field for each of their fields (`value`, `confidence`, and so on). It does not get one `result` decision field.
- [ ] `ZioSchemaCodec.scala:123-156`: the coercion has gaps. It does not change an Int or a Long to a BigDecimal or a BigInt, text to a Short, a Byte, or a BigDecimal, or a Double to an Int. The match on an enum label is case-sensitive (`:70`).

## lm

Source root: `modules/lm/src/main/scala/dspy4s/lm/`. The paths below are relative to it.
Simplify 5 (rank 5). Code-review 5 (rank 8). 4966 main lines, 6422 test lines.

### Passes

- (a) The providers, about 2600 lines: `providers/`.
  - [ ] /simplify
  - [ ] /code-review
- (b) The runtime and the contracts, about 2360 lines: `runtime/`, `contracts/`.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] Private copies of the `string`, `sequence`, `record`, and `isNull` helpers: `providers/ResponsesWire.scala:445-498`, `runtime/DiskLmCache.scala:444-475`, `runtime/ProviderLanguageModel.scala:39`, `runtime/LmHistoryRecord.scala:125`. Core now has `DynamicValues.isNull` and `DynamicValues.encodeJson`; `providers/DynamicJson.scala:59` also has an `isNull`.
- [ ] `runtime/DiskLmCache.scala:16-151`, `:263-279`: a reader for the old Java-serialization cache format, about 150 lines.
- [ ] `runtime/DiskLmCache.scala:370-475`: a hand-written JSON codec. A DTO with a derived Schema (JsonCodecDeriver) can replace it.
- [ ] `runtime/ProviderLanguageModel.scala:75` writes a magic `mode` key, and `providers/OpenAiClient.scala:24-39`, `:103-113` read it and remove it. Pass the mode as a typed value.
- [ ] `providers/PythonStr.scala:100-116` copies the Python float repr of adapters `internal/PythonText.scala:369-422`, with a different algorithm. `providers/OpenAiLanguageModel.scala:69-94` copies the pipeline of `runtime/ProviderLanguageModel.scala:34-36`.

### Code-review findings

- [ ] `runtime/ProviderLanguageModel.scala:76` puts `request_id` in the payload. `providers/OpenAiClient.scala:24` removes only `mode`, so `request_id` goes into the OpenAI request body. The only test checks `mode` (`OpenAiClientSuite.scala:91`). (checked)
- [ ] `providers/OpenAiClient.scala:53-55` sends the `prompt` body to `/chat/completions` in Text mode. The stream reads only `delta.content`. No test covers Text mode on the real client.
- [ ] `runtime/ManagedLanguageModel.scala:236`, `:417`: no upper limit on `Retry-After`. A bad header can block a thread for hours.
- [ ] `runtime/DiskLmCache.scala:295-304`: a failed write leaves `<key>.json.tmp`, and `clear` never removes it. The eviction (`:329-336`) lists up to 200000 files while it holds the lock.
- [ ] `providers/JdkHttpTransport.scala:128` calls `toLowerCase` with no `Locale.ROOT`. In a Turkish locale, the `X-Request-Id` lookup fails.

## adapters

Source root: `modules/adapters/src/main/scala/dspy4s/adapters/`. The paths below are relative to it.
Simplify 6 (rank 4). Code-review 6 (rank 3). 4243 main lines, 4943 test lines.

### Passes

- (a) The internals and the contracts, about 2190 lines: `internal/`, `contracts/`.
  - [ ] /simplify
  - [ ] /code-review
- (b) The adapters, the parsers, and the streaming states, about 2050 lines: the files at the top of the source root.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] Three JSON readers with different number rules: `internal/JsonDynamic.scala:55`, `internal/PythonText.scala:427` (ujson BufferedValue), and `JSONAdapterParser.scala:142`. Use one reader.
- [ ] `contracts/AdapterInvocation.scala:19`: `outputJsonSchema: Option[String]` is parsed again at `DecisionAdapter.scala:132`, `JSONAdapterPrompt.scala:134`, and `internal/PromptText.scala:256`. `DecisionAdapter.scala:82` writes it back to text. A typed record removes these round trips, but the change touches other modules.
- [ ] `contracts/ToolContracts.scala:31-65`, `:103-104`: `toOpenAiTools` (`Map[String, Any]`), `ToolCallData`, and `fromOutput`. Only tests use them.
- [ ] `ChatAdapterPrompt.scala:14-37` repeats `JSONAdapterPrompt.scala:17-51`. Three adapters repeat the same native-tool options (the flag, `parallelToolCalls`, and `toolChoice`). Four files have their own copy of the `str` helper.

### Code-review findings

- [ ] `XMLAdapter.scala:146-149` uses `XML.loadString`. A raw `&` or `<` in a value ("AT&T", "a < b") breaks the parse. Upstream uses a tolerant regex. With one output field, the fallback returns the whole tagged text as the value. `XmlStreamingState` accepts the same text, so the stream and the parse give different results. No test covers this.
- [ ] `internal/JsonDynamic.scala:21-26`, `:42`, and `JSONAdapterParser.scala:142`: ujson keeps numbers as Double. Integers above 2^53 in `json` and `list` fields change value. `internal/StrictJsonSchema.scala:345` does the same to defaults.
- [ ] `JsonStreamingState.scala:91` starts at the first `{`, but the parser prefers the ```` ```json ```` fence (`JSONAdapterParser.scala:97-105`). Duplicate keys and repeated XML tags give extra chunks, but the parser keeps one value.
- [ ] `internal/PythonText.scala:430-446` and `JsonDynamic.fromUjson` recurse on deep LM output. `Try` does not catch `StackOverflowError`.
- [ ] `JSONAdapterParser.scala:155-159` rejects an `int` above Int.MaxValue. `internal/AdapterTextSupport.scala:191` accepts a Long for an optional field.

## programs

Source root: `modules/programs/src/main/scala/dspy4s/programs/`. The paths below are relative to it.
Simplify 7 (rank 1). Code-review 6 (rank 4). 8004 main lines, 11858 test lines.

### Passes

- (a) The agents, about 2740 lines: `strategies/ReAct.scala`, `strategies/ReActV2.scala`, `strategies/ReActProtocol.scala`, `strategies/RLM*.scala`, `runtime/InterpretedTrajectoryAgent.scala`, `runtime/TrajectoryAgent.scala`, `runtime/AgentLoop.scala`, `runtime/TrajectoryTruncation.scala`, `runtime/ToolExecutor.scala`, `runtime/SandboxToolBridge.scala`, `internal/`, `contracts/ToolFunction.scala`, `contracts/ToolCall.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (b) Predict and the selection, about 2100 lines: `strategies/Predict.scala`, `strategies/DynamicPredict.scala`, `strategies/ChainOfThought.scala`, `strategies/MultiChainComparison.scala`, `strategies/BestOfN.scala`, `strategies/Refine.scala`, `strategies/RefineFeedback.scala`, `runtime/PredictEngine.scala`, `runtime/AttemptSelection.scala`, `runtime/ParallelExecutor.scala`, `runtime/SettingsProgramRuntime.scala`, `Aggregation.scala`, `Parallel.scala`, `retrievers/`.
  - [ ] /simplify
  - [ ] /code-review
- (c) The structure, about 3150 lines: `optimization/`, `algebra/`, `compose/`, the other files in `contracts/`, and the other files at the top of the source root.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `strategies/RLM.scala:246-270`, `:375-425`: a "Source-compatible facade" with type aliases and one-line forwarders to RLMModel, RLMReplProtocol, and RLMSandboxTools. `strategies/ReAct.scala:285-310`, `:323-325` and `strategies/Refine.scala:172`, `:246`, `:263`, `:271` repeat the pattern.
- [ ] `runtime/InterpretedTrajectoryAgent.scala` and `runtime/TrajectoryAgent.scala` have one implementer: ReAct. `ReAct.prepareAction` (`strategies/ReAct.scala:216-218`) always returns `Ready`, so the `Rejected` path and `recordRejection` (`:231-242`) are dead code.
- [ ] `strategies/ReAct.scala:84-90` and `strategies/ReActV2.scala:149-154` hold the same tool dedup fold. The "Execution error in" text also occurs in both. Keep both programs: ReActV2 ports upstream `dspy.ReActV2`, and ReAct ports the text protocol.
- [ ] `contracts/ToolCall.scala:6-7`: `ToolCallRequest` and `ToolCallResult` duplicate `core.contracts.ToolCall` and `ToolCallResult`. `ToolExecutor.invoke` (`runtime/ToolExecutor.scala:20-27`) has one caller, and its NotFound branch cannot run, because `strategies/ReAct.scala:109` checks the name first.
- [ ] `RecordObject.scala` has no user in the main code. Only ProgramAlgebraLawSuite uses it.
- [ ] `DynamicValues.fromAny` on typed values, against the single-codec rule: `compose/Mode.scala:58`, `:61`, `strategies/MultiChainComparison.scala:151`, `strategies/Refine.scala:210`, `strategies/RLMSandboxTools.scala:66`, `:76`, `:94`.
- [ ] Small items: the stale comment at `runtime/PredictEngine.scala:260-261` (the `lm` field exists), `Parallel.apply` is the same as `Parallel.run`, and `ErrorLimit(10)` occurs two times (`runtime/ParallelExecutor.scala:38`, `:173`).
- [ ] `optimization/CompositeOptimizableStructureInstances.scala:36-71` writes single-leaf instances by hand, while Refine uses `ParameterOptic`. That gives three styles for one job.

### Code-review findings

- [ ] `Aggregation.scala:51`: `keys.last` throws `NoSuchElementException` when the first completion row is an empty record. (checked)
- [ ] `strategies/RLMSandboxTools.scala:34-42`: `counter.get()` and then `addAndGet` is not atomic. Two `llm_query` calls at the same time can go past `maxLlmCalls`.
- [ ] `runtime/ParallelExecutor.scala:108-141`: the timeout applies to each completion, not to the batch. The error says "0 seconds" for a timeout of less than one second. `shutdownNow` does not stop a running LM call, so work can continue after the batch fails.
- [ ] `strategies/ReActV2.scala:504` merges the final outputs into the event record. An output named `next_thought` or `tool_calls` overwrites the event fields, but `checkNames` (`:406-413`) reserves only `history` and `termination_reason`. Line `:134` drops a task input named `history` or `tools` with no error.
- [ ] `strategies/RLMReplProtocol.scala:149-175`: `stripCodeFences` is a hand-written parser with `var`, and it does not handle all cases. `runtime/AttemptSelection.scala:63-120` (`bestOf`): the failure-budget rule `remainingFailures <= 0` can differ by one from upstream `idx > fail_count`. A NaN reward is never selected.

## evaluate

Source root: `modules/evaluate/src/main/scala/dspy4s/evaluate/`. The paths below are relative to it.
Simplify 5 (rank 9). Code-review 6 (rank 6). 853 main lines, 701 test lines.

### Passes

- (a) The whole module, 853 lines.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `EvaluationResultPersistence.scala:15-26`, `:72-96`: a second JSON library (ujson) and a conversion through `Any`. Core already has the zio-blocks DynamicValue JSON codec.
- [ ] `metrics/AutoEvaluation.scala:144-146`, `:230-237` call the legacy `fromAny` on Strings. The `threshold` fields (`:100`, `:171`) are not used. The layouts at `:107-131` and `:179-193` repeat the same fields.
- [ ] `Evaluate.scala:67-80` rebuilds the config field by field, where `config.copy` is enough. `displayTable: Either[Boolean, Int]` (`:31`) is a strange encoding. The metadata is a `Map[String, Any]` (`:152`). The `nonEmpty` branch at `:147` is dead code.
- [ ] `metrics/NormalizeText.scala:11-24`: `apply` and `dpr` repeat four steps. `metrics/BuiltinMetrics.scala:118-128` repeats the same extraction two times.

### Code-review findings

- [ ] `metrics/BuiltinMetrics.scala:30`, `:34`, `:50`, `:121`, `:127`: `toAny(Null)` gives `null`, and `.toString` then throws a NullPointerException. A null answer gives an exception, not a `Left`.
- [ ] `metrics/BuiltinMetrics.scala:136`: PassageMatch compares substrings, but upstream `has_answer` compares token sequences. With this code, "cat" matches "concatenate".
- [ ] `metrics/NormalizeText.scala:13-15` is different from upstream `normalize_text`. It removes accents, and it changes punctuation to a space where upstream deletes it ("U.S." gives "u s"). Thus EM and F1 can differ from dspy.
- [ ] `EvaluationResultPersistence.scala:54-63`: the CSV code finds each value from the header prefix. If a field name is the metric name, or starts with `example_` or `pred_`, the code reads the wrong value. Line `:85` writes a JSON null as the string "null". Lines `:34` and `:70` catch `Throwable`.
- [ ] `contracts/EvaluateContracts.scala:63-69`: an example field named `score` or `error` shows the evaluation value, not the field value.

## optimize

Source root: `modules/optimize/src/main/scala/dspy4s/optimize/`. The paths below are relative to it.
Simplify 7 (rank 2). Code-review 6 (rank 5). 2954 main lines, 4945 test lines.

### Passes

- (a) The bootstrap optimizers and the shared files, about 1030 lines: `BootstrapFewShot.scala`, `BootstrapFewShotWithRandomSearch.scala`, `LabeledFewShot.scala`, `KNNFewShot.scala`, `Ensemble.scala`, `DemoCount.scala`, `OptimizerSupport.scala`, `OptimizerLimits.scala`, `contracts/`, `para/`.
  - [ ] /simplify
  - [ ] /code-review
- (b) The instruction optimizers, about 1000 lines: `COPRO.scala`, `MIPROv2.scala`, `propose/GroundedProposer.scala`, `InferRules.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (c) ReAnchor and the persistence, about 930 lines: `ReAnchor.scala`, `ReAnchorSearch.scala`, `ProgramPersistence.scala`.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `BootstrapFewShotWithRandomSearch.scala:56-198` uses `return`, `var`, `boundary/break`, and mutable buffers. The `BootstrapFewShotConfig` at `:86` and `:111` is written two times, and only the seed is different.
- [ ] The same Either `traverse` helper: `ReAnchor.scala:566`, `propose/GroundedProposer.scala:251`, and adapters `internal/StrictJsonSchema.scala:328`.
- [ ] `COPRO.scala:213`, `propose/GroundedProposer.scala:188`, and `InferRules.scala:158` repeat one pattern: a DynamicPredict call loop with a rolloutId window, a temperature bag, and `recordGet`/`renderText`/`trim`. The window offsets (64, 100, 10000) are magic numbers.
- [ ] `var` and `ArrayBuffer` accumulation where a fold fits: `MIPROv2.scala:202-223`, `COPRO.scala:111-117`, `InferRules.scala:124-138`.
- [ ] `ReAnchor.scala:97-144` builds the report JSON by hand, not with a derived Schema. `Ensemble.scala:77` keeps a mutable `Random` in a module instance.

### Code-review findings

- [ ] `BootstrapFewShotWithRandomSearch.scala:101-121` (a parity bug): seeds of 0 or more cut the trainset to 1..maxBootstrappedDemos examples. Upstream passes the whole shuffled trainset and uses the random size as `max_bootstrapped_demos`. The bootstrap seed also ignores `config.seed`. No test covers this.
- [ ] `MIPROv2.scala:258` and `BootstrapFewShotWithRandomSearch.scala:153`: a failure scores 0.0 with no error. If the LM is down, every score is 0 and the result is still `Right`.
- [ ] `MIPROv2.scala:177-183`, `:250-252`, and `COPRO.scala:143`: a leaf with `instructions = None` can become `Some("")`. `MIPROv2.scala:213` assumes that `ps.inspect` (used by GroundedProposer) and `ps.read` give the same number of leaves.
- [ ] `propose/GroundedProposer.scala:203` (more than 64 instructions) and `InferRules.scala:163` (more than 100 leaves): the rolloutId windows can overlap. Overlapping IDs can give the same cached proposals.
- [ ] `ReAnchorSearch.scala:170-230` is a bit-exact port of the Python MT19937 and `fsum`. It is fragile, but ReAnchorUpstreamSuite tests it.

## gepa

Source root: `modules/gepa/src/main/scala/dspy4s/gepa/`. The paths below are relative to it.
Simplify 5 (rank 8). Code-review 4 (rank 9). 3242 main lines, 6331 test lines.

### Passes

- (a) The engine core, about 1750 lines: `GepaEngine.scala`, `GepaState.scala`, `MergeProposer.scala`, `GepaProposals.scala`, `MinibatchSampler.scala`, `GepaRandom.scala`, `GepaBudget.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (b) The adapter and the I/O, about 1490 lines: `GepaAdapter.scala`, `GepaEvalCache.scala`, `InstructionProposer.scala`, `ReflectiveRecord.scala`, `GepaStatePersistence.scala`, `contracts/`, `Candidate.scala`, `EvaluationBatch.scala`, `Gepa.scala`, `GepaLimits.scala`.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `ReflectiveRecord.scala:67-160` has its own Python `json.dumps` writer. Adapters `internal/PythonText.scala:215` (`json`) already does this, but it is `private[adapters]`.
- [ ] `GepaAdapter.scala:174-190` builds its own `newFixedThreadPool` and context capture. This copies what ParallelExecutor in programs does.
- [ ] Thin wrappers: `GepaEngine.scala:560` (`fullEval`) only calls `cache.scored`. `GepaEvalCache.scala:125` (`scores`) and the public `makeReflectiveDataset` overload (`GepaAdapter.scala:210`, with an adapter-level `rng`) are used only by tests.
- [ ] `MergeProposer.scala:59-105`: the engine builds a mutable proposer for each iteration and copies its `MergeSchedule` back. Plain functions on the schedule are simpler. `sharedExamples` (`:203`) always equals the valset size.
- [ ] Code for snapshots from older versions: the "empty means older" forms in `GepaState.scala:50-77`, `:204-210`, and the `Option` fields in `GepaStatePersistence.scala:23-49`.

### Code-review findings

- [ ] `GepaState.scala:117-122`, `:217-221`: no NaN guard on the metric scores. `maxBy` ranks NaN highest, so a candidate with a NaN score becomes `bestIndex`. The `== best` check then gives an empty Pareto front.
- [ ] `GepaStatePersistence.scala:179-181`: the checkpoint is written in place, not atomically. A crash during the write leaves a corrupt file, and `load` then refuses to resume.
- [ ] `GepaState.scala:355` and `MergeProposer.scala:112`: the random draws read the order of a hash `Map` or `Set`. Thus a resumed run depends on the order of the library collections.
- [ ] `GepaAdapter.scala:185-190`: an exception from one pair is thrown again, and `shutdownNow` interrupts the other pairs. The engine does not catch it, so `optimize` throws and does not return a `Left`.

## streaming

Source root: `modules/streaming/src/main/scala/dspy4s/streaming/`. The paths below are relative to it.
Simplify 4 (rank 10). Code-review 6 (rank 7). 689 main lines, 3378 test lines.

### Passes

- (a) The whole module, 689 lines.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `StreamingQueue.scala:8-66`: a queue of `Option` with `None` as the end marker, a nullable `pending: Option[A] | Null`, and three flags. A simpler design is possible.
- [ ] `Streamify.scala:68-71` repeats the adapter type test of programs `runtime/SettingsProgramRuntime.scala:25`, `:44`.
- [ ] `StreamingLanguageModelWrapper.scala:97-123`, `:164-198`: `call` and `stream` each repeat the chunk sort (cached, failure, emit) and the safe-close block.
- [ ] `StatusMessageProvider.scala:15-22` has a stale comment ("still free-form Maps"), two `@nowarn`, and `output: Any`. `StatusStreamingCallback.scala:24`, `:26` use `fold(identity, identity)`.
- [ ] The Scaladoc numbered list is broken at `Streamable.scala:21-26`. `docs/STREAMING_POSTPONED.md` still describes `Streamify.signatureFor`.

### Code-review findings

- [ ] `StreamingLanguageModelWrapper.scala:251` and `Streamify.scala:99-109` ignore the `false` that `offer` returns. After the consumer closes, the program and the LM stream still run to the end. No test covers this.
- [ ] `StreamingQueue.scala:23`: if a consumer stops reading and does not close, the daemon producer waits in `put` forever and keeps the SSE connection open.
- [ ] `StreamingLanguageModelWrapper.scala:54`, `:257-264`: `firedListeners` is a mutable Set that is not thread-safe. Calls to parallel predictors race on it, and `allowReuse=false` then depends on timing.
- [ ] `StreamingQueue.scala:26-30`: `close()` checks the flags and then acts, which is not atomic. Status callbacks from worker threads can add more producers.
- [ ] `StreamingLanguageModelWrapper.scala:164-173`: the look-ahead in `next()` holds back each chunk until the next chunk arrives. This logic is complex, and 12 of the last 14 commits on the module were fixes.

## mcp

Source root: `modules/mcp/src/main/scala/dspy4s/mcp/`. The paths below are relative to it.
Simplify 2 (rank 12). Code-review 3 (rank 10). 396 main lines, 586 test lines.

### Passes

- (a) The whole module, 396 lines.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `JavaJson.scala:90` reads `productElement(0)` by reflection for dates and UUIDs, which is fragile. Line `:88` maps `Unit` to an empty map.
- [ ] `McpTools.scala:198-206` (`typeRefOf`) is the inverse of programs `internal/ToolText.scala:49-57` (`jsonType`). The two copies of one mapping can become different.
- [ ] `McpTools.scala:90-99` (`convert`) uses alias vals and an anonymous `ToolFunction`.
- [ ] Optional: `JavaJson.toDynamic` walks the Java JSON tree by hand. A path through JSON text and the zio-blocks codec can replace it.

### Code-review findings

- [ ] `JavaJson.scala:65-70` (`fromNumber`): a `java.math.BigDecimal` becomes a `Double`, and this loses precision.
- [ ] `McpTools.scala:146-149`: the error text for several text parts is a JSON array. Upstream gives a Python `str(list)`.
- [ ] `SdkToolClient.scala:24-31`: the class sets no timeout of its own. It relies on the request timeout of the SDK client, and a call blocks the caller thread.
- [ ] `McpTools.scala:182-195` (`resolve`): it reads only the last segment of a `$ref` and looks only in `$defs`. A ref that it cannot find gives the type `Any`.

## examples

Source root: `modules/examples/src/main/scala/dspy4s/examples/`. The paths below are relative to it.
Simplify 6 (rank 6). Code-review 3 (rank 11). 6433 main lines, no tests.

### Passes

The shared-setup fix in (a) also touches `tutorials/talk_to_your_data/TalkToYourData.scala` in (c).

- (a) The shared setup, about 740 lines: `Demo.scala`, `verify/`, `tutorials/react_vs_rlm/ReactVsRlm.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (b) The two MCP files, about 880 lines: `learn/programming/Mcp.scala`, `tutorials/mcp/Mcp.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (c) Talk to your data, about 875 lines: `tutorials/talk_to_your_data/`.
  - [ ] /simplify
  - [ ] /code-review
- (d) The guides, about 1890 lines: the other files in `learn/`, and `deep_dive/`, `signatures/`, `Cheatsheet.scala`.
  - [ ] /simplify
  - [ ] /code-review
- (e) The other tutorials, about 2050 lines: the other directories in `tutorials/`.
  - [ ] /simplify
  - [ ] /code-review

### Simplify findings

- [ ] `verify/GepaSmokeTest.scala:106-123`, `verify/OptimizerSmokeTest.scala:128-145`, and `tutorials/talk_to_your_data/TalkToYourData.scala:65-75` each copy the LM setup of `Demo.scala:18-24`. The two smoke tests read `OPENAI_MODEL` (default `gpt-4o-mini`), but `Demo` reads `DSPY_MODEL` (default `gpt-5.5`).
- [ ] Other copies:
  - `envInt`: `verify/GepaSmokeTest.scala:91`, `verify/OptimizerSmokeTest.scala:103`, `tutorials/talk_to_your_data/TalkToYourData.scala:41`.
  - `ProgressLmCallback`: `verify/GepaSmokeTest.scala:95`, `verify/OptimizerSmokeTest.scala:110`.
  - `denoAvailable`: `tutorials/react_vs_rlm/ReactVsRlm.scala:321`, `tutorials/talk_to_your_data/TalkToYourData.scala:44`.
- [ ] The two MCP files hold the same code:
  - the client wrapper: `learn/programming/Mcp.scala:60-64` and `tutorials/mcp/Mcp.scala:475-480`.
  - the child-JVM transport: `learn/programming/Mcp.scala:240-246` and `tutorials/mcp/Mcp.scala:468-470`.
  - the server builders, which are almost the same.
- [ ] `Cheatsheet.scala:66` and `learn/programming/Adapters.scala:43` each define a private `rec`, which is the same as `DynamicValues.record`. `tutorials/react_vs_rlm/ReactVsRlm.scala:160` uses `fromAny` on a String.
- [ ] Four files are only TODO stubs with quoted Python: `deep_dive/data_handling/BuiltInDatasets.scala`, `tutorials/deployment/Deployment.scala`, `learn/programming/Assertions7.scala`, `tutorials/mem0_react_agent/Mem0ReactAgent.scala`. They hold all 19 TODO markers.
- [ ] In `tutorials/mcp/Mcp.scala`, 299 of 566 lines are quoted Python. Snippet 1 (`:56-232`) quotes the same code as snippets 3 to 5.

### Code-review findings

- [ ] `learn/programming/Mcp.scala:11-12`, `:236-237` say that ReActV2 is not ported (G-19), but programs `strategies/ReActV2.scala` exists. Thus the comment is out of date, and snippet 4 uses `ReAct` in place of the upstream agent.
- [ ] `learn/programming/Mcp.scala:169-172`: when an `add` argument is missing or is not a number, the tool uses 0 and gives no error.
- [ ] `tutorials/talk_to_your_data/QueryEngine.scala:88`, `:109`: an unknown column becomes `total` with no error, and `>` or `<` on a label becomes equality. This engine is the GEPA oracle, so a wrong plan can get a correct score.
- [ ] `verify/GepaSmokeTest.scala:120` calls `.toOption.get` on a parse result. The input is a constant, so the risk is low.
