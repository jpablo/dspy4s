package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** The compiled runtime layout of a typed Signature: a name, optional instructions, and separate ordered input and
  * output cohorts. Adapters, programs, and the rest of the runtime stack consume this directly; the typed
  * `Signature[I, O]` (in `dspy4s.signatures`) is the user-facing wrapper around it.
  *
  * '''Construction paths.'''
  *
  *   - Typed surface (`dspy4s.signatures.Signature.of[T]`, `Signature.fromType[F]`, `Signature.derived[I, O]`) -- the
  *     primary path; the resulting `Signature.layout` is the value adapters see.
  *   - [[SignatureLayout.create]] -- validating + normalizing factory for programmatic construction.
  *   - [[SignatureLayout.parse]] -- string-DSL parser escape hatch; prefer `dspy4s.signatures.Signature.fromString`
  *     from user code.
  *   - [[SignatureLayout.fromState]] -- re-hydrate from the `DynamicValue.Record` produced by [[dumpState]] (or from
  *     JSON via [[SignatureLayout.fromJson]]).
  *
  * The primary constructor is `private`: every layout comes from one of the paths above. This keeps name uniqueness
  * closed by construction (see Invariants) rather than relying on a runtime precondition.
  *
  * '''Field mutation.''' The `withInputFields` / `withOutputFields` methods are `private[dspy4s]`. They exist because
  * composite programs (`ChainOfThought`, `CodeAct`, `MultiChainComparison`, `ProgramOfThought`) need to augment a base
  * layout with auxiliary fields (e.g. prepending a `reasoning` output) before handing it to a `DynamicPredict`. User
  * code should mutate at the typed `Signature` surface (use a different `Spec` trait, a different
  * `Signature.derived[I, O]`, etc.) rather than reaching into the layout directly.
  *
  * '''Invariants.''' A field's role is represented exactly once by cohort membership. Name uniqueness across both
  * cohorts is maintained by construction, not by a precondition: the primary constructor is `private`, so every layout
  * comes from [[SignatureLayout.create]] (which rejects duplicate names) or from a cohort mutator, which dedups by name
  * and resolves cross-cohort collisions in favor of inputs. A built layout therefore always has unique field names;
  * adapters can rely on that without re-checking. The constructor still requires a non-empty `name` and
  * identifier-shaped field names.
  */
