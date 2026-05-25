# Typed Signatures Implementation Plan

## Recommendation

Implement typed signatures as an additive typed layer over the existing
`Signature`, `Record`, `Prediction`, and `Predict` APIs. The first production
version should use Kyo's `Record`/`Fields` machinery for structural field
selection and compile-time field checking, and should evaluate `kyo-schema` as
the preferred runtime schema/codec layer before introducing DSPy4S-owned codecs.
DSPy4S-owned APIs remain at the user boundary.

The implementation should not replace the current string DSL. Existing
untyped signatures remain the stable interoperability layer. Typed signatures
compile down to today's `Signature` model, and typed prediction values wrap
today's `Prediction` so usage accounting, completions, adapter behavior, and
runtime integration continue to work unchanged.

The target user-facing API should support three construction styles in this
order:

1. A builder API for fast internal progress and straightforward tests.
2. Case class / named-field syntax for ordinary Scala users.
3. A trait-as-spec syntax that mirrors DSPy's Python class style.

The trait syntax is the desired end-user surface:

```scala
trait Emotion extends TypedSignature.Spec:
  def sentence: InputField[String]
  def sentiment: OutputField["sadness" | "joy" | "love"]

val sig = TypedSignature.of[Emotion]
val predict = TypedPredict(sig)

val result = predict.run(sentence = "I missed the train")
val sentiment: "sadness" | "joy" | "love" = result.sentiment
```

The exact marker names can change during implementation, but the syntax should
preserve the main property: users describe signatures with ordinary Scala
members and get statically checked output selection. The literal-union example
is the ergonomic target; the MVP should use Scala enums for enum-like outputs if
`kyo-schema` does not support literal unions cleanly.

## Module Strategy

Add a new `typed` module rather than putting Kyo directly into `core`.

Proposed dependency direction:

```text
core
  ^
  |
typed
  ^
  |
programs
```

This keeps `core` free of a Kyo dependency while allowing `programs` to expose
`TypedPredict`. The tradeoff is that `programs` users will receive the typed
module transitively. That is acceptable because `programs` is already the
high-level execution module, while `core` remains the minimal contracts layer.

Build changes:

- Add a shared `kyoVersion` in `build.sbt`.
- Add `kyo-data` for typed structural records.
- Add `kyo-schema` only if the Phase 0 spike confirms it can cover the runtime
  decoding contract for the MVP.
- Add `lazy val typed = project.in(file("modules/typed")).dependsOn(core)`.
- Add `typed` to the root aggregate.
- Update `programs.dependsOn(...)` to include `typed`.
- Keep all new Kyo imports inside `modules/typed` and typed-specific files in
  `modules/programs`.

## Proposed Files

New files:

- `modules/typed/src/main/scala/dspy4s/typed/TypedSignature.scala`
- `modules/typed/src/main/scala/dspy4s/typed/TypedPrediction.scala`
- `modules/typed/src/main/scala/dspy4s/typed/FieldMarkers.scala`
- `modules/typed/src/main/scala/dspy4s/typed/ValueDecoder.scala`
- `modules/typed/src/main/scala/dspy4s/typed/KyoSchemaValueDecoder.scala`
- `modules/typed/src/main/scala/dspy4s/typed/Shape.scala`
- `modules/typed/src/main/scala/dspy4s/typed/internal/ShapeMacros.scala`
- `modules/programs/src/main/scala/dspy4s/programs/TypedPredict.scala`

Test files:

- `modules/typed/src/test/scala/dspy4s/typed/TypedSignatureSuite.scala`
- `modules/typed/src/test/scala/dspy4s/typed/ShapeSuite.scala`
- `modules/typed/src/test/scala/dspy4s/typed/TypedPredictionSuite.scala`
- `modules/programs/src/test/scala/dspy4s/programs/TypedPredictSuite.scala`

Optional package objects or exports can be added after the API settles. Avoid
adding broad exports in the first slice.

## Phase 0: Dependency And Feasibility Spike

Goal: prove that Kyo can cover both halves of the typed runtime bridge:
`kyo-data` for typed dot access and `kyo-schema` for schema-backed decoding.

Tasks:

- Add the `typed` module and Kyo dependencies behind the smallest possible
  surface.
- Create a tiny internal test that derives field names and field types from a
  Kyo-supported record shape.
- Confirm how to construct a runtime `Record` value from decoded prediction
  values.
