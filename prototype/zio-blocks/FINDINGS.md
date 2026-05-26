# zio-blocks Schema prototype — findings

> Throwaway experiment: can `zio-blocks-schema` 0.0.40 replace `kyo-schema` + our
> `Shape` / `FieldCodec` / `KyoSchemaFieldCodec` glue?
>
> Reproduce in this directory: `sbt prototypeZioBlocks/test`. See
> [`src/main/scala/dspy4s/prototype/Experiment.scala`](src/main/scala/dspy4s/prototype/Experiment.scala).

## TL;DR

**Yes, it fits.** zio-blocks Schema covers the same surface our hand-rolled stack covers, plus a few things we'd
otherwise build ourselves (typed intermediate, disciplined per-field metadata, JSON-schema generation).

**Major win:** `DynamicValue` is a typed ADT (`Primitive` / `Record` / `Variant` / `Sequence` / `Map` / `Null`)
instead of our erased `Map[String, Any]`. Adapters could pattern-match on the shape they're rendering instead of
inspecting runtime classes via metadata side-channels.

**Main risk:** zio-blocks is "Development" stage (versions 0.0.x, latest 0.0.40 from 2026-05-19). API churn is
plausible.

## What works out of the box (verified)

5/5 tests pass against zio-blocks-schema 0.0.40 + Scala 3.8.1:

| Concern | Verified |
|---|---|
| `Schema.derived` for case class | ✅ One-line per type |
| `Schema.derived` for Scala enum | ✅ Produces a `Reflect.Variant` |
| Walk fields of a record | ✅ `Reflect.Record.fields` gives Chunk of `Term(name, nested Reflect, modifiers)` |
| Round-trip case class through `DynamicValue` | ✅ `schema.toDynamicValue` / `fromDynamicValue` |
| Round-trip enum through `DynamicValue` | ✅ `DynamicValue.Variant(caseName = Some("joy"))` |
| Field-level metadata mechanism exists | ✅ `Modifier.config(key, value)` with `<format>.<property>` naming |

## Where each dspy4s concept maps

| dspy4s today | zio-blocks Schema equivalent |
|---|---|
| `Shape[A]` | `Schema[A]` (wraps `Reflect[A]`) |
| `FieldCodec[A]` | Built in: Schema covers primitives + collections + products + variants |
| `KyoProductShape` | `Schema.toDynamicValue` + `fromDynamicValue` (built in, no glue) |
| `KyoSchemaFieldCodec.fromStructure` | Walk `Reflect.Record.fields` (built in) |
| `KyoSchemaFieldCodec.toStructure` | `DynamicValue` constructors (built in) |
| `FieldMetadata.EnumCases` / `EnumName` | `Modifier.config("dspy.enum.cases", "...")` |
| `FieldSpec.typeRef` | `Reflect.Primitive.primitiveType` (`PrimitiveType.String` etc.) |
| `FieldSpec.description` | `Schema.doc` / `Reflect.doc` |
| `FieldSpec.defaultValue` | `Schema.defaultValue` / `Reflect.defaultValue` |
| `FieldSpec.role` (Input/Output) | **Custom** -- not in Schema; either two-schema split at `Signature[I, O]` level, or `Modifier.config("dspy.role", "input"|"output")` |
| JSONAdapter's hand-written JSON schema | `JsonSchema` (built in; `JsonSchemaToReflect` for the reverse) |
| `Map[String, Any]` adapter intermediate | `DynamicValue` ADT |
| `SignatureLayout.dumpState` / `fromState` | `Schema.toDynamicValue` round-trip |

## Where each dspy4s component would change

| Today | After migration |
|---|---|
| `modules/typed/Shape.scala` (3 impls + companion) | Delete; replaced by `Schema[A]` directly |
| `modules/typed/FieldCodec.scala` (primitives + FlatEnum) | Delete; replaced by `Schema[A]` for primitives + auto-derived `Schema[Enum]` |
| `modules/typed/KyoSchemaFieldCodec.scala` | Delete; `Schema.toDynamicValue` replaces both directions |
| `modules/typed/Signature.scala` (4 macro paths) | Rewrite to produce `Schema[I]` + `Schema[O]` from their respective input shapes |
| `modules/typed/SignatureBuilder.scala` | Rewrite to build `Reflect` records programmatically |
| `modules/typed/Spec.scala` (InputField / OutputField opaque types) | Likely keep -- the Spec trait is dspy4s-specific |
| `modules/core/contracts/SignatureLayout.scala` | Shrink: `FieldSpec` becomes a thin view over a `Reflect.Term`; metadata uses `Modifier.config` |
| `modules/core/contracts/SignatureLayout.scala` (`FieldMetadata`) | Delete; replaced by `Modifier.config` |
| `modules/adapters/{Chat,JSON,XML}Adapter.scala` | Switch from walking `FieldSpec` to walking `Reflect`; `JSONAdapter` gains free JSON-schema generation |
| `modules/programs/runtime/PredictEngine.scala` | Switch from `Map[String, Any]` to `DynamicValue` (or keep `Map` as the wire intermediate, convert at the typed boundary) |

