package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import scala.util.matching.Regex

/** Discriminator on [[FieldSpec]] that partitions a [[SignatureLayout]]'s fields into the values the LM is given
  * (`Input`) versus the values it must produce (`Output`). Adapters use this to drive prompt structure and to know
  * which keys to expect in the parsed response. */
enum FieldRole derives CanEqual:
  case Input
  case Output

/** Wire-format type tag for a field, surfaced to the LM via adapter prompts (e.g. `"answer: bool"`).
  *
  * This is the *adapter / prompt* type, not the Scala type -- the typed layer's `Shape[A]` carries the static
  * Scala-side encoding. A Scala enum, for instance, has Scala type `Sentiment` but [[TypeRef.string]] at the wire
  * level (the LM sees a flat string like `"joy"`).
  *
  * Five well-known refs cover the common cases. Anything outside that set passes through as an opaque token --
  * adapters that don't recognize it fall back to rendering it as a free-form string. */
final case class TypeRef(repr: String) derives CanEqual

object TypeRef:
  val string: TypeRef = TypeRef("string")
  val int: TypeRef    = TypeRef("int")
  val double: TypeRef = TypeRef("double")
  val bool: TypeRef   = TypeRef("bool")
  val json: TypeRef   = TypeRef("json")
  val list: TypeRef   = TypeRef("list")
  /** Sentinel for the output field that receives native provider tool calls (the analogue of Python dspy's
    * `ToolCalls` output annotation). An adapter with native function-calling enabled fills this field from the
    * provider's `tool_calls` instead of asking the model to emit it as text. See PORT_GAPS G-7b. */
  val toolCalls: TypeRef = TypeRef("tool_calls")

  /** Parse a string DSL type token (e.g. the `"bool"` in `"comment -> toxic: bool"`) into the matching well-known
    * [[TypeRef]]. Accepts a handful of synonyms (`"str"`, `"integer"`, `"float"`, `"number"`, `"dict"`, `"map"`)
    * and is case-insensitive. Unknown tokens become opaque `TypeRef(other)`. An empty / missing token defaults to
    * [[TypeRef.string]] (DSPy convention -- fields without a type annotation are strings). */
  def fromToken(token: String): TypeRef =
    token.trim.toLowerCase match
      case "" | "str" | "string"         => string
      case "int" | "integer"             => int
      case "float" | "double" | "number" => double
      case "bool" | "boolean"            => bool
      case "json" | "dict" | "map"       => json
      case "tool_calls" | "toolcalls"    => toolCalls
      case other                         => TypeRef(other)

/** Per-field metadata inside a [[SignatureLayout]]. Adapters consume this to render prompts and parse responses.
  *
  *   - [[name]] is the canonical field key used in input / output records.
  *   - [[role]] partitions the field into input vs output.
  *   - [[typeRef]] is the wire-format type the LM sees in the prompt -- see [[TypeRef]].
  *   - [[description]] is a per-field hint shown in adapter prompts (e.g. `"the question to answer"`). When
  *     `None`, [[FieldSpec.normalize]] defaults it to a placeholder like `"${question}"` so the prompt always
  *     names the slot.
  *   - [[prefix]] is the section header in adapter prompts (e.g. `"Question:"`); inferred from `name` by
  *     [[FieldSpec.inferPrefix]] when `None`.
  *   - [[defaultValue]] is the fallback value rendered into demos by Chat / JSON / XML adapters when a demo
  *     example omits this field. (Not used for live-call inputs.)
  *   - [[constraints]] are human-readable constraint hints (e.g. `"greater than: 0"`, `"maximum length: 10"`)
  *     surfaced after the field description in prose adapters. Build them with [[FieldConstraints]] so the text
  *     matches Python DSPy's `PYDANTIC_CONSTRAINT_MAP`. Empty by default; only emitted when non-empty.
  *
  * '''Constraint provenance (v1).''' Constraints are settable programmatically -- via this `FieldSpec` or
  * [[SignatureLayout.create]]. Deriving them automatically from the typed (`zio-blocks Schema`) surface is a
  * documented follow-up: the typed derivation has no constraint-annotation mechanism yet, so there is no path
  * from `Schema[A]` to these strings today.
  */
