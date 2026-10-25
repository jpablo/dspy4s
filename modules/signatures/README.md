# dspy4s `signatures`

`signatures` gives a signature Scala types: an input type `I` and an output type `O`. A `Signature[I, O]` holds a
`SignatureLayout` from [`core`](../core/README.md), a `Shape[I]`, and a `Shape[O]`. The domain-valued programs, for
example `Predict[I, O]`, use it. In the build, the module depends only on `core`.

## Main concepts

- **Types from Scala code.** You make a signature from case classes, a `Spec` trait, a method, a function type, or a
  text. Except for `fromStringDynamic`, dspy4s derives a zio-blocks `Schema` for `I` and `O`. It makes a `FieldSpec`
  for each field from that `Schema`.
- **The `Shape` codecs.** `Shape.encode` changes a Scala value into a `DynamicValue.Record`, the interchange format.
  `Shape.decode` changes a record into a Scala value. The layout and the codecs use the same `Schema`, thus they agree.
- **Errors at compile time.** A macro stops the compilation for a field type with no `Schema`, for two fields with the
  same name, or for a trait or a method that it does not support.

```scala
case class Input(sentence: String)
case class Output(sentiment: Emotion)

val sig = Signature.derived[Input, Output]("Sentiment") // layout + RoundTripShape[Input] + RoundTripShape[Output]
```

## Ways to make a signature

| Entry point | Source of the fields |
|-------------|----------------------|
| `Signature.derived[I, O](name)` | two case classes |
| `Signature.of[T <: Spec]` | a `Spec` trait: each abstract method returns an `InputField[A]` or an `OutputField[A]` |
| `Signature.from(method)` | a method: the parameters are the inputs, and the return type gives the outputs |
| `Signature.fromType[F]` | a function type, for example `(sentence: String) => Emotion` |
| `Signature.fromString("question -> answer: bool")` | a literal text, parsed at compile time. The field types can be `str`, `int`, `float`, `bool`, and `History` (inputs only). |
| `Signature.fromStringDynamic(text)` | a text at runtime. `I` and `O` are `DynamicValue.Record`. |
| `Signature.builder(name)` | a `SignatureBuilder` with `.input[T](name)` and `.output[T](name)`. `build` gives a `SignatureLayout`. |

`of`, `from`, `fromType`, and `fromString` give a named tuple for `I`. `from` and `fromType` put a single output value
in a field `result`.

## Main types

| Type | Description |
|------|-------------|
| `Signature[I, O]` | A name, a `SignatureLayout`, a `Shape[I]`, and a `Shape[O]`. Its field operations change the layout and the shapes in one call (see below). |
| `Shape[A]` | The `Shape` codecs of one type: `fieldSpecs`, `encode`, `decode`, and `jsonSchemaString`. |
| `RoundTripShape[A]` | A `Shape[A]` with a law: `decode(encode(a)) == Right(a)`. Each shape that comes from a `Schema` has this type. |
| `Shape.MapShape` | The shape of `fromStringDynamic`. Its `decode` only checks that each field of the layout is in the record. |
| `OutputAugmentation` | The output type of `Signature.prependOutput`: `WithField[O, Name, T]` is a named tuple, and the `PrependField` evidence makes and splits its values. |
| `SchemaInterop` | Gives other modules `decodeValue[A]` and `typeRef[A]`, for example to decode the arguments of a tool. |
| `ZioSchemaCodec` | The internal object that makes a `RoundTripShape`, the `FieldSpec` values, and the JSON schema from a `Schema`. |

## Field operations

A composite program adds fields to the signature of the user. One call changes the layout and the shapes together,
thus the layout and the shapes always agree on the names and the order of the fields. Each operation keeps the
effective instructions of the signature.

| Operation | Result | Use |
|-----------|--------|-----|
| `signature.appendInput(field)` | `Signature[(I, String), O]` | The `trajectory` input of `ReAct` |
| `signature.appendInputs(fields)` | `Signature.AppendedStringInputs[I, O]` | The `reasoning_attempt_i` inputs of `MultiChainComparison` |
| `signature.prependOutput(name, field)` | `Signature[I, WithField[O, name, String]]` | The `reasoning` output of `ChainOfThought` |
| `signature.withOutputs(fields, shape)` | `Signature[I, O2]` | The loop step of `ReAct` |

- `appendInput` and `appendInputs` reject a name that the signature has. A composite program adds the field, thus a
  name clash is a bug of the program.
- `appendInputs` is for a number of fields that is known only at run time. Its result holds the new `signature` and
  the opaque type `Values`. Only `validate` gives a `Values`, thus the encode always gets one value for each field.
- `prependOutput` changes nothing when the outputs already have the field. Else the field is first in the layout, in
  the output shape, and in the JSON schema of the output shape.
- `withOutputs` replaces all the outputs. The names of `shape` must be the names of `fields`, in the same order.

## The law of a shape

```scala
shape.decode(shape.encode(value)) == Right(value)
```

There is no law in the opposite direction, because `decode` can change the values of a model into a standard form.
A `MapShape` is not a `RoundTripShape`: a record without a required field is a valid `DynamicValue.Record`, but its
decode fails. [`RoundTripShapeLawSuite`](src/test/scala/dspy4s/signatures/RoundTripShapeLawSuite.scala) checks the law
and this case.

## Design notes

- **The schema comes from the structure of the type.** `derived` and the macros ignore a custom `Schema` in scope for
  `I`, `O`, and their fields. Thus a custom `given` cannot change the fields or the codecs of a signature.
- **Decode accepts the text of a model.** Before the decode, `ZioSchemaCodec.normalize` changes `"true"` into a
  `Boolean` and `"42"` into an `Int`. It also removes a currency symbol and commas from a number, and changes a text
  into an enum case.
- **An absent `Option` field is `None`,** also in a nested record. The values `null`, `"none"`, and `"n/a"` are also
  `None`. An absent field that is not an `Option` makes the decode fail.
- **A `History` field gets `TypeRef.history`** from each entry point and from the builder.
- **A sequence of `ToolCall` values gets `TypeRef.toolCalls`,** for example a `Vector[ToolCall]` output field. The
  adapter with native tool calls fills that field from the tool calls of the provider.
- **The framework types have a marker.** `History`, `ToolCall`, `Noul`, `Score`, and `Choice` have a type-level
  `Modifier.config` entry with the key `TypeMarker.Key` (`dspy4s.typeRef`). The codec reads the marker from the
  schema, and the macros read it from the annotations of the type. Neither of them keeps a list of these types.
- **A `Score[L]` field has the cases of `L` as its levels,** from the type of its `level: Option[L]` field. A
  `Choice[O]` field has the cases of `O` as its options, from the type of its `value: O` field.
- **The JSON schema has no `ToolCall.id`.** `Shape.jsonSchemaString` removes the `id` of each `ToolCall` from the
  schema that a model sees. The id comes from the provider.

## Source layout

| Package | Contents |
|---------|----------|
| `signatures/` | `Signature`, `Shape`, `ZioSchemaCodec`, `SignatureBuilder`, `Spec`, `OutputAugmentation`, and `SchemaInterop` |
| `signatures/internal/` | the macros: `FunctionMacro` (`from`, `fromType`, `fromString`), `SpecMacro` (`of`), and their helpers |

## Relation to dspy

A Python DSPy signature is a runtime object with no static types. A dspy4s signature has Scala types for its inputs
and its outputs, and a `SignatureLayout` at runtime. Thus the domain-valued programs and the record-valued programs in
[`programs`](../programs/README.md) both use `SignatureLayout`. The zio-blocks `Schema` is the only codec.
