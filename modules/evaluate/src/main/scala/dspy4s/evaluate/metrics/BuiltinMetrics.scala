package dspy4s.evaluate.metrics

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.evaluate.contracts.Metric
import zio.blocks.schema.DynamicValue

object MetricHelpers:
  def extractString(example: Example, prediction: DynamicPrediction, fieldName: String): Either[DspyError, (String, Vector[String])] =
    val exampleValue = example.get(fieldName).map(DynamicValues.toAny) match
      case Some(text: String) => Right(Vector(text))
      case Some(seq: Iterable[?]) =>
        val strings = seq.collect { case s: String => s }.toVector
        if strings.isEmpty then Left(NotFoundError(fieldName, s"Example.$fieldName has no string values"))
        else Right(strings)
      case Some(other) => Right(Vector(other.toString))
      case None => Left(NotFoundError(fieldName, s"Example is missing field '$fieldName'"))

    val predictionValue = prediction.get(fieldName).map(DynamicValues.toAny) match
      case Some(text: String) => Right(text)
      case Some(other) => Right(other.toString)
      case None => Left(NotFoundError(fieldName, s"Prediction is missing field '$fieldName'"))

    for
      examples <- exampleValue
      pred <- predictionValue
    yield (pred, examples)

  /** Render a single field value to text for an LM-judged metric: a string verbatim, an iterable joined by
    * newlines (e.g. a retrieved-context passage list), any other scalar via `toString`. A missing field is a
    * `NotFoundError`. Used by the auto-evaluation metrics to build the judge sub-program's inputs. */
  def scoringText(value: Option[DynamicValue], fieldName: String, owner: String): Either[DspyError, String] =
    value.map(DynamicValues.toAny) match
      case Some(text: String)     => Right(text)
      case Some(seq: Iterable[?]) => Right(seq.map(_.toString).mkString("\n"))
      case Some(other)            => Right(other.toString)
      case None => Left(NotFoundError(fieldName, s"$owner is missing field '$fieldName'"))

  def maxOverReferences[A](references: Vector[String], transform: String => A, score: (A, A) => Double): (Double, A) =
    if references.isEmpty then (0.0, transform(""))
    else
      val scores = references.map(r => (score(transform(""), transform(r)), transform(r)))
      scores.maxBy(_._1)

class ExactMatch(answerField: String = "answer") extends Metric:
  val name: String = "exact_match"

  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      val refsNorm = refTexts.map(NormalizeText.apply)
      if refsNorm.exists(_ == predNorm) then 1.0 else 0.0
    }

class ContainsMatch(answerField: String = "answer") extends Metric:
  val name: String = "contains"

  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      val refsNorm = refTexts.map(NormalizeText.apply)
      if refsNorm.exists(ref => predNorm.contains(ref)) then 1.0 else 0.0
    }

class F1Score(answerField: String = "answer") extends Metric:
  val name: String = "f1"

  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    MetricHelpers.extractString(example, prediction, answerField).map { case (predText, refTexts) =>
      val predNorm = NormalizeText(predText)
      refTexts.map(r => f1Score(predNorm, NormalizeText(r))).max
    }

  private def f1Score(pred: String, truth: String): Double =
    val predTokens = pred.split("\\s+").filter(_.nonEmpty)
    val truthTokens = truth.split("\\s+").filter(_.nonEmpty)
    if predTokens.isEmpty || truthTokens.isEmpty then 0.0
    else
      val common = predTokens.intersect(truthTokens).size.toDouble
      if common == 0.0 then 0.0
      else
        val precision = common / predTokens.size
        val recall = common / truthTokens.size
        2.0 * precision * recall / (precision + recall)

class AnswerMatch(frac: Double = 1.0, answerField: String = "answer") extends Metric:
  val name: String = if frac >= 1.0 then "answer_exact_match" else s"answer_match_$frac"

  private val emMetric = new ExactMatch(answerField)
  private val f1Metric = new F1Score(answerField)

  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    if frac >= 1.0 then emMetric.score(example, prediction, trace)
    else f1Metric.score(example, prediction, trace).map(f1 => if f1 >= frac then 1.0 else 0.0)

class PassageMatch(contextField: String = "context", answerField: String = "answer") extends Metric:
  val name: String = "answer_passage_match"

  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    val contexts = prediction.get(contextField).map(DynamicValues.toAny) match
      case Some(texts: Iterable[?]) =>
        Right(texts.collect { case s: String => s }.toVector)
      case Some(text: String) =>
        Right(Vector(text))
      case Some(other) =>
        Right(Vector(other.toString))
      case None =>
        Left(NotFoundError(contextField, s"Prediction is missing field '$contextField'"))

    val answers = example.get(answerField).map(DynamicValues.toAny) match
      case Some(texts: Iterable[?]) =>
        Right(texts.collect { case s: String => s }.toVector)
      case Some(text: String) =>
        Right(Vector(text))
      case Some(other) =>
        Right(Vector(other.toString))
      case None =>
        Left(NotFoundError(answerField, s"Example is missing field '$answerField'"))

    for
      ctx <- contexts
      ans <- answers
    yield
      val passages = ctx.map(NormalizeText.dpr)
      val answersNorm = ans.map(NormalizeText.dpr)
      if passages.exists(p => answersNorm.exists(a => p.contains(a))) then 1.0 else 0.0

class FunctionMetric(val name: String, fn: (Example, DynamicPrediction, Vector[TraceEntry]) => Either[DspyError, Double]) extends Metric:
  // The wrapped `fn` stays PURE (no RuntimeContext) — function metrics are plain `(example, prediction, trace)`
  // callables; the ambient context is accepted to satisfy the trait and ignored here.
  override def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    fn(example, prediction, trace)

object FunctionMetric:
  def apply(name: String)(fn: (Example, DynamicPrediction) => Either[DspyError, Double]): FunctionMetric =
    new FunctionMetric(name, (e, p, _) => fn(e, p))

  def bool(name: String)(fn: (Example, DynamicPrediction) => Boolean): FunctionMetric =
    new FunctionMetric(name, (e, p, _) => Right(if fn(e, p) then 1.0 else 0.0))
