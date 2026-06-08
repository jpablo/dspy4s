package dspy4s.core.contracts

import zio.blocks.schema.{DynamicValue, Schema}

/** A `Schema` for the `Record` case of `DynamicValue`.
  *
  * zio-blocks ships a bespoke `Schema[DynamicValue]` (the dynamic catch-all) but NONE for its individual subtypes,
  * and one can't be auto-derived — `DynamicValue.Record`'s backing `Chunk[(String, DynamicValue)]` field has no
  * matching collection `Schema` on the derivation path (it resolves to `IndexedSeq`, type-mismatching `Chunk`).
  * We build it instead by narrowing/widening the dynamic schema via `Schema.transform` (the only such combinator —
  * a `Schema` round-trips both ways, so there is no one-directional `map`/`contramap`). This lets a case class carry
  * a precisely-typed `DynamicValue.Record` field and still `derives Schema`. The narrowing throws if a value isn't
  * a `Record`, which is safe for the call sites here (a tool call's `args` is always a JSON object / record). */
given recordSchema: Schema[DynamicValue.Record] =
  Schema[DynamicValue].transform(
    {
      case record: DynamicValue.Record => record
      case other => throw new IllegalArgumentException(s"Expected a DynamicValue.Record, got: $other")
    },
    (record: DynamicValue.Record) => record
  )

/** A tool invocation requested by the model: the tool `name` and its `args` (the call's `arguments` object, decoded
  * from the provider's JSON into a `DynamicValue.Record` at the parse boundary, so it travels the tool pipeline as
  * structured data without a lossy `Any` round-trip).
  *
  * Single shared type across layers: the wire layer (`lm`) populates it from provider responses, the adapters read
  * it, and the typed decode path uses it as a `Vector[ToolCall]` output field (paired with
  * `dspy4s.typed.Signature.markToolCalls`). Lives in `core` so both `lm` and `typed` — which don't depend on each
  * other — can name it; the [[recordSchema]] given lets the typed `Schema` derivation keep the precise `args` type. */
final case class ToolCall(name: String, args: DynamicValue.Record) derives Schema
