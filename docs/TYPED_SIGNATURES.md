# Typed Signatures — Design Document

> **Status:** Draft for review · 2026-05-24
> **Scope:** dspy4s core + programs modules
> **Targets:** Scala 3.8.1, no new runtime dependencies

## TL;DR

`Prediction.value(key: String): Either[DspyError, Any]` returns `Any`,
forcing every typed access to a runtime cast. We propose a **layered
architecture** that gives users compile-time typed predictions while
keeping the existing runtime-flexible API intact:

- **Engine layer** — one canonical typed carrier (`Signature[I, O]` +
  `TypedPredict` + `TypedPrediction`) that lifts field/type info into
  the type system.
- **Surface layers** — multiple ergonomic translators that all compile
  down to the engine: typed builder, named tuples, case-class
  derivation, and (later) an inline macro over the string DSL.

**Foundation choice (§5):** rather than build the engine from scratch,
adopt `zio-blocks-schema` (Apache-2.0, NamedTuple-aware `Schema.derived`
macro, pure `Either`-returning codecs, no ZIO effect leakage). The
0.0.x churn risk is accepted in exchange for not reimplementing
primitives. A pure-Scala self-built fallback is documented in §5.3 in
case adoption hits unexpected friction.

---

## 1. Problem

### 1.1 Current shape

`Prediction.value` is the canonical accessor for output fields:

```scala
trait Prediction extends Record:
  def value(key: String): Either[DspyError, Any]
  def asDouble(key: String): Either[DspyError, Double]
  // ...
```

The `Any` return is forced by the fact that signatures are runtime
values built from strings. A typical call site:

```scala
val toxicitySignature: Signature =
  Signature(
    "comment -> toxic: bool",
    instructions = "Mark as 'toxic' if the comment includes insults, ..."
  ).toOption.get

val toxicity = Predict(toxicitySignature)

toxicity.run("comment" -> "you are beautiful.").flatMap(_.value("toxic"))
// : Either[DspyError, Any]   ← cast required to use as Boolean
```

### 1.2 What's wrong

1. **Silent miscasts.** A field declared `bool` in the DSL but read as
   `Double` compiles fine and fails at runtime.
2. **Field-name typos compile.** `_.value("toxix")` is indistinguishable
   from `_.value("toxic")` until the runtime `NotFoundError` fires.
3. **No IDE assistance.** Auto-completion can't suggest valid field
   names; refactor tools can't rename a field uniformly.
4. **Reads as plumbing.** Compare to Python's `toxicity(comment=c).toxic`
   — one expression, two tokens of access. The Scala version requires
   `.flatMap(_.value("toxic"))` and still hands you `Any`.

### 1.3 What we want

A path where, for class-based signature declarations:

```scala
val pred = classify.run(EmotionIn("i started feeling ..."))
//         : Either[DspyError, TypedPrediction[EmotionOut]]
pred.map(_.sentiment)
//         : Either[DspyError, Emotion]
// pred.map(_.typo)   ← compile error
```

…without losing the existing dynamic-signature path for cases that
genuinely need runtime DSL construction.

---

## 2. Analysis

### 2.1 Scala 3 building blocks (and which problem each addresses)

| Feature | Capability | Where it fits here |
|---|---|---|
| Literal types + `Singleton` bound | Compiler preserves `"toxic"` as type `"toxic"` through generic code | Typing on field-name literals at call sites |
| Match types | `type FieldType[K <: String] = K match { case "toxic" => Boolean; ... }` | Type-level lookup table from field name to type |
| `transparent inline def` + `inline match` | A method's declared return type can specialize per call | Wrapping match types in a callable accessor |
| `Selectable` + refined types | `pred.toxic` dispatched via `selectDynamic`, statically typed via a refinement | Dot-access instead of `.value("toxic")` |
| Named tuples (3.7+) | `(toxic: Boolean, sentiment: Boolean)` as a first-class typed record | Final user-facing prediction shape |
| Macros (`quotes`/`splices`) | Parse a literal DSL string at compile time, emit a typed signature type | Bridging the existing string DSL into typed form |
| `Mirror` + derivation | Walk a case class's `MirroredElemLabels`/`MirroredElemTypes` at compile time | Case-class-driven signature derivation |

