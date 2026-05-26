# Typed Signatures — User Guide

> A pragmatic guide to dspy4s's typed-signatures layer. For design
> rationale see [TYPED_SIGNATURES.md](TYPED_SIGNATURES.md); for the
> implementation arc see
> [TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md](TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md).

## Quick start

```scala
import dspy4s.programs.Predict
import dspy4s.typed.{InputField, OutputField, Spec, Signature}

// 1. Describe your I/O as a DSPy-style spec trait.
trait QA extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

// 2. Build the signature.
val sig = Signature.of[QA]

// 3. Run it. (RuntimeContext is summoned from RuntimeEnvironment as today.)
given dspy4s.core.contracts.RuntimeContext =
  dspy4s.core.runtime.RuntimeEnvironment.current

val result = Predict(sig).run((question = "Capital of France?"))

result.map(_.output.answer)   // typed: String
result.map(_.output.score)    // typed: Double
result.map(_.raw.lmUsage)     // raw escape hatch (completions, usage, etc.)
```

That's the whole loop. The rest of this guide explains the six
authoring surfaces, the supported types, and the few sharp edges to
know about.

---

## Authoring surfaces

The typed layer exposes **six ways** to define a signature. All six
compile down to the same `Signature[I, O]` (and pass through the same
adapter / LM / runtime path), so you can mix them freely in one
project — pick whichever fits each call site.

### 1. Trait-as-spec macro — `Signature.of[T <: Spec]`

The recommended typed surface and closest match for Python DSPy: a
trait extending `Spec` declares abstract methods wrapped in
`InputField[T]` / `OutputField[T]`.

```scala
import dspy4s.typed.{InputField, OutputField, Spec, Signature}

enum Emotion:
  case sadness, joy, love

object Emotion extends dspy4s.typed.FieldCodec.FlatEnum[Emotion]

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]

val sig = Signature.of[EmotionSpec]
```

The runtime name defaults to the trait name. Pass
`name = "Emotion"` or `instructions = "Classify emotion."` when you
want to override either value at construction time.

End-to-end typed I/O uses Scala named tuples:
`Predict(sig).run((sentence = "..."))` accepts a named-tuple input,
and `tp.output.sentiment` is typed as `Emotion`. Compile-time
validation catches methods not wrapped in the marker types, methods
with parameters, missing `FieldCodec[X]`, duplicate field names,
concrete methods, and empty spec traits.

See [`modules/examples/.../typed/SpecExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/SpecExample.scala).

### 2. Function signatures — `Signature.fromType[F]`

Concise Scala function types can declare typed signatures without a
throwaway method body. Input fields come from the function parameter
types. Output fields come from the return type:

- scalar return values become one output field named `result`
- named-tuple return values keep their labels
- case-class / product return values keep their product field names

The nicest form uses named function parameters and named-tuple outputs:

```scala
val sig =
  Signature.fromType[
    (sentence: String) => (sentiment: Emotion, confidence: Double)
  ]

Predict(sig).run((sentence = "..."))
  .map(_.output.sentiment)   // typed: Emotion
```

Anonymous inputs are supported too. A single anonymous input is named
`input`; multiple anonymous inputs are named `input1`, `input2`, and so
on. A single scalar output is named `result`:

```scala
val sig = Signature.fromType[String => Emotion]
// signature string: input -> result
```

The runtime name defaults to `"Signature"` for DSPy-style anonymous
declarations. Pass `name = "Classify"` when a stable name is useful for
debugging or tracing. You can also pass signature-level instructions at
construction time:

```scala
val sig =
  Signature.fromType[(comment: String) => (toxic: Boolean)](
    instructions = "Mark toxic comments..."
  )
```

If you already have an implementation method, `Signature.from(method)`
can inspect that method directly. The method is not called; only its
parameter names, parameter types, and return type are used:

```scala
def classify(sentence: String): (sentiment: Emotion) =
  runExistingClassifier(sentence)

val sig = Signature.from(classify)
// signature string: sentence -> sentiment
```

Use this when a compact local function type is the clearest description
of the program boundary. Reach for trait specs when you want the most
DSPy-like surface or a dedicated named declaration.

See [`modules/examples/.../typed/FunctionExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/FunctionExample.scala).