final case class FieldSpec(
    name: String,
    role: FieldRole,
    typeRef: TypeRef = TypeRef.string,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None,
    enumValues: Vector[String] = Vector.empty,
    constraints: Vector[Constraint] = Vector.empty
) derives CanEqual

/** A single field constraint. Carries BOTH the human-readable rendering prose adapters show after a field's
  * description (matching Python DSPy's `PYDANTIC_CONSTRAINT_MAP`, `dspy/signatures/field.py`, byte-for-byte) AND a
  * JSON-Schema keyword/value, so adapters that emit a schema can embed the constraint structurally (e.g.
  * `exclusiveMinimum`). Build these with [[FieldConstraints]]; they round-trip through layout state as `{op, value}`
  * records via [[Constraint.dumpState]] / [[Constraint.fromState]]. */
enum Constraint derives CanEqual:
  case Gt(value: Double)
  case Ge(value: Double)
  case Lt(value: Double)
  case Le(value: Double)
  case MinLength(value: Int)
  case MaxLength(value: Int)
  case MultipleOf(value: Double)

  /** The exact upstream prose hint, e.g. `"greater than: 0"`. Whole numbers render without a trailing `.0`. */
  def render: String = this match
    case Constraint.Gt(n)         => s"greater than: ${Constraint.num(n)}"
    case Constraint.Ge(n)         => s"greater than or equal to: ${Constraint.num(n)}"
    case Constraint.Lt(n)         => s"less than: ${Constraint.num(n)}"
    case Constraint.Le(n)         => s"less than or equal to: ${Constraint.num(n)}"
    case Constraint.MinLength(n)  => s"minimum length: $n"
    case Constraint.MaxLength(n)  => s"maximum length: $n"
    case Constraint.MultipleOf(n) => s"a multiple of the given number: ${Constraint.num(n)}"

  /** The JSON-Schema keyword this constraint maps to (for structural embedding by schema-emitting adapters). */
  def schemaKeyword: String = this match
    case _: Constraint.Gt         => "exclusiveMinimum"
    case _: Constraint.Ge         => "minimum"
    case _: Constraint.Lt         => "exclusiveMaximum"
    case _: Constraint.Le         => "maximum"
    case _: Constraint.MinLength  => "minLength"
    case _: Constraint.MaxLength  => "maxLength"
    case _: Constraint.MultipleOf => "multipleOf"

  /** The JSON-Schema value (a number) paired with [[schemaKeyword]]. */
  def schemaValue: DynamicValue = this match
    case Constraint.MinLength(n)  => DynamicValue.Primitive(PrimitiveValue.Int(n))
    case Constraint.MaxLength(n)  => DynamicValue.Primitive(PrimitiveValue.Int(n))
    case Constraint.Gt(n)         => Constraint.numValue(n)
    case Constraint.Ge(n)         => Constraint.numValue(n)
    case Constraint.Lt(n)         => Constraint.numValue(n)
    case Constraint.Le(n)         => Constraint.numValue(n)
    case Constraint.MultipleOf(n) => Constraint.numValue(n)

  /** Persisted form: a `{op, value}` record (used by [[SignatureLayout.dumpState]]). */
  def dumpState: DynamicValue.Record =
    DynamicValue.Record(Chunk(
      "op"    -> DynamicValue.Primitive(PrimitiveValue.String(op)),
      "value" -> Constraint.numValue(numericValue)
    ))

  private def op: String = this match
    case _: Constraint.Gt         => "gt"
    case _: Constraint.Ge         => "ge"
    case _: Constraint.Lt         => "lt"
    case _: Constraint.Le         => "le"
    case _: Constraint.MinLength  => "min_length"
    case _: Constraint.MaxLength  => "max_length"
    case _: Constraint.MultipleOf => "multiple_of"

  private def numericValue: Double = this match
    case Constraint.Gt(n)         => n
    case Constraint.Ge(n)         => n
    case Constraint.Lt(n)         => n
    case Constraint.Le(n)         => n
    case Constraint.MinLength(n)  => n.toDouble
    case Constraint.MaxLength(n)  => n.toDouble
    case Constraint.MultipleOf(n) => n

