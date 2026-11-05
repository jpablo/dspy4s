package dspy4s.programs.algebra

import dspy4s.core.contracts.DspyError
import dspy4s.programs.RecordCodec
import dspy4s.signatures.Shape
import zio.blocks.schema.{DynamicValue, Schema}

/** A custom record-boundary object whose value type is fresh for this stable path.
  *
  * An application-provided `Schema[A]` may carry semantics that differ from the structural codec determined by `A`, so
  * it cannot lawfully produce another `RecordCodec[A]`. This bundle instead hides `A` behind the fresh path-dependent
  * [[Value]] type. Distinct bundles are distinct category objects even when they wrap the same carrier, exactly as
  * distinct `DynamicSignature` parses mint distinct input and output objects.
  */
sealed trait RecordObject[A]:
  type Value

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

    def unwrap(value: V): A = underlying.unwrap(value)

  /** Mint a fresh input object from custom product-schema semantics. The returned abstract type member prevents this
    * schema from competing with the canonical `RecordCodec[A]` or with another call to this constructor.
    */
  def fromSchema[A <: Product](schema: Schema[A]): RecordObject[A] =
    new RecordObject[A]:
      type Value = A

      def wrap(value: A): Value = value

      def unwrap(value: Value): A = value

      given codec: RecordCodec[Value] = RecordCodec.fromShape(Shape.derived[A](using schema))
