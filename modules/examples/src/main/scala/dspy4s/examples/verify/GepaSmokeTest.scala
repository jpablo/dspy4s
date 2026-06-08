/*
 * Real-LM smoke / efficacy harness for GEPA (Genetic-Pareto reflective prompt evolution, PORT_GAPS G-12).
 *
 * Mirrors OptimizerSmokeTest: an instruction-SENSITIVE classification task (does the text contain a digit ->
 * "HAS_NUM" / "NO_NUM"). The vague baseline instruction under-scores; GEPA must reflect on its mistakes (via a
 * FeedbackMetric that explains the rule) and evolve a better instruction. Validates the whole tower —
 * FeedbackMetric -> GepaAdapter (failure-aware traces) -> reflective dataset -> reflection LM -> Pareto loop —
 * against a real model.
 *
 * Run:  OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"
 * Tune: OPENAI_MODEL (default gpt-4o-mini), GEPA_METRIC_CALLS (default 60), GEPA_MINIBATCH (default 3).
 */
package dspy4s.examples.verify

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{CallbackEvent, CallbackHandler, DspyError, DynamicPrediction, DynamicValues, Example, LmEndEvent, RuntimeContext, TraceEntry}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.gepa.{Gepa, GepaConfig}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.programs.DynamicPredict

import java.util.concurrent.atomic.AtomicInteger

object GepaSmokeTest:

  val signatureDsl            = "text -> label"
  val vagueBaselineInstruction = "Answer the question."

  private def example(text: String, label: String): Example =
    Example(values = DynamicValues.record("text" := text, "label" := label), inputKeys = Set("text"))

  private val withNum = Vector(
    "I bought 3 apples", "There are 12 months in a year", "She ran 5 miles", "The recipe needs 2 cups",
    "We have 7 days left", "He scored 21 points", "The box weighs 9 kg", "They planted 40 trees"
  )
  private val noNum = Vector(
    "The sky is clear today", "Dogs love the park", "She enjoys mystery novels", "The coffee smells great",
    "Birds sang in the trees", "He painted the fence", "We walked on the beach", "The soup is salty"
  )

  // 5 of each for training (reflection minibatches), 3 of each held out for validation (frontier scoring).
  val trainset: Vector[Example] = withNum.take(5).map(example(_, "HAS_NUM")) ++ noNum.take(5).map(example(_, "NO_NUM"))
  val valset: Vector[Example]   = withNum.drop(5).map(example(_, "HAS_NUM")) ++ noNum.drop(5).map(example(_, "NO_NUM"))

  /** Exact label match + feedback that states the rule on a miss — the reflection signal GEPA learns from. */
  val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "label_match"
    override def feedback(
        example: Example,
        prediction: DynamicPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val text = example.get("text").map(DynamicValues.renderText).getOrElse("")
      val gold = example.get("label").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("label").map(DynamicValues.renderText).getOrElse("")
      val correct = got.trim.equalsIgnoreCase(gold.trim)
      val fb =
        if correct then s"Correct ('$got')."
        else s"""Incorrect. For the text "$text" the correct label is '$gold', but you produced '$got'. """ +
          "The label must be exactly HAS_NUM when the text contains a digit, and NO_NUM otherwise."
      Right(ScoreWithFeedback(if correct then 1.0 else 0.0, fb))

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

  /** Prints a char per LM call so the otherwise-silent reflective loop visibly makes progress. */
  final class ProgressLmCallback extends CallbackHandler:
    private val calls = new AtomicInteger(0)
    def count: Int    = calls.get()
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
      case _: LmEndEvent => val _ = calls.incrementAndGet(); System.out.print("."); System.out.flush()
      case _             => ()

@main def gepaSmokeMain(): Unit =
  import GepaSmokeTest.*

  val model         = sys.env.getOrElse("OPENAI_MODEL", "gpt-4o-mini")
  val metricCalls   = envInt("GEPA_METRIC_CALLS", 60)
  val minibatchSize = envInt("GEPA_MINIBATCH", 3)

  OpenAiLanguageModel.fromEnv(model) match
    case Left(err) =>
      println(s"[gepa-smoke] Skipping — no live LM: ${err.message}")
      println("""[gepa-smoke] Set OPENAI_API_KEY and re-run: sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"""")

    case Right(lm) =>
      val baseLayout = SignatureDsl.parse(signatureDsl).toOption.get.withInstructions(Some(vagueBaselineInstruction))
      // Pin temperature=0 so before/after scores are real, not sampling noise.
      val student   = DynamicPredict(layout = baseLayout, config = DynamicValues.record("temperature" := 0.0))
      val progress  = new ProgressLmCallback

      RuntimeEnvironment.withSettings(
        RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()), callbacks = Vector(progress))
      ) {
        given RuntimeContext = RuntimeEnvironment.current

        // GEPA uses the same model as both task LM (ambient) and reflection LM here; pass a stronger model as the
        // reflection LM in real use.
        val gepa = new Gepa[DynamicPredict](metric, reflectionLm = lm, GepaConfig(maxMetricCalls = metricCalls, reflectionMinibatchSize = minibatchSize, seed = 0L))

        println(s"[gepa-smoke] model=$model  budget=$metricCalls metric calls  minibatch=$minibatchSize")
        println(s"""[gepa-smoke] task: HAS_NUM/NO_NUM   baseline instruction: "$vagueBaselineInstruction"""")
        println(s"[gepa-smoke] train=${trainset.size}, val=${valset.size}   (one '.' per LM call)\n")

        val result = gepa.compile(student, trainset = trainset, valset = valset)

        println(s"\n\n[gepa-smoke] ${progress.count} LM calls; ${result.numCandidates} candidates explored.")
        println(f"[gepa-smoke] best validation score: ${result.bestScore}%.1f%%-of-1.0")
        println(s"""[gepa-smoke] discovered instruction:\n    "${result.bestCandidate.getOrElse("self", "(none)")}"""")
        println("[gepa-smoke] done — GEPA ran end-to-end against a live model.")
      }
