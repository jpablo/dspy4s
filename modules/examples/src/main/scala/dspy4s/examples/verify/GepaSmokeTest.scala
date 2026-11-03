/*
 * Real-LM smoke / efficacy harness for GEPA (Genetic-Pareto reflective prompt evolution, PORT_GAPS G-12).
 *
 * Mirrors OptimizerSmokeTest: an instruction-SENSITIVE classification task (does the text contain a digit ->
 * "HAS_NUM" / "NO_NUM"). The vague baseline instruction usually has a low score. GEPA must reflect on its mistakes
 * (through a FeedbackMetric that explains the rule) and evolve a better instruction. The run covers
 * FeedbackMetric -> GepaAdapter (failure-aware traces) -> reflective dataset -> reflection LM -> Pareto loop
 * against a real model. It calls sys.error for an error of GEPA, and when each LM call failed.
 *
 * Run:  OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"
 * Tune: DSPY_MODEL (default gpt-4o-mini), OPENAI_BASE_URL, GEPA_METRIC_CALLS (default 60), GEPA_MINIBATCH (default 3).
 */
package dspy4s.examples.verify

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, TraceEntry}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.examples.Demo
import dspy4s.gepa.{Gepa, GepaBudget, GepaConfig, MetricCallCount, MinibatchSize}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.signatures.Signature

object GepaSmokeTest:

  val vagueBaselineInstruction = "Answer the question."

  private def example(text: String, label: String): Example =
    Example(values = DynamicValues.record("text" := text, "label" := label), inputKeys = Set("text"))

  private val withNum = Vector(
    "I bought 3 apples",
    "There are 12 months in a year",
    "She ran 5 miles",
    "The recipe needs 2 cups",
    "We have 7 days left",
    "He scored 21 points",
    "The box weighs 9 kg",
    "They planted 40 trees"
  )
  private val noNum = Vector(
    "The sky is clear today",
    "Dogs love the park",
    "She enjoys mystery novels",
    "The coffee smells great",
    "Birds sang in the trees",
    "He painted the fence",
    "We walked on the beach",
    "The soup is salty"
  )

  // 5 of each for training (reflection minibatches), 3 of each for validation (frontier scores and the best candidate).
  val trainset: Vector[Example] = withNum.take(5).map(example(_, "HAS_NUM")) ++ noNum.take(5).map(example(_, "NO_NUM"))
  val valset: Vector[Example]   = withNum.drop(5).map(example(_, "HAS_NUM")) ++ noNum.drop(5).map(example(_, "NO_NUM"))

  /** Exact label match + feedback that states the rule on a miss — the reflection signal GEPA learns from. */
  val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "label_match"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val text    = example.get("text").map(DynamicValues.renderText).getOrElse("")
      val gold    = example.get("label").map(DynamicValues.renderText).getOrElse("")
      val got     = prediction.get("label").map(DynamicValues.renderText).getOrElse("")
      val correct = got.trim.equalsIgnoreCase(gold.trim)
      val fb      =
        if correct then s"Correct ('$got')."
        else
          s"""Incorrect. For the text "$text" the correct label is '$gold', but you produced '$got'. """ +
            "The label must be exactly HAS_NUM when the text contains a digit, and NO_NUM otherwise."
      Right(ScoreWithFeedback(if correct then 1.0 else 0.0, fb))

@main def gepaSmokeMain(): Unit =
  import GepaSmokeTest.*

  val metricCalls   = Demo.envInt("GEPA_METRIC_CALLS", 60)
  val minibatchSize = Demo.envInt("GEPA_MINIBATCH", 3)
  val progress      = new ProgressLmCallback

  // The default model is gpt-4o-mini, because this run pins temperature 0, and a reasoning model rejects that value.
  Demo.withOpenAi(defaultModel = "gpt-4o-mini", callbacks = Vector(progress)) { lm =>
    val baseLayout = Signature.fromString("text -> label", vagueBaselineInstruction).layout
    val student    = DynamicPredict(layout = baseLayout, config = DynamicValues.record("temperature" := 0.0))

    // GEPA uses the same model as both task LM (ambient) and reflection LM here; pass a stronger model as the
    // reflection LM in real use.
    // Opt in to the perfect-score early stop so the harness doesn't burn its post-convergence budget on
    // skip-perfect minibatches (the library default is off, matching upstream gepa's run-to-budget behavior).
    val gepa = new Gepa[DynamicPredict](
      metric,
      reflectionLm = lm,
      GepaConfig(
        budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(metricCalls)),
        reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatchSize),
        stopOnPerfectScore = true,
        seed = 0L
      )
    )

    println(s"[gepa-smoke] model=${lm.model}  budget=$metricCalls metric calls  minibatch=$minibatchSize")
    println(s"""[gepa-smoke] task: HAS_NUM/NO_NUM   baseline instruction: "$vagueBaselineInstruction"""")
    println(s"[gepa-smoke] train=${trainset.size}, val=${valset.size}   (one '.' per LM call, 'x' = a failed call)\n")

    gepa.compile(student, trainset = trainset, valset = valset) match
      case Left(error)   => sys.error(s"[gepa-smoke] GEPA stopped with an error: ${error.message}")
      case Right(result) =>
        println(s"\n\n[gepa-smoke] ${progress.count} LM calls; ${result.numCandidates} candidates explored.")
        println(f"[gepa-smoke] seed validation score: ${result.state.aggregateScore(0)}%.3f / 1.0")
        println(f"[gepa-smoke] best validation score: ${result.bestScore}%.3f / 1.0 (${result.bestScore * 100}%.1f%%)")
        println(s"[gepa-smoke] stop reason: ${result.stopReason}")
        val discovered = result.bestProgram.layout.instructions.getOrElse("(none)")
        println(s"""[gepa-smoke] discovered instruction:\n    "$discovered"""")
        if progress.count > 0 && progress.errorCount == progress.count then
          sys.error(s"[gepa-smoke] each of the ${progress.count} LM calls failed.")
        println("[gepa-smoke] done: GEPA ran end-to-end against a live model.")
  }
