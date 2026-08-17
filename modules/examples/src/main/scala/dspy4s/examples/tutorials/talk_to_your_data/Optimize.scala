/** Talk to Your Data: GEPA optimization of the planner.
  *
  * The student is a functional `RecordProgram`. GEPA changes only the parameter value at the stable planner ID. The
  * metric executes each proposed `QueryPlan` on the JVM and gives exact feedback.
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.{Evaluate, Metric}
import dspy4s.examples.Demo
import dspy4s.gepa.{Gepa, GepaConfig, InstructionProposer, MetricCallCount, MinibatchSize}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.{IO, ZIO}
import zio.blocks.schema.Schema

final case class ReflectionPrompt(currentInstruction: String, records: String) derives Schema
final case class ReflectionAnswer(instruction: String) derives Schema

object Optimize:

  val plannerId: ParameterId = ParameterId("talk-data/planner")
  private val schema         = Dataset.schemaDescription

  private def toExample(gold: Dataset.GoldQuestion): Example =
    Example(
      values = DynamicValues.record(
        "question" := gold.question,
        "schema"   := schema,
        "answer"   := gold.answer(Dataset.orders),
        "goldplan" := Agent.describePlan(gold.plan)
      ),
      inputKeys = Set("question", "schema")
    )

  private val (trainGold, valGold) = Dataset.goldset.splitAt(16)
  val trainset: Vector[Example]    = trainGold.map(toExample)
  val valset: Vector[Example]      = valGold.map(toExample)

  private val plannerOutputShape = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape

  val metric: FeedbackMetric = new FeedbackMetric:
    val name: String = "answer_match"

    def feedback(
        example        : Example,
        prediction     : RawPrediction,
        events         : Vector[ProgramEvent],
        component      : Option[ParameterId],
        componentEvents: Vector[ProgramEvent]
    ): IO[DspyError, ScoreWithFeedback] =
      val question = example.get("question").map(DynamicValues.renderText).getOrElse("")
      val gold     = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val goldPlan = example.get("goldplan").map(DynamicValues.renderText).getOrElse("")
      ZIO.succeed(plannerOutputShape.decode(prediction.values) match
        case Left(error) =>
          ScoreWithFeedback(
            0.0,
            s"The plan for '$question' was not valid: ${error.message}. Output every QueryPlan field."
          )
        case Right(plan) =>
          val computed = QueryEngine.run(plan, Dataset.orders).answer
          if QueryEngine.answersMatch(gold, computed) then ScoreWithFeedback(1.0, s"Correct: $computed")
          else
            ScoreWithFeedback(
              0.0,
              s"The plan for '$question' produced '$computed', but the correct answer is '$gold'. " +
                s"A correct plan is:\n$goldPlan"
            ))

  private val evaluationMetric: Metric = new Metric:
    val name: String = metric.name
    def score(example: Example, prediction: RawPrediction, events: Vector[ProgramEvent]) =
      metric.score(example, prediction, events)

  def planner(instructions: String): RecordProgram[Question, QueryPlan] =
    val signature = Agent.plannerSignature(instructions)
    Program
      .predict(plannerId, signature, config = DynamicValues.record("temperature" := 0.0))
      .fromRecords(signature.inputShape)

  private val reflector = Program
    .predict(
      ParameterId("talk-data/reflector"),
      Signature.derived[ReflectionPrompt, ReflectionAnswer](
        "PlannerReflection",
        "Write an improved instruction from the current instruction and failure records. Return only the instruction."
      )
    )
    .contramap[InstructionProposer.Input](input => ReflectionPrompt(input.currentInstruction, input.records.mkString("\n\n")))
    .map(value => InstructionProposer.Output(value.instruction))

  def accuracy(
      program : RecordProgram[Question, QueryPlan],
      examples: Vector[Example]
  ): ZIO[PredictionBackend, Nothing, Double] =
    Evaluate(program, examples, evaluationMetric).map(_.score / 100.0)

  final case class OptimizationReport(
      baselineAccuracy    : Double,
      optimizedAccuracy   : Double,
      optimizedInstruction: String,
      numCandidates       : Int
  )

  def run(budget: Int, minibatch: Int)(using PredictionBackend): Either[DspyError, OptimizationReport] =
    val baseline = planner(Agent.plannerInstructionsBaseline)
    val effect = for
      baselineScore <- accuracy(baseline, valset)
      result <- Gepa(
                  student = baseline,
                  trainset = trainset,
                  valset = valset,
                  metric = metric,
                  reflector = reflector,
                  config = GepaConfig(
                    maxMetricCalls = MetricCallCount.applyUnsafe(budget),
                    reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatch),
                    stopOnPerfectScore = true,
                    seed = 0L
                  )
                )
      optimizedScore <- accuracy(result.bestProgram, valset)
      instruction = result.bestProgram.program.parameters
                      .get(plannerId)
                      .flatMap(_.instructions)
                      .getOrElse(Agent.plannerInstructionsBaseline)
    yield OptimizationReport(
      baselineScore,
      optimizedScore,
      instruction,
      result.numCandidates.toInt
    )
    Demo.runEffect(effect)
