package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

import scala.util.matching.Regex

/** Per-field metadata inside a [[SignatureLayout]]. Adapters consume this to render prompts and parse responses.
  *
  *   - [[name]] is the canonical field key used in input / output records.
  *   - [[typeRef]] is the wire-format type the LM sees in the prompt (see [[TypeRef]]).
  *   - [[description]] is a per-field hint shown in adapter prompts (e.g. `"the question to answer"`). When `None`,
  *     [[FieldSpec.normalize]] defaults it to a placeholder like `"${question}"` so the prompt always names the slot.
  *   - [[prefix]] is the section header in adapter prompts (e.g. `"Question:"`); inferred from `name` by
  *     [[FieldSpec.inferPrefix]] when `None`.
  *   - [[defaultValue]] is the default of the field (dspy's `default=` of a field). For an input field, an adapter
  *     renders it when the live call has no value for the field (dspy's `Predict` fills input defaults). For an output
  *     field, it is the value of the field when the response of the model does not have the field (dspy's
  *     `apply_output_field_defaults`). A demo does not use it: a demo with no value for a field is an incomplete demo.
  *     Carried as a `DynamicValue`, the value type of the interchange format, so adapters render it through the same
  *     path as live values; lift plain Scala values via `Schema.toDynamicValue` (the `:=` machinery).
  *   - [[optional]] is `true` for a field whose type permits no value (dspy's `Optional[T]` or `T | None`, an
  *     `Option[T]` field of a typed signature). An output field that the response does not have takes its
  *     [[defaultValue]], else a null when the field is optional. Else the response is a parse error.
  *   - [[constraints]] are human-readable constraint hints (e.g. `"greater than: 0"`, `"maximum length: 10"`) surfaced
  *     after the field description in prose adapters. Build them with [[FieldConstraints]] so the text matches Python
  *     DSPy's `PYDANTIC_CONSTRAINT_MAP`. Empty by default; only emitted when non-empty.
  *
  * '''Constraint provenance (v1).''' Code sets the constraints on this `FieldSpec` or through
  * [[SignatureLayout.create]]. A later change can derive them from the `zio-blocks Schema`. Schema derivation has no
  * constraint annotation yet, so no path goes from `Schema[A]` to [[Constraint]] values now.
  */
final case class FieldSpec(
    name        : String,
    typeRef     : TypeRef              = TypeRef.string,
    description : Option[String]       = None,
    prefix      : Option[String]       = None,
    defaultValue: Option[DynamicValue] = None,
    enumValues  : Vector[String]       = Vector.empty,
    constraints : Vector[Constraint]   = Vector.empty,
    optional    : Boolean              = false
) derives CanEqual

object FieldSpec:

  /** True if `name` is a valid Scala-style identifier: ASCII letters, ASCII digits, and underscores, with a letter or
    * an underscore first (the pattern `[A-Za-z_][A-Za-z0-9_]*`). Adapters require this. Field names are the keys of
    * `DynamicValue.Record` payloads and the labels of named tuples in the `Signature` API, so a name that is not an
    * identifier breaks that API.
    */
  def validateName(name: String): Boolean =
    name.nonEmpty && isIdentifierStart(name.charAt(0)) && name.forall(char => isIdentifierStart(char) || isDigit(char))

  private def isIdentifierStart(char: Char): Boolean =
    (char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z') || char == '_'

  private def isDigit(char: Char): Boolean = char >= '0' && char <= '9'

  private val CapitalizedWord: Regex   = "(.)([A-Z][a-z]+)".r
  private val CapitalAfterLower: Regex = "([a-z0-9])([A-Z])".r
  private val DigitAfterLetter: Regex  = "([A-Za-z])(\\d)".r
  private val LetterAfterDigit: Regex  = "(\\d)([A-Za-z])".r

  /** Convert a camelCase or snake_case field name into a human-readable prompt label (e.g. `"scoreValue"` →
    * `"Score Value"`). Used by [[normalize]] to default the [[FieldSpec.prefix]] when one isn't explicitly set. Handles
    * letter-digit boundaries (`"v2"` → `"V 2"`) and preserves all-caps acronyms unchanged.
    */
  def inferPrefix(name: String): String =
    val step1 = CapitalizedWord.replaceAllIn(name, "$1_$2")
    val step2 = CapitalAfterLower.replaceAllIn(step1, "$1_$2")
    val step3 = LetterAfterDigit.replaceAllIn(DigitAfterLetter.replaceAllIn(step2, "$1_$2"), "$1_$2")
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
    * are preserved: this only adds values.
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
