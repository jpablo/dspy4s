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

trait Signature:
  def name: String
  def fields: Vector[FieldSpec]
  def instructions: Option[String]

  def withFields(updated: Vector[FieldSpec]): Signature
  def withInstructions(text: Option[String]): Signature

  final def inputFields: Vector[FieldSpec] = fields.filter(_.role == FieldRole.Input)
  final def outputFields: Vector[FieldSpec] = fields.filter(_.role == FieldRole.Output)

  final def append(field: FieldSpec): Signature = withFields(fields :+ field)

  final def insert(index: Int, field: FieldSpec): Either[DspyError, Signature] =
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

  final def prepend(field: FieldSpec): Signature =
    val sameRole = fields.filter(_.role == field.role)
    val otherRole = fields.filterNot(_.role == field.role)
    field.role match
      case FieldRole.Input  => withFields((field +: sameRole) ++ otherRole)
      case FieldRole.Output => withFields(otherRole ++ (field +: sameRole))

  final def delete(fieldName: String): Signature = withFields(fields.filterNot(_.name == fieldName))

  final def updateField(fieldName: String, metadata: Map[String, String]): Signature =
    withFields(
      fields.map { field =>
        if field.name == fieldName then field.copy(metadata = field.metadata ++ metadata)
        else field
      }
    )

  final def withUpdatedField(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None,
      metadata: Map[String, String] = Map.empty
  ): Either[DspyError, Signature] =
    fields.find(_.name == fieldName) match
      case None => Left(NotFoundError("field", s"Field '$fieldName' does not exist in signature '$name'"))
      case Some(existing) =>
        val updated = existing.copy(
          typeRef = typeRef.getOrElse(existing.typeRef),
          description = description.orElse(existing.description),
          prefix = prefix.orElse(existing.prefix),
          defaultValue = defaultValue.orElse(existing.defaultValue),
          metadata = existing.metadata ++ metadata
        )
        Right(withFields(fields.map { field => if field.name == fieldName then updated else field }))

  final def withUpdatedFields(
      fieldName: String,
      typeRef: Option[TypeRef] = None,
      typeToken: Option[String] = None,
      description: Option[String] = None,
      prefix: Option[String] = None,
      defaultValue: Option[Any] = None,
      metadata: Map[String, String] = Map.empty
  ): Either[DspyError, Signature] =
    withUpdatedField(
      fieldName = fieldName,
      typeRef = typeRef.orElse(typeToken.map(TypeRef.fromToken)),
      description = description,
      prefix = prefix,
      defaultValue = defaultValue,
      metadata = metadata
    )

  final def withUpdatedFields(updates: (String, FieldUpdate)*): Either[DspyError, Signature] =
    updates.foldLeft[Either[DspyError, Signature]](Right(this)) { (acc, entry) =>
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

  final def signatureString: String =
    val inputs = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

  final def equalsByStructure(other: Signature): Boolean =
    instructions == other.instructions && fields == other.fields

  final def withInstructions(text: String): Signature =
    if text.isEmpty then this else withInstructions(Some(text))

  final def dumpState: Map[String, Any] =
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

/** Companion sugar so call sites can write:
  *
  *   Signature("comment -> toxic: bool", instructions = "...")
  *
  * instead of `SignatureDsl.parse(...).map(_.withInstructions(Some(...)))`.
  * Returns `Either[DspyError, Signature]` so parse errors stay strongly typed. */
object Signature:
  def apply(dsl: String, instructions: String = ""): Either[DspyError, Signature] =
    dspy4s.core.signatures.SignatureDsl
      .parse(dsl)
      .map(_.withInstructions(instructions))

final case class SignatureSpec(
    name: String,
    fields: Vector[FieldSpec],
    instructions: Option[String] = None
) extends Signature:
  require(name.nonEmpty, "Signature name cannot be empty")
  require(fields.map(_.name).distinct.size == fields.size, "Signature fields must have unique names")
  require(fields.forall(f => FieldSpec.validateName(f.name)), "Signature fields must be valid identifiers")

  override def withFields(updated: Vector[FieldSpec]): Signature = copy(fields = updated)
  override def withInstructions(text: Option[String]): Signature = copy(instructions = text)

object SignatureSpec:
  def create(
      name: String,
      fields: Vector[FieldSpec],
      instructions: Option[String] = None
  ): Either[DspyError, SignatureSpec] =
    if name.trim.isEmpty then Left(ValidationError("Signature name cannot be empty"))
    else if fields.isEmpty then Left(ValidationError("Signature must have at least one field"))
    else if fields.map(_.name).distinct.size != fields.size then
      Left(ValidationError("Signature fields must have unique names"))
    else if fields.exists(f => !FieldSpec.validateName(f.name)) then
      Left(ValidationError("Signature fields must be valid identifiers"))
    else
      val normalized = fields.map(FieldSpec.normalize)
      Right(SignatureSpec(name = name, fields = normalized, instructions = instructions))

  def fromState(state: Map[String, Any]): Either[DspyError, SignatureSpec] =
    def readName: Either[DspyError, String] =
      state.get("name") match
        case Some(value: String) if value.nonEmpty => Right(value)
        case _                                      => Left(ValidationError("Signature state is missing non-empty 'name'"))

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
        case _ => Left(ValidationError("Signature state is missing 'fields'"))

    for
      name <- readName
      instructions <- readInstructions
      fields <- readFields
      signature <- create(name = name, fields = fields, instructions = instructions)
    yield signature

trait SignatureParser:
  def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, Signature]
