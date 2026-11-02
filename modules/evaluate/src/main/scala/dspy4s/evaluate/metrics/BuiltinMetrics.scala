package dspy4s.evaluate.metrics

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.ValidationError
import dspy4s.evaluate.contracts.Metric
import zio.blocks.schema.DynamicValue

object MetricHelpers:

  /** The value of a field that is present and is not null. A missing field is a `NotFoundError`. A null is a
    * `ValidationError`, where upstream raises a `TypeError` or a `ValueError`.
    */
  private def nonNull(
      value    : Option[DynamicValue],
      fieldName: String,
      owner    : String
  ): Either[DspyError, DynamicValue] =
    value.map(PythonText.unwrap) match
      case None                            => Left(NotFoundError(fieldName, s"$owner is missing field '$fieldName'"))
      case Some(_: DynamicValue.Null.type) =>
        Left(ValidationError(s"$owner.$fieldName is null, and a metric needs text."))
      case Some(found) => Right(found)

  /** The text values of a field of `owner`: a string is itself, a sequence gives its string items, and each other value
    * is Python `str`. A sequence with no string item is a `NotFoundError`, and a null is a `ValidationError`.
    */
  def textValues(value: Option[DynamicValue], fieldName: String, owner: String): Either[DspyError, Vector[String]] =
    nonNull(value, fieldName, owner).flatMap { found =>
      val strings = texts(found)
      if strings.isEmpty then Left(NotFoundError(fieldName, s"$owner.$fieldName has no string values"))
      else Right(strings)
    }

  /** The passages of a field of a prediction: the rule of [[textValues]], but a sequence with no string item gives no
    * passages, because a retriever can find nothing (upstream `any` over an empty list gives `False`).
    */
  def passageValues(value: Option[DynamicValue], fieldName: String): Either[DspyError, Vector[String]] =
    nonNull(value, fieldName, "Prediction").map(texts)

  private def texts(found: DynamicValue): Vector[String] =
    found match
      case sequence: DynamicValue.Sequence => sequence.elements.iterator.flatMap(DynamicValues.asString).toVector
      case other                           => Vector(PythonText.str(other))

  /** The text of the prediction and the reference texts of the example for `fieldName`. The prediction has one text: a
    * string is itself, each other value is Python `str`, and a null is a `ValidationError`.
    */
  def extractString(
      example   : Example,
      prediction: RawPrediction,
      fieldName : String
  ): Either[DspyError, (String, Vector[String])] =
    for
      references <- textValues(example.get(fieldName), fieldName, "Example")
      predicted  <- nonNull(prediction.get(fieldName), fieldName, "Prediction")
    yield (PythonText.str(predicted), references)

class ExactMatch(answerField: String = "answer") extends Metric:
  val name: String = "exact_match"

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      val refsNorm = refTexts.map(NormalizeText(_))
      if refsNorm.exists(_ == predNorm) then 1.0 else 0.0
    }

class ContainsMatch(answerField: String = "answer") extends Metric:
  val name: String = "contains"

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      val refsNorm = refTexts.map(NormalizeText(_))
      if refsNorm.exists(ref => predNorm.contains(ref)) then 1.0 else 0.0
    }

class F1Score(answerField: String = "answer") extends Metric:
  val name: String = "f1"

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      refTexts.map(r => f1Score(predNorm, NormalizeText(r))).max
    }

  private def f1Score(pred: String, truth: String): Double =
    val predTokens  = pred.split("\\s+").filter(_.nonEmpty)
    val truthTokens = truth.split("\\s+").filter(_.nonEmpty)
    if predTokens.isEmpty || truthTokens.isEmpty then 0.0
    else
      val common = predTokens.intersect(truthTokens).size.toDouble
      if common == 0.0 then 0.0
      else
        val precision = common / predTokens.size
        val recall    = common / truthTokens.size
        2.0 * precision * recall / (precision + recall)

class AnswerMatch(frac: Double = 1.0, answerField: String = "answer") extends Metric:
  val name: String = if frac >= 1.0 then "answer_exact_match" else s"answer_match_$frac"

  private val emMetric = new ExactMatch(answerField)
  private val f1Metric = new F1Score(answerField)

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    if frac >= 1.0 then emMetric.score(example, prediction, trace)
    else f1Metric.score(example, prediction, trace).map(f1 => if f1 >= frac then 1.0 else 0.0)

/** Upstream `answer_passage_match` (`dspy/evaluate/metrics.py`, `has_answer` in `dspy/dsp/utils/dpr.py`): an answer
  * matches when its token sequence occurs in the tokens of a passage. An answer with no tokens matches each passage, as
  * upstream.
  */
class PassageMatch(contextField: String = "context", answerField: String = "answer") extends Metric:
  val name: String = "answer_passage_match"

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    for
      passages <- MetricHelpers.passageValues(prediction.get(contextField), contextField)
      answers  <- MetricHelpers.textValues(example.get(answerField), answerField, "Example")
    yield
      val answerTokens = answers.map(tokens)
      if passages.map(tokens).exists(passage => answerTokens.exists(passage.containsSlice)) then 1.0 else 0.0

  private def tokens(text: String): Vector[String] = NormalizeText.dprTokens(NormalizeText(text))

class FunctionMetric(val name: String, fn: (Example, RawPrediction, Vector[TraceEntry]) => Either[DspyError, Double])
    extends Metric:
  // The wrapped `fn` stays PURE (no RuntimeContext) — function metrics are plain `(example, prediction, trace)`
  // callables; the ambient context is accepted to satisfy the trait and ignored here.
  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    fn(example, prediction, trace)

object FunctionMetric:
  def apply(name: String)(fn: (Example, RawPrediction) => Either[DspyError, Double]): FunctionMetric =
    new FunctionMetric(name, (e, p, _) => fn(e, p))

  def bool(name: String)(fn: (Example, RawPrediction) => Boolean): FunctionMetric =
    new FunctionMetric(name, (e, p, _) => Right(if fn(e, p) then 1.0 else 0.0))