### 2.2 The fundamental constraint

A signature, in dspy4s today, is a **runtime value**. To get
compile-time typed accessors, *something* needs to bridge the runtime
field/type info into the type system. That bridge can happen in three
ways:

- **(a) Explicit user code** — a typed builder where each call enriches
  the type.
- **(b) Literal DSL inspected at compile time** — a macro that fires
  only when the DSL string is a constant.
- **(c) Don't bridge; stay dynamic** — add typed accessors keyed by
  primitive type rather than by signature field.

(a) and (b) deliver field-name + type checking. (c) only addresses the
silent-cast risk. The proposal below pursues (a) at the engine level,
opens the door to (b) as a surface, and treats (c) as a complementary
quick win that can ship in parallel.

### 2.3 What dspy4s constrains

- All seven modules consume `Signature` (the trait) through its runtime
  contract (`fields: Vector[FieldSpec]`, `inputFields`, `outputFields`,
  `withInstructions`, etc.). Adapters in particular read field
  metadata at runtime to format/parse messages. **The typed layer must
  produce something that still satisfies the trait at runtime.**
- `Predict` and `ChainOfThought` are case classes that take a
  `Signature` argument. A typed companion must coexist without
  breaking existing call sites.
- The existing string DSL (`Signature("comment -> toxic: bool")`) is
  the primary surface today and should keep working unchanged.

---

## 3. Solutions Considered

The class-based signature case (DSPy "class-based signatures" in
`docs/docs/learn/programming/signatures.md`, Snippet 5) is where
typing is easiest, because field names and types are already in source
code with proper Scala types. We surveyed four shapes:

### 3.1 Named tuple types

```scala
type EmotionIn  = (sentence: String)
type EmotionOut = (sentiment: Emotion)

val emotionSig = Signature.of[EmotionIn, EmotionOut](
  instructions = "Classify emotion."
)
```

- **Pros.** Zero ceremony. Both input and output are plain values you
  can construct with `(sentence = "…")`. Named tuples preserve field
  names at the type level, so dot-access on the prediction is
  automatically typed.
- **Cons.** Named tuples are anonymous at the type level — error
  messages show the structural form, not a friendly name. The `type`
  alias mitigates this when the elaborator can keep it visible.

### 3.2 Two case classes (Input/Output)

```scala
case class EmotionIn(sentence: String) derives Signature.Input
case class EmotionOut(sentiment: Emotion) derives Signature.Output

val emotionSig = Signature.of[EmotionIn, EmotionOut](
  instructions = "Classify emotion."
)
```

- **Pros.** Nominal types — best error messages. Case classes give
  `copy`, equality, pattern matching, easy ser/de. The Input/Output
  typeclass distinction is enforced by the compiler.
- **Cons.** Two declarations per signature. The `derives` clause is
  invisible after the first read but is still extra surface area.

### 3.3 Single case class with `In[T]`/`Out[T]` markers

```scala
opaque type In[+T]  = T
opaque type Out[+T] = T

case class Emotion(
  sentence:  In[String],
  sentiment: Out[Emotion]
) derives Signature
```

- **Pros.** One declaration, mirrors Python's `class Emotion(dspy.Signature)`.
- **Cons.** **Construction semantics are broken.** When you instantiate
  the case class to *make a request*, you don't yet know the outputs —
  you'd have to pass placeholders. Same problem inverted on the
  response side. Python sidesteps this by treating the class only as
  a spec, never as data. Adapting it to Scala would require splitting
  into two views anyway. **Not recommended.**

### 3.4 Typed builder DSL

```scala
val emotionSig = Signature.builder
  .input[String]("sentence")
  .output[Emotion]("sentiment")
  .instructions("Classify emotion.")
  .build
```

- **Pros.** No metaprogramming. Most "see exactly what's happening" of
  the options. Useful as a foundation that the others reduce to.
- **Cons.** Most ceremony at the call site. Loses the elegance of
  "just declare your data shape." Field names are stringly-typed at the
  call site, even though they're checked against the carrier.

