package dspy4s.core.contracts

import scala.util.matching.Regex

/** Per-field metadata inside a [[SignatureLayout]]. Adapters consume this to render prompts and parse responses.
  *
  *   - [[name]] is the canonical field key used in input / output records.
  *   - [[typeRef]] is the wire-format type the LM sees in the prompt -- see [[TypeRef]].
  *   - [[description]] is a per-field hint shown in adapter prompts (e.g. `"the question to answer"`). When `None`,
  *     [[FieldSpec.normalize]] defaults it to a placeholder like `"${question}"` so the prompt always names the slot.
  *   - [[prefix]] is the section header in adapter prompts (e.g. `"Question:"`); inferred from `name` by
  *     [[FieldSpec.inferPrefix]] when `None`.
  *   - [[defaultValue]] is the fallback value rendered into demos by Chat / JSON / XML adapters when a demo example
  *     omits this field. (Not used for live-call inputs.)
  *   - [[constraints]] are human-readable constraint hints (e.g. `"greater than: 0"`, `"maximum length: 10"`) surfaced
  *     after the field description in prose adapters. Build them with [[FieldConstraints]] so the text matches Python
  *     DSPy's `PYDANTIC_CONSTRAINT_MAP`. Empty by default; only emitted when non-empty.
  *
  * '''Constraint provenance (v1).''' Constraints are settable programmatically -- via this `FieldSpec` or
  * [[SignatureLayout.create]]. Deriving them automatically from the `zio-blocks Schema` surface is a documented
  * follow-up: schema derivation has no constraint-annotation mechanism yet, so there is no path from `Schema[A]` to
  * these strings today.
  */
final case class FieldSpec(
    name        : String,
    typeRef     : TypeRef            = TypeRef.string,
    description : Option[String]     = None,
    prefix      : Option[String]     = None,
    defaultValue: Option[Any]        = None,
    enumValues  : Vector[String]     = Vector.empty,
    constraints : Vector[Constraint] = Vector.empty
) derives CanEqual

object FieldSpec:
  private val identifierPattern: Regex = raw"[A-Za-z_][A-Za-z0-9_]*".r

  /** True if `name` is a valid Scala-style identifier (alphanumeric + underscore, must start with letter or
    * underscore). Adapters require this -- field names appear as keys in `Map[String, Any]` payloads and as named-tuple
    * labels in the `Signature` API, so non-identifier names would break that API.
    */
  def validateName(name: String): Boolean = identifierPattern.matches(name)

  /** Convert a camelCase or snake_case field name into a human-readable prompt label (e.g. `"scoreValue"` →
    * `"Score Value"`). Used by [[normalize]] to default the [[FieldSpec.prefix]] when one isn't explicitly set. Handles
    * letter-digit boundaries (`"v2"` → `"V 2"`) and preserves all-caps acronyms unchanged.
    */
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
    * [[inferPrefix]] and append `":"`; if `description` is `None`, default to a `${name}` placeholder. Existing values
    * are preserved -- this only ever adds.
    *
    * Applied to every field by [[SignatureLayout.create]], so adapters always see uniform prefix / description surfaces
    * regardless of which factory built the layout.
    */
  def normalize(field: FieldSpec): FieldSpec =
    val inferredPrefix = inferPrefix(field.name) + ":"
    field.copy(
      prefix = field.prefix.orElse(Some(inferredPrefix)),
      description = field.description.orElse(Some(s"$${${field.name}}"))
    )
