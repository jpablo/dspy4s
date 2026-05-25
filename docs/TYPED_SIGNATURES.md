# Typed Signatures — Design Document

> **Status: design archive.** This document captures the original
> design rationale (pre-implementation). The work has shipped, and the
> nomenclature has since shifted — what this doc calls `Signature` is
> now `SignatureLayout`, what it calls `Prediction` is `DynamicPrediction`,
> and the typed surface is `Signature[I, O]` / `Prediction[O]` /
> `Predict[I, O]`. For the current API see:
>
> - [TYPED_SIGNATURES_GUIDE.md](TYPED_SIGNATURES_GUIDE.md) — day-to-day usage
> - [ARCHITECTURE.md](ARCHITECTURE.md) — how the typed/dynamic split fits the broader stack
>
> The body of this document is preserved as a record of the design
> thinking; treat its code snippets as illustrative of intent, not as
> the current API.
>
> **Original draft date:** 2026-05-24
> **Scope:** dspy4s core + programs modules
> **Targets:** Scala 3.8.1, minimal exposed dependency surface (Phase 1
> may add one or two library dependencies per the foundation chosen in §5.6)

## TL;DR

`Prediction.value(key: String): Either[DspyError, Any]` returns `Any`,
forcing every typed access to a runtime cast. We propose a **layered
architecture** that gives users compile-time typed predictions while
keeping the existing runtime-flexible API intact:

- **Engine layer** — one canonical typed carrier (`Signature[I, O]` +
  `Predict` + `Prediction`) that lifts field/type info into
  the type system.
- **Surface layers** — multiple ergonomic translators that all compile
  down to the engine: typed builder, named tuples, case-class
  derivation, and (later) an inline macro over the string DSL.

**Foundation choice (§5):** three live options have been evaluated:
- **Option A** — adopt `zio-blocks-schema` (`Schema[A]` + `Codec`/`Format`
  + `schema-toon`; broad scope, 0.0.x).
- **Option B** — self-built engine using only Scala 3.8.1 stdlib
  features (documented as fallback).
- **Option C** — adopt Kyo's typed stack: `kyo-data` `Record[F]` for
  intersection-typed prediction access, and evaluate `kyo-schema` for
  schema-backed decoding before writing custom codecs.

Project owner prefers leaning into libraries where they give us pieces
we need. Final selection is now biased toward Option C if `kyo-schema`
is published and covers the MVP runtime decoding contract.

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
//         : Either[DspyError, Prediction[EmotionOut]]
pred.map(_.sentiment)
//         : Either[DspyError, Emotion]
// pred.map(_.typo)   ← compile error
```

In the example above the dot-access (`_.sentiment`) returns the **raw**
typed value, not an `Either`. Missing-field-at-runtime would indicate
a contract violation between the adapter and the LM (the adapter
parsed a response that lacked a declared output field), not a normal
user-facing error — see §8 Q2 for the design choice.

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
code with proper Scala types. We surveyed five shapes:

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

### 3.5 Trait/class-as-spec surface

```scala
trait Emotion extends Signature.Spec:
  def sentence: InputField[String]
  def sentiment: OutputField["sadness" | "joy" | "love" | "anger" | "fear" | "surprise"]