### 3.5 Comparison

| | Lines per signature | Mirror/macro work | Input/output construct asymmetry | Error message quality |
|---|---:|:---:|:---:|:---:|
| 1. Named tuples | 3 | small (derive via `NamedTuple.From`) | clean | structural (acceptable) |
| 2. Case classes | 4–6 | small (`Mirror`-based derive) | clean | nominal (best) |
| 3. Single case class + In/Out | 4 | medium (derive + role partition) | **bad** (placeholder outputs at construction) | nominal (good) |
| 4. Typed builder | 5–7 | none | clean | carrier-tuple-typed (verbose) |

---

## 4. Proposed Direction — Layered Architecture

Rather than picking one surface, we propose a **two-layer architecture**
where the engine is canonical and surfaces are ergonomic translators
that all compile to it.

### 4.1 Why a layered architecture

This pattern is well-validated in the Scala ecosystem (Cats `IO`
primitives + sugar, ZIO core + constructors, Doobie `Fragment` +
`sql"…"`, Tapir endpoint primitives + DSL, Iron predicate core +
macro-friendly aliases). For dspy4s specifically:

1. **Single runtime representation, multiple ergonomic front-doors.**
   The engine is the *only* thing the rest of the library (adapters,
   programs, optimizers) ever has to consume. New front-doors can be
   added without touching adapters or `Predict`. Front-doors can be
   retired without breaking downstream code. Bugs partition cleanly:
   surface (translation) vs. engine (semantics).

2. **The hard type-level work happens once.** Match-type lookups,
   `.value[K]` specialization, `Selectable` refinements, prediction
   shape inference — all of that lives in the engine. Surfaces just
   need to produce a well-formed `TypedSignature[I, O]`. Each translator
   is small and targeted.

3. **It gives us a testing strategy.** The engine gets exhaustive
   property tests on the carrier. Each surface gets a small parity
   test: "the field spec produced by surface X for input Y equals the
   one produced by the builder directly." That's much more tractable
   than end-to-end testing each surface.

### 4.2 Engine sketch

```scala
package dspy4s.typed

import dspy4s.core.contracts.{Signature, SignatureSpec}

/** Opaque carrier — at runtime it IS a SignatureSpec, at compile time it
  * carries phantom-typed tuples of (fieldName, fieldType) pairs per role. */
opaque type TypedSignature[I <: Tuple, O <: Tuple] = SignatureSpec

object TypedSignature:
  def empty: TypedSignature[EmptyTuple, EmptyTuple] = ???

  extension [I <: Tuple, O <: Tuple](sig: TypedSignature[I, O])
    def input[T](name: String & Singleton): TypedSignature[(name.type, T) *: I, O] = ???
    def output[T](name: String & Singleton): TypedSignature[I, (name.type, T) *: O] = ???
    def instructions(text: String): TypedSignature[I, O] = ???

    /** Erase back to the untyped trait for adapters / runtime consumers. */
    def asUntyped: Signature = sig
```

`Signature.builder` is the natural surface to this:

```scala
val sig = Signature.builder
  .input[String]("sentence")
  .output[Emotion]("sentiment")
  .instructions("Classify emotion.")
  .build
// inferred: TypedSignature[("sentence", String) *: EmptyTuple, ("sentiment", Emotion) *: EmptyTuple]
```

A typed companion to `Predict`:

```scala
final class TypedPredict[I <: Tuple, O <: Tuple](sig: TypedSignature[I, O]):
  def run(inputs: NamedTuple[Names[I], Types[I]])(using RuntimeContext)
      : Either[DspyError, TypedPrediction[O]] = ???
  // alternative varargs form for parity with PredictProgram.run:
  def run(inputs: (String, Any)*)(using RuntimeContext)
      : Either[DspyError, TypedPrediction[O]] = ???
```

`TypedPrediction[O]` exposes `.value[K]` via a match type over `O`,
plus optional `Selectable` for dot-access:

