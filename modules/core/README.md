# dspy4s `core`

The foundation every other module builds on. `core` defines the interchange format (how inputs, outputs, examples, and
predictions are represented), the signature/field metadata model, the execution context that threads through
every call, the error hierarchy, and the observability events. It has no dependencies on the rest of dspy4s.

## The core idea

Four abstractions anchor the framework:

- **A single interchange format.** All data — example inputs, model outputs, configs — flows as a zio-blocks
  `DynamicValue.Record`, a schema-backed discriminated-union value rather than `Map[String, Any]`. The `:=`
  operator and the `DynamicValues` helpers lift Scala values into this format at the edges; internal codec paths
  produce `DynamicValue` directly so nothing is lost in round-trips.
- **A signature/field model.** A `SignatureLayout` stores ordered input and output cohorts of role-free
  `FieldSpec` metadata. Each field has a wire `TypeRef`, description, prefix, and constraints. Adapters and programs
  read the cohorts to build prompts and parse responses.
- **A partitioned execution context.** A `RuntimeContext` keeps runtime dependencies (`RuntimeServices`), policy
  (`RuntimeConfig`), dynamic correlation (`RuntimeScope`), and observable output (`RuntimeDelta`) distinct. It
  passes as a `using` parameter, so configuration is scoped, not global mutable state.
- **A pure observability model.** Lifecycle work emits `CallbackEvent`s (module/LM/adapter/tool start and end),
  correlated into a tree by call id. Handlers build traces and history by observing events, so programs stay
  free of logging concerns.

## Key types

### Data

| Type | Role |
|------|------|
| `Example` | A training/demo data point: a `DynamicValue.Record` of fields plus the `inputKeys` partition marking inputs vs labels. |
| `RawPrediction` | Adapter-parsed prediction data before schema decoding into semantic output `O`: field values, optional multi-candidate `Completions`, and structured `LmUsage`. "Raw" does not mean unparsed LM text. Has lenient accessors (`asString`, `asInt`, `asDouble`, `asBoolean`). |
| `LmUsage` / `TokenCategory` | Token accounting shared by predictions and LM responses; pointwise addition is a commutative monoid. |
| `Completions` | A column-oriented view of N candidate completions; `at(i)` reconstructs row `i` as a `RawPrediction`. |
| `DynamicValues` | The helper surface over the interchange format: `fromAny`, `recordFromEntries`, `recordGet`, `mergeRecords`, `renderText`, plus the `:=` and `updated` extensions. `fromAny` is reserved for user-facing edges. |

### Signature / fields

| Type | Role |
|------|------|
| `SignatureLayout` | The runtime layout: name, optional instructions, and separate ordered input/output `FieldSpec` cohorts. Built via `create` (validating), `parse` (DSL), or `Signature` derivation. |
| `FieldSpec` | Role-free per-field metadata: name, wire type, description, prefix, default, constraints. `normalize` fills defaults so adapters see a uniform surface. |
| `TypeRef` | The wire-format type tag the LM sees (`string`, `bool`, `json`, …); unknown tokens pass through opaque. Two tags give a field a role: `tool_calls` (the output field that gets native tool calls) and `history` (the input field that holds the conversation history). |
| `Constraint` | Constraint algebra mirroring Python's `PYDANTIC_CONSTRAINT_MAP` (prose hint + JSON-Schema keyword). |

### Runtime & observability