- Confirm whether Kyo exposes enough API to wrap dynamic values safely, or
  whether DSPy4S needs a small internal representation that only exposes typed
  accessors through Kyo at the boundary.
- Confirm whether `kyo-schema` is published at a version compatible with this
  repository's Scala version.
- Confirm whether `kyo-schema` can derive schemas for the planned MVP shapes:
  case classes, simple Scala enums, primitives, and possibly named tuples or
  structural records.
- Confirm whether `kyo-schema` can decode from an untyped intermediate value
  (`Structure.Value`) or whether we should normalize adapter fields to JSON and
  call `Json.decode`.
- Confirm whether `kyo-schema` supports literal string unions directly. If not,
  make Scala enums the MVP enum-like output surface.

Acceptance criteria:

- `sbt typed/test` runs.
- A test can construct a typed value with fields equivalent to
  `sentence: String` and `sentiment: String`.
- A test can access an existing field with dot syntax.
- A compile-time test rejects an unknown field.
- A test can decode `{"sentiment":"joy"}` into a typed output shape.
- A test rejects `{"sentiment":"confused"}` for an enum-like output.
- A test maps `kyo-schema` decode failures into `DspyError`.

Risk gate:

- If runtime `Record` construction is not practical with public Kyo APIs, keep
  Kyo for compile-time shape derivation and generate a DSPy4S `Selectable`
  wrapper for predictions. Do not switch to ZIO Blocks in the first version.
- If `kyo-schema` is not published or cannot support the MVP decoding contract,
  fall back to a small DSPy4S `ValueDecoder[A]` typeclass for primitives and
  enums only. Do not build a broad custom codec ecosystem in the MVP.

### Outcomes (executed 2026-05-24)

Phase 0 was completed against `io.getkyo:kyo-data_3:1.0.0-RC2` and
`io.getkyo:kyo-schema_3:1.0.0-RC2` — both published on Maven Central two weeks
before this run. Tests live in
`modules/typed/src/test/scala/dspy4s/typed/Phase0FeasibilitySuite.scala`
(9 tests, all passing). All Phase 0 acceptance criteria met.

**Resolved confirmations (per the Tasks list above):**

- `kyo-schema` is published as `kyo-schema_3` at `1.0.0-RC2`, compatible with
  this repo's Scala 3.8.1 (kyo itself is built on 3.8.3 — same minor family,
  TASTy-compatible).
- `kyo-data` `Record` is constructible at runtime via the `~` extension on
  `String` and `&` composition; field access via `selectDynamic` with
  compile-time `Fields.Have` evidence works as documented.
- `kyo-schema` `Json.decode[A]` returns `Result[DecodeException, A]`; folding
  into `Either[DspyError, ParseError]` is a clean ~10-line mapping.
- Case classes derive `Schema` via `derives Schema`; primitives (`String`,
  `Int`, `Boolean`) decode correctly from natural JSON.

**Surprises worth surfacing for Phase 2 design:**

1. **kyo-schema encodes Scala enums as `{"caseName":{}}` (discriminated
   object), not as flat strings.** A Scala enum
   `Mood.happy` encodes to `{"happy":{}}`; embedded in a case class
   `Result(mood: Mood)` it becomes `{"mood":{"happy":{}}}`. Confirmed by the
   `EncodingProbe.scala` test. LLM outputs that produce flat strings
   (`"sentiment":"joy"`) **will not** decode against an enum-typed field
   without intervening transformation. This is the discriminated `oneOf`
   pattern documented in kyo-schema's `Json` scaladoc.

2. **Path-dependent types break Schema derivation.** Declaring an enum or
   case class *inside* a test class produces `ClassCastException` at decode
   time. All shapes must be top-level (or in a top-level object). Worth
   capturing in dspy4s docs for users.

**Implications for Phase 2 (Output Parsing And Coercion Contract):**

- The "decode from already typed values first" coercion policy needs to
  account for the enum wire format. Options to evaluate during Phase 2:
  (a) require adapters to wrap flat-string enum outputs before handoff to
  the typed layer; (b) provide a `Schema[A]` override that uses flat-string
  encoding for enums; (c) use an `inline given Schema[Emotion]` per-enum
  case that maps strings → cases. Option (b) is the most LLM-friendly and
  worth a spike during Phase 2 design.