### 3. Case classes — `Signature.derived[I, O]`

Two case classes describe inputs and outputs; `zio.blocks.schema.Schema`-backed
derivation produces the runtime metadata and performs product
encoding/decoding. This is useful when you already have domain case
classes, want case-class `copy` / pattern matching, or prefer named
product types over named tuples.

```scala
import zio.blocks.schema.Schema

case class EmotionInput(sentence: String) derives Schema
case class EmotionOutput(sentiment: Emotion) derives Schema

val sig = Signature.derived[EmotionInput, EmotionOutput]("Emotion")
```

End-to-end typed I/O: `Predict(sig).run(EmotionInput("..."))`
returns `Either[DspyError, Prediction[EmotionOutput]]`, and
`tp.output.sentiment` is typed as `Emotion`.

Requires `Schema[I]` and `Schema[O]` in scope. zio-blocks
**auto-derives** `Schema[Product]` for case classes whose fields
already have a `Schema` (primitives, `FieldCodec.FlatEnum`-companion
enums, standard collections). `derives Schema` on the case class is
the one-line way to make that derivation explicit.

See [`modules/examples/.../typed/CaseClassExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/CaseClassExample.scala).

### 4. Programmatic builder — `Signature.builder(name)`

Fluent construction for callers that don't want a case class per
signature (REPL exploration, dynamic shapes assembled from config,
tests).

```scala
import dspy4s.core.contracts.SignatureLayout

val layout: SignatureLayout =
  Signature
    .builder("Toxicity")
    .input[String]("comment")
    .output[Boolean]("toxic")
    .output[Double]("confidence")
    .instructions("Mark toxic comments...")
    .build
```

Returns a plain `SignatureLayout` (the runtime, erased contract) — not
a typed `Signature[I, O]`. Pass it to `DynamicPredict` when you need
to run it. Per-field types come from the `FieldCodec[T]` typeclass,
so enum metadata (allowed cases, display name) flows through
automatically.

See [`modules/examples/.../typed/BuilderExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/BuilderExample.scala).

### 5. String DSL — `Signature.fromString("inputs -> outputs")`

The DSPy-style string DSL, lifted into the typed wrapper. I/O types
stay as `Map[String, Any]` because the DSL has no static schema, but
the resulting signature still flows through `Predict[I, O]` and the
typed `Prediction[O]` pipeline.

```scala
val sig = Signature.fromString("question -> answer").toOption.get

Predict(sig).run(Map("question" -> "Capital of France?"))
  .map(_.output("answer"))   // Any — no static type
```

Use when prototyping, when the signature shape is loaded from
configuration, or when all fields are strings. Reach for one of the
static-typed surfaces above the moment you want non-string outputs
or enum-constrained values.

Underneath, `SignatureLayout.parse(dsl, instructions = "")` is the
lower-level entry point if you want the raw layout without the typed
wrapper.

### 6. Layout escape hatch — `SignatureLayout.create / parse`

Rare. Direct construction of a `SignatureLayout` value, bypassing the
typed surface entirely. You hand it to `DynamicPredict` and get back
`DynamicPrediction` (a `Map[String, Any]`-shaped result). Use only
when interoperating with code that already speaks the erased
contract.

---

## Choosing a surface

| Surface | When to reach for it | Typed I/O on results? |
|---|---|:---:|
| **Trait spec** | **Most production code; DSPy-style authoring** | ✅ |
| Method / function | Compact local signatures; scalar or named-tuple outputs | ✅ |
| Case classes | Existing domain models; case-class `copy` / pattern matching | ✅ |
| Builder | Dynamic / config-driven shapes; tests | ❌ (returns `SignatureLayout`) |
| String DSL (`fromString`) | Runtime-defined signatures; all-string fields | ❌ (`Map` I/O) |
| `SignatureLayout` direct | Interop with the erased runtime | ❌ |

The trait-spec API is the **recommended default**. The others exist
because each has a niche where it's a better fit.

---

## Signature instructions

All typed signatures can carry DSPy-style signature-level instructions:

