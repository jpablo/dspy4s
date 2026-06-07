package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** A single labeled data point: a `DynamicValue.Record` of field values plus an [[inputKeys]] partition that names
  * which fields are inputs (everything else is a label / expected output). Used as training data for optimizers
  * (`BootstrapFewShot`, `LabeledFewShot`), evaluation datasets (`Evaluate`), and few-shot demos attached to a
  * `Predict`.
  *
  *   - [[inputKeys]] partitions [[values]] into [[inputs]] and [[labels]]. The partition is content-derived, not
  *     stored separately, so updates to `values` flow through automatically.
  *   - [[augmented]] is set to `true` by `BootstrapFewShot` on traces it harvested from the teacher; raw user
  *     trainset examples stay `false`. The optimizer surface reads this flag to count bootstrapped vs labeled demos
  *     against the configured caps.
  *
  * Immutable; all mutators return a copy. `withInputs` intersects the requested key set with the current field
  * names, so passing keys that aren't in `values` silently drops them rather than declaring phantom inputs.
  */
final case class Example(
    values: DynamicValue.Record,
    inputKeys: Set[String] = Set.empty,
    augmented: Boolean = false
):
  /** Field-value accessor by name. */
  def get(key: String): Option[DynamicValue] = DynamicValues.recordGet(values, key)

  def inputs: DynamicValue.Record = DynamicValues.recordFilterKeys(values, inputKeys.contains)
  def labels: DynamicValue.Record = DynamicValues.recordFilterKeys(values, name => !inputKeys.contains(name))

  def withInputs(keys: Set[String]): Example =
    copy(inputKeys = keys.intersect(DynamicValues.recordKeys(values).toSet))

  def withValue(key: String, value: DynamicValue): Example =
    copy(values = values.updated(key, value))

  /** Convenience overload for callers passing a plain typed Scala value; lifts it via its `Schema`. A value
    * type without a `Schema` is a compile error. */
  def withRawValue[A](key: String, value: A)(using schema: Schema[A]): Example =
    withValue(key, schema.toDynamicValue(value))

  def without(keys: Set[String]): Example =
    copy(
      values    = DynamicValues.recordFilterKeys(values, name => !keys.contains(name)),
      inputKeys = inputKeys -- keys
    )

  def withAugmented(flag: Boolean): Example = copy(augmented = flag)

  /** Serialize to a [[zio.blocks.schema.DynamicValue.Record]] -- the codec spine carried everywhere else in
    * dspy4s. Round-trips with [[Example.fromState]] and serializes to clean JSON via the `DynamicValue` JSON
    * codec. The record has three fields: `values` (the field-value record verbatim), `inputKeys` (a sequence of
    * the input field-name strings), and `augmented` (a boolean). Mirrors `SignatureLayout.dumpState`. */
  def dumpState: DynamicValue.Record =
    val keyValues: Seq[DynamicValue] =
      inputKeys.toVector.sorted.map(k => DynamicValue.Primitive(PrimitiveValue.String(k)))
    DynamicValue.Record(Chunk.from(Seq(
      "values"    -> (values: DynamicValue),
      "inputKeys" -> DynamicValue.Sequence(Chunk.from(keyValues)),
      "augmented" -> DynamicValue.Primitive(PrimitiveValue.Boolean(augmented))
    )))

object Example:
  /** Convenience constructor: `Example("q" -> "...", "a" -> "...")`. Produces an example with no declared input
    * keys; call `withInputs(...)` to mark a subset as inputs. Values are lifted into the spine via
    * [[DynamicValues.fromAny]]. */
  def apply(entries: (String, DynamicValue)*): Example =
    Example(values = DynamicValues.recordFromEntries(entries))

  /** An example with no fields. */
  def empty: Example = Example(values = DynamicValue.Record.empty)

  /** Re-hydrate an [[Example]] from the `DynamicValue.Record` produced by [[Example.dumpState]]. The inverse of
    * the serialization primitive: reads `values` (must be a record), `inputKeys` (a sequence of strings), and
    * `augmented` (a boolean). */
  def fromState(state: DynamicValue.Record): Either[DspyError, Example] =
    def readValues: Either[DspyError, DynamicValue.Record] =
      DynamicValues.recordGet(state, "values") match
        case Some(rec: DynamicValue.Record) => Right(rec)
        case _ => Left(ValidationError("Example state is missing a record 'values'"))

    def readInputKeys: Either[DspyError, Set[String]] =
      DynamicValues.recordGet(state, "inputKeys") match
        case None | Some(_: DynamicValue.Null.type) => Right(Set.empty)
        case Some(seq: DynamicValue.Sequence) =>
          seq.elements.iterator.foldLeft[Either[DspyError, Set[String]]](Right(Set.empty)) { (acc, raw) =>
            acc.flatMap { keys =>
              raw match
                case DynamicValue.Primitive(PrimitiveValue.String(s)) => Right(keys + s)
                case _ => Left(ValidationError("Example state 'inputKeys' must be a sequence of strings"))
            }
          }
        case Some(_) => Left(ValidationError("Example state 'inputKeys' must be a sequence"))

    def readAugmented: Either[DspyError, Boolean] =
      DynamicValues.recordGet(state, "augmented") match
        case None | Some(_: DynamicValue.Null.type)            => Right(false)
        case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(b))) => Right(b)
        case Some(_) => Left(ValidationError("Example state 'augmented' must be a boolean"))

    for
      values    <- readValues
      inputKeys <- readInputKeys
      augmented <- readAugmented
    yield Example(values = values, inputKeys = inputKeys, augmented = augmented)