```scala
import scala.compiletime.error

final class TypedPrediction[O <: Tuple] extends Selectable:
  // `Fields` is special-cased by the compiler on Selectable + NamedTuple,
  // enabling typed dot-access like `pred.toxic` that expands to
  // `selectDynamic("toxic").asInstanceOf[T]` per the named-tuples spec.
  type Fields = NamedTupleOf[O]
  def selectDynamic(name: String): Any = ???

  // typed accessor for explicit lookups
  def value[K <: String & Singleton]: FieldOf[O, K] = ???

/** Recursive lookup of `K` in a `Tuple` of (name, type) pairs.
  * The "not found" terminal must be an explicit `compiletime.error`
  * call routed through a guard alias — a bare match type with no
  * matching case becomes "stuck" rather than failing compilation. */
type FieldOf[O <: Tuple, K <: String] = O match
  case (K, t) *: _    => t
  case _    *: rest   => FieldOf[rest, K]
  case EmptyTuple     => NoSuchField[K]

type NoSuchField[K <: String] = error.type   // expand via inline guard
```

The `NoSuchField` shape above is illustrative — the actual error-surfacing
needs a `transparent inline def value[...]` whose body invokes
`compiletime.error("Field '...' not declared in signature outputs")`
when the recursive `FieldOf` reduction reaches the empty-tuple terminal.
See §4.6 below for why this matters.

### 4.3 Surfaces as small translators

| Surface | Translator |
|---|---|
| **Builder** (engine itself) | identity |
| **Named tuples** (`Signature.of[I, O]`) | inline def using `NamedTuple.Names[I]` + `NamedTuple.From[I]` to walk I/O at compile time, emit a sequence of `.input`/`.output` calls |
| **Case classes** (`derives Signature.Input/Output`) | Mirror-based: walk `m.MirroredElemLabels` + `m.MirroredElemTypes`, produce the carrier |
| **String DSL** (`Signature("…")`) | macro: parse the literal at compile time, emit the carrier; for non-literals, fall back to the untyped `SignatureSpec` |

Each translator is approximately 50–100 lines, isolated, and exercises
only the public engine API.

### 4.4 Carrier shape — decision

Three workable options:

1. **`Tuple` of `(name, type)` pairs** — easy to build, easy to fold
   over with match types, but verbose in error messages.
2. **`NamedTuple[Names, Types]`** — first-class named tuples; cleaner
   errors, but harder to *build incrementally* in Scala 3.8 (the
   operations work on materialized named tuples, not on the construction
   process itself).
3. **Pair `(NamedTuple[InNames, InTypes], NamedTuple[OutNames, OutTypes])`** —
   uses named tuples but keeps role separation cleaner.

**Recommendation:** Use **(1) internally for the builder** (it's the
easy thing to manipulate during accumulation), with **a conversion to
named tuples at the boundary** when handing values to user code (so
`pred.toxic` looks like accessing a named tuple field). Best of both:
builder ergonomics + named-tuple ergonomics at the use site.

This is forced by the 3.8.1 stdlib: in
`library/src/scala/NamedTuple.scala` (lines 47–48), the incremental
construction operators `*:` and `:*` are **commented out** — explicitly
deferred. Named tuples must currently be materialized whole via
`NamedTuple.build`. Building a carrier incrementally on plain `Tuple`
of pairs and converting at the boundary is the supported pattern.

### 4.5 Verified against Scala 3.8.1 stdlib

Each ingredient of the proposal was validated against the 3.8.1
release in `~/GitHub/dotty` (HEAD `88438e2c6e Release Scala 3.8.1`):