object Constraint:
  /** Render a numeric bound: drop the `.0` for whole numbers, keep the fractional part otherwise. */
  private def num(n: Double): String =
    if n == Math.rint(n) && !n.isInfinite then n.toLong.toString else n.toString

  /** A DynamicValue number: a `Long` for whole values (so JSON shows `0`, not `0.0`), else a `Double`. */
  private def numValue(n: Double): DynamicValue =
    if n == Math.rint(n) && !n.isInfinite then DynamicValue.Primitive(PrimitiveValue.Long(n.toLong))
    else DynamicValue.Primitive(PrimitiveValue.Double(n))

  /** Rebuild from the persisted `{op, value}` record; `None` for an unrecognized op (forward-compat / corruption). */
  def fromState(rec: DynamicValue.Record): Option[Constraint] =
    for
      op    <- DynamicValues.recordGet(rec, "op").collect { case DynamicValue.Primitive(PrimitiveValue.String(s)) => s }
      value <- DynamicValues.recordGet(rec, "value").flatMap(numberOf)
      constraint <- op match
        case "gt"          => Some(Gt(value))
        case "ge"          => Some(Ge(value))
        case "lt"          => Some(Lt(value))
        case "le"          => Some(Le(value))
        case "min_length"  => Some(MinLength(value.toInt))
        case "max_length"  => Some(MaxLength(value.toInt))
        case "multiple_of" => Some(MultipleOf(value))
        case _             => None
    yield constraint

  private def numberOf(dv: DynamicValue): Option[Double] = dv match
    case DynamicValue.Primitive(PrimitiveValue.Long(n))   => Some(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Int(n))    => Some(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Double(n)) => Some(n)
    case _                                                => None

/** Builders for field [[Constraint]]s. Mirrors Python DSPy's `PYDANTIC_CONSTRAINT_MAP` (`dspy/signatures/field.py`)
  * so dspy4s prompts match upstream byte-for-byte: `gt(0).render == "greater than: 0"`, etc. Numeric helpers accept
  * `Double` (integral or fractional bounds); length helpers take `Int`. */
object FieldConstraints:
  def gt(n: Double): Constraint         = Constraint.Gt(n)
  def ge(n: Double): Constraint         = Constraint.Ge(n)
  def lt(n: Double): Constraint         = Constraint.Lt(n)
  def le(n: Double): Constraint         = Constraint.Le(n)
  def minLength(n: Int): Constraint     = Constraint.MinLength(n)
  def maxLength(n: Int): Constraint     = Constraint.MaxLength(n)
  def multipleOf(n: Double): Constraint = Constraint.MultipleOf(n)

/** Partial update DTO for the field-mutation surface on [[SignatureLayout]]. Each `Option` field that's `Some`
  * overwrites the corresponding [[FieldSpec]] property; metadata is merged additively.
  *
  * `typeToken` is a string alternative to `typeRef`: callers that have the DSL type word (`"bool"`, `"int"`, ...)
  * can pass it and the runtime resolves via [[TypeRef.fromToken]]. If both are set, `typeRef` wins.
  *
  * Public mostly as a transitional artifact -- the consumer
  * (`SignatureLayout.withUpdatedFields`) is `private[dspy4s]`, so user code currently has no path to call it. */
final case class FieldUpdate(
    typeRef: Option[TypeRef] = None,
    typeToken: Option[String] = None,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None
):
  def resolvedTypeRef: Option[TypeRef] =
    typeRef.orElse(typeToken.map(TypeRef.fromToken))

