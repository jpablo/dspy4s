package dspy4s.core.contracts

/** Minimal read-only field-access mixin: anything with a `Map[String, Any]` of named values. The two case classes in
  * this file -- [[ExampleData]] and [[PredictionData]] -- extend this so callers can introspect their fields without
  * knowing the concrete type, and adapters / programs that handle records uniformly can target this trait.
  *
  * Records carry an erased `Map[String, Any]` payload. Typed access at the user surface goes through
  * [[dspy4s.typed.Prediction]] / [[dspy4s.typed.Shape]]; this trait stays in the erased runtime layer that adapters
  * and the LM stack consume.
  */
trait Record:
  def values: Map[String, Any]

  def get(key: String): Option[Any] = values.get(key)
  def contains(key: String): Boolean = values.contains(key)
  def keys: Set[String] = values.keySet
  def size: Int = values.size

/** A single labeled data point: a `Record` plus the set of keys that are inputs (everything else is a label /
  * expected output). Used as training data for optimizers (`BootstrapFewShot`, `LabeledFewShot`), evaluation
  * datasets (`Evaluate`), and few-shot demos attached to a `Predict`.
  *
  *   - [[inputKeys]] partitions [[values]] into [[inputs]] and [[labels]]. The partition is content-derived, not
  *     stored separately, so updates to `values` flow through automatically.
  *   - [[augmented]] is set to `true` by `BootstrapFewShot` on traces it harvested from the teacher; raw user
  *     trainset examples stay `false`. The optimizer surface reads this flag to count bootstrapped vs labeled demos
  *     against the configured caps.
  *
  * Concrete implementation: [[ExampleData]].
  */
trait Example extends Record:
  def inputKeys: Set[String]
  def augmented: Boolean
  def withInputs(keys: Set[String]): Example

  def inputs: Map[String, Any] = values.filter((key, _) => inputKeys.contains(key))
  def labels: Map[String, Any] = values.removedAll(inputKeys)
  def withValue(key: String, value: Any): Example
  def without(keys: Set[String]): Example
  def withAugmented(flag: Boolean): Example

/** Canonical [[Example]] implementation. Immutable; all mutators return a copy.
  *
  * `withInputs` intersects the requested key set with the current `values.keySet`, so passing keys that aren't in
  * `values` silently drops them rather than declaring phantom inputs. `withValue` returns the widened `Example`
  * supertype for fluent composition; [[withValueUnsafe]] is the same operation but keeps the `ExampleData` type
  * (handy when subsequent calls need the concrete-class API like `copy`). The "unsafe" name flags that the type
  * preservation is the only difference -- there is no validation either method skips.
  */
final case class ExampleData(
    values: Map[String, Any],
    inputKeys: Set[String] = Set.empty,
    augmented: Boolean = false
) extends Example:
  override def withInputs(keys: Set[String]): Example = copy(inputKeys = keys.intersect(values.keySet))

  override def withValue(key: String, value: Any): Example = copy(values = values.updated(key, value))

  override def without(keys: Set[String]): Example =
    copy(values = values.removedAll(keys), inputKeys = inputKeys -- keys)

  override def withAugmented(flag: Boolean): Example = copy(augmented = flag)

  def withValueUnsafe(key: String, value: Any): ExampleData = copy(values = values.updated(key, value))

object ExampleData:
  def empty: ExampleData = ExampleData(values = Map.empty)

  /** Convenience constructor: `ExampleData("q" -> "...", "a" -> "...")`. Produces an example with no declared input
    * keys; call `withInputs(...)` to mark a subset as inputs. */
  def apply(entries: (String, Any)*): ExampleData = ExampleData(values = entries.toMap)

/** A column-oriented view of N candidate completions for one LM call. Each field name maps to a vector of N values
  * (one per candidate). All columns must have the same length, which defines [[size]]. The column layout makes
  * "give me all the answers" cheap (`field("answer")`); [[at]] / [[toPredictions]] convert back to row form when a
  * call site wants per-candidate records.
  *
  * Most code paths see exactly one completion per call (`size == 1`), produced by [[CompletionData.single]] or
  * [[CompletionData.fromRows]] with a single-row input. Multiple completions arise when an LM provider returns
  * `n > 1` choices (e.g. OpenAI's `n` parameter) or when `BestOfN` runs `Predict` multiple times and packages the
  * results.
  *
  * Concrete implementation: [[CompletionData]].
  */
