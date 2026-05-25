# Typed Signatures — User Guide

> A pragmatic guide to dspy4s's typed-signatures layer. For design
> rationale see [TYPED_SIGNATURES.md](TYPED_SIGNATURES.md); for the
> implementation arc see
> [TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md](TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md).

## Quick start

```scala
import dspy4s.programs.TypedPredict
import dspy4s.typed.{TypedSignature, ValueDecoder}

// 1. Describe your I/O as case classes.
case class QAInput(question: String)
case class QAOutput(answer: String, score: Double)

// 2. Build the signature.
val sig = TypedSignature.derived[QAInput, QAOutput](
  name         = "QA",
  instructions = "Answer the question concisely."
)

// 3. Run it. (RuntimeContext is summoned from RuntimeEnvironment as today.)
given dspy4s.core.contracts.RuntimeContext =
  dspy4s.core.runtime.RuntimeEnvironment.current

val result = TypedPredict(sig).run(QAInput("Capital of France?"))
// result : Either[DspyError, TypedPrediction[QAOutput]]

result.map(_.output.answer)   // typed: String
result.map(_.output.score)    // typed: Double
result.map(_.raw.lmUsage)     // raw escape hatch (completions, usage, etc.)
```

That's the whole loop. The rest of this guide explains the four
authoring surfaces, the supported types, and the few sharp edges to
know about.

---

## Authoring surfaces

The typed layer exposes **four ways** to define a signature. All four
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

### 2. Case classes — `TypedSignature.derived[I, O]`

The primary typed surface. Two case classes describe inputs and
outputs; Mirror-based derivation produces the runtime metadata.

```scala
case class EmotionInput(sentence: String)
case class EmotionOutput(sentiment: String)

val sig = TypedSignature.derived[EmotionInput, EmotionOutput]("Emotion")
```

End-to-end typed I/O: `TypedPredict(sig).run(EmotionInput("..."))`
returns `Either[DspyError, TypedPrediction[EmotionOutput]]`, and
`tp.output.sentiment` is a typed `String` with no runtime cast.

See [`modules/examples/.../typed/CaseClassExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/CaseClassExample.scala).

### 3. Programmatic builder — `TypedSignature.builder(name)`

Fluent construction for callers that don't want a case class per
signature (REPL exploration, dynamic shapes assembled from config,
tests).

```scala
val sig: dspy4s.core.contracts.Signature =
  TypedSignature
    .builder("Toxicity")
    .input[String]("comment")
    .output[Boolean]("toxic")
    .output[Double]("confidence")
    .instructions("Mark toxic comments...")
    .build
```

Returns a plain runtime `Signature`. Per-field types come from the
`ValueDecoder[T]` typeclass, so enum metadata (allowed cases, display
name) flows through automatically.

See [`modules/examples/.../typed/BuilderExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/BuilderExample.scala).

### 4. Trait-as-spec macro — `TypedSignature.of[T <: Spec]`

The most "DSPy-like" surface — a trait extending `Spec` declares
abstract methods wrapped in `InputField[T]` / `OutputField[T]`.

```scala
import dspy4s.typed.{InputField, OutputField, Spec, TypedSignature}

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]   // enum from elsewhere in scope

val sig = TypedSignature.of[EmotionSpec]
// sig : TypedSignature[Map[String, Any], Map[String, Any]]
```

Compile-time validation catches: methods not wrapped in the marker
types, methods with parameters, missing `ValueDecoder[X]` for the
inner type, duplicate field names, and empty spec traits.

**Note on I/O typing**: the Phase 5 MVP returns
`TypedSignature[Map[String, Any], Map[String, Any]]`. Output values
**are** decoded through the same `ValueDecoder` instances the
case-class API uses — so `decoded("tone")` is `P5Tone.calm` (the
typed enum value), not `"calm"` (the raw string). What's missing is
**typed dot-access**: you still write `decoded("tone").asInstanceOf[P5Tone]`
instead of `decoded.tone`. Synthesizing case classes from the trait
at compile time (to enable dot-access on the result) is deferred to a
follow-up. Use this surface when the declarative spec matters most;
use the case-class API when typed I/O ergonomics matter more.

See [`modules/examples/.../typed/SpecExample.scala`](../modules/examples/src/main/scala/dspy4s/examples/typed/SpecExample.scala).

---

## Choosing a surface

| Surface | When to reach for it | Typed I/O on results? |
|---|---|:---:|
| String DSL | Prototyping, all-string fields | ❌ |
| **Case classes** | **Most production code** | ✅ |
| Builder | Dynamic / config-driven shapes; tests | ❌ (runtime `Signature`) |
| Trait spec | Declarative authoring; Python-DSPy parity | partial (Map I/O) |

