package dspy4s.prototype

import zio.blocks.schema.*

/** Throwaway experiment: can `zio-blocks-schema` model what `dspy4s.typed.Signature.of[Spec]` models today, end
  * to end?
  *
  * We pick the simplest realistic shape -- a spec with one input field, one output field, and an enum output --
  * and try to reproduce these pieces using zio-blocks Schema:
  *
  *   1. Derive a Schema for a case class (the "Spec" analogue).
  *   2. Walk the schema's `Reflect` to enumerate fields with their name + Scala-side type info (the analogue of
  *      our `FieldSpec` walk).
  *   3. Attach per-field metadata (the analogue of `FieldMetadata.EnumCases` + `EnumName`).
  *   4. Encode a Scala value to `DynamicValue` (the analogue of `Shape.encode` → `Map[String, Any]`).
  *   5. Decode `DynamicValue` back to the Scala value (the analogue of `Shape.decode`).
  *   6. Try to attach an Input/Output role -- our signature concept that's not built into Schema.
  *
  * Each block below is annotated with what the dspy4s equivalent does today and what we'd save / change. */
object Experiment:

  // ── 1. Mirror what Signature.of[Spec] produces today ───────────────

  // Today: a `Spec` trait with `InputField[String]` / `OutputField[Sentiment]` members; macro materializes a
  // named-tuple I/O pair and an underlying FieldSpec list.
  //
  // For the prototype we sidestep the named-tuple question (revisit) and pick the simplest analogue: one case
  // class for inputs, one for outputs. zio-blocks Schema definitely supports case-class derivation.

  enum Sentiment:
    case sadness, joy, love
  object Sentiment:
    given Schema[Sentiment] = Schema.derived

  case class ClassifyInput(sentence: String)
  object ClassifyInput:
    given Schema[ClassifyInput] = Schema.derived

  case class ClassifyOutput(sentiment: Sentiment)
  object ClassifyOutput:
    given Schema[ClassifyOutput] = Schema.derived

  // What we learn from these definitions:
  //   - `Schema.derived` covers the simple cases automatically (no per-field boilerplate).
  //   - Enums get a `Variant` reflect; case classes get a `Record` reflect.
  //   - The headline `derives kyo.Schema` we'd need in our typed surface translates straight across.

  // ── 2. Reach into Reflect to walk fields ───────────────────────────

  def listFields[A](using s: Schema[A]): Vector[(String, String)] =
    s.reflect match
      case rec: Reflect.Record[?, ?] =>
        rec.fields.toVector.map(term => term.name -> reflectKind(term.value))
      case other =>
        Vector.empty -> reflectKind(other) match { case (v, _) => v }

  private def reflectKind(r: Reflect[?, ?]): String = r match
    case _: Reflect.Primitive[?, ?]    => "primitive"
    case _: Reflect.Record[?, ?]       => "record"
    case _: Reflect.Variant[?, ?]      => "variant"
    case _: Reflect.Sequence[?, ?, ?]  => "sequence"
    case _: Reflect.Map[?, ?, ?, ?]    => "map"
    case _                             => "other"

  // What we learn: the structural walk is similar to kyo-schema's `Structure.Type.Product(...fields)` pattern
  // match. The shape we'd need:
  //   - Reflect.Record.fields: chunk of Term (name + nested Reflect + modifiers)
  //   - Reflect.Variant for sealed/enum, with case list
  //   - Reflect.Primitive for primitives (with a PrimitiveType we'd map to TypeRef.string / .int / .bool / etc.)
  // This is a direct replacement for our `KyoSchemaFieldCodec.fromStructure` walk.

  // ── 3. Attach per-field metadata via Modifier ──────────────────────

  // Today: `FieldMetadata.EnumCases` + `EnumName` are well-known string keys stuffed into `FieldSpec.metadata`.
  //
  // zio-blocks gives us `Modifier.config(key, value)` for exactly this purpose, with the convention
  // `<format>.<property>` (their docs example: `protobuf.field-id`). The dspy4s convention would naturally be
  // `dspy.enum.cases` etc.

  // To attach a Modifier to a field, you'd use Schema.aspect / Reflect.term.modifier APIs. Demonstrating that
  // properly needs more digging into how derivation surfaces them. The Modifier ADT is there and is the right
  // shape; the integration point is what we'd verify next.

  // ── 4 + 5. Round-trip through DynamicValue ─────────────────────────

  // Today: `Shape.encode(value)` → `Map[String, Any]`; adapter reads/writes that map.
  // With Schema: `schema.toDynamicValue(value)` → `DynamicValue.Record(...)`, decoded back via
  // `schema.fromDynamicValue(...)`.

  def roundtripInput(in: ClassifyInput): Either[String, ClassifyInput] =
    val schema = summon[Schema[ClassifyInput]]
    val dyn    = schema.toDynamicValue(in)
    schema.fromDynamicValue(dyn).left.map(_.toString)

  def roundtripOutput(out: ClassifyOutput): Either[String, ClassifyOutput] =
    val schema = summon[Schema[ClassifyOutput]]
    val dyn    = schema.toDynamicValue(out)
    schema.fromDynamicValue(dyn).left.map(_.toString)

  // What we learn: the round-trip is built in. We don't need our own `KyoSchemaFieldCodec` to translate between
  // structured values and the adapter intermediate -- Schema does this directly.
  //
  // The DynamicValue ADT (Primitive / Record / Variant / Sequence / Map / Null) is RICHER than our
  // `Map[String, Any]`. Adapters pattern-matching on `DynamicValue.Variant` vs `Record` can render the right
  // shape; today they have to inspect runtime classes via the metadata side-channel.

  // ── 6. Input/Output role -- the dspy4s-specific addition ───────────

  // zio-blocks Schema models a type as ONE record. Our "signature" is two records (inputs + outputs) glued
  // together. We have two natural options:
  //
  //   (a) Two Schemas: Signature[I, O] holds Schema[I] + Schema[O]. The "role" is implicit in which schema
  //       owns the field. This matches our current `inputShape: Shape[I], outputShape: Shape[O]` split.
  //
  //   (b) One Schema with role as a Modifier.config on each field. Useful only if we want a single combined
  //       type for serialization (e.g., dumpState that round-trips both sides at once).
  //
  // Option (a) maps cleanly to the existing API. Recommendation: keep the I/O split at the typed surface, use
  // Schema for each side's structural model.

  // ── Named-tuple probe ──────────────────────────────────────────────

  // Our Signature.of[Spec] macro materializes named-tuple I/O like
  //   NamedTuple.NamedTuple["sentence" *: EmptyTuple, String *: EmptyTuple]
  //
  // Question: does Schema.derived work on a named-tuple type? Below: a type-alias form and the structurally
  // equivalent NamedTuple type. The munit suite probes both via summon[Schema[T]].

  type SimpleInput = (sentence: String, lang: String)

  // Try with explicit derivation. If this compiles, the macro can target named tuples directly.
  given namedTupleSchema: Schema[SimpleInput] = Schema.derived

  // Optionally probe the spelled-out NamedTuple form too. Kept commented unless we want to test directly.
  // type ExpandedInput = NamedTuple.NamedTuple[("sentence" *: EmptyTuple), (String *: EmptyTuple)]
  // given Schema[ExpandedInput] = Schema.derived
  //
  //   - Coercive decode: `fromDynamicValue` is strict. LM output is fuzzy ("true" -> Boolean, "42" -> Int).
  //     Need to either normalize the DynamicValue before decode (cheap fix) or hook the format-codec layer.
  //
  //   - Default values: `Reflect.defaultValue` exists. Maps to our `FieldSpec.defaultValue` (used by adapters
  //     for demo rendering).
  //
  //   - Field descriptions: `Schema.doc` / `Reflect.doc` exist. Maps to `FieldSpec.description`.

  // ── Comparison summary ─────────────────────────────────────────────
  //
  //   Today               zio-blocks Schema
  //   ──────────────────  ──────────────────────────────────────────────
  //   Shape[A]            Schema[A] (wraps Reflect[A])
  //   FieldCodec[A]       built in: Schema covers primitives + collections + products + variants
  //   KyoProductShape     Schema.toDynamicValue + fromDynamicValue (built in, no glue)
  //   KyoSchemaFieldCodec Schema.toDynamicValue + Reflect walk (built in)
  //   FieldSpec.metadata  Modifier.config(key, value) with <format>.<property> naming
  //   FieldSpec.typeRef   Reflect.Primitive kind / TypeId
  //   FieldSpec.role      Custom -- two-schema split at the Signature[I, O] level
  //   JSON Schema gen     `JsonSchemaToReflect` / `JsonSchema` -- already in zio-blocks
  //   Map[String, Any]    DynamicValue (typed ADT, richer than Map[String, Any])
  //
  // Net assessment: we'd replace ~3 files in `dspy4s.typed` (Shape, FieldCodec, KyoSchemaFieldCodec) and
  // ~1 file's worth of metadata constants with zio-blocks API calls. The macro paths (`of[Spec]`, `fromType[F]`,
  // `from(method)`) would each become a thin wrapper that produces `Reflect.Record` + Modifiers instead of
  // hand-rolling FieldSpec lists.

end Experiment
