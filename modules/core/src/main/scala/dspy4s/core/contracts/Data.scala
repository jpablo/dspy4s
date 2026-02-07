package dspy4s.core.contracts

trait Record:
  def values: Map[String, Any]

  def get(key: String): Option[Any] = values.get(key)
  def contains(key: String): Boolean = values.contains(key)

trait Example extends Record:
  def inputKeys: Set[String]
  def withInputs(keys: Set[String]): Example

  def inputs: Map[String, Any] = values.filter((key, _) => inputKeys.contains(key))
  def labels: Map[String, Any] = values.removedAll(inputKeys)

final case class ExampleData(values: Map[String, Any], inputKeys: Set[String] = Set.empty) extends Example:
  override def withInputs(keys: Set[String]): Example = copy(inputKeys = keys)

trait Completions:
  def fields: Map[String, Vector[Any]]

  def size: Int = fields.values.headOption.map(_.size).getOrElse(0)

  def at(index: Int): PredictionData =
    val row = fields.collect { case (key, values) if values.isDefinedAt(index) => key -> values(index) }
    PredictionData(values = row)

final case class CompletionData(fields: Map[String, Vector[Any]]) extends Completions

trait Prediction extends Record:
  def completions: Option[Completions]
  def lmUsage: Option[Map[String, Long]]

final case class PredictionData(
    values: Map[String, Any],
    completions: Option[Completions] = None,
    lmUsage: Option[Map[String, Long]] = None
) extends Prediction:
  def withUsage(usage: Map[String, Long]): PredictionData = copy(lmUsage = Some(usage))

object PredictionData:
  def fromCompletions(completions: Completions): PredictionData =
    val firstValues = completions.fields.collect { case (key, values) if values.nonEmpty => key -> values.head }
    PredictionData(values = firstValues, completions = Some(completions))