## Open questions worth answering before committing

1. **Named tuples.** Our `Spec` macro emits named-tuple I/O for `Signature.of[Spec]`. Does
   `Schema.derived[NamedTupleType]` work? Not tested in the prototype. If not:
   - Option A: restrict the macro to case-class I/O (lose ergonomics).
   - Option B: hand-write a `Reflect.Record` for the named tuple.
   - Option C: defer named tuples until upstream support lands.

2. **Coercive decoding.** `Schema.fromDynamicValue` is strict. LM output is fuzzy (`"true"` → `Boolean`,
   `"42"` → `Int`). The cleanest fix is to normalize the `DynamicValue` before decode -- a small helper that
   walks the tree, sees a `Primitive.String("true")` where `Reflect` expects `PrimitiveType.Boolean`, coerces.
   ~50 lines.

3. **Input/Output role.** The simpler choice is two Schemas in `Signature[I, O]` (matches current
   `inputShape: Shape[I]` / `outputShape: Shape[O]` split). The richer choice is one Schema with role as a
   `Modifier.config`. Recommendation: **two schemas** -- preserves the existing API shape, avoids a one-off
   `dspy.role` modifier.

4. **Coverage of all current test cases.** The current `Shape` test suite has ~40 cases covering primitives,
   enums (flat-encoded), nested case classes, `Option[A]`, `List[A]`, `Map[K, V]`, mixed nesting. A full
   migration would re-port each. Probably half a session for the test re-port alone.

5. **API stability.** zio-blocks is at 0.0.40 (versions 0.0.x). The Schema, Reflect, DynamicValue ADT, and
   Modifier surfaces have been around for a while but are not API-stable. We'd need to be ready to chase
   breaking changes during 0.0.x cycle. kyo at RC2 is similarly pre-1.0 but closer to API-stable.

6. **`FieldUpdate`-style targeted edits.** Today we have `private[dspy4s]` mutation helpers on `SignatureLayout`
   for composite programs (CodeAct etc.) that augment a base layout with extra fields. zio-blocks `Reflect`
   instances are immutable and there's no straightforward "add a field" API on a derived Schema. We'd either
   build helpers (a `RebindTransformer` and `ReflectTransformer` exist in the codebase, may help) or restructure
   how composites augment.

## Recommendation

**Go ahead with the full migration.** The prototype confirms the fit is real, the line-count savings are
substantial (3 typed files + 1 metadata file + JSON-schema generator → all replaced by library), and the typed
`DynamicValue` intermediate is a UX improvement adapters will feel.

**Caveats to surface before starting:**
- We're trading kyo (RC2, more stable) for zio-blocks (0.0.x, less stable). Acceptable for a pre-release
  project, painful for a published one.
- Composite-program field augmentation (the `private[dspy4s]` mutation helpers) needs a deliberate redesign
  during the migration -- it's the one piece that doesn't have a direct Schema equivalent.

**Suggested migration order:**
1. Replace `KyoSchemaFieldCodec` with `Schema.toDynamicValue` round-trip. Keep `Map[String, Any]` as the wire
   format at the boundary, convert to/from DynamicValue at the typed surface only.
2. Replace `Shape` impls with `Schema[A]` references. `FieldCodec` melts away because Schema covers primitives.
3. Replace `FieldMetadata` string constants with `Modifier.config("dspy.X.Y", value)` keys.
4. Replace `JSONAdapter`'s hand-rolled JSON schema with `JsonSchema` generation.
5. Optional: replace `Map[String, Any]` adapter intermediate with `DynamicValue` end-to-end.

Steps 1-4 are independent and can land in separate commits. Step 5 is the bigger payoff but touches every
adapter.

## Source

- `src/main/scala/dspy4s/prototype/Experiment.scala` — the exploration with inline commentary
- `src/test/scala/dspy4s/prototype/ExperimentSuite.scala` — 5 tests confirming the building blocks work

Run: `sbt prototypeZioBlocks/test`
