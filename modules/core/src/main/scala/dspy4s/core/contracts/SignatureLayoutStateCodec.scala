package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** Persistence codec for [[SignatureLayout]].
  *
  * The public `SignatureLayout.dumpState` / `dumpJson` and `SignatureLayout.fromState` / `fromJson` methods delegate
  * here, keeping persistence mechanics separate from the layout's construction and invariants.
  */
private[dspy4s] object SignatureLayoutStateCodec:
  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  def dumpState(layout: SignatureLayout): DynamicValue.Record =
    def str(value: String): DynamicValue            = DynamicValue.Primitive(PrimitiveValue.String(value))
    def opt(value: Option[String]): DynamicValue    = value.fold(DynamicValue.Null: DynamicValue)(str)
    def fieldRecord(field: FieldSpec): DynamicValue =
      DynamicValue.Record(Chunk.from(Seq(
        "name"         -> str(field.name),
        "typeRef"      -> str(field.typeRef.repr),
        "description"  -> opt(field.description),
        "prefix"       -> opt(field.prefix),
        "defaultValue" -> field.defaultValue.getOrElse(DynamicValue.Null),
        "enumValues"   -> DynamicValue.Sequence(Chunk.from(field.enumValues.map(str))),
        "constraints"  -> DynamicValue.Sequence(Chunk.from(field.constraints.map(c => c.dumpState: DynamicValue)))
      )))

    DynamicValue.Record(Chunk.from(Seq(
      "name"         -> str(layout.name),
      "instructions" -> opt(layout.instructions),
      "inputFields"  -> DynamicValue.Sequence(Chunk.from(layout.inputFields.map(fieldRecord))),
      "outputFields" -> DynamicValue.Sequence(Chunk.from(layout.outputFields.map(fieldRecord)))
    )))

  def dumpJson(layout: SignatureLayout): String =
    new String(dynamicJsonCodec.encode(dumpState(layout)), StandardCharsets.UTF_8)

  def fromState(state: DynamicValue.Record): Either[DspyError, SignatureLayout] =
    def getString(record: DynamicValue.Record, key: String): Option[String] =
      DynamicValues.recordGet(record, key) match
        case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) => Some(value)
        case _                                                          => None

    def readName: Either[DspyError, String] =
      getString(state, "name")
        .filter(_.nonEmpty)
        .toRight(ValidationError("SignatureLayout state is missing non-empty 'name'"))

    def readInstructions: Either[DspyError, Option[String]] =
      DynamicValues.recordGet(state, "instructions") match
        case None | Some(_: DynamicValue.Null.type)                     => Right(None)
        case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) => Right(Some(value))
        case Some(_)                                                    => Left(ValidationError("Invalid 'instructions' value in signature state"))

    def readField(raw: DynamicValue): Either[DspyError, FieldSpec] =
      raw match
        case record: DynamicValue.Record =>
          getString(record, "name").toRight(ValidationError("Field state is missing 'name'")).map { name =>
            val typeRef      = getString(record, "typeRef").map(TypeRef.fromToken).getOrElse(TypeRef.string)
            val defaultValue = DynamicValues.recordGet(record, "defaultValue") match
              case None | Some(_: DynamicValue.Null.type) => None
              case Some(value)                            => Some(value)
            val enumValues = DynamicValues.recordGet(record, "enumValues") match
              case Some(sequence: DynamicValue.Sequence) => sequence.elements.iterator.collect {
                  case DynamicValue.Primitive(PrimitiveValue.String(value)) => value
                }.toVector
              case _ => Vector.empty[String]
            val constraints = DynamicValues.recordGet(record, "constraints") match
              case Some(sequence: DynamicValue.Sequence) => sequence.elements.iterator.collect {
                  case value: DynamicValue.Record => value
                }.flatMap(Constraint.fromState).toVector
              case _ => Vector.empty[Constraint]
            FieldSpec(
              name = name,
              typeRef = typeRef,
              description = getString(record, "description"),
              prefix = getString(record, "prefix"),
              defaultValue = defaultValue,
              enumValues = enumValues,
              constraints = constraints
            )
          }
        case _ => Left(ValidationError("Invalid field entry in signature state"))

    def readFields(key: String): Either[DspyError, Vector[FieldSpec]] =
      DynamicValues.recordGet(state, key) match
        case Some(sequence: DynamicValue.Sequence) =>
          sequence.elements.iterator.foldLeft[Either[DspyError, Vector[FieldSpec]]](Right(Vector.empty)) {
            (acc, raw) =>
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
      layout       <- SignatureLayout.create(
                  name = name,
                  inputFields = inputFields,
                  outputFields = outputFields,
                  instructions = instructions
                )
    yield layout

  def fromJson(json: String): Either[DspyError, SignatureLayout] =
    dynamicJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) => fromState(record)
      case Right(other)                       => Left(ValidationError(s"Expected a JSON object for signature state, got: $other"))
      case Left(error)                        => Left(ValidationError(s"Invalid signature-state JSON: ${error.toString}"))