| Type | Role |
|------|------|
| `RuntimeContext` | The active context, partitioned into `RuntimeServices`, `RuntimeConfig`, `RuntimeScope`, and `RuntimeDelta`. Flat construction/access remains available for callers. |
| `RuntimeDelta` / `Executed[A]` | Ordered trace/history output and its writer-like value carrier. Delta concatenation is a monoid; `Executed.flatMap` combines deltas in execution order. |
| `RuntimeEnvironment` | Process-wide manager: the global default context, thread-local overlays, monotonic call/async-task ids, and callback dispatch. `configure` sets the global; `with*` scopes overrides. |
| `CallbackEvent` / `CallbackHandler` | The eight lifecycle events (module/LM/adapter/tool × start/end), correlated by `callId`/`parentCallId`, and the handler interface that consumes them. |
| `TraceEntry` / `HistoryEntry` | Recorded module calls (inputs/outputs/failure) and LM calls; appended to the context when enabled, capped by `maxHistorySize`. `HistoryRenderer` produces dspy-style inspection output. |
| `DspyError` | Sealed error hierarchy: `ConfigurationError`, `ValidationError`, `NotFoundError`, `ParseError`, `RuntimeError`, and the `LmError` family for a failure of a language model call (`LmRateLimitError`, `LmAuthError`, `ContextWindowExceededError`, and others, with `LmErrorDetails`). Each has a `code` + message, propagated through `Either`. |
| `ToolCall` | A model-requested tool invocation (`name` + args record, and the `id` of the provider when it has one), carried as structured data through wire → adapter → domain decode. `toRecord`, `fromRecord`, and `fromDynamic` give its record form. |
| `ToolCallResult` | The result of one tool call: `name`, `value`, `callId`, `isError`. `ToolCallResult.forCalls` pairs results with calls. |
| `History` | The conversation history of a signature: a list of message records whose keys are field names of the signature. An adapter replays the messages as earlier turns. A message holds the results of its tool calls under `History.ToolCallResultsKey`. |
| `CodeInterpreter` / `ReplCodeInterpreter` | The code-execution sandbox interface used by `RLM`; the Deno/Pyodide and subprocess-Python implementations live here. |
| `ReplInterpreterFactory` / `SandboxOutputField` | The factory that builds one REPL interpreter for one program call, from the host tools and the output fields of `SUBMIT`. `RuntimeServices.interpreterFactory` holds the factory of the runtime context. |

## Design notes

- **Schema-backed bags, not `Any`.** The `:=` operator requires a `Schema` in scope, so values lifted into the
  interchange format are losslessly encodable. This is what lets the same record serialize cleanly to JSON and flow through
  both domain-value and record-value call paths.
- **The runtime owns bookkeeping.** Programs are pure; trace, history, callbacks, and the call stack live in
  `RuntimeContext`/`RuntimeEnvironment`, not on per-instance fields. Nested and concurrent scopes coexist
  without shared mutable state.
- **Observability never swallows work.** `CallbackDispatcher` emits a paired start/end event around each scope;
  a failed thunk still emits its end event (carrying the `Left`), and a thrown exception becomes a
  `RuntimeError` and is rethrown.
- **Constraint parity.** The constraint enum is paired prose + JSON-Schema keywords so both prose adapters
  (`ChatAdapter`) and structured adapters (`JSONAdapter`) can render the same constraints.

## Source layout

| File / package | Contents |
|----------------|----------|
| `data/` | Individual `Example`, `Completions`, and `RawPrediction` data aggregates |
| `contracts/Usage.scala` | `LmUsage` and `TokenCategory` execution metadata |
| `contracts/DynamicValues.scala` | interchange-format helpers + `:=` / `updated` extensions |
| `contracts/Runtime.scala` | Cycle-breaking LM/adapter reference traits |
| `contracts/RuntimeContext.scala` | `RuntimeContext`, services/config/scope partitions, and the flat compatibility surface |
| `contracts/RuntimeOutput.scala` | `TraceEntry`, `HistoryEntry`, the `RuntimeDelta` monoid, and `Executed[A]` |
| `contracts/Errors.scala` | the `DspyError` hierarchy |
| `contracts/TypeRef.scala`, `Constraint.scala`, `FieldSpec.scala`, `SignatureLayout.scala` | signature wire types, field constraints and metadata, and the compiled layout |
| `contracts/SignatureLayoutStateCodec.scala` | `DynamicValue.Record` and JSON persistence for `SignatureLayout` |
| `contracts/SignatureOps.scala` | idempotent layout-surgery extensions (prepend/append/replace) |
| `contracts/Callbacks.scala` | `CallbackEvent` events + `CallbackHandler` |
| `contracts/ToolCall.scala`, `History.scala`, `CodeInterpreter.scala`, `ClosableIterator.scala`, `HistoryRenderer.scala` | tool calls and tool call results, the conversation history, code-exec primitives, streaming iterator, history rendering |
| `signatures/` | the `in -> out` DSL parser and `create` helpers |
| `runtime/` | `RuntimeEnvironment`, `CallbackDispatcher`, context propagation, the Deno/Pyodide and subprocess interpreters |

## Relation to dspy

`core` is where the dspy4s-specific shape decisions live: one codec (zio-blocks `Schema`, see the two-codec note
in the repo memory), data bags addressed by schema-checked `:=` keys rather than Python attribute access, and the
[module-purity principle](../../README.md) (pure programs, runtime-owned bookkeeping) that the rest of the
modules depend on.
