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

That's the whole loop. The rest of this guide explains the five
authoring surfaces, the supported types, and the few sharp edges to
know about.

---

## Authoring surfaces

The typed layer exposes **five ways** to define a signature. All five
compile down to the same runtime `Signature` (and pass through the same
adapter / LM / runtime path), so you can mix them freely in one
project — pick whichever fits each call site.

### 1. String DSL — `Signature("inputs -> outputs")`

Pre-existing, untyped. Fastest to type out. No compile-time type
checking on field values.

```scala
import dspy4s.core.contracts.Signature
val sig = Signature("question -> answer").toOption.get
```

Use when prototyping or when input/output types are all strings.
Reach for one of the typed surfaces below the moment you want
non-string outputs or enum-constrained values.

### 2. Trait-as-spec macro — `Signature.of[T <: Spec]`

The recommended typed surface and closest match for Python DSPy:
a trait extending `Spec` declares abstract methods wrapped in
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

End-to-end typed I/O uses Scala named tuples: `Predict(sig).run((sentence = "..."))`
accepts a named-tuple input, and `tp.output.sentiment` is typed as
`Emotion`. Compile-time validation catches methods not wrapped in the
marker types, methods with parameters, missing `FieldCodec[X]`,
duplicate field names, concrete methods, and empty spec traits.

See [`modules/examples/.../typed/SpecExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/SpecExample.scala).

### 3. Function signatures — `Signature.fromType[F]`

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

### 4. Programmatic builder — `Signature.builder(name)`

Fluent construction for callers that don't want a case class per
signature (REPL exploration, dynamic shapes assembled from config,
tests).

```scala
val sig: dspy4s.core.contracts.Signature =
  Signature
    .builder("Toxicity")
    .input[String]("comment")
    .output[Boolean]("toxic")
    .output[Double]("confidence")
    .instructions("Mark toxic comments...")
    .build
```

Returns a plain runtime `Signature`. Per-field types come from the
`FieldCodec[T]` typeclass, so enum metadata (allowed cases, display
name) flows through automatically.

See [`modules/examples/.../typed/BuilderExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/BuilderExample.scala).

### 5. Case classes — `Signature.derived[I, O]`

Two case classes describe inputs and outputs; `kyo.Schema`-backed
derivation produces the runtime metadata and performs product
encoding/decoding. This is useful when you already have domain case
classes, want case-class `copy` / pattern matching, or prefer named
product types over named tuples.

```scala
case class EmotionInput(sentence: String)
case class EmotionOutput(sentiment: Emotion)

val sig = Signature.derived[EmotionInput, EmotionOutput]("Emotion")
```

End-to-end typed I/O: `Predict(sig).run(EmotionInput("..."))`
returns `Either[DspyError, Prediction[EmotionOutput]]`, and
`tp.output.sentiment` is typed as `Emotion`.

Requires `kyo.Schema[I]` and `kyo.Schema[O]` in scope. kyo-schema
**auto-derives** `Schema[Product]` as long as every field's type
already has a `Schema` (primitives and `FieldCodec.FlatEnum`-companion
enums do). If your case class contains a custom type without a
`Schema`, add `derives kyo.Schema` on the case class itself (or on
the custom type) to make derivation explicit.

