package dspy4s.core.data

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, NotFoundError, ValidationError}
import dspy4s.core.contracts.updated
import dspy4s.algebra.Monoid
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** Adapter-parsed, schema-uninterpreted prediction data.
  *
  * "Raw" is relative to [[dspy4s.programs.contracts.Prediction]]: [[values]] have already been parsed by an adapter
  * into a `DynamicValue.Record`, but have not been decoded against an output `Shape` into the semantic output type `O`.
  * This is therefore not the unparsed text returned by an LM. It retains the primary completion's field values,
  * optional [[completions]] (when the LM returned multiple candidates), and [[lmUsage]] (token accounting).
  *
  * Every module result retains a `RawPrediction` on [[dspy4s.programs.contracts.Prediction.raw]]. Dynamic modules also
  * use [[values]] directly as their semantic `DynamicValue.Record` output.
  *
  * The `as*` coercive accessors apply the same lenient string-to-primitive parsing that the typed layer's Schema-backed
  * decode performs (`dspy4s.signatures.ZioSchemaCodec`), and are the standard escape hatch when consuming a prediction
  * without a typed `Signature[I, O]`.
  *
  * Coercion rules (deliberately strict to avoid silent surprises):
  *
  *   - [[asString]] -- accepts `String`, `Boolean`, and numeric primitives. Variants render as their case name.
  *     Everything else is a [[ValidationError]].
  *   - [[asInt]] -- accepts `Int`; `Long` only when it fits in `Int` range (rejects out-of-range, no silent
  *     truncation); strings via `String.toIntOption`. `Double`/`Float` are rejected (no silent rounding).
  *   - [[asDouble]] -- accepts any numeric primitive and clean numeric strings.
  *   - [[asBoolean]] -- accepts `Boolean`, or the case-insensitive strings `"true"`/`"false"`. `"yes"`/`"1"`/`0` etc.
  *     are rejected.
  *
  * Field missing from [[values]] is a [[NotFoundError]] from [[value]], propagated by the typed accessors. The
  * [[score]] helper is a thin alias for `asDouble("score")` used by metrics and optimizers.
  */
final case class RawPrediction(
    values     : DynamicValue.Record,
    completions: Option[Completions] = None,
    lmUsage    : Option[LmUsage]     = None
):
  /** Field-value accessor by name. */
  def get(key: String): Option[DynamicValue] = DynamicValues.recordGet(values, key)

  def withUsage(usage: LmUsage): RawPrediction = copy(lmUsage = Some(usage))

  def withValue(key: String, value: DynamicValue): RawPrediction =
    copy(values = values.updated(key, value))

  /** Convenience overload for callers passing a plain typed Scala value; lifts it via its `Schema`. */
  def withRawValue[A](key: String, value: A)(using schema: Schema[A]): RawPrediction =
    withValue(key, schema.toDynamicValue(value))

  /** Sequentially accumulate the observable evidence of two program stages.
    *
    * Field values and completions come from the rightmost stage that produced them; a lifecycle-transparent stage
    * contributes [[RawPrediction.empty]] and therefore preserves its predecessor's envelope. Token usage is a writer
    * and combines pointwise. These component operations are associative and [[RawPrediction.empty]] is their identity,
    * so sequential composition retains the complete prediction without weakening Category equality.
    */
  def followedBy(next: RawPrediction): RawPrediction =
    val combinedUsage = (lmUsage, next.lmUsage) match
      case (None, None)              => None
      case (Some(left), None)        => Some(left)
      case (None, Some(right))       => Some(right)
      case (Some(left), Some(right)) => Some(left.combine(right))
    RawPrediction(
      values = if next.values.fields.nonEmpty then next.values else values,
      completions = next.completions.orElse(completions),
      lmUsage = combinedUsage
    )

  def value(key: String): Either[DspyError, DynamicValue] =
    get(key).toRight(NotFoundError("prediction_field", s"Prediction field '$key' does not exist"))

  def asString(key: String): Either[DspyError, String] =
    value(key).flatMap(dv => RawPrediction.asString(key, dv))

  def asInt(key: String): Either[DspyError, Int] =
    value(key).flatMap(dv => RawPrediction.asInt(key, dv))

  def asDouble(key: String): Either[DspyError, Double] =
    value(key).flatMap(dv => RawPrediction.asDouble(key, dv))

  def asBoolean(key: String): Either[DspyError, Boolean] =
    value(key).flatMap(dv => RawPrediction.asBoolean(key, dv))

  /** Convenience for the conventional `"score"` field used by metrics and optimizers. Equivalent to
    * `asDouble("score")`.
    */
  def score: Either[DspyError, Double] =
    asDouble("score")

