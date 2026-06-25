/**
 * Talk to Your Data: GEPA optimization of the planner.
 *
 * The planner's job (English question -> typed [[QueryPlan]]) is the prompt-sensitive part: the types pin the
 * SHAPE of a plan, but not how to map "which region sold the most?" onto group-by + sort + top-1 + a category
 * answer. GEPA evolves the planner's INSTRUCTION to get that mapping right.
 *
 * The metric is computed in code rather than judged by an LLM: run the planner's QueryPlan through the Scala
 * [[QueryEngine]] and compare to the gold answer (which the engine also produced, so the answer key is correct by
 * construction). On a miss, the feedback hands GEPA the *correct* plan to reflect on. That clean signal is what
 * makes the before/after lift real and reproducible.
 */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.{:=, DspyError, DynamicPrediction, DynamicValues, Example, RuntimeContext, TraceEntry}
import dspy4s.gepa.{Gepa, GepaConfig}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.ProgramCall

object Optimize:

  private val schema = Dataset.schemaDescription

  /** Each gold question becomes an Example: question+schema are inputs; `answer` is the gold; `goldplan` is the
    * reflection signal the metric surfaces on a miss. */
  private def toExample(g: Dataset.GoldQuestion): Example =
    Example(
      values = DynamicValues.record(
        "question" := g.question,
        "schema"   := schema,
        "answer"   := g.answer(Dataset.orders),
        "goldplan" := Agent.describePlan(g.plan)
      ),
      inputKeys = Set("question", "schema")
    )

  private val (trainGold, valGold) = Dataset.goldset.splitAt(16)
  val trainset: Vector[Example] = trainGold.map(toExample)
  val valset: Vector[Example]   = valGold.map(toExample)

  /** Output shape is instruction-independent, so the baseline signature decodes any planner's QueryPlan output. */
  private val plannerOutputShape = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape

  /** Score = 1 iff the planned query, executed on the JVM, yields the gold answer. Feedback teaches the rule. */
  val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "answer_match"
    override def feedback(
        example: Example,
        prediction: DynamicPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val q        = example.get("question").map(DynamicValues.renderText).getOrElse("")
      val gold     = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val goldPlan = example.get("goldplan").map(DynamicValues.renderText).getOrElse("")
      plannerOutputShape.decode(prediction.values) match
        case Left(err) =>
          Right(ScoreWithFeedback(0.0,
            s"""Your plan for "$q" was not a valid QueryPlan (${err.message}). """ +
              "Output every field with a valid enum value (agg, column, groupBy, filters, timeRange, sort, limit, answerKind)."))
        case Right(qp) =>
          val computed = QueryEngine.run(qp, Dataset.orders).answer
          if QueryEngine.answersMatch(gold, computed) then Right(ScoreWithFeedback(1.0, s"Correct ($computed)."))
          else
            Right(ScoreWithFeedback(0.0,
              s"""Wrong for "$q": your plan evaluates to '$computed', but the correct answer is '$gold'. """ +
                s"A plan that produces the correct answer is:\n$goldPlan\n" +
                "Map the question's wording (totals, averages, counts, 'which/top', 'in <region/category>', date ranges) to the right fields."))

  /** The planner as a GEPA-optimizable predictor; temperature 0 so before/after scores are signal, not noise. */
  def planner(instructions: String): DynamicPredict =
    DynamicPredict(layout = Agent.plannerSignature(instructions).layout, config = DynamicValues.record("temperature" := 0.0))

  /** Average metric score of a planner instruction over `examples`, our accuracy number (no LLM judge). */
  def accuracy(instructions: String, examples: Vector[Example])(using RuntimeContext): Double =
    if examples.isEmpty then 0.0
    else
      val student = planner(instructions)
      val scores = examples.map { ex =>
        val inputs = DynamicValues.recordFromEntries(ex.inputKeys.toVector.flatMap(k => ex.get(k).map(k -> _)))
        student.apply(ProgramCall(inputs = inputs)) match
          case Left(_)     => 0.0
          case Right(pred) => metric.feedback(ex, pred, Vector.empty, None, Vector.empty).map(_.score).getOrElse(0.0)
      }
      scores.sum / scores.size

  final case class OptimizationReport(
      baselineAccuracy: Double,
      optimizedAccuracy: Double,
      optimizedInstruction: String,
      numCandidates: Int
  )

  /** Measure the baseline planner, evolve a better instruction with GEPA, measure again, all on the held-out val
    * split, with the grounded metric. */
  def run(reflectionLm: LanguageModel, budget: Int, minibatch: Int)(using RuntimeContext): OptimizationReport =
    val baseline = accuracy(Agent.plannerInstructionsBaseline, valset)
    val gepa = new Gepa[DynamicPredict](
      metric,
      reflectionLm = reflectionLm,
      GepaConfig(maxMetricCalls = budget, reflectionMinibatchSize = minibatch, stopOnPerfectScore = true, seed = 0L)
    )
    val result   = gepa.compile(planner(Agent.plannerInstructionsBaseline), trainset = trainset, valset = valset)
    val optInstr = result.bestCandidate.getOrElse("self", Agent.plannerInstructionsBaseline)
    OptimizationReport(baseline, accuracy(optInstr, valset), optInstr, result.numCandidates)