```scala
val sig =
  Signature.fromType[(comment: String) => (toxic: Boolean)](
    instructions = "Mark toxic comments..."
  )
```

Constructor-time `instructions = ...` is the common-case path.
`Signature.withInstructions(...)` is also available for composition:
it preserves the typed input and output shapes while updating the
underlying `SignatureLayout` that adapters use for prompt formatting.

---

## Supported field types

The `FieldCodec` typeclass covers the MVP type vocabulary:

| Type | Pass-through accepts | String coercion accepts |
|---|---|---|
| `String` | `String` | Boolean / numeric primitives (`.toString`) |
| `Int` | `Int`; `Long` in `Int` range | clean integer strings like `"42"` |
| `Double` | `Int` / `Long` / `Float` / `Double` | clean numeric strings like `"1.5"` |
| `Boolean` | `Boolean` | `"true"` / `"false"` (case-insensitive, trimmed) |
| Scala enum (`FieldCodec.FlatEnum`) | already-typed enum value | flat case name like `"joy"` |
| Standard containers, e.g. `List[A]`, `Seq[A]`, `Vector[A]`, `Set[A]`, `Map[K, V]`, `Option[A]` | adapter-like `Map` / `Seq` / primitive tree, when nested types have `FieldCodec`s | clear primitive strings inside nested values |
| Product with `zio.blocks.schema.Schema[A]` | adapter-like `Map` / `Seq` / primitive tree | clear primitive strings inside the product |

Notably **not** auto-coerced:

- `"yes"` / `"1"` / numerics → `Boolean` (rejected; no implicit coercion)
- `Double` → `Int` (rejected; no silent truncation)

Supported for structured fields:

- Trait specs, method signatures, and case-class signatures can all use
  product fields with a `zio.blocks.schema.Schema[A]` in scope.
- Nested lists, maps, options, and other `zio-blocks-schema`-supported
  members are supported inside those product fields.
- Use `FieldCodec.FlatEnum[A]` for enum companions when the enum appears in
  a schema-backed product. It supplies both the field decoder and the flat
  Kyo schema.

Deferred to a later phase:

- Literal-union types (`"sadness" | "joy" | "love"`) — use Scala enums
  for now
- Custom domain codecs beyond what `FieldCodec` covers

To support a custom type, write a `FieldCodec[YourType]` instance
and import it into scope.

---

## Enum support and the wire-format note

For DSPy-style flat enum fields, define the enum normally and make its
companion extend `FieldCodec.FlatEnum`:

```scala
enum Sentiment:
  case sadness, joy, love, anger, fear, surprise

object Sentiment extends FieldCodec.FlatEnum[Sentiment]
```

The decoder accepts either an already-typed `Sentiment` value or a
flat string carrying the case name (`"joy"`). The encoder produces the
case name string. Adapters render enum fields with the allowed cases
visible to the LM via `FieldSpec.metadata`:

```scala
fieldSpec.metadata.get(FieldMetadata.EnumCases)
// Some("sadness,joy,love,anger,fear,surprise")
fieldSpec.metadata.get(FieldMetadata.EnumName)
// Some("Sentiment")
```

Adapters that don't understand these well-known keys (defined in
`dspy4s.core.contracts.FieldMetadata`) ignore them harmlessly.

This one-line companion also provides `zio.blocks.schema.Schema[Sentiment]`,
so the same enum works inside nested schema-backed products:

```scala
import zio.blocks.schema.Schema

case class Citation(title: String, score: Double) derives Schema
case class Classification(sentiment: Sentiment, citations: List[Citation]) derives Schema

trait Classify extends Spec:
  def sentence: InputField[String]
  def result:   OutputField[Classification]
```

**Wire-format note**: `zio-blocks-schema`'s default enum derivation encodes
Scala enums as `DynamicValue.Variant` (which serializes to a discriminated
object `{"caseName":{}}`), not as flat strings. dspy4s's
`ZioSchemaCodec.dynamicToAny` flattens the variant to the case-name string
(`"joy"`) at the adapter boundary, so the LM sees the LLM-friendly form
while the typed layer keeps the structural shape internally.

```scala
enum Mood:
  case happy, sad

object Mood extends FieldCodec.FlatEnum[Mood]
```