val emotionSig = Signature.of[Emotion](
  instructions = "Classify emotion."
)
```

This is the closest Scala analogue to Python's class-based signatures:
the declaration is a **spec**, not request/response data. Users never
instantiate `Emotion`; they use it only to derive a `Signature`.
The macro reads abstract members, partitions them by `InputField` /
`OutputField`, and lowers them to the same engine carrier as every
other surface.

- **Pros.** Best Python parity. One declaration. Avoids the placeholder
  output problem because the trait is never constructed as data. Good
  field-name source for IDE rename/refactor tools.
- **Cons.** Requires a macro over trait/class members, not only
  `Mirror.Product`. The marker wrappers (`InputField` / `OutputField`)
  are extra vocabulary. A shorthand where unmarked members default to
  outputs is possible later, but the explicit form should ship first.

**Recommendation:** Make this the primary Python-like ergonomic
surface after the foundation and typed `Predict` are working. Keep
named tuples and case classes as data-oriented alternatives.

### 3.6 Comparison

| | Lines per signature | Mirror/macro work | Input/output construct asymmetry | Error message quality |
|---|---:|:---:|:---:|:---:|
| 1. Named tuples | 3 | small (derive via `NamedTuple.From`) | clean | structural (acceptable) |
| 2. Case classes | 4–6 | small (`Mirror`-based derive) | clean | nominal (best) |
| 3. Single case class + In/Out | 4 | medium (derive + role partition) | **bad** (placeholder outputs at construction) | nominal (good) |
| 4. Typed builder | 5–7 | none | clean | carrier-tuple-typed (verbose) |
| 5. Trait/class-as-spec | 5–7 | medium (member-inspection macro) | clean (spec-only) | nominal-ish (best Python parity) |

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
   need to produce a well-formed `Signature[I, O]`. Each translator
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
opaque type Signature[I <: Tuple, O <: Tuple] = SignatureSpec

object Signature:
  def empty: Signature[EmptyTuple, EmptyTuple] = ???

  extension [I <: Tuple, O <: Tuple](sig: Signature[I, O])
    def input[T](name: String & Singleton): Signature[(name.type, T) *: I, O] = ???
    def output[T](name: String & Singleton): Signature[I, (name.type, T) *: O] = ???
    def instructions(text: String): Signature[I, O] = ???

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
// inferred: Signature[("sentence", String) *: EmptyTuple, ("sentiment", Emotion) *: EmptyTuple]
```

A typed companion to `Predict`:

```scala
final class Predict[I <: Tuple, O <: Tuple](sig: Signature[I, O]):
  def run(inputs: NamedTuple[Names[I], Types[I]])(using RuntimeContext)
      : Either[DspyError, Prediction[O]] = ???
  // alternative varargs form for parity with PredictProgram.run:
  def run(inputs: (String, Any)*)(using RuntimeContext)
      : Either[DspyError, Prediction[O]] = ???
```

`Prediction[O]` exposes `.value[K]` via a match type over `O`,
plus optional `Selectable` for dot-access:

```scala
import scala.compiletime.error

final class Prediction[O <: Tuple] extends Selectable:
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
| **Trait/class spec** (`trait Foo extends Signature.Spec`) | macro: inspect abstract member methods, require `InputField[T]` / `OutputField[T]` markers, emit the carrier |
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
  *helps* us: our `opaque type Signature[I, O] = SignatureSpec`
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

Section 4.2's `Signature[I, O]` + `FieldOf` + `Prediction`
sketch is preserved as a self-contained design we can fall back on if
neither library foundation in §5 pans out. In a library-adoption path
(§5.2 zio-blocks-schema or §5.4 kyo-data Record), most of §4.2 is
replaced by primitives from the chosen library — the surface layout
from §4.3 remains the same either way.

### 4.8 The existing untyped surface stays

`Signature.apply(dsl: String, instructions: String = "")` and
`Predict(sig: Signature)` remain available unchanged. The typed layer
is purely additive. Callers who don't need typing pay nothing.

When the inline macro surface (Phase 5) ships, it can opt to *replace*
the existing `Signature.apply` if the user-facing improvement is worth
the macro footprint — but that decision can be deferred.

---

## 5. Foundation choice — three options

### 5.1 Background

Three live options for the typed engine have been evaluated:

- **Option A** (§5.2): adopt [zio-blocks](https://github.com/zio/zio-blocks)
  — broad `Schema` + `Codec`/`Format` ecosystem with NamedTuple-aware
  derivation.
- **Option B** (§5.3): self-built engine from §4.2 — pure Scala 3.8.1
  with no external deps.
- **Option C** (§5.4): adopt [kyo-data](https://github.com/getkyo/kyo)
  `Record[F]` — intersection-typed structural records with a focused
  scope, surfaced by [the kyo-sql preview gist](https://gist.github.com/fwbrasil/e5ea19cca971795ece95b6f7f1d1e14d).

Each is described below; §5.5 compares; §5.6 records the current lean
and defers the binding decision to external review.

The original investigation (kept here for context) focused on
zio-blocks. The `schema` module specifically overlaps significantly
with what §4 proposes to build:

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

### 5.2 Option A — Adopt zio-blocks-schema

**Shape.** Replace `Signature[I, O]` (§4.2) with a thin wrapper
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
is to keep `Signature` as a dspy4s-owned wrapper so churn is
contained at one layer — typed programs see `Signature[I, O]`,
runtime adapters still see the existing untyped `Signature` trait via
`asUntyped`, and no downstream code sees raw `Schema[A]`.

### 5.3 Option B — Self-built engine

The §4.2 sketch in full: `opaque type Signature[I <: Tuple, O <:
Tuple] = SignatureSpec`, builder extensions, `FieldOf` match type,
`Predict`, `Prediction`. Pure Scala 3.8.1 features, no
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
- Reinventing pieces both `zio-blocks-schema` and `kyo-data` have
  already shipped, for effectively the same use case.

**Patterns worth lifting even in this option.** Several techniques
from the kyo-sql preview (§5.4 cites the source) would improve our
self-built design materially:

- **`Name ~ Value` as a bidirectional operator** at type and value
  level. Schema reads identically to construction:
  `Record["k1" ~ V1 & "k2" ~ V2]` ↔ `("k1" ~ v1) & ("k2" ~ v2)`. This
  replaces our `.input[T]("name")` builder with something tighter.
- **`stage[A]`-style polymorphic field iteration** — a single
  inline-staged primitive that walks a type's fields with a per-field
  polymorphic function. The right abstraction for
  derive-from-NamedTuple, derive-from-case-class, and
  build-decoder-from-output-type — all the same shape. Replaces a
  collection of per-surface Mirror-derivation macros with one
  primitive plus calls.

If we go Option B, we should adopt these patterns from the outset.

### 5.4 Option C — Adopt Kyo `Record[F]` + evaluate `kyo-schema`

**Source.** `getkyo/kyo`, modules `kyo-data` and `kyo-schema`.
`kyo-data` provides `Record[F]` (file
`kyo-data/shared/src/main/scala/kyo/Record.scala`). `kyo-schema`
provides schema derivation, JSON/Protobuf serialization, validation,
runtime `Structure` descriptions, and value decoding while depending
only on `kyo-data` in the local checkout. Both are Apache-2.0. The
`Record[F]` pattern is showcased in the
[kyo-sql preview gist](https://gist.github.com/fwbrasil/e5ea19cca971795ece95b6f7f1d1e14d)
which motivated this option.

**Shape.** `Record`'s type parameter is an intersection of `Name ~
Value` pairs. Construction mirrors the schema:

```scala
import kyo.{Record, ~}

val r: Record["name" ~ String & "age" ~ Int] =
  ("name" ~ "Alice") & ("age" ~ 30)

