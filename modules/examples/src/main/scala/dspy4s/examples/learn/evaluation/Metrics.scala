/** Metrics
  *
  * Source: docs/docs/learn/evaluation/metrics.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/metrics.md Status: translated (function
  * metrics, snippets 1 and 2; a manual loop and `Evaluate`, snippets 3 and 4; an LM judge, snippets 5 and 6). Snippet 7
  * has a note and no code. The note tells why.
  *
  * A dspy4s metric implements `Metric` (`score(example, prediction, trace)(using RuntimeContext)`, which gives
  * `Either[DspyError, Double]`). The `using` value lets a metric run a judge program over an LM. `tweetMetric` below
  * and the built-in `SemanticF1` and `CompleteAndGrounded` (`dspy4s.evaluate.metrics`) do this. `FunctionMetric(name) {
  * (example, pred) => … }` and `FunctionMetric.bool(name) { … }` wrap a plain function, and that function gets no
  * context.
  */
package dspy4s.examples.learn.evaluation

import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeContext, ThreadCount, TraceEntry, :=}
import dspy4s.core.data.{RawPrediction, Example}
import dspy4s.evaluate.{Evaluate, TableDisplay}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.{FunctionMetric, MetricHelpers, SemanticF1}
import dspy4s.examples.Demo
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.DynamicValue

// Snippet 5 (lines 90–97): the signature of the LM judge, as a spec trait.
// | # Define the signature for automatic assessments.
// | class Assess(dspy.Signature):
// |     """Assess the quality of a tweet along the specified dimension."""
// |
// |     assessed_text = dspy.InputField()
// |     assessment_question = dspy.InputField()
// |     assessment_answer: bool = dspy.OutputField()
// The docstring goes to `instructions`, where `Metrics.tweetMetric` makes the signature.
trait Assess extends Spec:
  def assessed_text: InputField[String]
  def assessment_question: InputField[String]
  def assessment_answer: OutputField[Boolean]