See [`modules/examples/.../typed/CaseClassExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/CaseClassExample.scala).

---

## Choosing a surface

| Surface | When to reach for it | Typed I/O on results? |
|---|---|:---:|
| String DSL | Prototyping, all-string fields | ❌ |
| **Trait spec** | **Most production code; DSPy-style authoring** | ✅ |
| Method/function | Compact local signatures; scalar or named-tuple outputs | ✅ |
| Case classes | Existing domain models; case-class `copy` / pattern matching | ✅ |
| Builder | Dynamic / config-driven shapes; tests | ❌ (runtime `Signature`) |

The trait-spec API is the **recommended default**. The others exist
because each has a niche where it's a better fit.

---

## Signature Instructions

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
underlying runtime `Signature` that adapters use for prompt formatting.

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
| Product with `kyo.Schema[A]` | adapter-like `Map` / `Seq` / primitive tree | clear primitive strings inside the product |

Notably **not** auto-coerced:

- `"yes"` / `"1"` / numerics → `Boolean` (rejected; no implicit coercion)
- `Double` → `Int` (rejected; no silent truncation)

Supported for structured fields:

- Trait specs, method signatures, and case-class signatures can all use
  product fields with a `kyo.Schema[A]` in scope.
- Nested lists, maps, options, and other `kyo-schema`-supported members are
  supported inside those product fields.
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

This one-line companion also provides `kyo.Schema[Sentiment]`, so the
same enum works inside nested schema-backed products:

```scala
case class Citation(title: String, score: Double) derives kyo.Schema
case class Classification(sentiment: Sentiment, citations: List[Citation]) derives kyo.Schema

trait Classify extends Spec:
  def sentence: InputField[String]
  def result:   OutputField[Classification]
```

**Wire-format finding from Phase 0**: `kyo-schema`'s default enum
derivation encodes Scala enums as `{"caseName":{}}` (discriminated
object), not as flat strings. `FieldCodec.FlatEnum` overrides that
with the LLM-friendly flat string form.

```scala
enum Mood:
  case happy, sad

object Mood extends FieldCodec.FlatEnum[Mood]
```

Schema-backed products normalize clear primitive strings before handing
the record to `kyo-schema`, so `"0.9"` can decode as `Double`. They still
reject lossy conversions such as `0.9` into `Int`; no silent truncation.

---

## Accessing the raw `Prediction`

The typed wrapper preserves the underlying prediction for callers
that need completions, LM usage, or other adapter metadata:

```scala
val tp = Predict(sig).run((question = "...")).toOption.get

tp.output.answer        // typed access
tp.raw.lmUsage          // Option[Map[String, Long]] — token counts
tp.raw.completions      // Option[Completions] — multiple candidates
tp.raw.value("answer")  // dynamic accessor on the raw Prediction
```

`tp.raw` is the *exact* `Prediction` returned by the underlying
`Predict` — adapters, callbacks, trace, and history all see the same
object. The typed layer just decodes its values into `tp.output`.

---

## Per-call runtime knobs

`Predict.run` exposes the same knobs as the untyped path through
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
via raw `Predict`.

---

## `Signature` vs `Signature`

`Signature[I, O]` is a thin wrapper around a runtime `Signature`
plus two `Shape` instances (one for input encoding, one for output
decoding). The runtime stack — adapters, `Predict`, LM, callbacks,
trace — only consumes the **untyped** `Signature` (`sig.untyped`), so
the typed layer is purely additive:

- New typed code can opt in surface-by-surface.
- Existing untyped code keeps working unchanged.
- Adapter authors don't need to know the typed layer exists.

When you need to drop into the untyped world (passing a signature to
something that takes `Signature`), use `sig.untyped`. When you need
the typed value back, that's what `Prediction.output` is for.

---

## Known limitations

These are documented gaps, surfaced so you can plan around them:

- **Trait spec uses named tuples for I/O**: this gives typed dot-access
  and compile-time field-name checks, but it is not a case class. If
  you need case-class `copy`, extractors, or pattern matching, use
  `Signature.derived[I, O]`.
- **Literal-union output types**: not yet a `FieldCodec` instance;
  use a Scala enum.
- **Decode-failure + trace divergence**: when the inner `Predict`
  succeeds but the typed decode fails, the trace still records a
  successful module call while `Predict.run` returns `Left`.
  The discrepancy is benign (the underlying predict really did
  succeed); consolidating the typed boundary's tracing is an open
  design decision.
- **Multi-completion typed decoding**: only the primary prediction
  is decoded into `Prediction.output` today. The raw completions
  are still on `tp.raw.completions` for manual decoding.

---

## Where to go from here

- **Examples**: [`modules/examples/.../typed/`](../modules/examples/src/main/scala/dspy4s/examples/typed/)
- **Design doc**: [TYPED_SIGNATURES.md](TYPED_SIGNATURES.md)
- **Implementation arc** (phase-by-phase outcomes, design deviations,
  open questions): [TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md](TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md)
- **Tests as documentation**: the typed module's test files
  (`modules/typed/src/test/scala/dspy4s/typed/PhaseN*.scala`) double
  as worked examples for every behavior described above.
