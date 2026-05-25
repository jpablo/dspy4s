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
