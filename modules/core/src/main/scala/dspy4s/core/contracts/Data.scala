package dspy4s.core.contracts

/** A single labeled data point: a `Map[String, Any]` of field values plus a [[inputKeys]] partition that names which
  * fields are inputs (everything else is a label / expected output). Used as training data for optimizers
  * (`BootstrapFewShot`, `LabeledFewShot`), evaluation datasets (`Evaluate`), and few-shot demos attached to a
  * `Predict`.
  *
  *   - [[inputKeys]] partitions [[values]] into [[inputs]] and [[labels]]. The partition is content-derived, not
  *     stored separately, so updates to `values` flow through automatically.
  *   - [[augmented]] is set to `true` by `BootstrapFewShot` on traces it harvested from the teacher; raw user
  *     trainset examples stay `false`. The optimizer surface reads this flag to count bootstrapped vs labeled demos
  *     against the configured caps.
  *
  * Immutable; all mutators return a copy. `withInputs` intersects the requested key set with the current
  * `values.keySet`, so passing keys that aren't in `values` silently drops them rather than declaring phantom
  * inputs.
  */
final case class Example(
    values: Map[String, Any],
    inputKeys: Set[String] = Set.empty,
    augmented: Boolean = false
):
  def get(key: String): Option[Any] = values.get(key)
  def contains(key: String): Boolean = values.contains(key)
  def keys: Set[String] = values.keySet
  def size: Int = values.size

  def inputs: Map[String, Any] = values.filter((key, _) => inputKeys.contains(key))
  def labels: Map[String, Any] = values.removedAll(inputKeys)

  def withInputs(keys: Set[String]): Example = copy(inputKeys = keys.intersect(values.keySet))
  def withValue(key: String, value: Any): Example = copy(values = values.updated(key, value))
  def without(keys: Set[String]): Example =
    copy(values = values.removedAll(keys), inputKeys = inputKeys -- keys)
  def withAugmented(flag: Boolean): Example = copy(augmented = flag)

object Example:
  def empty: Example = Example(values = Map.empty)

  /** Convenience constructor: `Example("q" -> "...", "a" -> "...")`. Produces an example with no declared input
    * keys; call `withInputs(...)` to mark a subset as inputs. */
  def apply(entries: (String, Any)*): Example = Example(values = entries.toMap)

/** A column-oriented view of N candidate completions for one LM call. Each field name maps to a vector of N values
  * (one per candidate). All columns must have the same length, which defines [[size]]. The column layout makes
  * "give me all the answers" cheap (`field("answer")`); [[at]] / [[toPredictions]] convert back to row form when a
  * call site wants per-candidate records.
  *
  * Most code paths see exactly one completion per call (`size == 1`), produced by [[Completions.single]] or
  * [[Completions.fromRows]] with a single-row input. Multiple completions arise when an LM provider returns
  * `n > 1` choices (e.g. OpenAI's `n` parameter) or when `BestOfN` runs `Predict` multiple times and packages the
  * results.
  *
  * The `require` enforces the equal-column-length invariant at construction time so all downstream `at(i)` calls
  * can read column `i` without bounds-checking each field individually.
  */
final case class Completions(fields: Map[String, Vector[Any]]):
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

  def size: Int = fields.values.headOption.map(_.size).getOrElse(0)
  def fieldNames: Vector[String] = fields.keys.toVector
  def items: Vector[(String, Vector[Any])] = fields.toVector

  def field(name: String): Either[DspyError, Vector[Any]] =
    fields.get(name).toRight(NotFoundError("completion_field", s"Completion field '$name' does not exist"))

  def at(index: Int): Either[DspyError, DynamicPrediction] =
    if index < 0 || index >= size then
      Left(ValidationError(s"Completion index $index out of bounds for size $size"))
    else
      val row = fields.map { case (key, values) => key -> values(index) }
      Right(DynamicPrediction(values = row))

  def first: Either[DspyError, DynamicPrediction] =
    if size == 0 then Left(ValidationError("Cannot access first completion from empty completions"))
    else at(0)

  def last: Either[DspyError, DynamicPrediction] =
    if size == 0 then Left(ValidationError("Cannot access last completion from empty completions"))
    else at(size - 1)

  def toPredictions: Either[DspyError, Vector[DynamicPrediction]] =
    (0 until size).toVector.foldLeft[Either[DspyError, Vector[DynamicPrediction]]](Right(Vector.empty)) {
      (acc, index) =>
        for
          soFar <- acc
          prediction <- at(index)
        yield soFar :+ prediction
    }

