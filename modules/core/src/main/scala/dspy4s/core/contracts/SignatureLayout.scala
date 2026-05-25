package dspy4s.core.contracts

import scala.util.matching.Regex

enum FieldRole:
  case Input
  case Output

final case class TypeRef(repr: String)

object TypeRef:
  val string: TypeRef = TypeRef("string")
  val int: TypeRef = TypeRef("int")
  val double: TypeRef = TypeRef("double")
  val bool: TypeRef = TypeRef("bool")
  val json: TypeRef = TypeRef("json")

  def fromToken(token: String): TypeRef =
    token.trim.toLowerCase match
      case "" | "str" | "string"         => string
      case "int" | "integer"             => int
      case "float" | "double" | "number" => double
      case "bool" | "boolean"            => bool
      case "json" | "dict" | "map"       => json
      case other                         => TypeRef(other)

final case class FieldSpec(
    name: String,
    role: FieldRole,
    typeRef: TypeRef = TypeRef.string,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None,
    metadata: Map[String, String] = Map.empty
)

/** Well-known string keys that may appear in `FieldSpec.metadata`. Defined
  * here in `core` so any producer (the typed layer, custom builders, future
  * derivation macros) and any consumer (adapters in `lm`, prompt formatters
  * in `adapters`, etc.) can share one contract without depending on each
  * other. Adapters that understand a key may surface it to the LM; adapters
  * that don't ignore it harmlessly. */
object FieldMetadata:
  /** Comma-separated allowed case names for a field backed by a closed set
    * (typically a Scala enum). Example: `"sadness,joy,love"`. */
  val EnumCases: String = "enum.cases"

  /** The original Scala display name of an enum-typed field (e.g.
    * `"Sentiment"`). Useful for adapter prompt rendering when the lowercased
    * `TypeRef` is less informative than the source type name. */
  val EnumName: String = "enum.name"

final case class FieldUpdate(
    typeRef: Option[TypeRef] = None,
    typeToken: Option[String] = None,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None,
    metadata: Map[String, String] = Map.empty
):
  def resolvedTypeRef: Option[TypeRef] =
    typeRef.orElse(typeToken.map(TypeRef.fromToken))

object FieldSpec:
  private val identifierPattern: Regex = raw"[A-Za-z_][A-Za-z0-9_]*".r

  def validateName(name: String): Boolean = identifierPattern.matches(name)

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

  def normalize(field: FieldSpec): FieldSpec =
    val inferredPrefix = inferPrefix(field.name) + ":"
    field.copy(
      prefix = field.prefix.orElse(Some(inferredPrefix)),
      description = field.description.orElse(Some(s"$${${field.name}}"))
    )