final case class SignatureLayout private (
    name        : String,
    inputFields : Vector[FieldSpec],
    outputFields: Vector[FieldSpec],
    instructions: Option[String]
):
  require(name.nonEmpty, "SignatureLayout name cannot be empty")
  require(
    fields.forall(f => FieldSpec.validateName(f.name)),
    "SignatureLayout fields must be valid identifiers"
  )

  // ── Stable public accessors / settings ──────────────────────────────

  /** Compatibility view in canonical cohort order: all inputs followed by all outputs. */
  def fields: Vector[FieldSpec] = inputFields ++ outputFields

  /** Replace signature-level instructions. */
  def withInstructions(text: Option[String]): SignatureLayout = copy(instructions = text)

  /** Same as the `Option` overload, but treats the empty string as "no change" so callers can pass
    * `withInstructions("")` to leave instructions intact.
    */
  def withInstructions(text: String): SignatureLayout =
    if text.isEmpty then this else withInstructions(Some(text))

  // ── Field-mutation helpers ──────────────────────────────────────────
  // Narrowed to `private[dspy4s]`: composite programs (CodeAct,
  // MultiChainComparison, ProgramOfThought, ChainOfThought)
  // augment a base layout by appending / prepending / inserting fields
  // before handing it to a `DynamicPredict`. User code should use the
  // typed `Signature` surface (`derived`, `fromType`, `of[Spec]`,
  // `builder`, `fromString`) instead of mutating layouts directly.

  /** Replace the input cohort while keeping global field names unique (first occurrence wins). */
  private[dspy4s] def withInputFields(updated: Vector[FieldSpec]): SignatureLayout =
    val inputs     = updated.distinctBy(_.name)
    val inputNames = inputs.iterator.map(_.name).toSet
    copy(
      inputFields = inputs,
      outputFields = outputFields.filterNot(field => inputNames.contains(field.name))
    )

  /** Replace the output cohort while keeping global field names unique (the existing inputs win). */
  private[dspy4s] def withOutputFields(updated: Vector[FieldSpec]): SignatureLayout =
    val inputNames = inputFields.iterator.map(_.name).toSet
    copy(outputFields = updated.distinctBy(_.name).filterNot(field => inputNames.contains(field.name)))

  // ── Read helpers ────────────────────────────────────────────────────

  /** Render the DSPy-style string DSL for this layout (e.g. `"comment, lang -> toxic, confidence"`). Inverse of
    * [[SignatureLayout.parse]] for the shape only -- types / instructions / metadata are dropped.
    */
  def signatureString: String =
    val inputs  = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

  /** Equality that ignores the [[name]]. Useful for comparing two layouts that describe the same shape but were
    * constructed with different anonymous names (e.g. `"Signature"` from `fromType` vs `"X"` from a builder).
    */
  def equalsByStructure(other: SignatureLayout): Boolean =
    instructions == other.instructions &&
      inputFields.sameElements(other.inputFields) &&
      outputFields.sameElements(other.outputFields)

  /** Serialize this standalone layout to a [[zio.blocks.schema.DynamicValue.Record]] -- the same codec-spine type
    * carried elsewhere in dspy4s. Round-trips with [[SignatureLayout.fromState]] and serializes to clean JSON via
    * [[dumpJson]]. `Option` fields (`instructions`, and per-field `description` / `prefix` / `defaultValue`) encode as
    * `DynamicValue.Null` when empty. The higher-level program persistence API deliberately uses its smaller
    * `OptimizableParameters` contract instead of serializing a whole layout.
    */
  def dumpState: DynamicValue.Record =
    def str(s: String): DynamicValue                = DynamicValue.Primitive(PrimitiveValue.String(s))
    def opt(o: Option[Any]): DynamicValue           = o.fold(DynamicValue.Null: DynamicValue)(DynamicValues.fromAny)
    def fieldRecord(field: FieldSpec): DynamicValue =
      DynamicValue.Record(Chunk.from(Seq(
        "name"         -> str(field.name),
        "typeRef"      -> str(field.typeRef.repr),
        "description"  -> opt(field.description),
        "prefix"       -> opt(field.prefix),
        "defaultValue" -> opt(field.defaultValue),
        "enumValues"   -> DynamicValue.Sequence(Chunk.from(field.enumValues.map(str))),
        "constraints"  -> DynamicValue.Sequence(Chunk.from(field.constraints.map(c => c.dumpState: DynamicValue)))
      )))
    DynamicValue.Record(Chunk.from(Seq(
      "name"         -> str(name),
      "instructions" -> opt(instructions),
      "inputFields"  -> DynamicValue.Sequence(Chunk.from(inputFields.map(fieldRecord))),
      "outputFields" -> DynamicValue.Sequence(Chunk.from(outputFields.map(fieldRecord)))
    )))

  /** Serialize the state to a JSON string via zio-blocks' `DynamicValue` JSON codec. Round-trips with
    * [[SignatureLayout.fromJson]].
    */
  def dumpJson: String =
    new String(SignatureLayout.dynamicJsonCodec.encode(dumpState), java.nio.charset.StandardCharsets.UTF_8)