object Completions:
  /** Convert N row-shaped maps into the columnar layout. Fails if any row's key set differs from the first row's --
    * since `Completions.at(i)` is supposed to return a row whose fields are uniform across all candidates, missing
    * fields would corrupt that invariant. An empty input is the empty completion (`size == 0`). */
  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, Completions] =
    if rows.isEmpty then Right(Completions(Map.empty))
    else
      val expectedKeys = rows.head.keySet
      if rows.exists(_.keySet != expectedKeys) then
        Left(ValidationError("All completion rows must include the same set of fields"))
      else
        val columns = expectedKeys.map { key => key -> rows.map(_(key)) }.toMap
        Right(Completions(columns))

  /** Single-completion convenience: every field becomes a one-element column. Bypasses the row/column conversion. */
  def single(values: Map[String, Any]): Completions =
    Completions(values.view.mapValues(value => Vector(value)).toMap)

/** Result of a single `DynamicPredict.run` (the erased predict path): the primary completion's field values, plus
  * optional [[completions]] (when the underlying LM returned multiple candidates) and [[lmUsage]] (token
  * accounting). The typed surface wraps a `DynamicPrediction` on [[dspy4s.typed.Prediction.raw]]; adapters,
  * callbacks, trace, and history all see this same object.
  *
  * The `as*` coercive accessors mirror what [[dspy4s.typed.FieldCodec]] does at the typed layer (they apply the
  * same lenient parsing rules) and are the standard escape hatch when consuming a prediction without a typed
  * `Signature[I, O]`.
  *
  * Coercion rules (deliberately strict to avoid silent surprises):
  *
  *   - [[asString]] -- accepts `String`, `Boolean`, and numeric primitives (`Int` / `Long` / `Float` / `Double`).
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
    values: Map[String, Any],
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
):
  def get(key: String): Option[Any] = values.get(key)
  def contains(key: String): Boolean = values.contains(key)
  def keys: Set[String] = values.keySet
  def size: Int = values.size

  def withUsage(usage: Map[String, Long]): DynamicPrediction = copy(lmUsage = Some(usage))

  def withValue(key: String, value: Any): DynamicPrediction = copy(values = values.updated(key, value))

  def value(key: String): Either[DspyError, Any] =
    values.get(key).toRight(NotFoundError("prediction_field", s"Prediction field '$key' does not exist"))

  def asString(key: String): Either[DspyError, String] =
    value(key).flatMap {
      case s: String                                       => Right(s)
      case b: Boolean                                      => Right(b.toString)
      case n @ (_: Int | _: Long | _: Float | _: Double)   => Right(n.toString)
      case other =>
        Left(ValidationError(s"Prediction field '$key' cannot be converted to String: $other"))
    }

  def asInt(key: String): Either[DspyError, Int] =
    value(key).flatMap {
      case n: Int                                          => Right(n)
      case n: Long if n >= Int.MinValue && n <= Int.MaxValue => Right(n.toInt)
      case s: String =>
        s.trim.toIntOption.toRight(
          ValidationError(s"Prediction field '$key' is not a valid Int: $s")
        )
      case other =>
        Left(ValidationError(s"Prediction field '$key' is not an integer: $other"))
    }

  def asDouble(key: String): Either[DspyError, Double] =
    value(key).flatMap {
      case number: Int    => Right(number.toDouble)
      case number: Long   => Right(number.toDouble)
      case number: Float  => Right(number.toDouble)
      case number: Double => Right(number)
      case s: String =>
        s.trim.toDoubleOption.toRight(
          ValidationError(s"Prediction field '$key' is not a valid Double: $s")
        )
      case other          => Left(ValidationError(s"Prediction field '$key' is not numeric: $other"))
    }

  def asBoolean(key: String): Either[DspyError, Boolean] =
    value(key).flatMap {
      case b: Boolean => Right(b)
      case s: String  =>
        s.trim.toLowerCase match
          case "true"  => Right(true)
          case "false" => Right(false)
          case _       =>
            Left(ValidationError(s"Prediction field '$key' is not a valid Boolean: $s"))
      case other => Left(ValidationError(s"Prediction field '$key' is not a boolean: $other"))
    }

  /** Convenience for the conventional `"score"` field used by metrics and optimizers. Equivalent to
    * `asDouble("score")`. */
  def score: Either[DspyError, Double] =
    asDouble("score")

object DynamicPrediction:
  def empty: DynamicPrediction = DynamicPrediction(values = Map.empty)

  /** Lift the primary completion (index 0) of a multi-candidate [[Completions]] into a `DynamicPrediction`,
    * retaining the full completions on the result's [[DynamicPrediction.completions]] so callers can still reach
    * the other candidates. */
  def fromCompletions(completions: Completions): Either[DspyError, DynamicPrediction] =
    completions.at(0).map(_.copy(completions = Some(completions)))

  /** Row-form convenience: turns N rows into completions, then extracts the primary one as in [[fromCompletions]].
    */
  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, DynamicPrediction] =
    Completions.fromRows(rows).flatMap(fromCompletions)
