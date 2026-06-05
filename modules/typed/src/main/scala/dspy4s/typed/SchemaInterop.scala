package dspy4s.typed

import dspy4s.core.contracts.{DspyError, TypeRef, ValidationError}
import zio.blocks.schema.{DynamicValue, Schema}

/** The narrow public seam onto the otherwise-`private[typed]` [[ZioSchemaCodec]] bridge, for callers outside the
  * typed module that need to move a single `Schema`-typed value across the `DynamicValue` spine (notably
  * `ToolFunction.fromMethod`, which decodes one tool argument at a time and reports its wire type).
  *
  * It exposes exactly the two operations such callers need — decode-one-value and type-of-one-value — keeping the
  * rest of the codec (record-shaped encode/decode, `Shape` derivation, field-spec derivation) internal. */
object SchemaInterop:

  /** Decode a single `DynamicValue` into `A`, applying the same LM-shaped coercion the typed predict path uses
    * (string `"42"` → `Int`, `"true"` → `Boolean`, etc.) before delegating to `Schema.fromDynamicValue`. */
  def decodeValue[A](dv: DynamicValue)(using schema: Schema[A]): Either[DspyError, A] =
    schema.fromDynamicValue(ZioSchemaCodec.normalize(dv, schema.reflect))
      .left.map(err => ValidationError(err.toString))

  /** The wire [[TypeRef]] for a value type, derived from its `Schema` — the same mapping the signature layer
    * applies to each field, exposed one type at a time for argument-schema reporting. */
  def typeRef[A](using Schema[A]): TypeRef = ZioSchemaCodec.typeRefForSchema[A]