object SignatureLayout:

  /** JSON codec for the `DynamicValue`-shaped state, backed by zio-blocks' schema for `DynamicValue`. Encodes
    * `dumpState` to clean, natural JSON (records → objects, `Null` → `null`).
    */
  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Parse a DSPy-style string DSL (`"in1, in2 -> out1"`) into a `SignatureLayout`. Prefer
    * `dspy4s.signatures.Signature.fromString` from user code; this is the lower-level entry point that the typed
    * surface delegates to.
    */
  def parse(
      dsl         : String,
      instructions: String = ""
  ): Either[DspyError, SignatureLayout] =
    dspy4s.core.signatures.SignatureDsl
      .parse(dsl)
      .map(layout =>
        if instructions.isEmpty then layout
        else layout.withInstructions(instructions)
      )

  /** Validating + normalizing factory. Returns `Left` with a structured `DspyError` when validation fails (empty name,
    * no fields, duplicate names, invalid identifiers); on success, applies `FieldSpec.normalize` to each field so
    * adapters see consistent prefixes / descriptions.
    */
  def create(
      name        : String,
      inputFields : Vector[FieldSpec],
      outputFields: Vector[FieldSpec],
      instructions: Option[String] = None
  ): Either[DspyError, SignatureLayout] =
    val fields = inputFields ++ outputFields
    if name.trim.isEmpty then Left(ValidationError("SignatureLayout name cannot be empty"))
    else if fields.isEmpty then Left(ValidationError("SignatureLayout must have at least one field"))
    else if fields.map(_.name).distinct.size != fields.size then
      Left(ValidationError("SignatureLayout fields must have unique names"))
    else if fields.exists(f => !FieldSpec.validateName(f.name)) then
      Left(ValidationError("SignatureLayout fields must be valid identifiers"))
    else
      Right(SignatureLayout(
        name = name,
        inputFields = inputFields.map(FieldSpec.normalize),
        outputFields = outputFields.map(FieldSpec.normalize),
        instructions = instructions
      ))

  /** Trusted internal construction WITHOUT normalization: framework code builds a signature from known-good,
    * already-separated cohorts and wants a `SignatureLayout` directly (not an `Either`). The (private) constructor
    * still enforces a non-empty `name` and identifier-shaped field names; field-name uniqueness is the caller's
    * responsibility here (pass already-distinct fields; use [[create]] to validate arbitrary input). Replaces the
    * former public case-class apply for the framework's internal call sites.
    */
  private[dspy4s] def of(
      name        : String,
      inputFields : Vector[FieldSpec],
      outputFields: Vector[FieldSpec],
      instructions: Option[String]
  ): SignatureLayout =
    SignatureLayout(name, inputFields, outputFields, instructions)

  /** Re-hydrate a standalone layout from the `DynamicValue.Record` produced by [[SignatureLayout.dumpState]]. Program
    * persistence is state-only and therefore does not use this codec.
    */
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
        case Some(_)                                                => Left(ValidationError("Invalid 'instructions' value in signature state"))

    def readField(raw: DynamicValue): Either[DspyError, FieldSpec] =
      raw match
        case rec: DynamicValue.Record =>
          getString(rec, "name").toRight(ValidationError("Field state is missing 'name'")).map { name =>
            val typeRef      = getString(rec, "typeRef").map(TypeRef.fromToken).getOrElse(TypeRef.string)
            val defaultValue = DynamicValues.recordGet(rec, "defaultValue") match
              case None | Some(_: DynamicValue.Null.type) => None
              case Some(dv)                               => Some(DynamicValues.toAny(dv))
            val enumValues = DynamicValues.recordGet(rec, "enumValues") match
              case Some(seq: DynamicValue.Sequence) => seq.elements.iterator.collect {
                  case DynamicValue.Primitive(PrimitiveValue.String(value)) => value
                }.toVector
              case _ => Vector.empty[String]
            val constraints = DynamicValues.recordGet(rec, "constraints") match
              case Some(seq: DynamicValue.Sequence) => seq.elements.iterator.collect { case r: DynamicValue.Record =>
                  r
                }.flatMap(
                  Constraint.fromState
                ).toVector
              case _ => Vector.empty[Constraint]
            FieldSpec(
              name = name,
              typeRef = typeRef,
              description = getString(rec, "description"),
              prefix = getString(rec, "prefix"),
              defaultValue = defaultValue,
              enumValues = enumValues,
              constraints = constraints
            )
          }
        case _ => Left(ValidationError("Invalid field entry in signature state"))

    def readFields(key: String): Either[DspyError, Vector[FieldSpec]] =
      DynamicValues.recordGet(state, key) match
        case Some(seq: DynamicValue.Sequence) =>
          seq.elements.iterator.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) { (acc, raw) =>
            for
              fields <- acc
              field  <- readField(raw)
            yield fields :+ field
          }
        case _ => Left(ValidationError(s"SignatureLayout state is missing '$key'"))

    for
      name         <- readName
      instructions <- readInstructions
      inputFields  <- readFields("inputFields")
      outputFields <- readFields("outputFields")
      signature    <- create(
                     name = name,
                     inputFields = inputFields,
                     outputFields = outputFields,
                     instructions = instructions
                   )
    yield signature

  /** Re-hydrate a layout from a JSON string produced by [[SignatureLayout.dumpJson]]. */
  def fromJson(json: String): Either[DspyError, SignatureLayout] =
    dynamicJsonCodec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)) match
      case Right(rec: DynamicValue.Record) => fromState(rec)
      case Right(other)                    => Left(ValidationError(s"Expected a JSON object for signature state, got: $other"))
      case Left(err)                       => Left(ValidationError(s"Invalid signature-state JSON: ${err.toString}"))