- Literal string unions (e.g. `"sadness" | "joy" | "love"`) were *not* tested
  in Phase 0 — they're a known kyo-schema gap and the plan already directs us
  to use Scala enums for MVP. Re-evaluate in Phase 7 (Post-MVP Extensions).

**Files added:**

- `modules/typed/` — new sbt module aggregating under root.
- `build.sbt` — `kyoVersion = "1.0.0-RC2"` shared val; `typed` project added.
- `modules/typed/src/test/scala/dspy4s/typed/Phase0FeasibilitySuite.scala`
  (9 acceptance-criteria tests).
- `modules/typed/src/test/scala/dspy4s/typed/EncodingProbe.scala` (kept as a
  record of how the enum wire format was discovered; useful for future
  diagnostic work, low maintenance cost).

No production code added in Phase 0 — purely a test-only spike.

## Phase 1: Prediction Primitive Accessors

Goal: improve the untyped foundation before adding the typed layer.

Current `Prediction` has `value` and `asDouble`. Add a small primitive accessor
ladder so typed decoding does not duplicate ad hoc conversions.

API additions in `modules/core/src/main/scala/dspy4s/core/contracts/Data.scala`:

```scala
trait Prediction extends Record:
  def asString(key: String): Option[String]
  def asInt(key: String): Option[Int]
  def asDouble(key: String): Option[Double]
  def asBoolean(key: String): Option[Boolean]
```

Implementation notes:

- Preserve the existing `asDouble` behavior where possible.
- Decode from already typed values first.
- Accept string representations only for clear primitive cases.
- Do not add an `asJson` method in this phase.

Tests:

- Existing `PredictionData` behavior still passes.
- Each accessor handles typed values, reasonable string values, missing keys,
  and invalid conversions.

Acceptance criteria:

- `sbt core/test` passes.

### Outcomes (executed 2026-05-24)

Implemented in `modules/core/src/main/scala/dspy4s/core/contracts/Data.scala`;
tests appended to `DataSuite.scala` (DataSuite is now 20 tests, all passing;
project-wide `sbt test` remains at 318 / 318).

**Intentional deviation from the plan's literal signatures.** The plan
sketched `Option[T]` returns; the implementation uses
`Either[DspyError, T]` for consistency with the existing
`asDouble: Either[DspyError, Double]` and the codebase-wide
`Either[DspyError, _]` discipline. `None` would have been less informative
than `Left(ValidationError(...))` with a message about why the conversion
failed. No call sites needed updating beyond the new abstract methods on
`Prediction` — the only implementor is `PredictionData`.

**Additional change beyond the plan**: `asDouble` was extended to accept
clean numeric strings (`"1.5"`, `"42"`) in addition to numeric primitives.
This is purely additive — every prior call site continues to return the
same value. Mirrors the new `asInt` and `asBoolean` string-parsing.

**Coercion policy applied** (Phase-1 conservative; Phase 2 will formalize):

- `asString` — String pass-through; primitive numerics + Boolean stringify
  via `.toString`; Map / Seq / other reject with `ValidationError`.
- `asInt` — Int pass-through; Long accepted only when within Int range;
  string parsed via `.toIntOption`; Double rejected (no silent truncation);
  Boolean rejected.
- `asDouble` — existing behavior preserved; clean numeric strings now
  accepted.
- `asBoolean` — Boolean pass-through; `"true"`/`"false"` (case-insensitive,
  trimmed) accepted; `"yes"`/`"1"`/numerics rejected (no implicit
  coercion).

## Phase 2: Typed Core Model

Goal: represent typed signatures independently of prediction execution.

Core types in `modules/typed`:

```scala
final case class TypedSignature[I, O](
  name: String,
  untyped: Signature,
  inputShape: Shape[I],
  outputShape: Shape[O]
)

final case class TypedPrediction[O](
  typed: kyo.Record[O],
  raw: Prediction
) extends Selectable
```

`TypedPrediction` should support direct field selection:

```scala
val p: TypedPrediction[Output] = ???
val answer = p.answer
```

`Shape[A]` responsibilities:

- List fields in stable declaration order.
- Convert field metadata to `FieldSpec`.
- Encode typed inputs into `Map[String, Any]`.
- Decode a raw `Prediction` into a typed output record through the selected
  schema/value decoder.
- Return structured DSPy4S errors for missing or invalid output fields.

`ValueDecoder[A]` responsibilities:

- Map Scala types to existing `TypeRef` values.
- Decode raw prediction values into Scala values.
- Encode input values into untyped values.
- Prefer delegating decoding to `kyo-schema` when a `Schema[A]` is available.
- Provide small hand-written fallbacks only for field-level values that are not
  naturally represented by the selected schema library.

### Output Parsing And Coercion Contract

Typed signatures must provide runtime value safety, not only compile-time field
selection. This mirrors Python DSPy: the signature stores the expected output
annotation, the adapter extracts a raw field value from the LM response, and a
parser/coercer validates that value before the prediction is exposed.

DSPy4S should split that work into two explicit responsibilities:

- **Adapters extract fields.** `ChatAdapter`, `JsonAdapter`, and future
  adapters remain responsible for turning an LM response into named raw output
  fields. The raw values may be strings, JSON values, numbers, booleans, or
  adapter-specific values.
- **The typed layer decodes fields.** `ValueDecoder[A]` is the DSPy4S boundary
  that converts each raw field into the expected Scala type or returns a
  structured `DspyError`. Its first implementation should delegate to
  `kyo-schema` rather than recreate a general codec system.

`TypedPrediction[O]` must only be constructed after every required output field
has been decoded successfully. Field access such as `prediction.sentiment`
should not perform lazy parsing and should not return `Either`; all parse
failures happen at the `TypedPredict.run` boundary.

Initial coercion policy, whether implemented directly or delegated through
`kyo-schema`:

- `String`: accept any scalar value by stringifying it; preserve strings as-is.
- `Int`: accept integral numbers and unambiguous integral strings.
- `Double`: accept numeric values and unambiguous numeric strings.
- `Boolean`: accept boolean values and conservative string forms such as
  `true` and `false`.
- Scala enums: decode from their stable string representation, with the exact
  representation decided during implementation.
- string literal unions: include only if the selected schema/decoder supports
  them cleanly; otherwise defer and use Scala enums for MVP enum-like fields.

Do not silently coerce ambiguous values. For example, `"yes"` should not become
`true` unless the codec explicitly documents that policy.

Multiple completions:

- MVP may decode only the primary prediction value if the existing completion
  model makes full decoding awkward.
- The design must leave room to decode every completion into the same typed
  output shape, because Python DSPy stores parsed values in `Completions`, not
  only in the first visible prediction.

First supported types:

- `String`
- `Int`
- `Double`
- `Boolean`
- simple Scala enums

Second supported types:

- string literal unions such as `"sadness" | "joy" | "love"`
- collections and nested records if `kyo-schema` support proves direct enough
  to expose safely

Defer:

- arbitrary JSON values
- custom domain codecs that are not expressible through the selected schema
  library

Tests:

- field order is stable
- field names match source members
- input and output roles are correct
- unsupported field types fail with a useful compile-time or construction error
- missing outputs produce a typed DSPy4S error
- invalid primitive conversions produce a typed DSPy4S error
- enum-like outputs reject values outside the declared set
- `TypedPrediction` is never constructed after a decode failure
- `kyo-schema` decode errors, if used, are translated into stable `DspyError`
  values

Acceptance criteria:

- `TypedSignature` can produce the same untyped `Signature` shape as the DSL.
- `TypedPrediction` preserves the original raw `Prediction`.
- `sbt typed/test` passes.

### Outcomes (executed 2026-05-24)

Implemented four files in `modules/typed/src/main/scala/dspy4s/typed/`:

- `ValueDecoder.scala` — field-level codec typeclass with built-in givens for
  `String` / `Int` / `Double` / `Boolean`, and a `derived` inline def that
  enables `derives ValueDecoder` on Scala enums (`Mirror.SumOf[A]` →
  case-name → enum-value table). Decodes from `Any` (already-typed
  adapter values), not from JSON bytes — this sidesteps the Phase 0
  enum wire-format finding entirely.
- `Shape.scala` — `Shape[A <: Product]` derivation via
  `Mirror.ProductOf[A]` + inline `summonLabels` / `summonDecoders`.
  Produces `Vector[FieldSpec]`, `encode(A) → Map[String, Any]`, and
  `decode(Map[String, Any]) → Either[DspyError, A]`. Role is stamped at
  derivation time via `derivedWithRole(role)`.
