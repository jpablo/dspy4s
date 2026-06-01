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
  */
final case class FieldSpec(
    name: String,
    role: FieldRole,
    typeRef: TypeRef = TypeRef.string,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None
) derives CanEqual

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
  * The case-class `apply(name, fields, instructions)` form is also available but skips normalization -- use only
  * from internal code that builds the field list deliberately.
  *
  * '''Field mutation.''' The `append` / `prepend` / `insert` / `delete` / `withFields` /
  * `withUpdatedField*` methods are `private[dspy4s]`. They exist because composite programs (`ChainOfThought`,
  * `CodeAct`, `MultiChainComparison`, `ProgramOfThought`) need to augment a base layout with auxiliary fields
  * (e.g. prepending a `reasoning` output) before handing it to a `DynamicPredict`. User code should mutate at the
  * typed `Signature` surface (use a different `Spec` trait, a different `Signature.derived[I, O]`, etc.) rather
  * than reaching into the layout directly.
  *
  * '''Invariants.''' The `require` block at construction guarantees that a built layout has a non-empty `name`,
  * unique field names, and identifier-shaped field names. Adapters can rely on those without re-checking.
  */
final case class SignatureLayout(
    name: String,
    fields: Vector[FieldSpec],
    instructions: Option[String] = None
):
  require(name.nonEmpty, "SignatureLayout name cannot be empty")
  require(
    fields.map(_.name).distinct.size == fields.size,
    "SignatureLayout fields must have unique names"
  )
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

  private[dspy4s] def withFields(updated: Vector[FieldSpec]): SignatureLayout = copy(fields = updated)

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
        "defaultValue" -> opt(field.defaultValue)
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
            FieldSpec(
              name         = name,
              role         = role,
              typeRef      = typeRef,
              description  = getString(rec, "description"),
              prefix       = getString(rec, "prefix"),
              defaultValue = defaultValue
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
