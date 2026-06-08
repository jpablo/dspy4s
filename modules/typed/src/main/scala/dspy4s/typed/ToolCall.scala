package dspy4s.typed

import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

/** A single native tool call decoded onto the typed output: the tool `name` and its `args` (kept as a
  * `DynamicValue`, since tool arguments are inherently dynamic — they vary per tool). Use `Vector[ToolCall]` as an
  * output field type on a typed `Signature`/`Predict`, paired with [[Signature.markToolCalls]], to receive native
  * provider tool calls on the typed path (the dynamic counterpart is the `TypeRef.toolCalls` field). See G-7b. */
final case class ToolCall(name: String, args: DynamicValue) derives Schema
