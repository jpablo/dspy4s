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

  def at(index: Int): Either[DspyError, PredictionData] =
    if index < 0 || index >= size then
      Left(ValidationError(s"Completion index $index out of bounds for size $size"))
    else
      val row = fields.map { case (key, values) => key -> values(index) }
      Right(PredictionData(values = row))

final case class CompletionData(fields: Map[String, Vector[Any]]) extends Completions:
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

  def fieldNames: Vector[String] = fields.keys.toVector

trait Prediction extends Record:
  def completions: Option[Completions]
  def lmUsage: Option[Map[String, Long]]
  def withUsage(usage: Map[String, Long]): Prediction

final case class PredictionData(
    values: Map[String, Any],
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
) extends Prediction:
  override def withUsage(usage: Map[String, Long]): PredictionData = copy(lmUsage = Some(usage))

  def score: Either[DspyError, Double] =
    values.get("score") match
      case Some(number: Int)    => Right(number.toDouble)
      case Some(number: Long)   => Right(number.toDouble)
      case Some(number: Float)  => Right(number.toDouble)
      case Some(number: Double) => Right(number)
      case Some(other)          => Left(ValidationError(s"Prediction score is not numeric: $other"))
      case None                 => Left(NotFoundError("score", "Prediction does not contain a score field"))

object PredictionData:
  def empty: PredictionData = PredictionData(values = Map.empty)

  def fromCompletions(completions: Completions): Either[DspyError, PredictionData] =
    completions.at(0).map(_.copy(completions = Some(completions)))
