# dspy4s `core`

The foundation every other module builds on. `core` defines the data spine (how inputs, outputs, examples, and
predictions are represented), the signature/field metadata model, the execution context that threads through
every call, the error hierarchy, and the observability events. It has no dependencies on the rest of dspy4s.

## The core idea

Four abstractions anchor the framework:

- **A single data spine.** All data — example inputs, model outputs, configs — flows as a zio-blocks
  `DynamicValue.Record`, a schema-typed discriminated-union value rather than `Map[String, Any]`. The `:=`
  operator and the `DynamicValues` helpers lift typed values onto this spine at the edges; internal codec paths
  produce `DynamicValue` directly so nothing is lost in round-trips.
- **A signature/field model.** A `SignatureLayout` is an ordered list of `FieldSpec`s, each tagged with a
  `FieldRole` (`Input`/`Output`), a wire `TypeRef`, a description, prefix, and constraints. Adapters and
  programs read this to build prompts and parse responses.
- **A threaded execution context.** A `RuntimeContext` carries the active LM, adapter, callbacks, concurrency
  knobs, and the per-thread accumulations (trace, history, call stack). It passes as a `using` parameter, so
  configuration is scoped, not global mutable state.
- **A pure observability model.** Lifecycle work emits `CallbackEvent`s (module/LM/adapter/tool start and end),
  correlated into a tree by call id. Handlers build traces and history by observing events, so programs stay
  free of logging concerns.

## Key types

### Data

| Type | Role |
|------|------|
| `Example` | A training/demo data point: a `DynamicValue.Record` of fields plus the `inputKeys` partition marking inputs vs labels. |
| `DynamicPrediction` | The result of one predict call: completion fields, optional multi-candidate `Completions`, and LM usage. Has lenient accessors (`asString`, `asInt`, `asDouble`, `asBoolean`). |
| `Completions` | A column-oriented view of N candidate completions; `at(i)` reconstructs row `i` as a `DynamicPrediction`. |
| `DynamicValues` | The helper surface over the spine: `fromAny`, `recordFromEntries`, `recordGet`, `mergeRecords`, `renderText`, plus the `:=` and `updated` extensions. `fromAny` is reserved for user-facing edges. |

### Signature / fields

| Type | Role |
|------|------|
| `SignatureLayout` | The runtime layout: name, optional instructions, ordered `FieldSpec`s. Built via `create` (validating), `parse` (DSL), or typed derivation. |
| `FieldSpec` | Per-field metadata: name, role, wire type, description, prefix, default, constraints. `normalize` fills defaults so adapters see a uniform surface. |
| `FieldRole` | `Input` (the LM receives) vs `Output` (the LM produces). |
| `TypeRef` | The wire-format type tag the LM sees (`string`, `bool`, `json`, …); unknown tokens pass through opaque. |
| `Constraint` | Constraint algebra mirroring Python's `PYDANTIC_CONSTRAINT_MAP` (prose hint + JSON-Schema keyword). |

### Runtime & observability

| Type | Role |
|------|------|
| `RuntimeContext` | The active context: configured fields (LM, adapter, callbacks, concurrency, history cap) + accumulated fields (trace, history, call stack). Threads as `using`. |
| `RuntimeEnvironment` | Process-wide manager: the global default context, thread-local overlays, monotonic call/async-task ids, and callback dispatch. `configure` sets the global; `with*` scopes overrides. |
| `CallbackEvent` / `CallbackHandler` | The eight lifecycle events (module/LM/adapter/tool × start/end), correlated by `callId`/`parentCallId`, and the handler interface that consumes them. |
| `TraceEntry` / `HistoryEntry` | Recorded module calls (inputs/outputs/failure) and LM calls; appended to the context when enabled, capped by `maxHistorySize`. `HistoryRenderer` produces dspy-style inspection output. |
| `DspyError` | Sealed error hierarchy: `ConfigurationError`, `ValidationError`, `NotFoundError`, `ParseError`, `RuntimeError`, `ContextWindowExceededError`, each with a `code` + message, propagated through `Either`. |
| `ToolCall` | A model-requested tool invocation (`name` + args record), carried as structured data through wire → adapter → typed decode. |
| `CodeInterpreter` / `ReplCodeInterpreter` | The code-execution sandbox interface used by `CodeAct` / `RLM`; the Deno/Pyodide and subprocess-Python implementations live here. |

## Design notes

- **Schema-typed bags, not `Any`.** The `:=` operator requires a `Schema` in scope, so values lifted onto the
  spine are losslessly encodable. This is what lets the same record serialize cleanly to JSON and flow through
  both typed and dynamic call paths.
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
| `contracts/Data.scala` | `Example`, `Completions`, `DynamicPrediction` |
| `contracts/DynamicValues.scala` | spine helpers + `:=` / `updated` extensions |
| `contracts/Runtime.scala` | `RuntimeContext`, `TraceEntry`, `HistoryEntry`, LM/adapter refs |
| `contracts/Errors.scala` | the `DspyError` hierarchy |
| `contracts/SignatureLayout.scala` | `SignatureLayout`, `FieldSpec`, `FieldRole`, `TypeRef`, `Constraint` |
| `contracts/SignatureOps.scala` | idempotent layout-surgery extensions (prepend/append/replace) |
| `contracts/Callbacks.scala` | `CallbackEvent` events + `CallbackHandler` |
| `contracts/ToolCall.scala`, `CodeInterpreter.scala`, `ClosableIterator.scala`, `HistoryRenderer.scala` | tool calls, code-exec primitives, streaming iterator, history rendering |
| `signatures/` | the `in -> out` DSL parser and `create` helpers |
| `runtime/` | `RuntimeEnvironment`, `CallbackDispatcher`, context propagation, the Deno/Pyodide and subprocess interpreters |

## Relation to dspy

`core` is where the dspy4s-specific shape decisions live: one codec (zio-blocks `Schema`, see the two-codec note
in the repo memory), data bags addressed by typed `:=` keys rather than Python attribute access, and the
[module-purity principle](../../README.md) (pure programs, runtime-owned bookkeeping) that the rest of the
modules depend on.
