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
    val (inputs, outputs) = fields.partition(_.role == field.role)
    field.role match
      case FieldRole.Input  => withFields((field +: inputs) ++ outputs)
      case FieldRole.Output => withFields(inputs ++ (field +: outputs))

  final def delete(fieldName: String): Signature = withFields(fields.filterNot(_.name == fieldName))

  final def updateField(fieldName: String, metadata: Map[String, String]): Signature =
    withFields(
      fields.map { field =>
        if field.name == fieldName then field.copy(metadata = field.metadata ++ metadata)
        else field
      }
    )

  final def signatureString: String =
    val inputs = inputFields.map(_.name).mkString(", ")
    val outputs = outputFields.map(_.name).mkString(", ")
    s"$inputs -> $outputs"

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

trait SignatureParser:
  def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, Signature]