r.name   // String   (typed via Fields.Have evidence)
r.age    // Int
// r.email  // compile error: no Fields.Have evidence
```

Internally `Record[F]` is `final class Record[F](dict: Dict[String,
Any]) extends Dynamic`. Field access goes through `selectDynamic[Name
<: String & Singleton](using Fields.Have[F, Name]): h.Value`.
Composition is `&[A](other: Record[A]): Record[F & A]` — intersection
composition of schemas. Duplicate keys normalize to a union via `~`
being contravariant in `Value` (`"f" ~ Int & "f" ~ String` ≡ `"f" ~
(Int | String)`).

**Polymorphic field iteration.** `Record.stage[A]` (verified at
`~/GitHub/kyo/kyo-data/shared/src/main/scala/kyo/Record.scala:204`,
checkout `e075492a`) takes a `Fields[A]` instance and returns a
`StageOps[A, f.AsTuple]` builder for compile-time field transformation:

```scala
inline def stage[A](using f: Fields[A]): StageOps[A, f.AsTuple]
```

Combined with `Record.fromProduct[A <: Product]`
(`Record.scala:270` — `transparent inline def`), this is the public
field-iteration surface today. Conceptually it does what we'd otherwise
hand-roll for derivation, but **the user-facing helpers we'd want
(`stageNamed`, `fromNamedTuple`) are not yet in the published source**
— they appear in the kyo-sql preview gist as aspirational API. The
gap is small (each is a few lines on top of `stage` / `fromProduct`),
but we'd need to either contribute them upstream or ship our own
thin wrappers in dspy4s.

**Use in dspy4s.** Wrap as:

```scala
final case class Signature[I, O](
  input:        Record[I],
  output:       Record[O],
  instructions: Option[String] = None
)
```

`Prediction[O]` becomes a `Record[O]` directly — typed dot-access
already works via `Record.selectDynamic`. Surface translators:

- **Builder DSL** — `Record["k1" ~ V1 & "k2" ~ V2]` via `~` and `&`
  (works today; no `.input[T]` chain needed)
- **Case classes** — `Record.fromProduct(emotionIn)` (works today,
  *transparent inline* returns the typed Record schema)
- **Named-tuple types** — needs a helper we'd add: e.g.
  `Record.from[(sentence: String)]` built on `stage[NamedTuple]`
- **String DSL (later)** — macro emits a `Record[Schema]` literal

**What we gain.**
- The structural carrier we want, already implemented.
- A plausible schema/codec companion in `kyo-schema`, so the typed
  layer can try schema-backed runtime decoding before writing its own
  field codec stack.
- `~` and `&` as bidirectional operators give the cleanest DSL
  syntax. Schema in the type parameter reads identically to
  construction. No separate builder vocabulary.
- `stage[A]` + `fromProduct` cover most derivation we'd need; the
  remaining helpers we'd add are thin.
- Constant-depth field access regardless of arity (no nested-tuple
  destructuring like §4.4's option 1).
- Subtyping is intersection-subtyping (wider Record subtypes narrower)
  — the semantics we want for free.
- Field-presence is enforced by `Fields.Have` typeclass evidence
  instead of a recursive match type, so the "stuck match type" issue
  from §4.6 doesn't apply.

**What we take on.**
- Dependency on `kyo-data`; likely also `kyo-schema` if the Phase 0
  spike confirms it is published and covers MVP decoding.
- Adapter field extraction still stays on us (roughly the status quo:
  `ChatAdapter`/`JsonAdapter`/`XmlAdapter` already extract named
  fields). The goal is to avoid writing a general value codec system;
  `kyo-schema` should handle runtime value decoding wherever possible.
- New vocabulary at the engine layer: `Record`, `~`, `&`, `Fields`,
  `stage`, `Dict`. Smaller surface than zio-blocks but still visible.
- **Locally verified** that `kyo-data` does not transitively pull the
  full Kyo effect runtime (`Abort`, `Async`, fibers) in checkout
  `e075492a`; re-check the published POM for the exact pinned version
  before committing. Same zero-effect-leakage discipline we required
  of zio-blocks.
- Smaller community + ecosystem than zio-blocks.

**Possible fallback hybrid: ZIO Blocks + Kyo.** Use `kyo-data`
`Record[F]` for the typed prediction carrier, and `zio-blocks-schema`
for the runtime mirror + codec layer only if `kyo-schema` cannot cover
the MVP or future format requirements. This is a fallback, not the
first choice, because Kyo can now plausibly cover both typed records
and schema-backed decoding behind one family of dependencies.

### 5.5 Comparison

|  | A (zio-blocks-schema) | B (self-built) | C (Kyo Record + schema) |
|---|---|---|---|
| Engine LOC | ~100 wrapper | ~300 engine + match-type work | ~50 wrapper |
| Macro work | None (`Schema.derived`) | Case-class + string-DSL macros | Small (thin wrappers on `stage`/`fromProduct` for NamedTuple surface) |
| Carrier shape | `Schema[A]` / `Reflect.Record` | Tuple of pairs in opaque type | Intersection of `Name ~ Value` |
| Schema composition | wrapper-level | tuple concat ops | `&` (intersection types) |
| Typed dot-access | NamedTuple + Selectable | Selectable + match types | Built-in `selectDynamic` + `Fields.Have` |
| Field-not-found error | clean (Mirror) | needs `summonFrom` + `compiletime.error` mitigation | clean (typeclass evidence missing) |
| Codec ecosystem | Bundled (JSON, YAML, BSON, TOON, ...) | Hand-built per format | `kyo-schema` JSON/Protobuf + runtime `Structure`; verify MVP fit |
| Stability risk | 0.0.x (accepted) | None external | Lower than zio-blocks; verify version + transitive deps |
| Vocabulary footprint | `Reflect` / `Term` / `Format` / `TypeId` | All dspy4s names | `Record` / `~` / `&` / `Fields` / `Dict` |
| Lock-in | Moderate (wrapped) | None | Moderate (wrapped) |
| Time to working prototype | ~1–3 sessions* | ~3–5 sessions | ~1–3 sessions* |

\* First integration with any external library reliably uncovers
surprises (macro edge cases, error-message quality, perf on real
shapes). Estimates assume an initial prototype + one round of
integration fixes. The relative cost of A vs. C is roughly equal once
this padding is included.

### 5.6 Current lean — pending external review

Project owner has stated a preference for **leaning into libraries
that give us pieces we need**. That bias points away from Option B
(self-built) for the foundation, but leaves Option A vs. Option C
genuinely open. The key trade is **scope** vs **structural fit**:

- **Option A** brings more — including a codec ecosystem (`schema-toon`,
  JSON, YAML, etc.) that could simplify adapter work and a typed-mirror
  story. Larger vocabulary footprint and a 0.0.x version pin.
- **Option C** is structurally a better match for the typed-carrier
  problem specifically (intersection types compose better than tuples
  for structural records; `~` and `&` are the cleanest DSL syntax we
  could ask for; `stage`/`fromProduct` is the right derivation
  primitive). Caveat: a few helpers shown in the kyo-sql preview
  (`stageNamed`, `fromNamedTuple`) are *not* in the published
  `kyo-data` source today — we'd ship thin wrappers in dspy4s or
  contribute them upstream. New information: `kyo-schema` may cover
  the runtime schema/codec side too, so custom codecs should be a
  fallback rather than the default implementation.
- **ZIO Blocks + Kyo hybrid** remains available if Kyo's schema layer
  cannot cover the decoding or format requirements, but it is no
  longer the first path to try.

**Items to confirm before the binding decision:**

1. `kyo-data` / `kyo-schema` published-artifact dependencies — the
   local checkout does not pull the full Kyo runtime for these modules,
   but confirm the POM for the exact version we pin.
2. `kyo-schema` derivation and decoding behavior on our actual
   signature shapes (case classes, Scala enums, primitive fields, named
   tuples / structural records, and literal string unions if possible).
3. `kyo-data` `Record.stage[A]` / `fromProduct[A]` behavior on
   identical shapes — plus prototyping the thin wrappers we'd need
   (`fromNamedTuple` equivalent) since they're not in the published
   source.
4. ZIO Blocks schema behavior as fallback if Kyo cannot cover the
   decoding contract.
5. Performance: macro-derived signature construction time. Could
   matter if we end up with hundreds of `Signature` instances.

**Until the binding decision is made**, Phase 1 of the rollout (§7)
remains gated. Either of A or C unblocks the same Phase 2 work
(typed `Predict` / `Prediction`).

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
```

