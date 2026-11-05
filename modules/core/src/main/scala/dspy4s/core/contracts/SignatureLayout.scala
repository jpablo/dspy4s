package dspy4s.core.contracts

/** The compiled runtime layout of a Signature: a name, optional instructions, and separate ordered input and output
  * cohorts. Adapters, programs, and the rest of the runtime stack consume this directly; the `Signature[I, O]` (in
  * `dspy4s.signatures`) is the user-facing wrapper around it.
  *
  * '''Construction paths.'''
  *
  *   - `Signature` API (`dspy4s.signatures.Signature.of[T]`, `Signature.fromType[F]`, `Signature.derived[I, O]`): the
  *     primary path; the resulting `Signature.layout` is the value adapters see.
  *   - [[SignatureLayout.create]]: validating + normalizing factory for programmatic construction.
  *   - [[SignatureLayout.parse]]: string-DSL parser escape hatch; prefer `dspy4s.signatures.Signature.fromString` from
  *     user code.
  *
  * The primary constructor is `private`: every layout comes from one of the paths above. This keeps name uniqueness
  * closed by construction (see Invariants) rather than relying on a runtime precondition.
  *
  * '''Field mutation.''' The `withInputFields` / `withOutputFields` methods are `private[dspy4s]`. They exist because
  * composite programs (`ChainOfThought`, `MultiChainComparison`) need to augment a base layout with auxiliary fields
  * (e.g. prepending a `reasoning` output) before handing it to a `DynamicPredict`. User code should use the `Signature`
  * API (a different `Spec` trait, a different `Signature.derived[I, O]`, etc.) rather than reaching into the layout
  * directly.
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

  /** Compatibility view in canonical cohort order: all inputs followed by all outputs. */
  val fields: Vector[FieldSpec] = inputFields ++ outputFields

  require(name.nonEmpty, "SignatureLayout name cannot be empty")
  require(
    fields.forall(f => FieldSpec.validateName(f.name)),
    "SignatureLayout fields must be valid identifiers"
  )

  // ── Stable public accessors / settings ──────────────────────────────

  /** Replace signature-level instructions. */
  def withInstructions(text: Option[String]): SignatureLayout = copy(instructions = text)

  /** Same as the `Option` overload, but treats the empty string as "no change" so callers can pass
    * `withInstructions("")` to leave instructions intact.
    */
  def withInstructions(text: String): SignatureLayout =
    if text.isEmpty then this else withInstructions(Some(text))

  /** The same fields, with `text` after the instructions, and a blank line between them (dspy's
    * `Signature.append_instructions`). When the layout has no instructions, `text` comes after the default instructions
    * of the layout (dspy's `_default_instructions`), as [[dspy4s.core.signatures.SignatureDsl.defaultInstructions]]
    * writes them.
    */
  def appendInstructions(text: String): SignatureLayout =
    withInstructions(Some(s"$effectiveInstructions\n\n$text"))

  /** The instructions, else the default instructions of the fields of this layout (dspy's `_default_instructions`).
    * Upstream computes the default instructions when it makes a signature, so they name the fields of that signature.
    */
  private[dspy4s] lazy val effectiveInstructions: String =
    instructions.getOrElse(dspy4s.core.signatures.SignatureDsl.defaultInstructions(this))

  // ── Field-mutation helpers (see "Field mutation" above) ─────────────

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
    * [[SignatureLayout.parse]] for the shape only: the types, the instructions, and the metadata are dropped.
    */
  def signatureString: String =
    val inputs  = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

object SignatureLayout:

  /** Parse a DSPy-style string DSL (`"in1, in2 -> out1"`) into a `SignatureLayout`. Prefer
    * `dspy4s.signatures.Signature.fromString` from user code; this is the lower-level entry point that the `Signature`
    * API delegates to.
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
