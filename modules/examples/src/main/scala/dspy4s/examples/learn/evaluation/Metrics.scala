/**
 * Metrics
 *
 * Source:   docs/docs/learn/evaluation/metrics.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/metrics.md
 * Status:   translated (function metrics + Evaluate, snippets 1/3/4/5; LLM-as-judge, snippet 6). Context-aware
 *           metrics (2) and trace-over-retrieval-hops (7) remain noted (dspy4s has no retriever). The
 *           LLM-as-judge metric (6) is now runnable — `Metric.score` carries `(using RuntimeContext)`
 *           (PORT_GAPS G-6), so a metric can run a judge over an LM; the ported `SemanticF1` /
 *           `CompleteAndGrounded` (`dspy4s.evaluate.metrics`) are concrete examples, exercised by
 *           `metricsJudgeMain` below.
 *
 * dspy4s metrics implement `Metric` (`score(example, prediction, trace) => Either[DspyError, Double]`).
 * `FunctionMetric(name) { (example, pred) => … }` / `FunctionMetric.bool(name) { … }` wrap a plain function.
 */
package dspy4s.examples.learn.evaluation

import dspy4s.core.contracts.{DspyError, DynamicPrediction, DynamicValues, Example, RuntimeContext, :=}
import dspy4s.evaluate.{Evaluate, EvaluateConfig}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.{FunctionMetric, SemanticF1}
import dspy4s.examples.Demo
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
  // --8<-- [start:metric-function]
  val validateAnswer: FunctionMetric = FunctionMetric.bool("validate_answer") { (example, pred) =>
    exField(example, "answer").toLowerCase == predField(pred, "answer").toLowerCase
  }
  // --8<-- [end:metric-function]

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
    // Offline example code: these metrics don't call an LM, so a default context suffices. A real
    // LM-judged metric would thread `RuntimeEnvironment.current` here instead.
    given RuntimeContext = RuntimeContext()
    devset.map(x => program(x).flatMap(pred => metric.score(x, pred)))

  // ── Snippet 4 (lines 72–80) — the built-in Evaluate runner ──
  // | evaluator = Evaluate(devset=YOUR_DEVSET, num_threads=1, display_progress=True, display_table=5)
  // | evaluator(YOUR_PROGRAM, metric=YOUR_METRIC)
  // --8<-- [start:metric-evaluate]
  def evaluator(devset: Vector[Example], metric: Metric): Evaluate =
    new Evaluate(EvaluateConfig(
      devset          = devset,
      metric          = metric,
      numThreads      = Some(1),
      displayProgress = true,
      displayTable    = Right(5)
    ))
  // --8<-- [end:metric-evaluate]
  // Launch: `evaluator(devset, metric).apply()(program)(using RuntimeContext)` — the `using` is the
  // ambient RuntimeContext; `program` is `Example => Either[DspyError, DynamicPrediction]`.

  // ── Snippet 6 (lines 101–116) — LLM-as-judge metric ──
  // Now runnable: `Metric.score` takes `(using RuntimeContext)` (PORT_GAPS G-6), so a metric can run a judge
  // sub-program over an LM. The ported `SemanticF1` (in `dspy4s.evaluate.metrics`) is a concrete LLM-judged
  // metric — it asks an LM to judge recall+precision of a response vs the ground truth, then returns their F1.
  // The `Assess` spec above is the signature a bespoke quality-judge metric would use the same way.
  // (Exercised by `metricsJudgeMain` below — it needs `OPENAI_API_KEY`.)
  // --8<-- [start:metric-judge]
  val semanticF1: Metric = SemanticF1()
  // --8<-- [end:metric-judge]

  // ── Snippet 7 (lines 134–142) — validate_hops over the trace ──
  // Needs retrieval (`outputs.query` hops) and `answer_exact_match_str`; neither is ported.

  /** A small devset row, for wiring the metrics/evaluator above. */
  def example(question: String, answer: String): Example =
    Example("question" -> DynamicValues.fromAny(question), "answer" -> DynamicValues.fromAny(answer))
      .withInputs(Set("question"))

// Pure (no LM). Run with: sbt "examples/runMain dspy4s.examples.learn.evaluation.metricsMain"
@main def metricsMain(): Unit =
  given RuntimeContext = RuntimeContext()
  val ex      = Metrics.example("What is the capital of France?", "Paris")
  def pred(answer: String): DynamicPrediction =
    DynamicPrediction(values = DynamicValues.recordFromEntries(Seq("answer" -> DynamicValues.fromAny(answer))))
  println("validate_answer('paris'): " + Metrics.validateAnswer.score(ex, pred("paris")))
  println("validate_answer('Lyon'):  " + Metrics.validateAnswer.score(ex, pred("Lyon")))

// Snippet 6 — the LLM-judged `SemanticF1` metric, run against a live model.
// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.evaluation.metricsJudgeMain"
@main def metricsJudgeMain(): Unit = Demo.withLm {
  // SemanticF1 defaults: question on the example, ground truth + system response in the `response` field.
  val gold = Example("question" := "What is the capital of France?", "response" := "Paris is the capital of France.")
    .withInputs(Set("question"))
  val pred = DynamicPrediction(values = DynamicValues.recordFromEntries(Seq(
    "response" -> DynamicValues.fromAny("The capital of France is Paris.")
  )))
  println("SemanticF1 score: " + Metrics.semanticF1.score(gold, pred))
}