/** The compiled runtime layout of a typed Signature: a name, optional
  * instructions, and an ordered list of `FieldSpec`s. Adapters, programs,
  * and the rest of the runtime stack consume this directly; the typed
  * `Signature[I, O]` is the user-facing wrapper around it.
  *
  * Construction paths:
  *   - The typed surface (`dspy4s.typed.Signature.of[T]`,
  *     `Signature.fromType[F]`, `Signature.derived[I, O]`) — the
  *     primary path; the resulting `Signature.layout` is the value
  *     adapters see.
  *   - `SignatureLayout.create(name, fields, instructions)` —
  *     validating + normalizing factory for programmatic construction.
  *   - `SignatureLayout(dsl, instructions)` — string-DSL parser
  *     (legacy escape hatch; prefer `dspy4s.typed.Signature.fromString`
  *     from user code).
  *
  * The case-class `apply(name, fields, instructions)` form is also
  * available but skips normalization — only use it from internal code
  * that builds the field list deliberately. */
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

  def withFields(updated: Vector[FieldSpec]): SignatureLayout = copy(fields = updated)

  def withInstructions(text: Option[String]): SignatureLayout = copy(instructions = text)

  def withInstructions(text: String): SignatureLayout =
    if text.isEmpty then this else withInstructions(Some(text))

  def inputFields: Vector[FieldSpec]  = fields.filter(_.role == FieldRole.Input)
  def outputFields: Vector[FieldSpec] = fields.filter(_.role == FieldRole.Output)

  def append(field: FieldSpec): SignatureLayout = withFields(fields :+ field)

  def insert(index: Int, field: FieldSpec): Either[DspyError, SignatureLayout] =
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

  def prepend(field: FieldSpec): SignatureLayout =
    val sameRole = fields.filter(_.role == field.role)
    val otherRole = fields.filterNot(_.role == field.role)
    field.role match
      case FieldRole.Input  => withFields((field +: sameRole) ++ otherRole)
      case FieldRole.Output => withFields(otherRole ++ (field +: sameRole))

  def delete(fieldName: String): SignatureLayout =
    withFields(fields.filterNot(_.name == fieldName))

  def updateField(fieldName: String, metadata: Map[String, String]): SignatureLayout =
    withFields(
      fields.map { field =>
        if field.name == fieldName then field.copy(metadata = field.metadata ++ metadata)
        else field
      }
    )

  def withUpdatedField(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None,
      metadata: Map[String, String] = Map.empty
  ): Either[DspyError, SignatureLayout] =
    fields.find(_.name == fieldName) match
      case None =>
        Left(NotFoundError("field", s"Field '$fieldName' does not exist in signature '$name'"))
      case Some(existing) =>
        val updated = existing.copy(
          typeRef = typeRef.getOrElse(existing.typeRef),
          description = description.orElse(existing.description),
          prefix = prefix.orElse(existing.prefix),
          defaultValue = defaultValue.orElse(existing.defaultValue),
          metadata = existing.metadata ++ metadata
        )
        Right(withFields(fields.map { field => if field.name == fieldName then updated else field }))

  def withUpdatedFields(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      typeToken: Option[String] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None,
      metadata: Map[String, String] = Map.empty
  ): Either[DspyError, SignatureLayout] =
    withUpdatedField(
      fieldName = fieldName,
      typeRef = typeRef.orElse(typeToken.map(TypeRef.fromToken)),
      description = description,
      prefix = prefix,
      defaultValue = defaultValue,
      metadata = metadata
    )

  def withUpdatedFields(updates: (String, FieldUpdate)*): Either[DspyError, SignatureLayout] =
    updates.foldLeft[Either[DspyError, SignatureLayout]](Right(this)) { (acc, entry) =>
      val (fieldName, update) = entry
      acc.flatMap(
        _.withUpdatedFields(
          fieldName = fieldName,
          typeRef = update.typeRef,
          typeToken = update.typeToken,
          description = update.description,
          prefix = update.prefix,
          defaultValue = update.defaultValue,
          metadata = update.metadata
        )
      )
    }

  def signatureString: String =
    val inputs = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

  def equalsByStructure(other: SignatureLayout): Boolean =
    instructions == other.instructions && fields == other.fields

  def dumpState: Map[String, Any] =
    Map(
      "name" -> name,
      "instructions" -> instructions,
      "fields" -> fields.map { field =>
        Map(
          "name" -> field.name,
          "role" -> field.role.toString,
          "typeRef" -> field.typeRef.repr,
          "description" -> field.description,
          "prefix" -> field.prefix,
          "defaultValue" -> field.defaultValue,
          "metadata" -> field.metadata
        )
      }
    )

