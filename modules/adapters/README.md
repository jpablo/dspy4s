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

## Key types

| Type | Role |
|------|------|
| `Adapter` | The trait: `format` (build the prompt), `parse` (extract outputs), `streamingState` (optional per-field streaming), `execute` (full round-trip). |
| `ChatAdapter` | Marker-framed (`[[ ## name ## ]]`) formatting and parsing; mirrors Python dspy. Configurable native function-calling, parallel tool calls, tool choice. |
| `JSONAdapter` | JSON instructions; inlines a JSON schema when one is available (typed path), merges `response_format` into request options; falls back to text for single-output signatures. |
| `XMLAdapter` | `<outputs><field>…</field></outputs>` framing with the same single-output fallback. |
| `TwoStepAdapter` | A natural-language first call to the main LM, then a ChatAdapter second call to an `extractionModel` that structures the free-form reply. |
| `AdapterInvocation` | Everything an adapter needs: layout, demos, inputs, the `LmRequest`, optional output JSON schema, and tool specs. |
| `FormattedPrompt` | An adapter's output: messages + metadata + `requestOptions` (the seam where structured-output and native-tool options are injected). |
| `ParsedOutput` | A parser's result: the `values` record, the `rawText`, and a metadata bag. |
| `FieldChunk` / `AdapterStreamingState` | A field-routed text fragment with an `isLast` boundary, and the per-call state machine (`receive(delta)`, `finish()`) that emits them. |
| `NativeFunctionCalling` | Shared helpers that gate native tool-calling and inject `tools` / `parallel_tool_calls` / `tool_choice` into request options. |
| `ToolSpec` / `ToolParameterSpec` / `ToolChoice` / `ToolSchemaBridge` | Tool definitions and the bridge that renders them to OpenAI function schemas. |
| `AdapterConstraints` | Renders field-constraint prose blocks (inline per-field for chat, consolidated for JSON/XML). |

## Design notes

- **The field-prefix protocol.** JSON and XML adapters label each input value with the field's `prefix`
  (default `field_name:`); ChatAdapter uses its marker framing instead.
- **Single-output fallback.** JSON and XML adapters, when one output field exists, treat the whole reply as that
  field's value if structured parsing fails (default on). ChatAdapter extends this to streaming: before any
  marker is seen, raw tokens route to the single field.
- **Native tool-calling is adapter-level and gated.** It activates only when native function-calling is on, tools
  are supplied, the signature has a `tool_calls` field, and the LM advertises support. The `tool_calls` field is
  excluded from the rendered text prompt and filled post-parse from the structured response; missing text fields
  on a tool turn default to `Null` rather than erroring (matching dspy's lenient `setdefault`). ReAct keeps its
  text protocol unchanged.
- **Two JSON modes, one lenient.** Without schema support, JSONAdapter gives prose key-guidance; with it, it
  injects `response_format: {type: "json_schema", …, strict: false}`. The schema is embedded leniently because
  dspy4s schemas may not satisfy OpenAI strict mode. (See the [two-codec note](../../README.md): the
  DynamicValue codec is strict round-trip; the typed-Schema JSON codec is the lenient one for loose provider
  payloads.)
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
| `contracts/AdapterContracts.scala` | `Adapter`, `AdapterInvocation`, `FormattedPrompt`, `ParsedOutput`, `FieldChunk`, `AdapterStreamingState` |
| `contracts/NativeFunctionCalling.scala` | tool-call gating + option injection helpers |
| `contracts/ToolContracts.scala` | `ToolSpec`, `ToolParameterSpec`, `ToolSchemaBridge` |
| `contracts/AdapterConstraints.scala` | constraint-prose rendering |
| `internal/JsonDynamic.scala` | shared `ujson.Value` ↔ `DynamicValue` conversion |

## Relation to dspy

This ports `dspy.adapters`. The chat marker protocol, the JSON adapter's structured-output path, and the
constraint rendering follow upstream; the native function-calling design — adapter-level, with ReAct staying on
the text protocol — is recorded in the [function-calling design memory](../../README.md).