/** A column-oriented view of N candidate completions for one LM call. Each field name maps to a vector of N values
  * (one per candidate). All columns must have the same length, which defines [[size]]. [[at]] converts a single
  * column index back to row form when a call site wants a per-candidate record.
  *
  * Most code paths see exactly one completion per call (`size == 1`), produced by [[Completions.fromRows]] with a
  * single-row input. Multiple completions arise when an LM provider returns `n > 1` choices (e.g. OpenAI's `n`
  * parameter) or when `BestOfN` runs `Predict` multiple times and packages the results.
  *
  * The `require` enforces the equal-column-length invariant at construction time so all downstream `at(i)` calls
  * can read column `i` without bounds-checking each field individually.
  */
final case class Completions(fields: Map[String, Vector[DynamicValue]]):
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

  def size: Int = fields.values.headOption.map(_.size).getOrElse(0)

  def at(index: Int): Either[DspyError, DynamicPrediction] =
    if index < 0 || index >= size then
      Left(ValidationError(s"Completion index $index out of bounds for size $size"))
    else
      val row = DynamicValue.Record(Chunk.from(fields.iterator.map((k, vs) => k -> vs(index)).toSeq))
      Right(DynamicPrediction(values = row))

object Completions:
  /** Convert N row-shaped records into the columnar layout. Fails if any row's key set differs from the first
    * row's — since `Completions.at(i)` is supposed to return a row whose fields are uniform across all candidates,
    * missing fields would corrupt that invariant. An empty input is the empty completion (`size == 0`). */
  def fromRows(rows: Vector[DynamicValue.Record]): Either[DspyError, Completions] =
    if rows.isEmpty then Right(Completions(Map.empty))
    else
      val expectedKeys = DynamicValues.recordKeys(rows.head).toSet
      if rows.exists(r => DynamicValues.recordKeys(r).toSet != expectedKeys) then
        Left(ValidationError("All completion rows must include the same set of fields"))
      else
        val columns = expectedKeys.iterator.map { key =>
          key -> rows.map(r => DynamicValues.recordGet(r, key).getOrElse(DynamicValue.Null))
        }.toMap
        Right(Completions(columns))

/** Result of a single `DynamicPredict.apply` (the erased predict path): the primary completion's field values, plus
  * optional [[completions]] (when the underlying LM returned multiple candidates) and [[lmUsage]] (token
  * accounting). The typed surface wraps a `DynamicPrediction` on [[dspy4s.typed.Prediction.raw]]; adapters,
  * callbacks, trace, and history all see this same object.
  *
  * The `as*` coercive accessors apply the same lenient string-to-primitive parsing that the typed layer's
  * Schema-backed decode performs (`dspy4s.typed.ZioSchemaCodec`), and are the standard escape hatch when
  * consuming a prediction without a typed `Signature[I, O]`.
  *
  * Coercion rules (deliberately strict to avoid silent surprises):
  *
  *   - [[asString]] -- accepts `String`, `Boolean`, and numeric primitives. Variants render as their case name.
  *     Everything else is a [[ValidationError]].
  *   - [[asInt]] -- accepts `Int`; `Long` only when it fits in `Int` range (rejects out-of-range, no silent
  *     truncation); strings via `String.toIntOption`. `Double`/`Float` are rejected (no silent rounding).
  *   - [[asDouble]] -- accepts any numeric primitive and clean numeric strings.
  *   - [[asBoolean]] -- accepts `Boolean`, or the case-insensitive strings `"true"`/`"false"`. `"yes"`/`"1"`/`0`
  *     etc. are rejected.
  *
  * Field missing from [[values]] is a [[NotFoundError]] from [[value]], propagated by the typed accessors. The
  * [[score]] helper is a thin alias for `asDouble("score")` used by metrics and optimizers.
  */