| Feature | Status in 3.8.1 | Source citation |
|---|---|---|
| Named tuples | Stable since 3.7; full type-level API (`Names`, `From`, `Map`, `Concat`, `Zip`, `Reverse`, `Take`, `Drop`, etc.) | `library/src/scala/NamedTuple.scala`, line 14: `opaque type NamedTuple[N <: Tuple, +V <: Tuple] >: V <: AnyNamedTuple = V` |
| Named-tuple incremental build | **Not supported**; `*:` / `:*` ops commented out | `library/src/scala/NamedTuple.scala`, lines 47–48 |
| `Selectable.Fields` typed dot-access | Compiler-special; expands `pred.toxic` to `selectDynamic("toxic").asInstanceOf[T]` | `docs/_docs/reference/other-new-features/named-tuples.md`, lines 144–167 |
| `Mirror.Of[T]`, `MirroredElemLabels`, `MirroredElemTypes` | Stable; case classes get a `Mirror.Product` for free | `library/src/scala/deriving/Mirror.scala`, lines 7–17, 51 |
| `derives` keyword | Stable; generates `given (...) => TC[T] = TC.derived` in companion | `docs/_docs/reference/contextual/derivation.md`, lines 1–50 |
| Opaque types with `Tuple`-parameterized phantoms | Stable; NamedTuple itself uses this shape | `library/src/scala/NamedTuple.scala`, line 14 |
| Recursive match types (e.g. `FieldOf[O, K]`) | Reduce reliably when scrutinees are concrete; **stuck (not error) when no case matches** | `docs/_docs/reference/new-types/match-types.md`, lines 120–145, 201–224 |
| `transparent inline def`, `constValue`, `summonInline`, `summonFrom`, `inline match` | All stable | `library/src/scala/compiletime/package.scala`, lines 119–171 |

**Two 3.8-specific items worth knowing:**

- **`-Xmax-inlines = 32`** (default, per
  `compiler/src/dotty/tools/dotc/config/ScalaSettings.scala:320`).
  A signature with more than ~32 fields built through recursive inline
  expansion would hit the limit. Realistic dspy4s signatures are far
  smaller, but it's worth noting as a configurable ceiling.
- **"Prevent opaque types leaking from transparent inline methods"**
  (3.8.0 changelog) — `transparent inline` no longer accidentally
  exposes the underlying type of an opaque alias. This is a fix that
  *helps* us: our `opaque type TypedSignature[I, O] = SignatureSpec`
  stays opaque even when consumed through inline surface translators.
- **"Make opaque types decomposable"** in pattern matching (3.8.0) —
  not central to the proposal but useful elsewhere.

### 4.6 Match-type "no match → stuck, not error" — design impact

The reference doc is explicit (`docs/_docs/reference/new-types/match-types.md`,
lines 201–224): a recursive match type with no matching case becomes
**stuck** (unevaluated), not a compile error. So our `FieldOf[O, K]`
sketch will not, on its own, give a clean compile-time error for
`pred.value["typo"]` — it will produce an unevaluated match type
that surfaces as confusing downstream type errors.

The mitigation is to wrap the accessor in a `transparent inline def`
that uses `summonFrom` + `compiletime.error` to detect the stuck case
and emit a targeted message:

```scala
transparent inline def value[K <: String & Singleton]: FieldOf[O, K] =
  inline scala.compiletime.summonFrom[FieldOf[O, K]] {
    case t: FieldOf[O, K] => /* extract */
    case _                => scala.compiletime.error(
      "Field '" + scala.compiletime.constValue[K] +
      "' is not declared in the signature outputs"
    )
  }
```

(Exact pattern depends on what the engine returns; the structure above
is the shape, not the literal implementation.) This is a small but
non-trivial addition that the engine must include for the typing
guarantee to be usable in practice.

### 4.7 The engine sketch above is the *fallback* design

Section 4.2's `TypedSignature[I, O]` + `FieldOf` + `TypedPrediction`
sketch is preserved as a self-contained design we can fall back on if
the foundation choice in §5 doesn't pan out. In the recommended path
(§5.2), most of §4.2 is replaced by `zio-blocks-schema` primitives —
the surface layout from §4.3 remains the same either way.

### 4.8 The existing untyped surface stays

`Signature.apply(dsl: String, instructions: String = "")` and
`Predict(sig: Signature)` remain available unchanged. The typed layer
is purely additive. Callers who don't need typing pay nothing.

When the inline macro surface (Phase 4) ships, it can opt to *replace*
the existing `Signature.apply` if the user-facing improvement is worth
the macro footprint — but that decision can be deferred.

---

## 5. Foundation choice — zio-blocks-schema or self-built

### 5.1 Background

