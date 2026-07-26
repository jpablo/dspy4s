package dspy4s.programs

import dspy4s.core.contracts.{DspyError, FieldRole}
import dspy4s.typed.Shape
import zio.blocks.schema.{DynamicValue, Schema}

/** A custom record-boundary object whose value type is fresh for this stable path.
  *
  * An application-provided `Schema[A]` may carry semantics that differ from the structural codec determined by `A`, so
  * it cannot lawfully produce another `RecordCodec[A]`. This bundle instead hides `A` behind the fresh path-dependent
  * [[Value]] type. Distinct bundles are distinct category objects even when they wrap the same carrier, exactly as
  * distinct [[DynamicSignature]] parses mint distinct input and output objects.
  */
sealed trait RecordObject[A]:
  type Value

  /** The custom record shape, reindexed to this object's fresh value type. */
  val shape: Shape[Value]

  /** Enter this object's branded value type. */
  def wrap(value: A): Value

  /** Leave this object's branded value type. */
  def unwrap(value: Value): A

  /** The only record codec minted for this fresh object type. */
  given codec: RecordCodec[Value]

  final def decode(record: DynamicValue.Record): Either[DspyError, Value] = codec.decode(record)

  /** Capture this path's fresh type in ordinary type arguments so inferred aliases preserve it. */
  final def stable: RecordObject.Stable[A, Value] = new RecordObject.Stable(this)

object RecordObject:

  /** Alias-safe view of a branded object. */
  final class Stable[A, V] private[RecordObject] (
      underlying: RecordObject[A] { type Value = V }
  ):
    type Value = V

    val shape: Shape[V] = underlying.shape

    def wrap(value: A): V = underlying.wrap(value)

    def unwrap(value: V): A = underlying.unwrap(value)

    given codec: RecordCodec[V] = underlying.codec

    def decode(record: DynamicValue.Record): Either[DspyError, V] = codec.decode(record)

  /** Mint a fresh object from an explicitly chosen shape. The returned abstract type member prevents this shape from
    * competing with the canonical `RecordCodec[A]` or with another call to this constructor.
    */
  def fromShape[A](source: Shape[A]): RecordObject[A] =
    new RecordObject[A]:
      type Value = A

      val shape: Shape[Value] = source

      def wrap(value: A): Value = value

      def unwrap(value: Value): A = value

      given codec: RecordCodec[Value] = RecordCodec.fromShape(shape)

  /** Mint a fresh input object from custom product-schema semantics. */
  def fromSchema[A <: Product](schema: Schema[A]): RecordObject[A] =
    fromShape(Shape.derivedWithRole[A](FieldRole.Input)(using schema))
