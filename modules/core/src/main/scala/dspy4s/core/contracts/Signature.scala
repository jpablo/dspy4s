package dspy4s.core.contracts

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

final case class FieldSpec(
    name: String,
    role: FieldRole,
    typeRef: TypeRef = TypeRef.string,
    description: Option[String] = None,
    prefix: Option[String] = None,
    defaultValue: Option[Any] = None,
    metadata: Map[String, String] = Map.empty
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

final case class SignatureSpec(
    name: String,
    fields: Vector[FieldSpec],
    instructions: Option[String] = None
) extends Signature:
  require(fields.map(_.name).distinct.size == fields.size, "Signature fields must have unique names")

  override def withFields(updated: Vector[FieldSpec]): Signature = copy(fields = updated)
  override def withInstructions(text: Option[String]): Signature = copy(instructions = text)

trait SignatureParser:
  def parse(signatureDsl: String, name: String = "StringSignature"): Either[DspyError, Signature]
