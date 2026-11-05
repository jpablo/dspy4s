package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, DynamicValues, TypeRef, updated}
import zio.blocks.schema.{DynamicValue, Schema}

/** The small public interface of the [[ZioSchemaCodec]] bridge, which is `private[signatures]`. Callers outside the
  * signatures module use it to move one value with a `Schema` to or from a `DynamicValue`. An example is
  * `ToolFunction.fromMethod`, which decodes one tool argument at a time and gives its wire type.
  *
  * It gives only the operations that such callers need: the decode of one value, the type of one value, and the JSON
  * schema of one type. The other parts of the codec (the encode and the decode of a record, the derivation of a
  * `Shape`, and the derivation of the field specs) stay internal.
  */
object SchemaInterop:

  /** Decode one `DynamicValue` as an `A`. The value first gets the coercion of the `Predict` path (the text `"42"`
    * becomes an `Int`, the text `"true"` becomes a `Boolean`, and so on), and then `Schema.fromDynamicValue` decodes
    * it.
    */
  def decodeValue[A](dv: DynamicValue)(using Schema[A]): Either[DspyError, A] = ZioSchemaCodec.decodeValue[A](dv)

  /** The wire [[TypeRef]] of a value type, from its `Schema`. The signatures use the same rules for each field. */
  def typeRef[A](using Schema[A]): TypeRef = ZioSchemaCodec.typeRefForSchema[A]

  /** The JSON schema of `A` as a model sees it, the same schema as [[Shape.jsonSchema]]. Only an `Option` permits a
    * null, and each field with no default value that is not an `Option` is in `required`. `None` when the schema is not
    * a JSON object.
    */
  def jsonSchema[A](using schema: Schema[A]): Option[DynamicValue.Record] = ZioSchemaCodec.jsonSchemaForModel(schema)

  /** The JSON schema of a tool argument of the type `A`, when its wire type does not say its form. `None` for a text, a
    * number, and a bool: their wire type is enough, and a zio-blocks schema of an integer also has `minimum` and
    * `maximum`. An enum is not a plain text, because its schema has the allowed values. The zio-blocks schema of an
    * enum has no `type`, so this function adds it, as pydantic writes it.
    */
  def argumentSchema[A](using schema: Schema[A]): Option[DynamicValue.Record] =
    val spec  = ZioSchemaCodec.fieldSpecOf("argument", schema.reflect)
    val plain = Set(TypeRef.string, TypeRef.int, TypeRef.double, TypeRef.bool).contains(spec.typeRef)
    if plain && spec.enumValues.isEmpty then None
    else if spec.typeRef == TypeRef.string then jsonSchema[A].map(withTextType)
    else jsonSchema[A]

  /** `schema` with `"type": "string"` when it has `enum` and no `type`. An `Option` of an enum has the `enum` in an
    * option of its `anyOf`.
    */
  private def withTextType(schema: DynamicValue.Record): DynamicValue.Record =
    val typed =
      if DynamicValues.recordGet(schema, "enum").isDefined && DynamicValues.recordGet(schema, "type").isEmpty then
        schema.updated("type", DynamicValues.string("string"))
      else schema
    DynamicValues.recordGet(typed, "anyOf") match
      case Some(options: DynamicValue.Sequence) => typed.updated(
          "anyOf",
          DynamicValue.Sequence(options.elements.map {
            case option: DynamicValue.Record => withTextType(option)
            case other                       => other
          })
        )
      case _ => typed