object FieldSpec:
  private val identifierPattern: Regex = raw"[A-Za-z_][A-Za-z0-9_]*".r

  /** True if `name` is a valid Scala-style identifier (alphanumeric + underscore, must start with letter or
    * underscore). Adapters require this -- field names appear as keys in `Map[String, Any]` payloads and as
    * named-tuple labels in the typed layer, so non-identifier names would break the typed surface. */
  def validateName(name: String): Boolean = identifierPattern.matches(name)

  /** Convert a camelCase or snake_case field name into a human-readable prompt label (e.g. `"scoreValue"` →
    * `"Score Value"`). Used by [[normalize]] to default the [[FieldSpec.prefix]] when one isn't explicitly set.
    * Handles letter-digit boundaries (`"v2"` → `"V 2"`) and preserves all-caps acronyms unchanged. */
  def inferPrefix(name: String): String =
    val step1 = name.replaceAll("(.)([A-Z][a-z]+)", "$1_$2")
    val step2 = step1.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
    val step3 = step2
      .replaceAll("([A-Za-z])(\\d)", "$1_$2")
      .replaceAll("(\\d)([A-Za-z])", "$1_$2")
    step3
      .split("_")
      .filter(_.nonEmpty)
      .map { token =>
        if token.forall(_.isUpper) then token
        else s"${token.head.toUpper}${token.drop(1).toLowerCase}"
      }
      .mkString(" ")

  /** Fill in adapter-friendly defaults for a [[FieldSpec]]: if `prefix` is `None`, derive one from `name` via
    * [[inferPrefix]] and append `":"`; if `description` is `None`, default to a `${name}` placeholder. Existing
    * values are preserved -- this only ever adds.
    *
    * Applied to every field by [[SignatureLayout.create]], so adapters always see uniform prefix / description
    * surfaces regardless of which factory built the layout. */
  def normalize(field: FieldSpec): FieldSpec =
    val inferredPrefix = inferPrefix(field.name) + ":"
    field.copy(
      prefix = field.prefix.orElse(Some(inferredPrefix)),
      description = field.description.orElse(Some(s"$${${field.name}}"))
    )

/** The compiled runtime layout of a typed Signature: a name, optional instructions, and an ordered list of
  * [[FieldSpec]]s. Adapters, programs, and the rest of the runtime stack consume this directly; the typed
  * `Signature[I, O]` (in `dspy4s.typed`) is the user-facing wrapper around it.
  *
  * '''Construction paths.'''
  *
  *   - Typed surface (`dspy4s.typed.Signature.of[T]`, `Signature.fromType[F]`, `Signature.derived[I, O]`) -- the
  *     primary path; the resulting `Signature.layout` is the value adapters see.
  *   - [[SignatureLayout.create]] -- validating + normalizing factory for programmatic construction.
  *   - [[SignatureLayout.parse]] -- string-DSL parser escape hatch; prefer `dspy4s.typed.Signature.fromString`
  *     from user code.
  *   - [[SignatureLayout.fromState]] -- re-hydrate from the `DynamicValue.Record` produced by [[dumpState]]
  *     (or from JSON via [[SignatureLayout.fromJson]]).
  *
  * The primary constructor is `private`: every layout comes from one of the paths above. This keeps name
  * uniqueness closed by construction (see Invariants) rather than relying on a runtime precondition.
  *
  * '''Field mutation.''' The `append` / `prepend` / `insert` / `delete` / `withFields` /
  * `withUpdatedField*` methods are `private[dspy4s]`. They exist because composite programs (`ChainOfThought`,
  * `CodeAct`, `MultiChainComparison`, `ProgramOfThought`) need to augment a base layout with auxiliary fields
  * (e.g. prepending a `reasoning` output) before handing it to a `DynamicPredict`. User code should mutate at the
  * typed `Signature` surface (use a different `Spec` trait, a different `Signature.derived[I, O]`, etc.) rather
  * than reaching into the layout directly.
  *
  * '''Invariants.''' Name uniqueness is maintained by construction, not by a precondition: the primary
  * constructor is `private`, so every layout comes from [[SignatureLayout.create]] (which rejects duplicate
  * names) or from a mutator, and every mutator routes through [[withFields]], which dedups by name. A built
  * layout therefore always has unique field names; adapters can rely on that without re-checking. The
  * constructor still requires a non-empty `name` and identifier-shaped field names.
  */