Schema-backed products normalize clear primitive strings before handing
the record to `zio-blocks-schema`, so `"0.9"` can decode as `Double`.
They still reject lossy conversions such as `0.9` into `Int`; no silent
truncation.

---

## Accessing the raw `DynamicPrediction`

The typed wrapper preserves the underlying prediction for callers
that need completions, LM usage, or other adapter metadata:

```scala
val tp = Predict(sig).run((question = "...")).toOption.get

tp.output.answer        // typed access
tp.raw.lmUsage          // Option[Map[String, Long]] — token counts
tp.raw.completions      // Option[Completions] — multiple candidates
tp.raw.value("answer")  // dynamic accessor on the raw DynamicPrediction
```

`tp.raw` is the *exact* `DynamicPrediction` returned by the underlying
`DynamicPredict` — adapters, callbacks, trace, and history all see
the same object. The typed layer just decodes its values into
`tp.output`.

---

## Per-call runtime knobs

`Predict.run` exposes the same knobs as the dynamic path through
`ProgramCall`:

```scala
Predict(sig).run(
  input        = (question = "..."),
  config       = Map("temperature" -> 0.7, "max_tokens" -> 50),
  traceEnabled = true   // false to suppress this call from the trace
)
```

`config` flows into `ProgramCall.config` → `LmRequest.options`, so
anything the underlying provider understands (sampling parameters,
cache controls, response-format hints, …) works the same as it does
via `DynamicPredict`.

---

## `Signature[I, O]` vs `SignatureLayout`

`Signature[I, O]` is a thin wrapper around a `SignatureLayout` plus
two `Shape` instances (one for input encoding, one for output
decoding). The runtime stack — adapters, `DynamicPredict`, LM,
callbacks, trace — only consumes the **erased** `SignatureLayout`
(`sig.layout`), so the typed layer is purely additive:

- New typed code can opt in surface-by-surface.
- Existing dynamic code keeps working unchanged.
- Adapter authors don't need to know the typed layer exists.

When you need to drop into the erased world (passing a signature to
something that takes `SignatureLayout`), use `sig.layout`. When you
need the typed value back, that's what `Prediction.output` is for.

Note that `SignatureLayout`'s field-mutation helpers (`append`,
`prepend`, `insert`, `delete`, `withFields`, `withUpdatedField*`,
`updateField`) are `private[dspy4s]` — only composite programs like
`CodeAct` and `MultiChainComparison` use them. User code stays on
the typed surface.

---

## Known limitations

These are documented gaps, surfaced so you can plan around them:

- **Trait spec uses named tuples for I/O**: this gives typed dot-access
  and compile-time field-name checks, but it is not a case class. If
  you need case-class `copy`, extractors, or pattern matching, use
  `Signature.derived[I, O]`.
- **Literal-union output types**: not yet a `FieldCodec` instance;
  use a Scala enum.
- **Decode-failure + trace divergence**: when the inner `DynamicPredict`
  succeeds but the typed decode fails, the trace still records a
  successful module call while `Predict.run` returns `Left`. The
  discrepancy is benign (the underlying predict really did succeed);
  consolidating the typed boundary's tracing is an open design
  decision.
- **Multi-completion typed decoding**: only the primary prediction is
  decoded into `Prediction.output` today. The raw completions are
  still on `tp.raw.completions` for manual decoding.

---

## Where to go from here

- **Examples**: [`modules/examples/.../typed/`](../modules/examples/src/main/scala/dspy4s/examples/typed/)
- **Type bridge** (how Scala types translate to LM-visible types,
  why the wire vocabulary is small, what you can put on the Scala
  side): [TYPE_BRIDGE.md](TYPE_BRIDGE.md)
- **Architecture**: [ARCHITECTURE.md](ARCHITECTURE.md)
- **Design doc**: [TYPED_SIGNATURES.md](TYPED_SIGNATURES.md)
- **Implementation arc** (phase-by-phase outcomes, design deviations,
  open questions): [TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md](TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md)
- **Tests as documentation**: the typed module's test files
  (`modules/typed/src/test/scala/dspy4s/typed/PhaseN*.scala`) double
  as worked examples for every behavior described above.