This eliminates the silent-cast risk for the primitive translated
examples without locking us out of the engine work. `TypeRef.json`
continues to use `.value` until the typed engine or a codec-backed
adapter path can represent JSON without adding `ujson` to `core`.
~30 lines, no design commitments.

### 6.1 Runtime output parsing contract

Typed prediction access is only sound if output values are decoded before
the typed prediction is created. Python DSPy does this by keeping each
field's annotation on the signature, extracting raw fields in the
adapter, and then coercing each value with a parser such as Pydantic's
`TypeAdapter`.

The Scala port needs the same contract:

1. **The adapter extracts named fields** from the LM response. For
   example, a chat adapter extracts field sections and a JSON adapter
   extracts JSON object properties.
2. **The typed layer decodes raw values** with a bounded field decoder.
   The first implementation should delegate to `kyo-schema` when
   possible, falling back to hand-written decoders only for uncovered
   MVP field types. This is where strings, numbers, booleans, literal
   unions, and enums become Scala values.
3. **`Prediction[O]` is constructed only after successful decode.**
   Dot-access reads already-decoded values; it does not parse lazily and
   it does not return `Either`.

This is distinct from committing to arbitrary Pydantic-style schema
derivation as a public feature. If `kyo-schema` covers case classes,
enums, primitives, and possibly nested records cleanly, we should use it
behind the DSPy4S boundary instead of recreating codecs. Rich custom
schemas and domain-specific codecs can arrive later without changing
this boundary.