The case-class API is the **recommended default**. The others exist
because each has a niche where it's a better fit.

---

## Supported field types

The `ValueDecoder` typeclass covers the MVP type vocabulary:

| Type | Pass-through accepts | String coercion accepts |
|---|---|---|
| `String` | `String` | Boolean / numeric primitives (`.toString`) |
| `Int` | `Int`; `Long` in `Int` range | clean integer strings like `"42"` |
| `Double` | `Int` / `Long` / `Float` / `Double` | clean numeric strings like `"1.5"` |
| `Boolean` | `Boolean` | `"true"` / `"false"` (case-insensitive, trimmed) |
| Scala enum (`derives ValueDecoder`) | already-typed enum value | flat case name like `"joy"` |

Notably **not** auto-coerced:

- `"yes"` / `"1"` / numerics → `Boolean` (rejected; no implicit coercion)
- `Double` → `Int` (rejected; no silent truncation)

Deferred to a later phase:

- Literal-union types (`"sadness" | "joy" | "love"`) — use Scala enums
  for now
- Arbitrary JSON values, collections of records, nested records
- Custom domain codecs beyond what `ValueDecoder` covers

To support a custom type, write a `ValueDecoder[YourType]` instance
and import it into scope.

---

## Enum support and the wire-format note

Scala enums get a `ValueDecoder` via `derives`:

```scala
enum Sentiment derives ValueDecoder:
  case sadness, joy, love, anger, fear, surprise
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

**Wire-format finding from Phase 0** (only relevant if you ever route
enum outputs through `kyo-schema`'s JSON layer): `kyo-schema` encodes
Scala enums as `{"caseName":{}}` (discriminated object), not as flat
strings. The dspy4s typed layer doesn't go through kyo-schema's JSON
encoding — it accepts flat strings directly — so this isn't a problem
in normal use.

---

## Accessing the raw `Prediction`

The typed wrapper preserves the underlying prediction for callers
that need completions, LM usage, or other adapter metadata:

```scala
val tp = TypedPredict(sig).run(QAInput("...")).toOption.get

tp.output.answer        // typed access (case-class API)
tp.raw.lmUsage          // Option[Map[String, Long]] — token counts
tp.raw.completions      // Option[Completions] — multiple candidates
tp.raw.value("answer")  // dynamic accessor on the raw Prediction
```

`tp.raw` is the *exact* `Prediction` returned by the underlying
`Predict` — adapters, callbacks, trace, and history all see the same
object. The typed layer just decodes its values into `tp.output`.

---

## Per-call runtime knobs

`TypedPredict.run` exposes the same knobs as the untyped path through
`ProgramCall`:

```scala
TypedPredict(sig).run(
  input        = QAInput("..."),
  config       = Map("temperature" -> 0.7, "max_tokens" -> 50),
  traceEnabled = true   // false to suppress this call from the trace
)
```

`config` flows into `ProgramCall.config` → `LmRequest.options`, so
anything the underlying provider understands (sampling parameters,
cache controls, response-format hints, …) works the same as it does
via raw `Predict`.

---

## `TypedSignature` vs `Signature`

`TypedSignature[I, O]` is a thin wrapper around a runtime `Signature`
plus two `Shape` instances (one for input encoding, one for output
decoding). The runtime stack — adapters, `Predict`, LM, callbacks,
trace — only consumes the **untyped** `Signature` (`sig.untyped`), so
the typed layer is purely additive:

- New typed code can opt in surface-by-surface.
- Existing untyped code keeps working unchanged.
- Adapter authors don't need to know the typed layer exists.

When you need to drop into the untyped world (passing a signature to
something that takes `Signature`), use `sig.untyped`. When you need
the typed value back, that's what `TypedPrediction.output` is for.

---

## Known limitations

These are documented gaps, surfaced so you can plan around them:

- **Trait spec → typed dot-access on outputs**: deferred (the spec
  surface returns `TypedSignature[Map, Map]`).
- **Literal-union output types**: not yet a `ValueDecoder` instance;
  use a Scala enum.
- **Decode-failure + trace divergence**: when the inner `Predict`
  succeeds but the typed decode fails, the trace still records a
  successful module call while `TypedPredict.run` returns `Left`.
  The discrepancy is benign (the underlying predict really did
  succeed); consolidating the typed boundary's tracing is a Phase 5+
  design decision.
- **Multi-completion typed decoding**: only the primary prediction
  is decoded into `TypedPrediction.output` today. The raw completions
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
