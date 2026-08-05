package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** A single field constraint. Carries BOTH the human-readable rendering prose adapters show after a field's description
  * (matching Python DSPy's `PYDANTIC_CONSTRAINT_MAP`, `dspy/signatures/field.py`, byte-for-byte) AND a JSON-Schema
  * keyword/value, so adapters that emit a schema can embed the constraint structurally (e.g. `exclusiveMinimum`). Build
  * these with [[FieldConstraints]]; they round-trip through layout state as `{op, value}` records via
  * [[Constraint.dumpState]] / [[Constraint.fromState]].
  */
enum Constraint derives CanEqual:
  case Gt(value: Double)
  case Ge(value: Double)
  case Lt(value: Double)
  case Le(value: Double)
  case MinLength(value: Int)
  case MaxLength(value: Int)
  case MultipleOf(value: Double)

  /** The exact upstream prose hint, e.g. `"greater than: 0"`. Whole numbers render without a trailing `.0`. */
  def render: String = this match
    case Constraint.Gt(n)         => s"greater than: ${Constraint.num(n)}"
    case Constraint.Ge(n)         => s"greater than or equal to: ${Constraint.num(n)}"
    case Constraint.Lt(n)         => s"less than: ${Constraint.num(n)}"
    case Constraint.Le(n)         => s"less than or equal to: ${Constraint.num(n)}"
    case Constraint.MinLength(n)  => s"minimum length: $n"
    case Constraint.MaxLength(n)  => s"maximum length: $n"
    case Constraint.MultipleOf(n) => s"a multiple of the given number: ${Constraint.num(n)}"

  /** The JSON-Schema keyword this constraint maps to (for structural embedding by schema-emitting adapters). */
  def schemaKeyword: String = this match
    case _: Constraint.Gt         => "exclusiveMinimum"
    case _: Constraint.Ge         => "minimum"
    case _: Constraint.Lt         => "exclusiveMaximum"
    case _: Constraint.Le         => "maximum"
    case _: Constraint.MinLength  => "minLength"
    case _: Constraint.MaxLength  => "maxLength"
    case _: Constraint.MultipleOf => "multipleOf"

  /** The JSON-Schema value (a number) paired with [[schemaKeyword]]. */
  def schemaValue: DynamicValue = this match
    case Constraint.MinLength(n)  => DynamicValue.Primitive(PrimitiveValue.Int(n))
    case Constraint.MaxLength(n)  => DynamicValue.Primitive(PrimitiveValue.Int(n))
    case Constraint.Gt(n)         => Constraint.numValue(n)
    case Constraint.Ge(n)         => Constraint.numValue(n)
    case Constraint.Lt(n)         => Constraint.numValue(n)
    case Constraint.Le(n)         => Constraint.numValue(n)
    case Constraint.MultipleOf(n) => Constraint.numValue(n)

  /** Persisted form: a `{op, value}` record (used by [[SignatureLayout.dumpState]]). */
  def dumpState: DynamicValue.Record =
    DynamicValue.Record(Chunk(
      "op"    -> DynamicValue.Primitive(PrimitiveValue.String(op)),
      "value" -> Constraint.numValue(numericValue)
    ))

  private def op: String = this match
    case _: Constraint.Gt         => "gt"
    case _: Constraint.Ge         => "ge"
    case _: Constraint.Lt         => "lt"
    case _: Constraint.Le         => "le"
    case _: Constraint.MinLength  => "min_length"
    case _: Constraint.MaxLength  => "max_length"
    case _: Constraint.MultipleOf => "multiple_of"

  private def numericValue: Double = this match
    case Constraint.Gt(n)         => n
    case Constraint.Ge(n)         => n
    case Constraint.Lt(n)         => n
    case Constraint.Le(n)         => n
    case Constraint.MinLength(n)  => n.toDouble
    case Constraint.MaxLength(n)  => n.toDouble
    case Constraint.MultipleOf(n) => n

object Constraint:
  /** Render a numeric bound: drop the `.0` for whole numbers, keep the fractional part otherwise. */
  private def num(n: Double): String =
    if n == Math.rint(n) && !n.isInfinite then n.toLong.toString else n.toString

  /** A DynamicValue number: a `Long` for whole values (so JSON shows `0`, not `0.0`), else a `Double`. */
  private def numValue(n: Double): DynamicValue =
    if n == Math.rint(n) && !n.isInfinite then DynamicValue.Primitive(PrimitiveValue.Long(n.toLong))
    else DynamicValue.Primitive(PrimitiveValue.Double(n))

  /** Rebuild from the persisted `{op, value}` record; `None` for an unrecognized op (forward-compat / corruption). */
  def fromState(rec: DynamicValue.Record): Option[Constraint] =
    for
      op    <- DynamicValues.recordGet(rec, "op").collect { case DynamicValue.Primitive(PrimitiveValue.String(s)) => s }
      value <- DynamicValues.recordGet(rec, "value").flatMap(numberOf)
      constraint <- op match
        case "gt"          => Some(Gt(value))
        case "ge"          => Some(Ge(value))
        case "lt"          => Some(Lt(value))
        case "le"          => Some(Le(value))
        case "min_length"  => Some(MinLength(value.toInt))
        case "max_length"  => Some(MaxLength(value.toInt))
        case "multiple_of" => Some(MultipleOf(value))
        case _             => None
    yield constraint

  private def numberOf(dv: DynamicValue): Option[Double] = dv match
    case DynamicValue.Primitive(PrimitiveValue.Long(n))   => Some(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Int(n))    => Some(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Double(n)) => Some(n)
    case _                                                => None

/** Builders for field [[Constraint]]s. Mirrors Python DSPy's `PYDANTIC_CONSTRAINT_MAP` (`dspy/signatures/field.py`) so
  * dspy4s prompts match upstream byte-for-byte: `gt(0).render == "greater than: 0"`, etc. Numeric helpers accept
  * `Double` (integral or fractional bounds); length helpers take `Int`.
  */
object FieldConstraints:
  def gt(n: Double): Constraint         = Constraint.Gt(n)
  def ge(n: Double): Constraint         = Constraint.Ge(n)
  def lt(n: Double): Constraint         = Constraint.Lt(n)
  def le(n: Double): Constraint         = Constraint.Le(n)
  def minLength(n: Int): Constraint     = Constraint.MinLength(n)
  def maxLength(n: Int): Constraint     = Constraint.MaxLength(n)
  def multipleOf(n: Double): Constraint = Constraint.MultipleOf(n)