final case class SignatureLayout private (
    name: String,
    fields: Vector[FieldSpec],
    instructions: Option[String]
):
  require(name.nonEmpty, "SignatureLayout name cannot be empty")
  require(
    fields.forall(f => FieldSpec.validateName(f.name)),
    "SignatureLayout fields must be valid identifiers"
  )

  // ── Stable public accessors / settings ──────────────────────────────

  /** All input-role fields in declaration order. */
  def inputFields: Vector[FieldSpec]  = fields.filter(_.role == FieldRole.Input)

  /** All output-role fields in declaration order. */
  def outputFields: Vector[FieldSpec] = fields.filter(_.role == FieldRole.Output)

  /** Replace signature-level instructions. */
  def withInstructions(text: Option[String]): SignatureLayout = copy(instructions = text)

  /** Same as the `Option` overload, but treats the empty string as "no change" so callers can pass
    * `withInstructions("")` to leave instructions intact. */
  def withInstructions(text: String): SignatureLayout =
    if text.isEmpty then this else withInstructions(Some(text))

  // ── Field-mutation helpers ──────────────────────────────────────────
  // Narrowed to `private[dspy4s]`: composite programs (CodeAct,
  // MultiChainComparison, ProgramOfThought, ChainOfThought)
  // augment a base layout by appending / prepending / inserting fields
  // before handing it to a `DynamicPredict`. User code should use the
  // typed `Signature` surface (`derived`, `fromType`, `of[Spec]`,
  // `builder`, `fromString`) instead of mutating layouts directly.

  // The single chokepoint every field mutator routes through: dedup by name (keep first), so a duplicate is
  // impossible to introduce, which is what lets the unique-name precondition be retired. In practice the
  // callers never pass a duplicate, so this is a structural guarantee rather than an observable change.
  private[dspy4s] def withFields(updated: Vector[FieldSpec]): SignatureLayout =
    copy(fields = updated.distinctBy(_.name))

  private[dspy4s] def append(field: FieldSpec): SignatureLayout = withFields(fields :+ field)

  private[dspy4s] def insert(index: Int, field: FieldSpec): Either[DspyError, SignatureLayout] =
    val (inputs, outputs) = (inputFields, outputFields)
    val target = if field.role == FieldRole.Input then inputs else outputs
    val normalizedIndex = if index < 0 then target.size + index + 1 else index
    if normalizedIndex < 0 || normalizedIndex > target.size then
      Left(
        ValidationError(
          s"Invalid index $index for ${field.role} fields of size ${target.size}"
        )
      )
    else
      val updatedTarget = target.patch(normalizedIndex, Vector(field), 0)
      val updatedFields =
        if field.role == FieldRole.Input then updatedTarget ++ outputs
        else inputs ++ updatedTarget
      Right(withFields(updatedFields))

  private[dspy4s] def prepend(field: FieldSpec): SignatureLayout =
    val sameRole = fields.filter(_.role == field.role)
    val otherRole = fields.filterNot(_.role == field.role)
    field.role match
      case FieldRole.Input  => withFields((field +: sameRole) ++ otherRole)
      case FieldRole.Output => withFields(otherRole ++ (field +: sameRole))

  private[dspy4s] def delete(fieldName: String): SignatureLayout =
    withFields(fields.filterNot(_.name == fieldName))

  private[dspy4s] def withUpdatedField(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None
  ): Either[DspyError, SignatureLayout] =
    fields.find(_.name == fieldName) match
      case None =>
        Left(NotFoundError("field", s"Field '$fieldName' does not exist in signature '$name'"))
      case Some(existing) =>
        val updated = existing.copy(
          typeRef = typeRef.getOrElse(existing.typeRef),
          description = description.orElse(existing.description),
          prefix = prefix.orElse(existing.prefix),
          defaultValue = defaultValue.orElse(existing.defaultValue)
        )
        Right(withFields(fields.map { field => if field.name == fieldName then updated else field }))

  private[dspy4s] def withUpdatedFields(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      typeToken: Option[String] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None
  ): Either[DspyError, SignatureLayout] =
    withUpdatedField(
      fieldName = fieldName,
      typeRef = typeRef.orElse(typeToken.map(TypeRef.fromToken)),
      description = description,
      prefix = prefix,
      defaultValue = defaultValue
    )

  private[dspy4s] def withUpdatedFields(updates: (String, FieldUpdate)*): Either[DspyError, SignatureLayout] =
    updates.foldLeft[Either[DspyError, SignatureLayout]](Right(this)) { (acc, entry) =>
      val (fieldName, update) = entry
      acc.flatMap(
        _.withUpdatedFields(
          fieldName = fieldName,
          typeRef = update.typeRef,
          typeToken = update.typeToken,
          description = update.description,
          prefix = update.prefix,
          defaultValue = update.defaultValue
        )
      )
    }

  // ── Read helpers ────────────────────────────────────────────────────

  /** Render the DSPy-style string DSL for this layout (e.g. `"comment, lang -> toxic, confidence"`). Inverse of
    * [[SignatureLayout.parse]] for the shape only -- types / instructions / metadata are dropped. */
  def signatureString: String =
    val inputs = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

  /** Equality that ignores the [[name]]. Useful for comparing two layouts that describe the same shape but were
    * constructed with different anonymous names (e.g. `"Signature"` from `fromType` vs `"X"` from a builder). */
  def equalsByStructure(other: SignatureLayout): Boolean =
    instructions == other.instructions && fields.sameElements(other.fields)

  /** Serialize to a [[zio.blocks.schema.DynamicValue.Record]] -- the same codec-spine type carried everywhere
    * else in dspy4s. Round-trips with [[SignatureLayout.fromState]] and serializes to clean JSON via [[dumpJson]].
    * `Option` fields (`instructions`, and per-field `description` / `prefix` / `defaultValue`) encode as
    * `DynamicValue.Null` when empty. This is the building block of the (not-yet-wired) save/load story -- a
    * deliberately non-Python-pickle path (see PORT_DIFFERENCES). */
  def dumpState: DynamicValue.Record =
    def str(s: String): DynamicValue        = DynamicValue.Primitive(PrimitiveValue.String(s))
    def opt(o: Option[Any]): DynamicValue   = o.fold(DynamicValue.Null: DynamicValue)(DynamicValues.fromAny)
    val fieldRecords: Seq[DynamicValue] = fields.map { field =>
      DynamicValue.Record(Chunk.from(Seq(
        "name"         -> str(field.name),
        "role"         -> str(field.role.toString),
        "typeRef"      -> str(field.typeRef.repr),
        "description"  -> opt(field.description),
        "prefix"       -> opt(field.prefix),
        "defaultValue" -> opt(field.defaultValue),
        "constraints"  -> DynamicValue.Sequence(Chunk.from(field.constraints.map(c => c.dumpState: DynamicValue)))
      )))
    }
    DynamicValue.Record(Chunk.from(Seq(
      "name"         -> str(name),
      "instructions" -> opt(instructions),
      "fields"       -> DynamicValue.Sequence(Chunk.from(fieldRecords))
    )))

  /** Serialize the state to a JSON string via zio-blocks' `DynamicValue` JSON codec. Round-trips with
    * [[SignatureLayout.fromJson]]. */
  def dumpJson: String =
    new String(SignatureLayout.dynamicJsonCodec.encode(dumpState), java.nio.charset.StandardCharsets.UTF_8)

