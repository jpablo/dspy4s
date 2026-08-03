# dspy4s `typed`

The compile-time-typed signature layer. Where [`core`](../core/README.md) carries a runtime `SignatureLayout`
(separate untyped input and output field cohorts), `typed` pairs that layout with static Scala types — an input type `I` and an
output type `O` — and the bidirectional codec that moves between a typed value and the `DynamicValue.Record`
spine. This is what gives the typed program surface (`Predict[I, O]`, `ChainOfThought[I, O]`, …) its
compile-time safety. It depends only on `core`.

## The core idea

A `Signature[I, O]` wraps a `SignatureLayout` alongside a `Shape[I]` and a `Shape[O]` — schema-aware
projections of the input and output types. At compile time a macro inspects a case-class (or named-tuple, or
`Spec`-trait) signature, extracts each field's name and type, assigns it to an input or output cohort, and derives its wire `TypeRef` from the
field's zio-blocks `Schema`. At runtime, `Shape` uses that same `Schema` to **encode** a typed value into a
record (`Schema.toDynamicValue`) and to **decode** a record back into the typed value
(`Schema.fromDynamicValue`, after LM-shaped coercion). The codec speaks only `DynamicValue.Record` end to end —
no intermediate `Map[String, Any]` — so a typed program can hand the adapter a fully-typed `I` and decode the
model's reply into a validated `Prediction[O]`.

```scala
case class Input(sentence: String)
case class Output(sentiment: Emotion)

val sig = Signature.derived[Input, Output] // layout + RoundTripShape[Input] + RoundTripShape[Output]
```

## Key types

| Type | Role |
|------|------|
| `Signature[I, O]` | The typed signature: `SignatureLayout` + `Shape[I]` + `Shape[O]`. Four entry points — `derived[I, O]` (case classes), `of[T <: Spec]` (trait spec), `from(method)` (method introspection), `fromType[F]`. |
| `Shape[A]` | The general typed/record boundary: `fieldSpecs`, encode (`A => Record`), and decode (`Record => Either[DspyError, A]`). It does not claim a total round trip for every possible carrier. |
| `RoundTripShape[A]` | A lawful `Shape[A]` satisfying `decode(encode(a)) == Right(a)`. Schema-derived product and named-tuple shapes implement it. |
| `Shape.MapShape` | The dynamic fallback for raw records. It validates required fields on decode and therefore is intentionally only a `Shape[DynamicValue.Record]`, not a `RoundTripShape`: its carrier can represent invalid records. |
| `ZioSchemaCodec` | The bridge between a zio-blocks `Schema[A]`/`Reflect` and a dspy4s `RoundTripShape[A]`: derives `FieldSpec`s, maps wire `TypeRef`s (`typeRefFor`), and normalizes LM strings before decode. |
| `Prediction[O]` | The typed output: decoded `O` plus its `RawPrediction` evidence, so token usage and schema-uninterpreted values stay inspectable. |
| `SignatureBuilder` | A fluent, macro-free builder (`.input[T](name)` / `.output[T](name)`) for assembling a layout programmatically. |
| `Spec` / `InputField[A]` / `OutputField[A]` | The declarative trait-spec surface: a `Spec` subclass declares fields as `InputField`/`OutputField` members. |
| `OutputAugmentation` | Type-level machinery to prepend a named field to an output type (the `WithField[O, Name, T]` match type), idempotent and cast-free — how `ChainOfThought` adds `reasoning: String`. |
| `SchemaInterop` | The public seam onto `ZioSchemaCodec` (`decodeValue[A]`, `typeRef[A]`) for code outside this module, e.g. tool-argument decoding. |

## Shape law boundary

Schema-backed shapes carry their law on the abstract `RoundTripShape[A]` trait:

```scala
shape.decode(shape.encode(value)) == Right(value)
```

There is no reverse law requiring `encode(decode(record)) == record`. Decode may normalize LM-shaped primitives and
accept additional wire fields, so re-encoding can legitimately produce a canonicalized record.

The distinction from `Shape[A]` matters for dynamic signatures. A `MapShape` can require a field named `answer`, while
its Scala carrier remains the unrestricted type `DynamicValue.Record`; a record without `answer` is representable and
correctly fails decoding. Claiming the round-trip law for that carrier would be false.
[`RoundTripShapeLawSuite`](src/test/scala/dspy4s/typed/RoundTripShapeLawSuite.scala) executes the positive law and pins
this dynamic counterexample.

## Design notes

- **One codec, no `FieldCodec`.** All encode/decode goes through zio-blocks `Schema` on the `DynamicValue.Record`
  spine. There is no per-field codec layer (it was removed; don't reintroduce it).
- **Metadata derives from the same `Reflect` as the decode.** `FieldSpec`s (name and wire type) come from the
  field's `Reflect`, and the decode path is driven by it too, so names and type mappings never drift.
- **LM-shaped coercion before decode.** `ZioSchemaCodec.normalize` walks the `DynamicValue` and coerces
  primitives the way LM output tends to arrive — `"true"` → `Boolean`, `"42"` → `Int`, trimmed numerics,
  Option/Variant wrapping — so a loosely-formatted model reply still decodes into the strict typed value.
- **Direction-neutral shapes.** `Shape[A]` derives role-free field metadata. `Signature.derived` assigns the input
  and output shapes' fields to the corresponding `SignatureLayout` cohorts.
- **Law claims follow the carrier.** Schema-derived shapes return `RoundTripShape[A]`; validating raw-record shapes
  remain plain `Shape[A]`. The API does not attach a stronger equation than a carrier can satisfy.
- **Compile-time validation.** The macros (`FunctionMacro`, `SpecMacro`) fail compilation when a field type
  lacks a `Schema`, names collide, or the trait/method shape is invalid — type errors, not runtime surprises.

## Source layout

| File | Contents |
|------|----------|
| `Signature.scala` | `Signature[I, O]` and its four entry points |
| `Shape.scala` | `Shape[A]`, lawful `RoundTripShape[A]`, `SchemaTupleShape`, `MapShape`, derivation factories |
| `ZioSchemaCodec.scala` | the Schema↔Shape bridge, `normalize`, `typeRefFor`, field-spec derivation |
| `Prediction.scala` | `Prediction[O]` and its decoding factory |
| `SignatureBuilder.scala` | the fluent, macro-free builder |
| `Spec.scala` | the `Spec` trait + `InputField`/`OutputField` markers |
| `OutputAugmentation.scala` | type-level field prepending (`WithField`, `PrependField`) |
| `SchemaInterop.scala` | public codec seam for other modules |
| `internal/FunctionMacro.scala`, `internal/SpecMacro.scala` | the derivation macros |

## Relation to dspy

Python dspy has no static signature types — a `Signature` is a runtime object with attribute access. The
`typed` module is the dspy4s addition that makes signatures statically typed Scala values while keeping a
faithful runtime `SignatureLayout` underneath, so the statically typed surface and dynamic spine in
[`programs`](../programs/README.md) share one representation. Per the [single-codec
decision](../../README.md), zio-blocks `Schema` is the only codec involved.
