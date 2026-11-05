/** Talk to Your Data: GEPA optimization of the planner.
  *
  * The planner's job (English question -> [[QueryPlan]]) is the prompt-sensitive part: the types pin the SHAPE of a
  * plan, but not how to map "which region sold the most?" onto group-by + sort + top-1 + a category answer. GEPA
  * evolves the planner's INSTRUCTION to get that mapping right.
  *
  * The metric is computed in code rather than judged by an LLM: run the QueryPlan of the planner through the Scala
  * [[QueryEngine]], and compare the result with the gold result. The answer key comes from hand-written plans and the
  * same engine, and [[SelfCheck]] compares some answers with a direct computation. On a miss, the feedback gives GEPA a
  * correct plan to reflect on.
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, TraceEntry}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.Evaluate
import dspy4s.gepa.{Gepa, GepaBudget, GepaConfig, MetricCallCount, MinibatchSize}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.ProgramRunner
import dspy4s.programs.strategies.Predict

object Optimize:

  /** Each gold question becomes an Example: question+schema are inputs; `answer` and `goldvalue` are the gold result;
    * `goldplan` is the reflection signal the metric surfaces on a miss.
    */
  private def toExample(g: Dataset.GoldQuestion): Example =
    val gold = g.result(Dataset.orders)
    Example(
      values = DynamicValues.recordFromEntries(
        Vector(
          "question" := g.question,
          "schema"   := Dataset.schemaDescription,
          "answer"   := gold.answer,
          "goldplan" := Agent.describePlan(g.plan)
        ) ++ gold.value.map("goldvalue" := _)
      ),
      inputKeys = Set("question", "schema")
    )

  private val (trainGold, valGold) = Dataset.goldset.splitAt(16)
  val trainset: Vector[Example]    = trainGold.map(toExample)
  val valset: Vector[Example]      = valGold.map(toExample)

  /** Output shape is instruction-independent, so the baseline signature decodes any planner's QueryPlan output. */
  private val plannerOutputShape = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape

  /** Score = 1 iff the planned query, executed on the JVM, gives the gold result. Feedback teaches the rule. */
  val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "answer_match"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val q    = example.get("question").map(DynamicValues.renderText).getOrElse("")
      val gold = QueryEngine.EvalResult(
        answer = example.get("answer").map(DynamicValues.renderText).getOrElse(""),
        value = example.get("goldvalue").flatMap(DynamicValues.asDouble)
      )
      val goldPlan = example.get("goldplan").map(DynamicValues.renderText).getOrElse("")
      plannerOutputShape.decode(prediction.values) match
        case Left(err) => Right(ScoreWithFeedback(
            0.0,
            s"""Your plan for "$q" was not a valid QueryPlan (${err.message}). """ +
              "Output every field with a valid enum value (agg, column, groupBy, filters, timeRange, sort, limit, answerKind)."
          ))
        case Right(qp) => QueryEngine.run(qp, Dataset.orders) match
            case Left(problem)                                             => Right(ScoreWithFeedback(0.0, s"""Your plan for "$q" has a problem. $problem"""))
            case Right(computed) if QueryEngine.sameResult(gold, computed) =>
              Right(ScoreWithFeedback(1.0, s"Correct (${computed.answer})."))
            case Right(computed) => Right(ScoreWithFeedback(
                0.0,
                s"""Wrong for "$q": your plan evaluates to '${computed.answer}', but the correct answer is '${gold.answer}'. """ +
                  s"A plan that produces the correct answer is:\n$goldPlan\n" +
                  "Map the question's wording (totals, averages, counts, 'which/top', 'in <region/category>', date ranges) to the right fields."
              ))

  /** The accuracy of a planner on a set of examples: the mean metric score on the scale 0 to 100, as [[Evaluate]] gives
    * it, and one error for each example with no prediction. Such an example has the score 0, so a low score with errors
    * is a failure of the calls and not a set of wrong plans.
    */
  final case class Accuracy(score: Double, errors: Vector[String])

  /** The accuracy of a planner instruction on `examples` (no LLM judge). */
  def accuracy(instructions: String, examples: Vector[Example])(using RuntimeContext): Either[DspyError, Accuracy] =
    val student = Agent.planner(instructions)
    val runner  = summon[ProgramRunner[Predict[Question, QueryPlan]]]
    Evaluate(devset = examples, metric = metric)()(ex => runner.run(student, ex.inputs))
      .map(result => Accuracy(result.score, result.results.flatMap(_.error)))

  final case class OptimizationReport(
      baselineAccuracy    : Accuracy,
      optimizedAccuracy   : Accuracy,
      optimizedInstruction: String,
      numCandidates       : Int
  )

  /** Measure the baseline planner, evolve a better instruction with GEPA, and measure again, with the grounded metric.
    * The two measures use the val split, which GEPA also uses to select the best candidate. A `Left` is an error from
    * GEPA or from a measure.
    *
    * The planner sends no temperature. The default model of `Demo` is a gpt-5 model, upstream takes temperature 1.0 or
    * none for such a reasoning model, and the port sends the options as they are, so temperature 0 can fail each call.
    */
  def run(reflectionLm: LanguageModel, budget: Int, minibatch: Int)(using
      RuntimeContext
  ): Either[DspyError, OptimizationReport] =
    val gepa = new Gepa[Predict[Question, QueryPlan]](
      metric,
      reflectionLm = reflectionLm,
      GepaConfig(
        budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget)),
        reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatch),
        stopOnPerfectScore = true,
        // A reply that does not decode to a QueryPlan never reaches the metric, so it teaches the reflection this way.
        addFormatFailureAsFeedback = true,
        seed = 0L
      )
    )
    for
      baseline   <- accuracy(Agent.plannerInstructionsBaseline, valset)
      result     <- gepa.compile(Agent.planner(Agent.plannerInstructionsBaseline), trainset = trainset, valset = valset)
      instruction = result.bestProgram.signature.layout.instructions.getOrElse(Agent.plannerInstructionsBaseline)
      optimized  <- accuracy(instruction, valset)
    yield OptimizationReport(baseline, optimized, instruction, result.numCandidates)