- `TypedSignature.scala` — `final case class TypedSignature[I, O]` with
  `derived[I <: Product, O <: Product](name, instructions)` that wires
  two `Shape`s into an untyped `SignatureSpec` whose fields are
  `inputs ++ outputs` in declaration order.
- `TypedPrediction.scala` — `final case class TypedPrediction[O]` holding
  the decoded output case-class instance + the raw `Prediction`. The
  `TypedPrediction.from(raw, shape)` constructor returns
  `Either[DspyError, TypedPrediction[O]]` so the value is *only*
  constructed after every required output field decodes successfully.

**Test results**: `Phase2TypedCoreSuite` adds 15 tests, all passing. Full
project remains green at 333 / 333 (was 318; +15 from this suite).

**Intentional deviation from the plan's literal shape**:

- `TypedPrediction[O]` does not carry a `typed: kyo.Record[O]` field as
  the plan sketched. `Record`'s `dict` field is `private[kyo]`, so a
  general-purpose `selectDynamic` wrapper would need either reflection
  or upstream API changes. The case-class-direct approach
  (`p.output.sentiment`) gives users the same typed dot-access through
  ordinary case-class syntax, with no kyo Record involvement at the
  user surface. A `.toRecord` extension can be added later if users
  want intersection-typed structural composition.
- `ValueDecoder` is hand-written; no `kyo-schema` Schema delegation in
  Phase 2. The MVP coercion policy is fully covered by hand-written
  primitive instances + enum-via-Mirror derivation, and decoding
  happens from `Any` (typed adapter values), not from JSON bytes — so
  kyo-schema's `Schema[A]` would be the wrong tool here. A
  `ValueDecoder.fromSchema[A](using Schema[A])` adapter could land
  later for users who already have a Schema in scope.

**Lessons from implementation**:

- Inline def + anonymous class issues warning `-W E197` ("New anonymous
  class definition will be duplicated at each inline site"). Extract
  to a named `private[typed]` class to silence; the class still needs
  to be visible from inline-expansion call sites.
- Case-class / enum fixtures must be top-level (Phase 0 finding) AND
  must not collide with other top-level fixtures in the same package
  (e.g. `Phase0FeasibilitySuite`'s `EmotionOutput` clashed with our
  Phase 2 `EmotionOutput`). Convention: prefix per-phase fixtures
  (`P2SentenceInput`, `P2ScoredSentiment`, etc.) until we move to a
  per-suite namespace.

## Phase 3: Builder And Case Class Surfaces

Goal: provide a working typed API before implementing the trait macro.

Builder API:

```scala
val sig =
  TypedSignature
    .builder("Emotion")
    .input[String]("sentence")
    .output[String]("sentiment")
    .build
```

Case class API:

```scala
case class EmotionInput(sentence: String)
case class EmotionOutput(sentiment: String)

val sig = TypedSignature.derived[EmotionInput, EmotionOutput]("Emotion")
```

Implementation notes:

- Builder shape can be implemented with ordinary runtime metadata.
- Case class derivation should reuse `Shape[A]`.
- Case class support is valuable even if the trait-as-spec API becomes the
  preferred public syntax because it gives users a conventional Scala escape
  hatch and makes testing easier.

Tests:

- builder emits expected `Signature`
- case classes derive expected field names and types
- case class values encode to the expected input map
- decoded typed predictions expose fields through the chosen typed surface

Acceptance criteria:

- Users can define typed signatures without macros beyond derivation support.
- The implementation can be exercised end to end before trait syntax exists.

### Outcomes (executed 2026-05-24)

Implemented one new file:

- `modules/typed/src/main/scala/dspy4s/typed/SignatureBuilder.scala` —
  fluent, immutable builder. `.input[T](name)` / `.output[T](name)`
  summon a `ValueDecoder[T]` to derive `TypeRef` + well-known metadata
  (enum cases, etc.) for the resulting `FieldSpec`. `.instructions(text)`
  is chainable anywhere; empty text → `None`. `.build` returns a plain
  runtime `Signature` (inputs then outputs in declaration order).

- `TypedSignature.builder(name)` factory added on the companion for
  discoverability — same return type as `SignatureBuilder.apply`.

**Design choice: builder returns `Signature`, not `TypedSignature[I, O]`.**
The builder is the **programmatic** path for callers that don't want a
case class per signature (REPL, dynamic shapes from config, tests). For
typed input encoding / output decoding, callers use
`TypedSignature.derived[I, O]` (case classes) from Phase 2. A future
phase may add a `TypedSignature.fromSignature[I, O](sig, Shape[I],
Shape[O])` adapter if a use case emerges for upgrading a builder result
to a typed surface; for now, the two surfaces are explicitly separate.

**Test results**: `Phase3SurfacesSuite` adds 9 tests (builder ordering,
TypeRefs from `ValueDecoder`, enum metadata propagation, immutability,
empty-instructions handling; case-class field-name / encode / decode
end-to-end; cross-surface parity for equivalent shapes). Full project
green at 345 / 345 (was 336; +9 from this suite).

**Phase 3 falsifiable claim now exercised in tests**: for a shape like
`P3CommentInput(comment: String, lang: String)` →
`P3ClassifyOutput(toxic: Boolean, confidence: Double)`, the
`SignatureBuilder` and the case-class-`derived` path emit
**structurally identical Signatures** (name, signatureString, and
per-field name/role/typeRef.repr all match). This is the parity
property that lets us treat the two surfaces as alternate front-doors
to the same runtime substrate.

## Phase 4: TypedPredict

Goal: connect typed signatures to the existing prediction runtime.

API:

```scala
final case class TypedPredict[I, O](
  signature: TypedSignature[I, O],
  demos: Chunk[Record] = Chunk.empty,
  name: Option[String] = None,
  runtime: DspyRuntime = DspyRuntime.default
):
  def run(input: I): Either[DspyError, TypedPrediction[O]]
```

The implementation should:

- Call `Predict(signature.untyped, demos, name, runtime)`.
- Convert typed input into `ProgramCall` inputs through `inputShape`.
- Delegate all adapter/model behavior to existing `Predict`.
- Decode the resulting `Prediction` through `outputShape`.
- Preserve completions and LM usage on the raw prediction.

Tests:

- `TypedPredict` passes the expected untyped inputs to the existing path.
- `TypedPredict` returns a typed prediction on valid output.
- decode failures are returned as `Left(DspyError)`.
- existing `PredictSuite` behavior is unchanged.

Acceptance criteria:

- `sbt programs/test typed/test core/test` passes.
- No adapter changes are required.

### Outcomes (executed 2026-05-24)

Implemented one file + one build-graph edit:

- `modules/programs/src/main/scala/dspy4s/programs/TypedPredict.scala` —
  `final case class TypedPredict[I, O](signature, demos, name, runtime)`.
  `run(input: I)(using RuntimeContext): Either[DspyError, TypedPrediction[O]]`
  encodes via `signature.inputShape`, dispatches through the existing
  `Predict(signature.untyped, demos, name, runtime).run(ProgramCall(...))`,
  then decodes via `TypedPrediction.from(raw, signature.outputShape)`.

- `build.sbt` — `programs.dependsOn(core, lm, adapters, typed)` (was
  `core, lm, adapters`). The typed module already depends on core only,
  so the new edge stays acyclic.

**Plan deviations** (called out for the next reviewer):

- The plan sketch shows `demos: Chunk[Record]` and `runtime: DspyRuntime`.
  Used `Vector[Example]` and `ProgramRuntime` instead to match the existing
  `Predict` case-class signature exactly, keeping kyo types Test-scoped and
  avoiding a parallel runtime-contract that would diverge from the rest of
  `programs`. Typed demos (`Vector[(I, O)]` or similar) are a Phase 7
  consideration.
- The plan's API sketches `run(input: I)` without an explicit context.
  Added `(using RuntimeContext)` because the underlying `Predict.run`
  requires it for settings / callbacks / trace.

**Test results**: `TypedPredictSuite` adds 5 tests covering happy path,
input-encoding-reaches-adapter (with a capturing test double),
completions/usage preserved on `raw`, decode failures surfaced as
`Left`, and a regression check that the inner `Predict` path still
works directly. Programs module: 59 / 59 (was 54; +5). Full project:
350 / 350 (was 345; +5).

**No adapter changes were required**, per the acceptance criterion.
The typed layer composes purely above the existing runtime.

## Phase 5: Trait-As-Spec Syntax

Goal: add the Python-like signature authoring syntax.

Target syntax:

```scala
trait Emotion extends TypedSignature.Spec:
  def sentence: InputField[String]
  def sentiment: OutputField["sadness" | "joy" | "love"]

val sig = TypedSignature.of[Emotion]
```

Marker types:

```scala
opaque type InputField[A] = A
opaque type OutputField[A] = A
```

Macro responsibilities:

- Inspect abstract members on the spec trait.
- Require each member to return `InputField[A]` or `OutputField[A]`.
- Preserve declaration order.
- Derive or summon `ValueDecoder[A]` / `Schema[A]` evidence for each field.
- Build `TypedSignature[inputRecord, outputRecord]`.
- Emit readable compile errors for unsupported members.

Validation rules:

- Reject concrete methods in spec traits.
- Reject duplicate field names.
- Reject fields without an input/output marker.
- Reject unsupported output types.
- Allow optional annotations for descriptions and prefixes after the base syntax
  works.

Possible metadata syntax:

```scala
trait QA extends TypedSignature.Spec:
  @description("Question to answer")
  def question: InputField[String]

  @prefix("Answer:")
  def answer: OutputField[String]
```

Tests:

- trait specs derive expected `Signature`
- input fields and output fields are assigned correct roles
- literal union outputs decode only allowed values
- unsupported trait members fail compilation
- unknown prediction field selection fails compilation

Acceptance criteria:

- The `Emotion` example compiles and runs.
- The resulting untyped signature is equivalent to a hand-written
  `Signature("sentence -> sentiment")` plus type metadata.

## Phase 6: Examples And Documentation

Goal: make the feature discoverable without forcing users through the design
document.

Add examples:

- `modules/examples/src/main/scala/dspy4s/examples/TypedEmotionExample.scala`
- one builder example
- one trait-as-spec example
- one case class example if the API remains public

Update docs:

- Add a user-facing typed signatures guide.
- Link from `docs/TYPED_SIGNATURES.md`.
- Document supported field types and deferred types.
- Document the relationship between `TypedSignature` and `Signature`.
- Document how to access the raw `Prediction` when users need completions,
  usage, or debugging data.

Acceptance criteria:

- A new user can copy one example and understand the intended API.
- The design document continues to describe rationale, while the new guide
  describes usage.

## Phase 7: Post-MVP Extensions

Only start these after the trait syntax and `TypedPredict` are stable.

Potential extensions:

- Inline checked string DSL:

  ```scala
  val sig = typed"question: String -> answer: String"
  ```

- richer `TypeRef` values for enums, arrays, objects, and literal unions
- custom codecs for domain types not covered by `kyo-schema`
- optional ZIO Blocks interop if `kyo-schema` cannot cover a future schema or
  format requirement
- typed examples for optimizers and evaluation
- typed datasets and demos

## Suggested PR Slices

1. Add `typed` module and Kyo feasibility tests, including `kyo-schema`.
2. Add primitive accessors to `Prediction`.
3. Implement `TypedSignature`, `Shape`, schema-backed `ValueDecoder`, and
   builder API.
4. Implement `TypedPrediction` and typed output decoding.
5. Implement `TypedPredict`.
6. Add case class derivation.
7. Add trait-as-spec macro and field markers.
8. Add examples and user-facing docs.

Each slice should compile independently and avoid changing existing untyped
behavior except for the additive primitive accessor methods.

## Validation Commands

Run focused tests while developing:

```bash
sbt core/test
sbt typed/test
sbt programs/test
```

Before merging the full feature:

```bash
sbt test
```

If macro diagnostics become substantial, add compile-time negative tests using
MUnit's `compileErrors` support so invalid user syntax is tested explicitly.

## Open Decisions

Resolve these during Phase 0 and Phase 1:

- Whether Kyo `Record` can be constructed directly from runtime prediction
  values with public APIs.
- Whether `kyo-schema` is published and stable enough to be the MVP decoder
  dependency.
- Whether to decode typed outputs from `Structure.Value`, JSON object strings,
  or a DSPy4S raw-value adapter into `kyo-schema`.
- Whether `TypedPrediction` should expose Kyo `Record[O]` directly or hide it
  behind a DSPy4S wrapper.
- Whether `programs` should depend on `typed`, or whether a later
  `typed-programs` module is worth the extra module complexity.
- Whether literal string unions should be included in the MVP or deferred in
  favor of Scala enums.
- The final names for `InputField`, `OutputField`, and `TypedSignature.Spec`.

The default answer for all open decisions should favor the smallest additive
API that can support the trait syntax and typed prediction field access.
