package dspy4s.core.contracts

import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** A constraint on one field. [[render]] gives the prose hint that an adapter writes after the description of the
  * field. The hint is the same as in Python DSPy (`PYDANTIC_CONSTRAINT_MAP`, `dspy/signatures/field.py`). An adapter
  * that writes a JSON Schema uses [[schemaKeyword]] and [[schemaValue]] (for example `exclusiveMinimum`). Each case
  * gives its JSON-Schema keyword and the start of its hint (`label`). Make a constraint with [[FieldConstraints]].
  */
enum Constraint(val schemaKeyword: String, label: String) derives CanEqual:
  case Gt(value: Double)         extends Constraint("exclusiveMinimum", "greater than")
  case Ge(value: Double)         extends Constraint("minimum", "greater than or equal to")
  case Lt(value: Double)         extends Constraint("exclusiveMaximum", "less than")
  case Le(value: Double)         extends Constraint("maximum", "less than or equal to")
  case MinLength(value: Int)     extends Constraint("minLength", "minimum length")
  case MaxLength(value: Int)     extends Constraint("maxLength", "maximum length")
  case MultipleOf(value: Double) extends Constraint("multipleOf", "a multiple of the given number")

  /** The bound of the constraint: a `Double` for a number bound, an `Int` for a length. */
  def value: Double | Int

  /** The exact upstream prose hint, for example `"greater than: 0"`. A whole number has no `.0` at the end. */
  def render: String =
    value match
      case n: Int    => s"$label: $n"
      case n: Double => s"$label: ${Constraint.num(n)}"

  /** The JSON-Schema value (a number) of [[schemaKeyword]]. A length is an `Int`. */
  def schemaValue: DynamicValue =
    value match
      case n: Int    => DynamicValue.Primitive(PrimitiveValue.Int(n))
      case n: Double => Constraint.numValue(n)

object Constraint:
  /** The value 2^63. `toLong` saturates at this value, so a whole value at or above it needs a `BigInt`. */
  private val LongBound: Double = Math.pow(2, 63)

  private def isWhole(n: Double): Boolean = n == Math.rint(n) && !n.isInfinite

  private def inLongRange(n: Double): Boolean = n >= -LongBound && n < LongBound

  /** All the digits of a whole value. Use it only for a whole value outside the `Long` range. */
  private def bigIntOf(n: Double): BigInt = BigDecimal(n).toBigInt

  /** Render a numeric bound: drop the `.0` for whole numbers, keep the fractional part otherwise. */
  private def num(n: Double): String =
    if !isWhole(n) then n.toString
    else if inLongRange(n) then n.toLong.toString
    else bigIntOf(n).toString

  /** A DynamicValue number: a `Long` for whole values (so JSON shows `0`, not `0.0`), else a `Double`. A whole value
    * outside the `Long` range is a `BigInt`.
    */
  private def numValue(n: Double): DynamicValue =
    if !isWhole(n) then DynamicValue.Primitive(PrimitiveValue.Double(n))
    else if inLongRange(n) then DynamicValue.Primitive(PrimitiveValue.Long(n.toLong))
    else DynamicValue.Primitive(PrimitiveValue.BigInt(bigIntOf(n)))

/** Builders for field [[Constraint]]s. Mirrors Python DSPy's `PYDANTIC_CONSTRAINT_MAP` (`dspy/signatures/field.py`) so
  * dspy4s prompts match upstream byte-for-byte: `gt(0).render == "greater than: 0"`, etc. Numeric helpers accept
  * `Double` (integral or fractional bounds); length helpers take `Int`.
  */
object FieldConstraints:
  def gt(n        : Double): Constraint = Constraint.Gt(n)
  def ge(n        : Double): Constraint = Constraint.Ge(n)
  def lt(n        : Double): Constraint = Constraint.Lt(n)
  def le(n        : Double): Constraint = Constraint.Le(n)
  def minLength(n : Int): Constraint    = Constraint.MinLength(n)
  def maxLength(n : Int): Constraint    = Constraint.MaxLength(n)
  def multipleOf(n: Double): Constraint = Constraint.MultipleOf(n)