object Metrics:

  private def predField(pred: RawPrediction, key: String): String =
    pred.get(key).map(DynamicValues.renderText).getOrElse("")
  private def exField(example: Example, key: String): String =
    example.get(key).map(DynamicValues.renderText).getOrElse("")

  /** The text of a field. A missing field is an error, as `pred.answer` raises in Python. */
  private def required(value: Option[DynamicValue], name: String): Either[DspyError, String] =
    value.map(DynamicValues.renderText).toRight(NotFoundError(name, s"The field '$name' is missing"))

  // ── Snippet 1 (lines 29–32) ────────────────────
  // | def validate_answer(example, pred, trace=None):
  // |     return example.answer.lower() == pred.answer.lower()
  // --8<-- [start:metric-function]
  val validateAnswer: FunctionMetric = FunctionMetric.bool("validate_answer") { (example, pred) =>
    exField(example, "answer").toLowerCase == predField(pred, "answer").toLowerCase
  }
  // --8<-- [end:metric-function]

  // ── Snippet 2 (lines 41–53): a metric with more than one rule ──
  // | def validate_context_and_answer(example, pred, trace=None):
  // |     # check the gold label and the predicted answer are the same
  // |     answer_match = example.answer.lower() == pred.answer.lower()
  // |
  // |     # check the predicted answer comes from one of the retrieved contexts
  // |     context_match = any((pred.answer.lower() in c) for c in pred.context)
  // |
  // |     if trace is None: # if we're doing evaluation or optimization
  // |         return (answer_match + context_match) / 2.0
  // |     else: # if we're doing bootstrapping, i.e. self-generating good demonstrations of each step
  // |         return answer_match and context_match
  // `trace is None` becomes `trace.isEmpty`: the trace is empty during an evaluation, and it has entries during a
  // bootstrap. The metric only reads `pred.context`, so it needs no retriever.
  val validateContextAndAnswer: FunctionMetric = new FunctionMetric(
    "validate_context_and_answer",
    (example, pred, trace) =>
      for
        gold     <- required(example.get("answer"), "answer")
        answer   <- required(pred.get("answer"), "answer")
        contexts <- MetricHelpers.passageValues(pred.get("context"), "context")
      yield
        val answerMatch  = gold.toLowerCase == answer.toLowerCase
        val contextMatch = contexts.exists(_.contains(answer.toLowerCase))
        if trace.isEmpty then Vector(answerMatch, contextMatch).count(identity) / 2.0
        else if answerMatch && contextMatch then 1.0
        else 0.0
  )

  // ── Snippet 3 (lines 62–68) — a manual evaluation loop ──
  // | scores = []
  // | for x in devset:
  // |     pred = program(**x.inputs()); score = metric(x, pred); scores.append(score)
  def manualScores(
      devset : Vector[Example],
      metric : Metric,
      program: Example => Either[DspyError, RawPrediction]
  )(using RuntimeContext): Vector[Either[DspyError, Double]] =
    devset.map(x => program(x).flatMap(pred => metric.score(x, pred)))

  // ── Snippet 4 (lines 72–80) — the built-in Evaluate runner ──
  // | evaluator = Evaluate(devset=YOUR_DEVSET, num_threads=1, display_progress=True, display_table=5)
  // | evaluator(YOUR_PROGRAM, metric=YOUR_METRIC)
  // --8<-- [start:metric-evaluate]
  def evaluator(devset: Vector[Example], metric: Metric): Evaluate =
    Evaluate(
      devset = devset,
      metric = metric,
      numThreads = Some(ThreadCount(1)),
      displayProgress = true,
      displayTable = TableDisplay.Rows(5)
    )
  // --8<-- [end:metric-evaluate]
  // Launch: `evaluator(devset, metric)()(program)(using RuntimeContext)` — the `using` is the
  // ambient RuntimeContext; `program` is `Example => Either[DspyError, RawPrediction]`.

  // ── Snippet 6 (lines 102–115): a metric that runs an LM judge ──
  // | def metric(gold, pred, trace=None):
  // |     question, answer, tweet = gold.question, gold.answer, pred.output
  // |
  // |     engaging = "Does the assessed text make for a self-contained, engaging tweet?"
  // |     correct = f"The text should answer `{question}` with `{answer}`. Does the assessed text contain this answer?"
  // |
  // |     correct =  dspy.Predict(Assess)(assessed_text=tweet, assessment_question=correct)
  // |     engaging = dspy.Predict(Assess)(assessed_text=tweet, assessment_question=engaging)
  // |
  // |     correct, engaging = [m.assessment_answer for m in [correct, engaging]]
  // |     score = (correct + engaging) if correct and (len(tweet) <= 280) else 0
  // |
  // |     if trace is not None: return score >= 2
  // |     return score / 2.0
  // The function of a `FunctionMetric` gets no context, so a metric with a judge is a `new Metric`. A failed judge call
  // is an error and not a score of 0, as in Python. `codePointCount` gives the length of Python `len`.
  private val assess = Predict(
    Signature.of[Assess](instructions = "Assess the quality of a tweet along the specified dimension.")
  )

  val tweetMetric: Metric = new Metric:
    override val name: String = "tweet_metric"

    override def score(gold: Example, pred: RawPrediction, trace: Vector[TraceEntry])(using
        RuntimeContext
    ): Either[DspyError, Double] =
      val engagingAsk = "Does the assessed text make for a self-contained, engaging tweet?"

      for
        question  <- required(gold.get("question"), "question")
        answer    <- required(gold.get("answer"), "answer")
        tweet     <- required(pred.get("output"), "output")
        correctAsk = s"The text should answer `$question` with `$answer`. Does the assessed text contain this answer?"
        correct   <- assess((assessed_text = tweet, assessment_question = correctAsk)).map(_.output.assessment_answer)
        engaging  <- assess((assessed_text = tweet, assessment_question = engagingAsk)).map(_.output.assessment_answer)
      yield
        val score = if correct && tweet.codePointCount(0, tweet.length) <= 280 then (if engaging then 2 else 1) else 0
        if trace.nonEmpty then (if score >= 2 then 1.0 else 0.0) else score / 2.0

  // `SemanticF1` is a built-in metric with an LM judge. It is not a translation of snippet 6: it asks the LM to judge
  // the recall and the precision of a response against the ground truth, and it gives their F1.
  // --8<-- [start:metric-judge]
  val semanticF1: Metric = SemanticF1()
  // --8<-- [end:metric-judge]

  // ── Snippet 7 (lines 134–142) — validate_hops over the trace ──
  // | def validate_hops(example, pred, trace=None):
  // |     hops = [example.question] + [outputs.query for *_, outputs in trace if 'query' in outputs]
  // |
  // |     if max([len(h) for h in hops]) > 100: return False
  // |     if any(dspy.evaluate.answer_exact_match_str(hops[idx], hops[:idx], frac=0.8) for idx in range(2, len(hops))): return False
  // |
  // |     return True
  // Not translated. The metric reads only the trace, and `TraceEntry.outputs` holds the outputs of each call. But its
  // last rule calls `answer_exact_match_str`, and the upstream package does not define that function.

  /** A small devset row, for wiring the metrics/evaluator above. */
  def example(question: String, answer: String): Example =
    Example("question" := question, "answer" := answer).withInputs(Set("question"))

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.learn.evaluation.metricsMain"
@main def metricsMain(): Unit =
  given RuntimeContext                    = RuntimeContext()
  val ex                                  = Metrics.example("What is the capital of France?", "Paris")
  def pred(answer: String): RawPrediction = RawPrediction(values = DynamicValues.record("answer" := answer))
  println("validate_answer('paris'): " + Metrics.validateAnswer.score(ex, pred("paris")))
  println("validate_answer('Lyon'):  " + Metrics.validateAnswer.score(ex, pred("Lyon")))

// Snippet 6 and the built-in `SemanticF1`, with a live model.
// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.evaluation.metricsJudgeMain"
@main def metricsJudgeMain(): Unit =
  Demo.withLm {
    val question = Metrics.example("What is the capital of France?", "Paris")
    val tweet    = RawPrediction(values = DynamicValues.record("output" := "Paris is the capital of France."))
    println("tweet_metric score: " + Metrics.tweetMetric.score(question, tweet))

    // SemanticF1 defaults: question on the example, ground truth + system response in the `response` field.
    val gold = Example("question" := "What is the capital of France?", "response" := "Paris is the capital of France.")
      .withInputs(Set("question"))
    val pred = RawPrediction(values = DynamicValues.record("response" := "The capital of France is Paris."))
    println("SemanticF1 score: " + Metrics.semanticF1.score(gold, pred))
  }