object RawPrediction:
  def empty: RawPrediction = RawPrediction(values = DynamicValue.Record.empty)

  given monoid: Monoid[RawPrediction] with
    def empty: RawPrediction = RawPrediction.empty
    extension (current: RawPrediction)
      infix def combine(next: RawPrediction): RawPrediction = current.followedBy(next)

  /** Lift the primary completion (index 0) of a multi-candidate [[Completions]] into a `RawPrediction`, retaining the
    * full completions on the result's [[RawPrediction.completions]] so callers can still reach the other candidates.
    */
  def fromCompletions(completions: Completions): Either[DspyError, RawPrediction] =
    completions.at(0).map(_.copy(completions = Some(completions)))

  /** Row-form convenience: turns N rows into completions, then extracts the primary one as in [[fromCompletions]].
    */
  def fromRows(rows: Vector[DynamicValue.Record]): Either[DspyError, RawPrediction] =
    Completions.fromRows(rows).flatMap(fromCompletions)

  private def asString(key: String, dv: DynamicValue): Either[DspyError, String] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => Right(s)
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Right(b.toString)
      case DynamicValue.Primitive(PrimitiveValue.Int(n))     => Right(n.toString)
      case DynamicValue.Primitive(PrimitiveValue.Long(n))    => Right(n.toString)
      case DynamicValue.Primitive(PrimitiveValue.Float(n))   => Right(n.toString)
      case DynamicValue.Primitive(PrimitiveValue.Double(n))  => Right(n.toString)
      case variant: DynamicValue.Variant                     => variant.caseName.toRight(ValidationError(
          s"Prediction field '$key' is a variant without a case name"
        ))
      case other => Left(ValidationError(s"Prediction field '$key' cannot be converted to String: $other"))

  private def asInt(key: String, dv: DynamicValue): Either[DspyError, Int] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.Int(n))                                            => Right(n)
      case DynamicValue.Primitive(PrimitiveValue.Long(n)) if n >= Int.MinValue && n <= Int.MaxValue => Right(n.toInt)
      case DynamicValue.Primitive(PrimitiveValue.String(s))                                         =>
        s.trim.toIntOption.toRight(ValidationError(s"Prediction field '$key' is not a valid Int: $s"))
      case other => Left(ValidationError(s"Prediction field '$key' is not an integer: $other"))

  private def asDouble(key: String, dv: DynamicValue): Either[DspyError, Double] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.Int(n))    => Right(n.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Long(n))   => Right(n.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Float(n))  => Right(n.toDouble)
      case DynamicValue.Primitive(PrimitiveValue.Double(n)) => Right(n)
      case DynamicValue.Primitive(PrimitiveValue.String(s)) =>
        s.trim.toDoubleOption.toRight(ValidationError(s"Prediction field '$key' is not a valid Double: $s"))
      case other => Left(ValidationError(s"Prediction field '$key' is not numeric: $other"))

  private def asBoolean(key: String, dv: DynamicValue): Either[DspyError, Boolean] =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Right(b)
      case DynamicValue.Primitive(PrimitiveValue.String(s))  => s.trim.toLowerCase match
          case "true"  => Right(true)
          case "false" => Right(false)
          case _       => Left(ValidationError(s"Prediction field '$key' is not a valid Boolean: $s"))
      case other => Left(ValidationError(s"Prediction field '$key' is not a boolean: $other"))
