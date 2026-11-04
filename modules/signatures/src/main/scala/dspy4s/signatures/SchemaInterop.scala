package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, TypeRef}
import zio.blocks.schema.{DynamicValue, Schema}

/** The small public interface of the [[ZioSchemaCodec]] bridge, which is `private[signatures]`. Callers outside the
  * signatures module use it to move one value with a `Schema` to or from a `DynamicValue`. An example is
  * `ToolFunction.fromMethod`, which decodes one tool argument at a time and gives its wire type.
  *
  * It gives only the three operations that such callers need: the decode of one value, the type of one value, and the
  * JSON schema of one type. The other parts of the codec (the encode and the decode of a record, the derivation of a
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
