# dspy4s `core`

`core` is the base of dspy4s, and all other modules use it. It contains the interchange format, the signature layout,
the runtime context, the errors, and the events. It uses only the `algebra` module.

## Main concepts

- **One interchange format.** All data moves as a zio-blocks `DynamicValue.Record`: example inputs, model outputs,
  and configuration values. Use `:=` or the `DynamicValues` helpers to put Scala values into a record.
- **A signature layout.** A `SignatureLayout` keeps the input fields and the output fields, each in order. Each field
  is a `FieldSpec` with a `TypeRef`, a description, a prefix, and constraints. Adapters use the layout to write the
  prompt and to read the response.
- **A runtime context.** Each call gets a `RuntimeContext` as a `using` parameter. A `with*` helper of
  `RuntimeEnvironment` changes the context for one block of code only.
- **Events.** dspy4s sends a start event and an end event for each unit of work, for example for a module call or an
  LM call. The events make a tree. Callback handlers use them to make the trace and the history.

## Main types

### Data

| Type | Description |
|------|-------------|
| `Example` | An example in a trainset, or a demo. `inputKeys` tells which fields are inputs. The other fields are labels. |
| `RawPrediction` | The parsed output of an adapter, before it becomes the Scala output type `O`. `asString`, `asInt`, `asDouble`, and `asBoolean` read one field. |
| `LmUsage` / `TokenCategory` | The token counts of an LM call. You can add two `LmUsage` values. |
| `Completions` | N candidate completions. `at(i)` gives candidate `i` as a `RawPrediction`. |
| `DynamicValues` | Helpers for the interchange format, and the `:=` and `updated` extensions. Use `fromAny` only at the boundary with user code. |

### Signatures and fields

| Type | Description |
|------|-------------|
| `SignatureLayout` | The runtime form of a signature: a name, instructions, the input fields, and the output fields. Make one with `create`, with `parse` (from text such as `"question -> answer"`), or from a `Signature`. |
| `FieldSpec` | The data of one field: name, type, description, prefix, default value, and constraints. |
| `TypeRef` | The type of a field as the model sees it, for example `string`, `bool`, or `json`. A Scala enum is a `string`. `tool_calls` and `history` mark the fields for native tool calls and for the conversation history. |
| `Constraint` | A limit on the value of a field, for example "greater than: 0". |

### Runtime and events

| Type | Description |
|------|-------------|
| `RuntimeContext` | The active context, in four parts: `RuntimeServices` (the model, the adapter, and the handlers), `RuntimeConfig` (the settings), `RuntimeScope` (the call stack), and `RuntimeDelta`. |
| `RuntimeDelta` / `Executed[A]` | `RuntimeDelta` is the trace and the history that a call adds. `Executed[A]` is a value together with its `RuntimeDelta`. |
| `RuntimeEnvironment` | Holds the global default context and the context of each thread. `configure` sets the global default. It also sends the events to the handlers. |
| `CallbackEvent` / `CallbackHandler` | The start events and the end events, and the interface of a handler that gets them. |
| `TraceEntry` / `HistoryEntry` | The record of one module call and the record of one LM call. `HistoryRenderer` shows the history as text. |
| `DspyError` | The sealed set of errors. The `LmError` errors are for a failed LM call. Functions return errors in an `Either`. |
| `ToolCall` / `ToolCallResult` | A tool call that the model asks for, and its result. |
| `History` | The conversation history of a signature. An adapter sends its messages as earlier turns of the conversation. |
| `CodeInterpreter` | The interface of a Python interpreter for `RLM`. Of the three implementations, only `DenoPyodideInterpreter` is a sandbox. |
| `ReplInterpreterFactory` | Makes one REPL interpreter for one program call. `RuntimeServices.interpreterFactory` holds it. |

## Design notes

- **Each value has a `Schema`.** The `:=` operator needs a `Schema` for the value. Thus a record can go to JSON and
  back with no loss.
- **The runtime keeps the records of a call.** The trace, the history, and the call stack are in the runtime
  context. A program does not keep them in its own fields.
- **Each start event has an end event.** The end event also comes when the work fails or throws an exception.
- **The same constraints in each adapter.** Each `Constraint` has a text for prose prompts and a JSON Schema keyword.
  Thus `ChatAdapter` and `JSONAdapter` show the same constraints.

## Source layout

| Package | Contents |
|---------|----------|
| `data/` | `Example`, `Completions`, and `RawPrediction` |
| `collections/` | `SizedVector`, a vector that has its length in its type |
| `contracts/` | the signature layout, the runtime context, the errors, the events, the tool calls, the history, and the interpreter interfaces |
| `signatures/` | the parser for signature text such as `"question -> answer"` |
| `runtime/` | `RuntimeEnvironment`, `CallbackDispatcher`, `ContextPropagation` (gives the context to other threads), and the three Python interpreters |

## Relation to dspy

`core` contains the main design decisions of dspy4s:

- There is one codec: the zio-blocks `Schema`.
- `:=` sets the value of a record field, and the compiler checks the `Schema` of the value. Python DSPy uses
  attribute access.
- A program has no state that changes, and the runtime keeps the trace and the history.
