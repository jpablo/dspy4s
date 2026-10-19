# dspy4s `adapters`

An adapter writes the prompt of a signature and reads the response of the model. It makes the messages from a
`SignatureLayout`, the inputs, and the demos. Then it reads the output fields from the response. It uses the
[`core`](../core/README.md) and [`lm`](../lm/README.md) modules.

## Main concepts

- **Format and parse.** `format` makes the messages of one request. `parse` reads one model output into a record of
  output fields. `execute` does the two steps, with the LM call between them.
- **Four adapters.** `ChatAdapter` puts each field after a `[[ ## name ## ]]` marker. `JSONAdapter` asks for one JSON
  object. `XMLAdapter` puts each field in a `<name>` tag. `TwoStepAdapter` asks for an answer in natural language, and
  then a second model reads the fields from that answer.
- **Native tool calls.** The Chat and JSON adapters can send the tools to the provider. The provider then gives the
  tool calls in a structured form, and the adapter puts them in the `tool_calls` output field. Else the model writes
  the tool calls as JSON text in that field.
- **Conversation history.** A signature can have an input field of type `History`. The Chat, JSON, and XML adapters
  send its messages as earlier turns, between the demos and the current request.

## Main types

| Type | Description |
|------|-------------|
| `Adapter` | The interface: `format`, `parse`, `execute`, `streamingState`, and `fallback` (the adapter to try after a parse error). |
| `ChatAdapter` | The marker format. Its options set native tool calls, parallel tool calls, the tool choice, and the JSON fallback. |
| `JSONAdapter` | The JSON format. It adds a `response_format` to the request options when the model supports a response schema. |
| `XMLAdapter` | The XML format. It has no native tool calls. |
| `TwoStepAdapter` | Asks the main model for an answer in natural language. Then a `ChatAdapter` call to the `extractionModel` reads the output fields from that answer. It shows a `History` input as a field value. |
| `AdapterInvocation` | The input of `format`: the layout, the demos, the inputs, the `LmRequest`, the output JSON schema, and the tool specs. |
| `FormattedPrompt` | The result of `format`: the messages, the metadata, and the `requestOptions` of the adapter. The options of the request win over `requestOptions`. |
| `ParsedOutput` | The result of `parse`: the `values` record, the `rawText` of the response, and the metadata. |
| `AdapterStreamingState` / `FieldChunk` | The state of one streamed call. `receive(delta)` and `finish()` give a `FieldChunk` for each part of the text of a field. The last part of a field has `isLast`. |
| `NativeFunctionCalling` | Decides if native tool calls are active. It adds `tools`, `parallel_tool_calls`, and `tool_choice` to the request options. |
| `ToolSpec` / `ToolParameterSpec` / `ToolChoice` | The data of a tool, of its parameters, and of the `tool_choice` value. `ToolSchemaBridge` writes a tool as an OpenAI function schema. |

## Design notes

- **Native tool calls have four conditions.** The adapter sends the tools only when `useNativeFunctionCalling` is on,
  the request has tools, the signature has a `tool_calls` output field, and the model supports native tool calls. In
  each other case, `tool_calls` is a text output field. The prompt then asks for `{"tool_calls": [{"name", "args"}]}`,
  and the parser reads that object or a JSON list of calls. The Chat, JSON, and XML adapters read the text tool calls.
- **An output that the response does not have.** The output takes the `defaultValue` of its field. Else it is a null
  when the field is `optional`. Else it is a `ParseError`. A value that does not fit the type of its field is also a
  `ParseError`. The `ParseError` keeps the raw response.
- **One output field.** When the signature has one output field, the Chat, JSON, and XML adapters can read a response
  with no structure as the value of that field. The stream state of `ChatAdapter` also sends the text to that field
  until a marker comes.
- **The JSON fallback.** After a `ParseError` of a `ChatAdapter` response, the predictor formats, calls, and parses
  one more time with a `JSONAdapter`. `useJsonAdapterFallback` controls this, and it is on by default. An LM error, a
  configuration error, or a call that streamed output to a listener gives no second call.
- **Demos.** The adapters drop a demo that does not have one input field and one output field at least. A demo that
  does not have a value for each field comes first, and its text tells the model that some fields are not supplied.
- **History turns.** The messages come in this order: the system message, the demos, the history turns, and the
  current request. With native tool calls active, a turn with tool calls becomes an assistant message with
  `toolCalls` and one `MessageRole.Tool` message for each result. Each call must have an `id`, and the results must
  have the same `callId`s in the same order. Else the adapter drops the results and keeps the assistant text. With
  text tool calls, the calls and their results stay text.
- **Strict response schema.** When the model supports a response schema, `JSONAdapter` sends a strict `json_schema`
  `response_format`. The schema is the typed output schema, else a schema from the field types. The adapter makes it
  strict: each property is required, and each object is closed. JSON mode (`{"type": "json_object"}`) replaces the
  schema for a text `tool_calls` field, an open map, or a type with no schema.
- **Stream markers.** In the `ChatAdapter` format, a marker must be at the start of a line. The stream state keeps the
  last characters of the text until it knows that they do not start a marker. Thus a token boundary does not cut a
  marker. `finish()` sends the rest of the text, with `isLast = true` on the last chunk.

## Source layout

| Package | Contents |
|---------|----------|
| top level | the four adapters and their prompt objects, parser objects, and stream states |
| `contracts/` | the `Adapter` interface, its input and result data, the stream state interface, the demo rule, the parse errors, native tool calls, and the tool data |
| `internal/` | the shared prompt texts, the history turns, the demo messages, the strict JSON schema, and the rules for text values. `ProgramPromptText` gives some of these texts to `ChainOfThought`, `ReAct`, and `RLM`. |

## Relation to dspy

This module ports `dspy.adapters`:

- The prompts of the four adapters have the same text as the upstream prompts, where the port can compute that text.
  `AdapterPromptParitySuite` checks them. A value prints in the Python form, for example `True` and `None`.
- Native tool calls are a feature of the adapter, as upstream. `ReAct` keeps its text protocol.
- `XMLAdapter` asks for a list, a dict, or a structured value as JSON text in its tag. Upstream asks for nested XML
  elements.
