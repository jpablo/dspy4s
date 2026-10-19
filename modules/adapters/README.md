# dspy4s `adapters`

The prompt-protocol layer. An adapter is what turns a `SignatureLayout` + inputs + few-shot demos into the
messages an LM receives, and parses the model's reply back into output fields. It is the seam between the
structured task definition and the unstructured LM. Depends on [`core`](../core/README.md) and
[`lm`](../lm/README.md).

## The core idea

A signature says *what* a task's inputs and outputs are; an adapter decides *how* to frame that for a model and
*how* to read the answer back. dspy4s ships three framing strategies:

- **`ChatAdapter`** frames each field with `[[ ## field_name ## ]]` markers — unambiguous for multi-line values
  and for streaming.
- **`JSONAdapter`** asks the model for a JSON object, and when the LM supports it, enforces an OpenAI
  `response_format` JSON schema rather than relying on prose.
- **`XMLAdapter`** frames fields in `<field>…</field>` tags.

All three also understand **native function-calling**: when enabled and the LM supports it, a `tool_calls`
output field is filled directly from the provider's structured tool response instead of being requested as
text. This is handled uniformly at the adapter level, independent of any ReAct-style reasoning flow.

All three also replay a **conversation history**. A signature takes the history as one input field of type
`History` (wire type `TypeRef.history`). The adapter does not show that field as a value. It replays each message as
earlier turns, between the demos and the current request.

## Key types

| Type | Role |
|------|------|
| `Adapter` | The trait: `format` (build the prompt), `parse` (extract outputs), `streamingState` (optional per-field streaming), `execute` (full round-trip). |
| `ChatAdapter` | Marker-framed (`[[ ## name ## ]]`) formatting and parsing; mirrors Python dspy. Configurable native function-calling, parallel tool calls, tool choice. |
| `JSONAdapter` | The system message of the chat adapter with a JSON object for the outputs; merges a strict `response_format` (or JSON mode) into request options; falls back to text for single-output signatures. |
| `XMLAdapter` | `<field>…</field>` framing for each field, with the same single-output fallback. |
| `TwoStepAdapter` | A natural-language first call to the main LM, then a ChatAdapter second call to an `extractionModel` that structures the free-form reply. |
| `AdapterInvocation` | Everything an adapter needs: layout, demos, inputs, the `LmRequest`, optional output JSON schema, and tool specs. |
| `FormattedPrompt` | An adapter's output: messages + metadata + `requestOptions` (the seam where structured-output and native-tool options are injected). |
| `ParsedOutput` | A parser's result: the `values` record, the `rawText`, and a metadata bag. |
| `FieldChunk` / `AdapterStreamingState` | A field-routed text fragment with an `isLast` boundary, and the per-call state machine (`receive(delta)`, `finish()`) that emits them. |
| `NativeFunctionCalling` | Shared helpers that gate native tool-calling and inject `tools` / `parallel_tool_calls` / `tool_choice` into request options. `encodeToolCalls` writes the value of a `tool_calls` field: one `{name, args}` record for each `ToolCall`, with the provider `id` when the call has one. |
| `ToolSpec` / `ToolParameterSpec` / `ToolChoice` / `ToolSchemaBridge` | Tool definitions and the bridge that renders them to OpenAI function schemas. |

## Design notes

- **Prompt parity (decision D3).** The Chat, JSON, XML, and TwoStep prompts are equal to the upstream prompts,
  character for character, where the text is fixed or the adapters can compute it from the data of the port
  (`internal/PromptText`, `internal/PythonText`; golden tests in `AdapterPromptParitySuite`). A structured type
  shows the type name of the port and the schema of its property in the output schema (`PortTypeTextSuite`). A
  value prints as upstream prints it: `True`, `None`, the Python form of a float, and JSON with the separators
  `", "` and `": "`. The JSON adapter labels each input with the chat markers, as upstream. The TwoStep adapter
  labels each value with the field name (`name: value`).
- **Single-output fallback.** JSON and XML adapters, when one output field exists, treat the whole reply as that
  field's value if structured parsing fails (default on). ChatAdapter extends this to streaming: before any
  marker is seen, raw tokens route to the single field.
- **Native tool-calling is adapter-level and gated.** It activates only when native function-calling is on, tools
  are supplied, the signature has a `tool_calls` field, and the LM advertises support. The `tool_calls` field is
  then excluded from the rendered text prompt and filled post-parse from the structured response; a missing text
  field on a tool turn takes its `defaultValue`, else a null, rather than an error (as dspy's lenient
  `_call_postprocess`). When native function-calling is off, or the LM does not support it, `tool_calls` is an
  ordinary output field in the Chat and JSON adapters: the prompt asks for a JSON object like
  `{"tool_calls": [{"name", "args"}]}`, and the parser reads that object, or a JSON list of the calls, from the text,
  as upstream. The XML adapter keeps the field as text (an open point). ReAct
  keeps its text protocol unchanged.
