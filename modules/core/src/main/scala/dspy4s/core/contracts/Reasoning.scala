package dspy4s.core.contracts

import zio.blocks.schema.Schema
import zio.blocks.typeid.TypeId

/** The type of an output that holds the reasoning of the model (upstream `dspy.Reasoning`). A value is a text: it is a
  * subtype of `String`, so each operation of a text works on it.
  *
  * A field of this type has the wire type [[TypeRef.reasoning]]. When such a field is not optional, the native
  * reasoning step of an adapter call can fill it from the reply (`dspy4s.adapters.internal.NativeReasoning`).
  */
opaque type Reasoning <: String = String

object Reasoning:

  /** The reasoning with the text `content`. */
  def apply(content: String): Reasoning = content

  extension (reasoning: Reasoning)
    /** The text of this reasoning (upstream `Reasoning.content`). */
    def content: String = reasoning

  given CanEqual[Reasoning, Reasoning] = CanEqual.derived
  given CanEqual[Reasoning, String]    = CanEqual.derived
  given CanEqual[String, Reasoning]    = CanEqual.derived

  /** The schema of a text with the type id of this type. `Schema.derived` of a record makes this form for a field of an
    * opaque type, also where a signature ignores each given schema (`Shape.canonicalDerived`). Thus the type id is the
    * one mark of this type in a schema, and a marker annotation cannot do this work.
    */
  given schema: Schema[Reasoning] = new Schema(Schema[String].reflect.typeId(TypeId.of[Reasoning]))

  /** `true` when `typeId` is the type id of this type. */
  private[dspy4s] def hasTypeId(typeId: TypeId[?]): Boolean =
    typeId.equals(schema.reflect.typeId)