trait Completions:
  def fields: Map[String, Vector[Any]]

  def size: Int = fields.values.headOption.map(_.size).getOrElse(0)
  def fieldNames: Vector[String] = fields.keys.toVector
  def items: Vector[(String, Vector[Any])] = fields.toVector

  def field(name: String): Either[DspyError, Vector[Any]] =
    fields.get(name).toRight(NotFoundError("completion_field", s"Completion field '$name' does not exist"))

  def at(index: Int): Either[DspyError, PredictionData] =
    if index < 0 || index >= size then
      Left(ValidationError(s"Completion index $index out of bounds for size $size"))
    else
      val row = fields.map { case (key, values) => key -> values(index) }
      Right(PredictionData(values = row))

  def first: Either[DspyError, PredictionData] =
    if size == 0 then Left(ValidationError("Cannot access first completion from empty completions"))
    else at(0)

  def last: Either[DspyError, PredictionData] =
    if size == 0 then Left(ValidationError("Cannot access last completion from empty completions"))
    else at(size - 1)

  def toPredictions: Either[DspyError, Vector[PredictionData]] =
    (0 until size).toVector.foldLeft[Either[DspyError, Vector[PredictionData]]](Right(Vector.empty)) {
      (acc, index) =>
        for
          soFar <- acc
          prediction <- at(index)
        yield soFar :+ prediction
    }

/** Canonical [[Completions]] implementation. The `require` enforces the equal-column-length invariant at
  * construction time so all downstream `at(i)` calls can read column `i` without bounds-checking each field
  * individually.
  */
final case class CompletionData(fields: Map[String, Vector[Any]]) extends Completions:
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

object CompletionData:
  /** Convert N row-shaped maps into the columnar layout. Fails if any row's key set differs from the first row's --
    * since `Completions.at(i)` is supposed to return a row whose fields are uniform across all candidates, missing
    * fields would corrupt that invariant. An empty input is the empty completion (`size == 0`). */
  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, CompletionData] =
    if rows.isEmpty then Right(CompletionData(Map.empty))
    else
      val expectedKeys = rows.head.keySet
      if rows.exists(_.keySet != expectedKeys) then
        Left(ValidationError("All completion rows must include the same set of fields"))
      else
        val columns = expectedKeys.map { key => key -> rows.map(_(key)) }.toMap
        Right(CompletionData(columns))

  /** Single-completion convenience: every field becomes a one-element column. Bypasses the row/column conversion. */
  def single(values: Map[String, Any]): CompletionData =
    CompletionData(values.view.mapValues(value => Vector(value)).toMap)

/** Result of a single `DynamicPredict.run` (the erased predict path): a [[Record]] with the primary completion's
  * field values, plus optional [[completions]] (when the underlying LM returned multiple candidates) and
  * [[lmUsage]] (token accounting). The typed surface wraps a `DynamicPrediction` on [[dspy4s.typed.Prediction.raw]];
  * adapters, callbacks, trace, and history all see this same object.
  *
  * The `as*` coercive accessors mirror what [[dspy4s.typed.FieldCodec]] does at the typed layer (they apply the
  * same lenient parsing rules) and are the standard escape hatch when consuming a prediction without a typed
  * `Signature[I, O]`.
  *
  * Concrete implementation: [[PredictionData]].
  */
trait DynamicPrediction extends Record:
  def completions: Option[Completions]
  def lmUsage: Option[Map[String, Long]]
  def withUsage(usage: Map[String, Long]): DynamicPrediction
  def withValue(key: String, value: Any): DynamicPrediction
  def value(key: String): Either[DspyError, Any]
  def asString(key: String): Either[DspyError, String]
  def asInt(key: String): Either[DspyError, Int]
  def asDouble(key: String): Either[DspyError, Double]
  def asBoolean(key: String): Either[DspyError, Boolean]

/** Canonical [[DynamicPrediction]] implementation.
  *
  * Coercion rules for the `as*` accessors (deliberately strict to avoid silent surprises):
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
final case class PredictionData(
    values: Map[String, Any],
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
) extends DynamicPrediction:
  override def withUsage(usage: Map[String, Long]): PredictionData = copy(lmUsage = Some(usage))

  override def withValue(key: String, value: Any): PredictionData = copy(values = values.updated(key, value))

  override def value(key: String): Either[DspyError, Any] =
    values.get(key).toRight(NotFoundError("prediction_field", s"Prediction field '$key' does not exist"))

  override def asString(key: String): Either[DspyError, String] =
    value(key).flatMap {
      case s: String                                       => Right(s)
      case b: Boolean                                      => Right(b.toString)
      case n @ (_: Int | _: Long | _: Float | _: Double)   => Right(n.toString)
      case other =>
        Left(ValidationError(s"Prediction field '$key' cannot be converted to String: $other"))
    }

  override def asInt(key: String): Either[DspyError, Int] =
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

  override def asDouble(key: String): Either[DspyError, Double] =
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

  override def asBoolean(key: String): Either[DspyError, Boolean] =
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

object PredictionData:
  def empty: PredictionData = PredictionData(values = Map.empty)

  /** Lift the primary completion (index 0) of a multi-candidate [[Completions]] into a `PredictionData`, retaining
    * the full completions on the result's [[PredictionData.completions]] so callers can still reach the other
    * candidates. */
  def fromCompletions(completions: Completions): Either[DspyError, PredictionData] =
    completions.at(0).map(_.copy(completions = Some(completions)))

  /** Row-form convenience: turns N rows into completions, then extracts the primary one as in [[fromCompletions]].
    */
  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, PredictionData] =
    CompletionData.fromRows(rows).flatMap(fromCompletions)