object SignatureLayout:

  /** JSON codec for the `DynamicValue`-shaped state, backed by zio-blocks' schema for `DynamicValue`. Encodes
    * `dumpState` to clean, natural JSON (records → objects, `Null` → `null`). */
  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a DSPy-style string DSL (`"in1, in2 -> out1"`) into a `SignatureLayout`. Prefer
    * `dspy4s.typed.Signature.fromString` from user code; this is the lower-level entry point that the typed
    * surface delegates to. */
  def parse(
      dsl: String,
      instructions: String = ""
  ): Either[DspyError, SignatureLayout] =
    dspy4s.core.signatures.SignatureDsl
      .parse(dsl)
      .map(layout =>
        if instructions.isEmpty then layout
        else layout.withInstructions(instructions)
      )

  /** Validating + normalizing factory. Returns `Left` with a structured `DspyError` when validation fails (empty
    * name, no fields, duplicate names, invalid identifiers); on success, applies `FieldSpec.normalize` to each
    * field so adapters see consistent prefixes / descriptions. */
  def create(
      name: String,
      fields: Vector[FieldSpec],
      instructions: Option[String] = None
  ): Either[DspyError, SignatureLayout] =
    if name.trim.isEmpty then Left(ValidationError("SignatureLayout name cannot be empty"))
    else if fields.isEmpty then Left(ValidationError("SignatureLayout must have at least one field"))
    else if fields.map(_.name).distinct.size != fields.size then
      Left(ValidationError("SignatureLayout fields must have unique names"))
    else if fields.exists(f => !FieldSpec.validateName(f.name)) then
      Left(ValidationError("SignatureLayout fields must be valid identifiers"))
    else
      val normalized = fields.map(FieldSpec.normalize)
      Right(SignatureLayout(name = name, fields = normalized, instructions = instructions))

  /** Trusted internal construction WITHOUT normalization: framework code builds a signature from known-good,
    * already-shaped fields and wants a `SignatureLayout` directly (not an `Either`). The (private) constructor
    * still enforces a non-empty `name` and identifier-shaped field names; field-name uniqueness is the
    * caller's responsibility here (pass already-distinct fields; use [[create]] to validate arbitrary input).
    * Replaces the former public case-class apply for the framework's internal call sites. */
  private[dspy4s] def of(name: String, fields: Vector[FieldSpec], instructions: Option[String]): SignatureLayout =
    SignatureLayout(name, fields, instructions)

  /** Re-hydrate a layout from the `DynamicValue.Record` produced by [[SignatureLayout.dumpState]]. The inverse
    * of the save/load serialization primitive; no production code wires this into a save/load feature yet. */
  def fromState(state: DynamicValue.Record): Either[DspyError, SignatureLayout] =
    def getString(rec: DynamicValue.Record, key: String): Option[String] =
      DynamicValues.recordGet(rec, key) match
        case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Some(s)
        case _                                                      => None

    def readName: Either[DspyError, String] =
      getString(state, "name")
        .filter(_.nonEmpty)
        .toRight(ValidationError("SignatureLayout state is missing non-empty 'name'"))

    def readInstructions: Either[DspyError, Option[String]] =
      DynamicValues.recordGet(state, "instructions") match
        case None | Some(_: DynamicValue.Null.type)                 => Right(None)
        case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(Some(s))
        case Some(_) => Left(ValidationError("Invalid 'instructions' value in signature state"))

    def parseRole(role: String): Either[DspyError, FieldRole] =
      role.trim.toLowerCase match
        case "input"  => Right(FieldRole.Input)
        case "output" => Right(FieldRole.Output)
        case _        => Left(ValidationError(s"Invalid field role '$role' in signature state"))

    def readField(raw: DynamicValue): Either[DspyError, FieldSpec] =
      raw match
        case rec: DynamicValue.Record =>
          for
            name    <- getString(rec, "name").toRight(ValidationError("Field state is missing 'name'"))
            roleStr <- getString(rec, "role").toRight(ValidationError(s"Field '$name' is missing role"))
            role    <- parseRole(roleStr)
          yield
            val typeRef = getString(rec, "typeRef").map(TypeRef.fromToken).getOrElse(TypeRef.string)
            val defaultValue = DynamicValues.recordGet(rec, "defaultValue") match
              case None | Some(_: DynamicValue.Null.type) => None
              case Some(dv)                                => Some(DynamicValues.toAny(dv))
            val constraints = DynamicValues.recordGet(rec, "constraints") match
              case Some(seq: DynamicValue.Sequence) =>
                seq.elements.iterator.collect { case r: DynamicValue.Record => r }.flatMap(Constraint.fromState).toVector
              case _ => Vector.empty[Constraint]
            FieldSpec(
              name         = name,
              role         = role,
              typeRef      = typeRef,
              description  = getString(rec, "description"),
              prefix       = getString(rec, "prefix"),
              defaultValue = defaultValue,
              constraints  = constraints
            )
        case _ => Left(ValidationError("Invalid field entry in signature state"))

    def readFields: Either[DspyError, Vector[FieldSpec]] =
      DynamicValues.recordGet(state, "fields") match
        case Some(seq: DynamicValue.Sequence) =>
          seq.elements.iterator.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) { (acc, raw) =>
            for
              fields <- acc
              field  <- readField(raw)
            yield fields :+ field
          }
        case _ => Left(ValidationError("SignatureLayout state is missing 'fields'"))

    for
      name         <- readName
      instructions <- readInstructions
      fields       <- readFields
      signature    <- create(name = name, fields = fields, instructions = instructions)
    yield signature

  /** Re-hydrate a layout from a JSON string produced by [[SignatureLayout.dumpJson]]. */
  def fromJson(json: String): Either[DspyError, SignatureLayout] =
    dynamicJsonCodec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)) match
      case Right(rec: DynamicValue.Record) => fromState(rec)
      case Right(other) => Left(ValidationError(s"Expected a JSON object for signature state, got: $other"))
      case Left(err)    => Left(ValidationError(s"Invalid signature-state JSON: ${err.toString}"))