object SignatureLayout:

  /** String-DSL parser kept on the companion as overloaded `apply` for
    * source-compatibility with the pre-collapse `SignatureSchema(dsl, ...)`
    * form. Prefer `dspy4s.typed.Signature.fromString` from user code; this
    * factory is the internal entry point that the typed surface delegates
    * to. */
  def apply(dsl: String, instructions: String): Either[DspyError, SignatureLayout] =
    dspy4s.core.signatures.SignatureDsl
      .parse(dsl)
      .map(_.withInstructions(instructions))

  def apply(dsl: String): Either[DspyError, SignatureLayout] = apply(dsl, "")

  /** Validating + normalizing factory. Returns `Left` with a structured
    * `DspyError` when validation fails (empty name, no fields, duplicate
    * names, invalid identifiers); on success, applies `FieldSpec.normalize`
    * to each field so adapters see consistent prefixes / descriptions. */
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

  /** Re-hydrate a layout from the `Map[String, Any]` produced by
    * `dumpState`. Used by persistence / state-snapshot paths. */
  def fromState(state: Map[String, Any]): Either[DspyError, SignatureLayout] =
    def readName: Either[DspyError, String] =
      state.get("name") match
        case Some(value: String) if value.nonEmpty => Right(value)
        case _ =>
          Left(ValidationError("SignatureLayout state is missing non-empty 'name'"))

    def readInstructions: Either[DspyError, Option[String]] =
      state.get("instructions") match
        case None                 => Right(None)
        case Some(None)           => Right(None)
        case Some(value: String)  => Right(Some(value))
        case Some(value: Option[?]) =>
          value match
            case Some(text: String) => Right(Some(text))
            case None               => Right(None)
            case _                  => Left(ValidationError("Invalid 'instructions' value in signature state"))
        case _ => Left(ValidationError("Invalid 'instructions' value in signature state"))

    def parseRole(role: String): Either[DspyError, FieldRole] =
      role.trim.toLowerCase match
        case "input"  => Right(FieldRole.Input)
        case "output" => Right(FieldRole.Output)
        case _        => Left(ValidationError(s"Invalid field role '$role' in signature state"))

    def readFields: Either[DspyError, Vector[FieldSpec]] =
      state.get("fields") match
        case Some(rawFields: Seq[?]) =>
          rawFields.toVector.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) { (acc, raw) =>
            for
              fields <- acc
              fieldMap <- raw match
                case value: collection.Map[?, ?] =>
                  val mapped: Map[String, Any] = value.iterator.collect {
                    case (k: String, v) => k -> v
                  }.toMap
                  Right(mapped)
                case _ =>
                  Left(ValidationError("Invalid field entry in signature state"))
              name <- fieldMap.get("name") match
                case Some(value: String) => Right(value)
                case _                   => Left(ValidationError("Field state is missing 'name'"))
              roleValue <- fieldMap.get("role") match
                case Some(value: String) => parseRole(value)
                case _                   => Left(ValidationError(s"Field '$name' is missing role"))
              typeRef = fieldMap.get("typeRef") match
                case Some(value: String) => TypeRef.fromToken(value)
                case _                   => TypeRef.string
              description = fieldMap.get("description") match
                case Some(value: String) => Some(value)
                case Some(Some(value: String)) => Some(value)
                case _                   => None
              prefix = fieldMap.get("prefix") match
                case Some(value: String) => Some(value)
                case Some(Some(value: String)) => Some(value)
                case _                   => None
              defaultValue = fieldMap.get("defaultValue") match
                case Some(value: Option[?]) => value
                case Some(value)            => Some(value)
                case None                   => None
              metadata = fieldMap.get("metadata") match
                case Some(value: collection.Map[?, ?]) =>
                  val parsed: Map[String, String] = value.iterator.collect {
                    case (k: String, v: String) => k -> v
                  }.toMap
                  parsed
                case _ => Map.empty[String, String]
            yield fields :+ FieldSpec(
              name = name,
              role = roleValue,
              typeRef = typeRef,
              description = description,
              prefix = prefix,
              defaultValue = defaultValue,
              metadata = metadata
            )
          }
        case _ => Left(ValidationError("SignatureLayout state is missing 'fields'"))

    for
      name <- readName
      instructions <- readInstructions
      fields <- readFields
      signature <- create(name = name, fields = fields, instructions = instructions)
    yield signature

trait SignatureParser:
  def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, SignatureLayout]