After §4 was drafted, we evaluated [zio-blocks](https://github.com/zio/zio-blocks)
(currently `0.0.40`, Apache-2.0) as a possible foundation. The `schema`
module specifically overlaps significantly with what §4 proposes to
build:

- **NamedTuple is already a first-class derivation target** via
  `Schema.derived` (their macro handles `type SentimentIn = (sentence:
  String)` and case classes uniformly).
- **`Reflect.Record`** provides a runtime mirror with `Term`-level
  field metadata.
- **`Codec[Format, A]`** uses pure `Either[SchemaError, A]` decoding —
  matches our `Either[DspyError, T]` discipline with no ZIO effect
  leakage.
- **`schema-toon` is an LLM-optimized text format** that already ships
  — directly relevant to our ChatAdapter / JsonAdapter work.
- **`ToStructural` macro** synthesizes refinement types from records
  → Selectable-style dot-access without us writing it.
- No transitive ZIO dependencies in `schema`/`chunk`/`maybe`/`scope`/
  `context`. (`endpoint` and `mux` pull `zio.http` — avoid those.)

The choice is whether to build the typed engine ourselves or adopt
`zio-blocks-schema` as the underlying type-and-codec substrate.

### 5.2 Option A — Adopt zio-blocks-schema *(recommended)*

**Shape.** Replace `TypedSignature[I, O]` (§4.2) with a thin wrapper
around two `Schema`s:

```scala
import zio.blocks.schema.Schema

final case class Signature[I, O](
  inputSchema:  Schema[I],
  outputSchema: Schema[O],
  instructions: Option[String] = None
)

object Signature:
  // Surface 1 — named-tuple types (free: Schema.derived handles them)
  def of[I, O](instructions: String = "")(
    using inSch: Schema[I], outSch: Schema[O]
  ): Signature[I, O] =
    Signature(inSch, outSch, Option(instructions).filter(_.nonEmpty))

  // Surface 2 — case classes: just `derives Schema` upstream
  // Surface 3 — builder DSL: delegates to Reflect.Record construction
  // Surface 4 — string DSL: macro emits Schema[NamedTuple[Names, Types]]
```

Each surface from §4.3 still applies, but the carrier becomes
`Schema[I]` × `Schema[O]` instead of `(Tuple, Tuple)`. The match-type
work over the carrier (§4.2's `FieldOf`) becomes mostly unnecessary —
`NamedTuple` already provides typed dot-access via Selectable.

**What we gain.**
- Phases 1–3 of the rollout (§7) shrink dramatically. Case-class and
  named-tuple surfaces are essentially free; we don't write any
  Mirror-based derivation.
- A pre-built `Codec` ecosystem (JSON, YAML, MessagePack, BSON, XML,
  CSV, Thrift, **TOON**) for free. TOON especially is worth studying
  for adapter prompt-format design.
- `ToStructural` macro gives us Selectable-style dot-access without
  writing it.
- Some binary-compat care inherited (`mimaChecks` configured for
  `schemaJVM`).

**What we take on.**
- Dependency on a 0.0.x library with weekly releases — breaking
  changes expected; pinning and tracking required.
- A new vocabulary (`Reflect`, `Term`, `Format`, `TypeId`, `Binding`)
  visible at the engine layer.
- Friction at the boundary: `Schema.decode` returns `Either[SchemaError,
  A]`; we map `SchemaError → DspyError` at adapter boundaries.

**Strategic note.** Project owner preference is to **lean into
zio-blocks where it gives us pieces we need**. The 0.0.x churn risk
is accepted in exchange for not reimplementing primitives. Mitigation
is to keep `Signature` as a dspy4s-owned wrapper so churn is contained
at one layer — adapters / programs see `Signature[I, O]`, never raw
`Schema[A]`.

### 5.3 Option B — Self-built engine *(fallback)*

The §4.2 sketch in full: `opaque type TypedSignature[I <: Tuple, O <:
Tuple] = SignatureSpec`, builder extensions, `FieldOf` match type,
`TypedPredict`, `TypedPrediction`. Pure Scala 3.8.1 features, no
external dependencies beyond what we already use.

**What we gain.**
- Zero external surface to track. Evolves at our cadence.
- Vocabulary stays inside dspy4s — small, focused, documented in one
  place.
- Macro-derivation work is custom but small (each surface ~50–100
  lines).

**What we take on.**
- Writing macros for case-class and string-DSL derivation ourselves.
- Building codec / adapter infrastructure from scratch (no `Format`
  registry to lean on).
- Reinventing pieces zio-blocks-schema has already shipped, for
  effectively the same use case.

### 5.4 Comparison

|  | Option A (adopt) | Option B (self-built) |
|---|---|---|
| Engine LOC | ~100 wrapper LOC | ~300 LOC engine + match-type work |
| Macro work | None (use `Schema.derived`) | Case-class + string-DSL macros (~100 LOC each) |
| Codec infrastructure | `Codec`/`Format` reusable; TOON ready | All adapter formats hand-written |
| Stability risk | 0.0.x churn (accepted) | None external |
| Vocabulary footprint | `Reflect`/`Term`/`Format`/`TypeId` at engine layer | All dspy4s names |
| Lock-in | Moderate (wrapped, pervasive) | None |
| Time to working prototype | ~1–2 sessions | ~3–5 sessions |

### 5.5 Recommendation

**Pursue Option A.** Concrete first steps:

1. Add `dev.zio` `zio-blocks-schema` to the `core` module (or a new
   `core-schema` module for cleanliness).
2. Build `Signature[I, O]` as a thin wrapper around two `Schema`s.
3. Adapt `Predict`/`ChainOfThought` to accept `Signature[I, O]` and
   produce a typed `Prediction[O]`.
4. Adapter integration: start with the existing `ChatAdapter`; map the
   runtime `Reflect.Record` walk to current `FieldSpec` iteration.
   Defer reuse of `Codec`/`Format` until the basics work.
5. Study `schema-toon` for prompt-format inspiration (separate
   exploration; not on the critical path).

**Keep Option B documented as the fallback.** If Option A hits
unexpected friction — surprising macro behavior, missing primitives,
unworkable binary churn — the self-built engine remains a viable
alternative. The two options share the same surface layout from §4.3,
so the user-visible API stays similar either way.

---

## 6. Complementary: typed accessor ladder

Independently of the typed engine, we can ship a small win **today** by
mirroring the existing `asDouble` for every primitive value shape:

```scala
trait Prediction extends Record:
  def value(key: String): Either[DspyError, Any]
  def asBoolean(key: String): Either[DspyError, Boolean]
  def asInt(key: String):     Either[DspyError, Int]
  def asString(key: String):  Either[DspyError, String]
  def asDouble(key: String):  Either[DspyError, Double]   // already exists
  def asJson(key: String):    Either[DspyError, ujson.Value]
```

This eliminates the silent-cast risk for every translated example
without locking us out of the engine work. ~30 lines, no design
commitments.

---

## 7. Phased rollout *(schema-first)*

Assumes Option A (§5.2). If we fall back to Option B, the same phases
apply but each is bigger because we own the engine.

1. **Phase 0 (optional, immediate)** — Typed accessor ladder (Section
   6). Pure win, independent of the foundation choice.

2. **Phase 1 — Adopt `zio-blocks-schema` + wrap as `Signature[I, O]`.**
   Add the dependency, build the thin wrapper, write a small
   compatibility shim from `Schema[A]`'s `Reflect.Record` to the
   existing `FieldSpec` so current adapters keep working unchanged.
   Tests prove `Signature.of[(sentence: String), (sentiment: Boolean)]`
   round-trips field names and types correctly.

3. **Phase 2 — Typed `Predict`/`Prediction`.** Add `TypedPredict[I,
   O]` (consumes `Signature[I, O]`) and `TypedPrediction[O]` (returns
   typed values via `NamedTuple` + Selectable). Re-translate
   `Signatures.scala` Snippet 5 against it to validate ergonomics on
   a real example. Existing `Predict(sig: Signature)` API stays
   untouched.

4. **Phase 3 — Case-class surface parity.** Verify `case class Foo
   derives Schema` works end-to-end through `Signature.of[Foo,
   Bar]`. Most or all of the work should already be done via
   `Schema.derived` — this phase is mostly validation + tests.

5. **Phase 4 (optional) — String DSL → `Schema`.** Inline transparent
   macro that parses literal DSL constants at compile time and emits
   a `Schema[NamedTuple[Names, Types]]`. Defer until Phases 1–3
   settle and the macro pays for itself.

6. **Phase 5 (optional, exploratory) — Adapter on `Codec`/`Format`.**
   Study `schema-toon` and the JSON `Codec`. Decide whether to
   refactor `ChatAdapter` / `JsonAdapter` to delegate prompt
   format/parse to the `Codec` infrastructure. Big refactor, big
   potential win. Out of scope until the above stabilize.

Phases 2–5 are purely additive. Each can be skipped if it doesn't pull
its weight in practice.

---

## 8. Open questions for review

1. **Module placement.** Does `Signature[I, O]` live in `core`, or do
   we add a new `core-schema` module so the `zio-blocks-schema`
   dependency is opt-in for downstream module authors? (Most modules
   would still pull it transitively via `core`, so the split is
   mostly about declared dependencies.)

2. **`Either` at the typed boundary.** Should `TypedPrediction.value[K]`
   return `FieldOf[O, K]` directly (constructed from LM, "always
   present") or `Either[DspyError, FieldOf[O, K]]` (consistent with
   the rest of the API)? Recommendation: `Either` for now.

3. **Dot-access default.** `Selectable` enables `pred.toxic`. Worth the
   small abstraction layer? Recommendation: yes for the typed layer;
   keep dynamic dispatch out of the untyped layer to avoid surprise.

4. **Input ergonomics.** Should `TypedPredict.run` take a named tuple
   (`run((comment = "..."))`), varargs of pairs (`run("comment" -> "...")`),
   or both? Named tuples are typed but more verbose; varargs match the
   current `PredictProgram.run` overload.

5. **Surface migration policy.** Once the engine ships, do we
   re-translate existing example files to the typed layer immediately,
   or only opt in selectively? Recommendation: opt-in on a per-example
   basis; never automatic.

6. **Untyped `Signature.apply` deprecation.** Should the string-DSL
   `Signature.apply` eventually be replaced by the Phase 4 macro, or
   coexist forever as a runtime-DSL escape hatch? Recommendation:
   coexist; runtime DSL is a legitimate use case (config-driven
   signatures, REPL exploration).

7. **Phase 0 priority.** Ship the typed-accessor ladder before Phase 1,
   in parallel, or skip it? Recommendation: ship it before Phase 1 as
   a low-risk warm-up.

8. **`-Xmax-inlines` ceiling.** Default is 32. Realistic dspy4s
   signatures are well under this, but should the engine guard
   against pathological cases (e.g. emit a clearer error if a builder
   chain approaches the limit)? Recommendation: leave at default; if
   users hit it, they're building something unusual and the standard
   compiler message is informative.

9. **SchemaError → DspyError mapping.** `zio-blocks-schema` codecs
   return `Either[SchemaError, A]`. Where do we translate? Per-call
   at adapter boundaries, or once via an implicit conversion?
   Recommendation: explicit per-call mapping for now; revisit if it
   becomes boilerplate.

10. **Tracking upstream churn.** With `zio-blocks-schema` at 0.0.x,
    breaking changes are expected. Strategy: pin to a specific
    version in `build.sbt`, add a `versionBump.md` entry to track
    upgrades, run our test suite against each bump before merging.

---

## 9. Non-goals (this document)

- A full inline macro for the string DSL. Sketched as Phase 4, design
  details deferred.
- Refined / Iron predicate integration. Useful eventually for
  validated fields, but orthogonal.
- Schema derivation for arbitrary user types (pydantic-style). The
  engine relies on adapters to format/parse field values; that contract
  is out of scope here.
- Cross-module refactor. The typed engine is purely additive; existing
  modules continue to consume `Signature` (the trait) and `Prediction`
  unchanged.