---

## 7. Phased rollout *(foundation-neutral)*

The phases are written against the public typed surface. The concrete
carrier underneath is foundation-specific: Option A uses
`Schema[I]`/`Schema[O]`, Option C uses `Record[I]`/`Record[O]` plus a
`kyo-schema` decoding spike, and Option B uses the self-built fallback
engine from §4.2.

1. **Phase 0 (optional, immediate)** — Typed accessor ladder (Section
   6). Pure win, independent of the foundation choice.

2. **Phase 1 — Adopt the foundation chosen in §5.6 + wrap as
   `Signature[I, O]`.** Add the dependency (`kyo-data` plus
   `kyo-schema` if the spike succeeds; otherwise zio-blocks-schema or
   the fallback engine), build the thin wrapper, write a small
   compatibility shim from the chosen library's mirror type to the
   existing `FieldSpec` so current adapters keep working unchanged.
   Tests prove `Signature.of[(sentence: String), (sentiment: Boolean)]`
   round-trips field names and types correctly. If B is chosen,
   substitute the self-built engine from §4.2 enriched with the
   `Name ~ Value` and `stage`-style patterns from §5.3.

3. **Phase 2 — Typed `Predict`/`Prediction`.** Add `Predict[I,
   O]` (consumes `Signature[I, O]`) and `Prediction[O]`.
   The underlying prediction carrier is foundation-specific
   (`NamedTuple`/Selectable for A or B, `Record[O]` for C), but the
   public ergonomic target is the same: typed dot-access and typed
   `.value[K]`. This phase also implements the runtime output parsing
   contract from §6.1: raw adapter outputs are decoded with the expected
   schemas/field codecs before a `Prediction[O]` is constructed.
   Re-translate `Signatures.scala` Snippet 5 against it to validate
   ergonomics on a real example. Existing `Predict(sig: Signature)` API
   stays untouched.

4. **Phase 3 — Case-class surface parity.** Verify `case class Foo
   derives Schema` works end-to-end through `Signature.of[Foo,
   Bar]`. Most or all of the work should already be done via
   `Schema.derived` — this phase is mostly validation + tests.

5. **Phase 4 — Trait/class-as-spec surface.** Add
   `Signature.Spec`, `InputField[T]`, and `OutputField[T]`, plus
   a macro that derives `Signature.of[Spec]` from abstract
   members. This is the Python-parity surface and should lower to the
   same engine as named tuples, case classes, and builder signatures.
   Start with explicit input/output markers; consider output-default
   shorthand only after the explicit form is stable.

6. **Phase 5 (optional) — String DSL → `Schema`.** Inline transparent
   macro that parses literal DSL constants at compile time and emits
   a `Schema[NamedTuple[Names, Types]]`. Defer until Phases 1–4
   settle and the macro pays for itself.

7. **Phase 6 (optional, exploratory) — Adapter on `Codec`/`Format`.**
   Study `kyo-schema` codecs first, then `schema-toon` and the ZIO
   Blocks JSON `Codec` only if Kyo is insufficient. Decide whether to
   refactor `ChatAdapter` / `JsonAdapter` to delegate prompt
   format/parse to the `Codec` infrastructure. Big refactor, big
   potential win. Out of scope until the above stabilize.

Phases 2–6 are purely additive. Each can be skipped if it doesn't pull
its weight in practice.

---

## 8. Open questions for review

1. **Module placement.** Does `Signature[I, O]` live in `core`, or do
   we add a new `core-schema` module so the `zio-blocks-schema`
   dependency is opt-in for downstream module authors? (Most modules
   would still pull it transitively via `core`, so the split is
   mostly about declared dependencies.)