- **Missing outputs.** An output that the response does not have takes the `defaultValue` of its field, else a null
  when the field is `optional` (an `Option[T]` field, or `Optional[T]` in a string signature), else it is a
  `ParseError`. An explicit JSON null for an optional field is a null. The rule is the same in the Chat, JSON, and
  XML adapters (dspy's `apply_output_field_defaults`).
- **Parse errors.** A value that does not fit the type of its field is a `ParseError` with the field name and the
  raw response. In the chat format, the first header of a field wins. An optional field also reads the lenient
  forms of the typed decode (`AdapterTextSupport.optionalFallback`): `N/A` and `none` are a null, and a number can
  have a currency symbol and thousands commas (`$2,399.00`).
- **JSON fallback.** After a `ParseError` of a `ChatAdapter` response, the predictor formats, calls, and parses one
  more time with a `JSONAdapter` that has the native function-calling options of the chat adapter
  (`Adapter.fallback`, `ChatAdapter.useJsonAdapterFallback`, on by default). An LM error or a configuration error
  gives no second call, and a call that streamed visible output gets no second call.
- **Incomplete demos.** A demo with a value that is not null for each field is complete. A demo with the key of
  one input field and one output field at least, but not complete, is incomplete. A key with a null value counts, as
  upstream. An incomplete demo comes first, its user turn starts with "This is an example of the task, though some
  input or output fields are not supplied.", and a missing output shows "Not supplied for this particular example."
  A null value shows as `None` (or `null` in JSON). Each other demo is dropped. A field default does not fill a demo
  (`AdapterDemos`).
- **Conversation history replay.** `internal/ConversationHistory` builds the message sequence for the Chat, JSON,
  and XML adapters: system, demos, history turns, current request. Each adapter supplies only its text for a user
  turn and for an assistant turn (`TurnText`).
    - The system message is built from the full signature, so its field list names the history field. The current
      request and each replayed turn use the signature without the history field.
    - The input fields of a message make a user turn, with the output reminder of a current request. The output
      fields make an assistant turn in the form of a demo. A turn shows only the fields that its message has. A
      message with no input field gets no user turn, and a message with no output field gets no assistant turn.
      A key that is not a field of the signature is ignored.
    - A message can hold tool calls in the `tool_calls` field of the signature, and their results under
      `History.ToolCallResultsKey` (`ToolCallResult` records). With native function-calling on and a model that
      supports it, the turn is one assistant message with `toolCalls`, then one `MessageRole.Tool` message for each
      result. This needs an `id` on each call and the same `callId`s on the results, in the same order. If they do
      not match, the results are dropped and only the assistant text stays. In each other case the calls stay text:
      the assistant turn shows them as a field value with no ids (`{"tool_calls": [...]}`, as upstream `ToolCalls`),
      and one more user turn shows the results as the field `tool_call_results`
      (`{"tool_call_results": [...]}`).
    - A demo that has a history value shows it as a field. The history of a demo is not replayed.
      `TwoStepAdapter` does not replay a history. It shows the value as a field.
- **Strict structured outputs.** When the model supports a response schema, JSONAdapter sends
  `response_format: {type: "json_schema", json_schema: {name: "DSPyProgramOutputs", schema, strict: true}}`, as
  upstream `JSONAdapter._prepare_response_format`. The schema is the typed output schema, else a schema built from
  the field types (`StrictJsonSchema.fromFields`). `StrictJsonSchema.shape` puts it in strict shape: each property
  required, each object closed, an optional field nullable, a lone `allOf` merged, a `$ref` with other keys resolved,
  and a `oneOf` sent as `anyOf`. JSON mode (`{"type": "json_object"}`) replaces the schema for a text `tool_calls`
  field, an open map, or a type with no schema. Without schema support, JSONAdapter sends no `response_format` and
  gives prose key-guidance only.
- **Marker discipline for streaming.** Markers must be line-aligned; the streaming state holds back the longest
  possible partial marker so a token boundary never truncates one. On finish, held content flushes with
  `isLast = true`.
- **Parse failures carry the raw response.** A `ParseError` retains the raw LM text for debugging, and fallbacks
  tag their metadata (e.g. `"fallback" -> "text"`).

## Source layout

| File | Contents |
|------|----------|
| `ChatAdapter.scala`, `ChatStreamingState.scala` | marker-framed formatting/parsing and its streaming state machine |
| `JSONAdapter.scala`, `JsonStreamingState.scala` | JSON instructions / `response_format` / schema embedding and the char-by-char streaming JSON parser |
| `XMLAdapter.scala`, `XmlStreamingState.scala` | XML framing and its streaming parser |
| `TwoStepAdapter.scala` | natural-language-then-extract two-stage adapter |
| `contracts/Adapter.scala` | adapter formatting, parsing, streaming, and execution interface |
| `contracts/AdapterInvocation.scala`, `FormattedPrompt.scala`, `ParsedOutput.scala` | adapter input, formatted prompt, and parsed result data |
| `contracts/AdapterStreamingState.scala` | field chunks and the per-call streaming state machine |
| `contracts/AdapterFailure.scala` | fallback policy and shared parse-error construction |
| `contracts/NativeFunctionCalling.scala` | tool-call gating + option injection helpers |
| `contracts/ToolContracts.scala` | `ToolSpec`, `ToolParameterSpec`, `ToolSchemaBridge` |
| `internal/JsonDynamic.scala` | shared `ujson.Value` ↔ `DynamicValue` conversion |
| `internal/ConversationHistory.scala` | the message sequence of a request and the replay of a `History` input field (`TurnText`) |
| `internal/PromptText.scala`, `internal/PythonText.scala` | the shared prompt texts (type slots, field list, notes, values) and the Python text forms (`repr`, `json.dumps`, `strip`, `dedent`) |

## Relation to dspy

This ports `dspy.adapters`. The prompts of the four adapters, the JSON adapter's structured-output path, and the
constraint rendering follow upstream; the native function-calling design — adapter-level, with ReAct staying on
the text protocol — is recorded in the [function-calling design memory](../../README.md).
