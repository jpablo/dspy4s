/**
 * Metrics
 *
 * Source:   docs/docs/learn/evaluation/metrics.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/metrics.md
 * Status:   translated (function metrics + Evaluate, snippets 1/3/4/5). Context-aware metrics (2),
 *           LLM-as-judge metrics (6) and trace-over-retrieval-hops (7) are noted: dspy4s has no
 *           retriever, and `Metric.score` carries no `RuntimeContext` so a metric can't call an LM.
 *
 * dspy4s metrics implement `Metric` (`score(example, prediction, trace) => Either[DspyError, Double]`).
 * `FunctionMetric(name) { (example, pred) => … }` / `FunctionMetric.bool(name) { … }` wrap a plain function.
 */
package dspy4s.examples.learn.evaluation

import dspy4s.core.contracts.{DspyError, DynamicPrediction, DynamicValues, Example}
import dspy4s.evaluate.{Evaluate, EvaluateConfig}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.FunctionMetric
import dspy4s.typed.{InputField, OutputField, Spec}

// Snippet 5 (lines 89–97) — the LLM-judge signature, as a spec trait (must be top-level for Mirror).
// | class Assess(dspy.Signature):
// |     """Assess the quality of a tweet along the specified dimension."""
// |     assessed_text = dspy.InputField()
// |     assessment_question = dspy.InputField()
// |     assessment_answer: bool = dspy.OutputField()
trait Assess extends Spec:
  def assessed_text:       InputField[String]
  def assessment_question: InputField[String]
  def assessment_answer:   OutputField[Boolean]

object Metrics:

  private def predField(pred: DynamicPrediction, key: String): String =
    pred.get(key).map(DynamicValues.renderText).getOrElse("")
  private def exField(example: Example, key: String): String =
    example.get(key).map(DynamicValues.renderText).getOrElse("")

  // ── Snippet 1 (lines 29–32) ────────────────────
  // | def validate_answer(example, pred, trace=None):
  // |     return example.answer.lower() == pred.answer.lower()
  val validateAnswer: FunctionMetric = FunctionMetric.bool("validate_answer") { (example, pred) =>
    exField(example, "answer").toLowerCase == predField(pred, "answer").toLowerCase
  }

  // ── Snippet 2 (lines 41–53) — context-aware metric ──
  // | answer_match = example.answer.lower() == pred.answer.lower()
  // | context_match = any((pred.answer.lower() in c) for c in pred.context)  # needs a retriever
  // | trace is None -> (answer_match + context_match) / 2.0  else answer_match and context_match
  // The `context_match` half needs a retriever (`pred.context`), which dspy4s doesn't have; the answer
  // half + the eval-vs-bootstrap branch (Python's `trace is None`) carry over to the 3-arg form
  // (`trace.isEmpty` during evaluation/optimization; non-empty during bootstrapping):
  val validateAnswerOnly: FunctionMetric =
    new FunctionMetric("validate_answer_only", { (example, pred, _) =>
      val answerMatch = exField(example, "answer").toLowerCase == predField(pred, "answer").toLowerCase
      Right(if answerMatch then 1.0 else 0.0)
    })

  // ── Snippet 3 (lines 62–68) — a manual evaluation loop ──
  // | scores = []
  // | for x in devset:
  // |     pred = program(**x.inputs()); score = metric(x, pred); scores.append(score)
  def manualScores(
      devset: Vector[Example],
      metric: Metric,
      program: Example => Either[DspyError, DynamicPrediction]
  ): Vector[Either[DspyError, Double]] =
    devset.map(x => program(x).flatMap(pred => metric.score(x, pred)))

  // ── Snippet 4 (lines 72–80) — the built-in Evaluate runner ──
  // | evaluator = Evaluate(devset=YOUR_DEVSET, num_threads=1, display_progress=True, display_table=5)
  // | evaluator(YOUR_PROGRAM, metric=YOUR_METRIC)
  def evaluator(devset: Vector[Example], metric: Metric): Evaluate =
    new Evaluate(EvaluateConfig(
      devset          = devset,
      metric          = metric,
      numThreads      = Some(1),
      displayProgress = true,
      displayTable    = Right(5)   // display_table=5 (table rendering itself is deferred in dspy4s)
    ))
  // Launch: `evaluator(devset, metric).apply()(program)(using RuntimeContext)` — the `using` is the
  // ambient RuntimeContext; `program` is `Example => Either[DspyError, DynamicPrediction]`.

  // ── Snippet 6 (lines 101–116) — LLM-as-judge metric ──
  // A metric that calls `dspy.Predict(Assess)` inside its body is not expressible: `Metric.score` takes
  // no `RuntimeContext`, so a metric can't invoke an LM. (LLM-judged metrics — `SemanticF1` etc. — are
  // tracked as deferred Phase-6 v2 work.) The `Assess` spec above is the signature it would use.

  // ── Snippet 7 (lines 134–142) — validate_hops over the trace ──
  // Needs retrieval (`outputs.query` hops) and `answer_exact_match_str`; neither is ported.

  /** A small devset row, for wiring the metrics/evaluator above. */
  def example(question: String, answer: String): Example =
    Example("question" -> DynamicValues.fromAny(question), "answer" -> DynamicValues.fromAny(answer))
      .withInputs(Set("question"))

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.learn.evaluation.metricsMain"
@main def metricsMain(): Unit =
  val ex      = Metrics.example("What is the capital of France?", "Paris")
  def pred(answer: String): DynamicPrediction =
    DynamicPrediction(values = DynamicValues.recordFromEntries(Seq("answer" -> DynamicValues.fromAny(answer))))
  println("validate_answer('paris'): " + Metrics.validateAnswer.score(ex, pred("paris")))
  println("validate_answer('Lyon'):  " + Metrics.validateAnswer.score(ex, pred("Lyon")))