final case class DynamicPrediction(
    values: DynamicValue.Record,
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
):
  /** Field-value accessor by name. */
  def get(key: String): Option[DynamicValue] = DynamicValues.recordGet(values, key)

  def withUsage(usage: Map[String, Long]): DynamicPrediction = copy(lmUsage = Some(usage))

  def withValue(key: String, value: DynamicValue): DynamicPrediction =
    copy(values = values.updated(key, value))

  /** Convenience overload for callers passing a plain typed Scala value; lifts it via its `Schema`. */
  def withRawValue[A](key: String, value: A)(using schema: Schema[A]): DynamicPrediction =
    withValue(key, schema.toDynamicValue(value))

  def value(key: String): Either[DspyError, DynamicValue] =
    get(key).toRight(NotFoundError("prediction_field", s"Prediction field '$key' does not exist"))

  def asString(key: String): Either[DspyError, String] =
    value(key).flatMap(dv => DynamicPrediction.asString(key, dv))

  def asInt(key: String): Either[DspyError, Int] =
    value(key).flatMap(dv => DynamicPrediction.asInt(key, dv))

  def asDouble(key: String): Either[DspyError, Double] =
    value(key).flatMap(dv => DynamicPrediction.asDouble(key, dv))

  def asBoolean(key: String): Either[DspyError, Boolean] =
    value(key).flatMap(dv => DynamicPrediction.asBoolean(key, dv))

  /** Convenience for the conventional `"score"` field used by metrics and optimizers. Equivalent to
    * `asDouble("score")`. */
  def score: Either[DspyError, Double] =
    asDouble("score")

object DynamicPrediction:
  def empty: DynamicPrediction = DynamicPrediction(values = DynamicValue.Record.empty)

  /** Lift the primary completion (index 0) of a multi-candidate [[Completions]] into a `DynamicPrediction`,
    * retaining the full completions on the result's [[DynamicPrediction.completions]] so callers can still reach
    * the other candidates. */
  def fromCompletions(completions: Completions): Either[DspyError, DynamicPrediction] =
    completions.at(0).map(_.copy(completions = Some(completions)))

  /** Row-form convenience: turns N rows into completions, then extracts the primary one as in [[fromCompletions]].
    */
  def fromRows(rows: Vector[DynamicValue.Record]): Either[DspyError, DynamicPrediction] =
    Completions.fromRows(rows).flatMap(fromCompletions)

  // ---- coercive accessors over DynamicValue ----

  private[contracts] def asString(key: String, dv: DynamicValue): Either[DspyError, String] = dv match
    case DynamicValue.Primitive(PrimitiveValue.String(s))  => Right(s)
    case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Right(b.toString)
    case DynamicValue.Primitive(PrimitiveValue.Int(n))     => Right(n.toString)
    case DynamicValue.Primitive(PrimitiveValue.Long(n))    => Right(n.toString)
    case DynamicValue.Primitive(PrimitiveValue.Float(n))   => Right(n.toString)
    case DynamicValue.Primitive(PrimitiveValue.Double(n))  => Right(n.toString)
    case variant: DynamicValue.Variant                     =>
      variant.caseName.toRight(ValidationError(
        s"Prediction field '$key' is a variant without a case name"
      ))
    case other                                             =>
      Left(ValidationError(s"Prediction field '$key' cannot be converted to String: $other"))

  private[contracts] def asInt(key: String, dv: DynamicValue): Either[DspyError, Int] = dv match
    case DynamicValue.Primitive(PrimitiveValue.Int(n))                                           => Right(n)
    case DynamicValue.Primitive(PrimitiveValue.Long(n)) if n >= Int.MinValue && n <= Int.MaxValue =>
      Right(n.toInt)
    case DynamicValue.Primitive(PrimitiveValue.String(s))                                         =>
      s.trim.toIntOption.toRight(ValidationError(s"Prediction field '$key' is not a valid Int: $s"))
    case other =>
      Left(ValidationError(s"Prediction field '$key' is not an integer: $other"))

  private[contracts] def asDouble(key: String, dv: DynamicValue): Either[DspyError, Double] = dv match
    case DynamicValue.Primitive(PrimitiveValue.Int(n))    => Right(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Long(n))   => Right(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Float(n))  => Right(n.toDouble)
    case DynamicValue.Primitive(PrimitiveValue.Double(n)) => Right(n)
    case DynamicValue.Primitive(PrimitiveValue.String(s)) =>
      s.trim.toDoubleOption.toRight(ValidationError(s"Prediction field '$key' is not a valid Double: $s"))
    case other =>
      Left(ValidationError(s"Prediction field '$key' is not numeric: $other"))

  private[contracts] def asBoolean(key: String, dv: DynamicValue): Either[DspyError, Boolean] = dv match
    case DynamicValue.Primitive(PrimitiveValue.Boolean(b)) => Right(b)
    case DynamicValue.Primitive(PrimitiveValue.String(s))  =>
      s.trim.toLowerCase match
        case "true"  => Right(true)
        case "false" => Right(false)
        case _       => Left(ValidationError(s"Prediction field '$key' is not a valid Boolean: $s"))
    case other =>
      Left(ValidationError(s"Prediction field '$key' is not a boolean: $other"))