2. **`Either` at the typed boundary — *unilateral resolution pending
   sign-off*.** Both dot-access (`pred.sentiment`) and the explicit
   accessor (`pred.value[K]`) currently return the **raw** typed
   value, not `Either`. Either lives one level up: `Predict.run`
   returns `Either[DspyError, Prediction[O]]`, so all error
   handling is at the prediction-as-a-whole boundary. Missing-field-
   at-runtime would be a contract violation between adapter and LM
   (the adapter parsed a response that omitted a declared output
   field) and surfaces as a thrown exception — the same semantics
   Scala's `Map.apply` uses, and what `Record.selectDynamic` does in
   kyo-data. The examples in §1.3 reflect this.

   **Alternative not chosen:** make field access return `Either[DspyError,
   T]` for consistency with the rest of dspy4s's pervasive
   `Either`-everywhere discipline. The case for the alternative is
   that returning raw values is admitting we don't fully trust the
   type-level guarantee; defensive `Either` at every level removes
   that asymmetry at the cost of one more `.flatMap` per field access.

   The current pick was made on the rationale that the type-level
   guarantee *is the whole point* of this layer, so wrapping every
   typed access in `Either` would undermine it. External reviewers
   may disagree and ask for the alternative; if so, the change is
   local (swap return types in two methods + update one §1.3 example).

3. **Dot-access default.** `Selectable` enables `pred.toxic`. Worth the
   small abstraction layer? Recommendation: yes for the typed layer;
   keep dynamic dispatch out of the untyped layer to avoid surprise.

4. **Input ergonomics.** Should `Predict.run` take a named tuple
   (`run((comment = "..."))`), varargs of pairs (`run("comment" -> "...")`),
   or both? Named tuples are typed but more verbose; varargs match the
   current `PredictProgram.run` overload.

5. **Surface migration policy.** Once the engine ships, do we
   re-translate existing example files to the typed layer immediately,
   or only opt in selectively? Recommendation: opt-in on a per-example
   basis; never automatic.

6. **Untyped `Signature.apply` deprecation.** Should the string-DSL
   `Signature.apply` eventually be replaced by the Phase 5 macro, or
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

9. **Schema decode error → DspyError mapping.** `kyo-schema` and
   `zio-blocks-schema` expose their own decode error shapes. Where do
   we translate? Per-call at adapter boundaries, or once via an
   implicit conversion? Recommendation: explicit per-call mapping for
   now; revisit if it becomes boilerplate.

10. **Tracking upstream churn.** With `kyo-schema` newly discovered and
    `zio-blocks-schema` at 0.0.x, breaking changes are possible.
    Strategy: pin to a specific version in `build.sbt`, add a
    `versionBump.md` entry to track upgrades, run our test suite
    against each bump before merging.

11. **Foundation: Kyo vs. ZIO Blocks fallback.** The binding decision is
    now biased toward Kyo if `kyo-schema` covers the MVP. Items to
    confirm beforehand are listed in §5.6.
    The Phase 1 surface API (`Signature[I, O]`, `Predict`,
    `Prediction`) is intended to be identical across foundations
    so a late switch is recoverable, but the engine internals and
    derivation macros differ.

12. **Kyo data/schema effect-runtime independence — *locally verified*.**
    Against the local checkout, `kyo-schema` depends on `kyo-data` and
    does not depend on `kyo-core`, the scheduler, or fibers. A previous
    check against `kyo-data` at checkout `e075492a` showed only
    `kyo-stats-registry`, `pprint` (compile), and `izumi-reflect`
    (Test). Re-check the published POM of whatever version we pin
    before adopting, in case a release has altered the transitive set.

---

## 9. Non-goals (this document)

- A full inline macro for the string DSL. Sketched as Phase 5, design
  details deferred.
- Refined / Iron predicate integration. Useful eventually for
  validated fields, but orthogonal.
- Supporting every arbitrary Pydantic-style user type as a public
  compatibility goal. A bounded parser/coercer for supported
  typed-signature fields is in scope (§6.1), and `kyo-schema` may expand
  that surface if the spike proves it clean. Arbitrary custom domain
  codecs remain out of scope for the MVP.
- Cross-module refactor. The typed engine is purely additive; existing
  modules continue to consume `Signature` (the trait) and `Prediction`
  unchanged.
