package dspy4s.core.contracts

trait Record:
  def values: Map[String, Any]

  def get(key: String): Option[Any] = values.get(key)
  def contains(key: String): Boolean = values.contains(key)
  def keys: Set[String] = values.keySet
  def size: Int = values.size

trait Example extends Record:
  def inputKeys: Set[String]
  def withInputs(keys: Set[String]): Example

  def inputs: Map[String, Any] = values.filter((key, _) => inputKeys.contains(key))
  def labels: Map[String, Any] = values.removedAll(inputKeys)
  def withValue(key: String, value: Any): Example
  def without(keys: Set[String]): Example

final case class ExampleData(values: Map[String, Any], inputKeys: Set[String] = Set.empty) extends Example:
  override def withInputs(keys: Set[String]): Example = copy(inputKeys = keys.intersect(values.keySet))

  override def withValue(key: String, value: Any): Example = copy(values = values.updated(key, value))

  override def without(keys: Set[String]): Example =
    copy(values = values.removedAll(keys), inputKeys = inputKeys -- keys)

  def withValueUnsafe(key: String, value: Any): ExampleData = copy(values = values.updated(key, value))

object ExampleData:
  def empty: ExampleData = ExampleData(values = Map.empty)
  def apply(entries: (String, Any)*): ExampleData = ExampleData(values = entries.toMap)

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

final case class CompletionData(fields: Map[String, Vector[Any]]) extends Completions:
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

object CompletionData:
  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, CompletionData] =
    if rows.isEmpty then Right(CompletionData(Map.empty))
    else
      val expectedKeys = rows.head.keySet
      if rows.exists(_.keySet != expectedKeys) then
        Left(ValidationError("All completion rows must include the same set of fields"))
      else
        val columns = expectedKeys.map { key => key -> rows.map(_(key)) }.toMap
        Right(CompletionData(columns))

  def single(values: Map[String, Any]): CompletionData =
    CompletionData(values.view.mapValues(value => Vector(value)).toMap)

trait Prediction extends Record:
  def completions: Option[Completions]
  def lmUsage: Option[Map[String, Long]]
  def withUsage(usage: Map[String, Long]): Prediction
  def withValue(key: String, value: Any): Prediction
  def value(key: String): Either[DspyError, Any]
  def asDouble(key: String): Either[DspyError, Double]

final case class PredictionData(
    values: Map[String, Any],
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
) extends Prediction:
  override def withUsage(usage: Map[String, Long]): PredictionData = copy(lmUsage = Some(usage))

  override def withValue(key: String, value: Any): PredictionData = copy(values = values.updated(key, value))

  override def value(key: String): Either[DspyError, Any] =
    values.get(key).toRight(NotFoundError("prediction_field", s"Prediction field '$key' does not exist"))

  override def asDouble(key: String): Either[DspyError, Double] =
    value(key).flatMap {
      case number: Int    => Right(number.toDouble)
      case number: Long   => Right(number.toDouble)
      case number: Float  => Right(number.toDouble)
      case number: Double => Right(number)
      case other          => Left(ValidationError(s"Prediction field '$key' is not numeric: $other"))
    }

  def score: Either[DspyError, Double] =
    asDouble("score")

object PredictionData:
  def empty: PredictionData = PredictionData(values = Map.empty)

  def fromCompletions(completions: Completions): Either[DspyError, PredictionData] =
    completions.at(0).map(_.copy(completions = Some(completions)))

  def fromRows(rows: Vector[Map[String, Any]]): Either[DspyError, PredictionData] =
    CompletionData.fromRows(rows).flatMap(fromCompletions)
