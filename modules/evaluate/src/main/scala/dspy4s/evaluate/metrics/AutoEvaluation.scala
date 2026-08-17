package dspy4s.evaluate.metrics

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, SignatureLayout, TypeRef}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.Metric
import dspy4s.programs.{ParameterId, PredictionBackend, Program, ProgramEvent, ProgramRunner}
import dspy4s.signatures.{Shape, Signature}
import zio.ZIO
import zio.blocks.schema.DynamicValue

object AutoEvaluation:

  def f1Score(precision: Double, recall: Double): Double =
    val boundedPrecision = math.max(0.0, math.min(1.0, precision))
    val boundedRecall    = math.max(0.0, math.min(1.0, recall))
    if boundedPrecision + boundedRecall == 0.0 then 0.0
    else 2.0 * (boundedPrecision * boundedRecall) / (boundedPrecision + boundedRecall)

  private[metrics] def input(name: String): FieldSpec = FieldSpec(name = name)

  private[metrics] def output(name: String, description: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.double, description = Some(description))

  private[metrics] def textOutput(name: String, description: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.string, description = Some(description))

  private[metrics] def judge(id: ParameterId, layout: SignatureLayout) =
    val signature = Signature[DynamicValue.Record, DynamicValue.Record](
      name = layout.name,
      layout = layout,
      inputShape = Shape.MapShape(layout.inputFields),
      outputShape = Shape.MapShape(layout.outputFields)
    )
    Program.predictStable(id, signature, name = "judge")

  private[metrics] def runJudge(
      predictor : Program[DynamicValue.Record, DynamicValue.Record],
      inputs    : DynamicValue.Record,
      readFields: Seq[String]
  ): ZIO[PredictionBackend, DspyError, Vector[Double]] =
    ProgramRunner.run(predictor, inputs).flatMap { prediction =>
      ZIO.fromEither(readFields.foldLeft[Either[DspyError, Vector[Double]]](Right(Vector.empty)) { (acc, field) =>
        for
          values <- acc
          value  <- prediction.raw.asDouble(field)
        yield values :+ value
      })
    }

object SemanticF1:
  private val recallDescription    = "fraction (out of 1.0) of ground truth covered by the system response"
  private val precisionDescription = "fraction (out of 1.0) of system response covered by the ground truth"

  private val instructions =
    "Compare a system response to the ground truth. Identify their key ideas, then report recall and precision."

  private val decompositionalInstructions =
    "Enumerate key ideas in the ground truth and system response. Discuss their overlap, then report recall and precision."

final case class SemanticF1(
    decompositional : Boolean = false,
    threshold       : Double  = 0.66,
    questionField   : String  = "question",
    groundTruthField: String  = "response",
    responseField   : String  = "response"
) extends Metric:
  val name: String = "semantic_f1"

  private val layout =
    val keyIdeaFields =
      if decompositional then
        Vector(
          AutoEvaluation.textOutput("ground_truth_key_ideas", "key ideas in the ground truth"),
          AutoEvaluation.textOutput("system_response_key_ideas", "key ideas in the system response"),
          AutoEvaluation.textOutput("discussion", "overlap between the responses")
        )
      else Vector.empty
    SignatureLayout.of(
      name = "SemanticRecallPrecision",
      inputFields = Vector(
        AutoEvaluation.input("question"),
        AutoEvaluation.input("ground_truth"),
        AutoEvaluation.input("system_response")
      ),
      outputFields = keyIdeaFields ++ Vector(
        AutoEvaluation.output("recall", SemanticF1.recallDescription),
        AutoEvaluation.output("precision", SemanticF1.precisionDescription)
      ),
      instructions = Some(
        if decompositional then SemanticF1.decompositionalInstructions else SemanticF1.instructions
      )
    )

  private val judge = AutoEvaluation.judge(ParameterId("metric/semantic-f1"), layout)

  override def score(
      example                  : Example,
      prediction               : RawPrediction,
      @annotation.unused events: Vector[ProgramEvent]
  ): ZIO[PredictionBackend, DspyError, Double] =
    for
      question    <- ZIO.fromEither(MetricHelpers.scoringText(example.get(questionField), questionField, "Example"))
      groundTruth <- ZIO.fromEither(
                       MetricHelpers.scoringText(example.get(groundTruthField), groundTruthField, "Example")
                     )
      systemResponse <- ZIO.fromEither(
                          MetricHelpers.scoringText(prediction.get(responseField), responseField, "Prediction")
                        )
      inputs = DynamicValues.recordFromEntries(Seq(
                 "question"        -> DynamicValues.fromAny(question),
                 "ground_truth"    -> DynamicValues.fromAny(groundTruth),
                 "system_response" -> DynamicValues.fromAny(systemResponse)
               ))
      scores <- AutoEvaluation.runJudge(judge, inputs, Seq("recall", "precision"))
    yield AutoEvaluation.f1Score(precision = scores(1), recall = scores(0))

object CompleteAndGrounded:
  private val completenessInstructions =
    "Compare the system response to the ground truth. Report how completely it covers the ground truth."

  private val groundednessInstructions =
    "Compare the system response to the retrieved context. Report how well the context supports its claims."

final case class CompleteAndGrounded(
    threshold       : Double = 0.66,
    questionField   : String = "question",
    groundTruthField: String = "response",
    responseField   : String = "response",
    contextField    : String = "context"
) extends Metric:
  val name: String = "complete_and_grounded"

  private val completeness = AutoEvaluation.judge(
    ParameterId("metric/completeness"),
    SignatureLayout.of(
      name = "AnswerCompleteness",
      inputFields = Vector(
        AutoEvaluation.input("question"),
        AutoEvaluation.input("ground_truth"),
        AutoEvaluation.input("system_response")
      ),
      outputFields = Vector(AutoEvaluation.output("completeness", "ground truth covered by the response")),
      instructions = Some(CompleteAndGrounded.completenessInstructions)
    )
  )

  private val groundedness = AutoEvaluation.judge(
    ParameterId("metric/groundedness"),
    SignatureLayout.of(
      name = "AnswerGroundedness",
      inputFields = Vector(
        AutoEvaluation.input("question"),
        AutoEvaluation.input("retrieved_context"),
        AutoEvaluation.input("system_response")
      ),
      outputFields = Vector(AutoEvaluation.output("groundedness", "response supported by retrieved context")),
      instructions = Some(CompleteAndGrounded.groundednessInstructions)
    )
  )

  override def score(
      example                  : Example,
      prediction               : RawPrediction,
      @annotation.unused events: Vector[ProgramEvent]
  ): ZIO[PredictionBackend, DspyError, Double] =
    for
      question    <- ZIO.fromEither(MetricHelpers.scoringText(example.get(questionField), questionField, "Example"))
      groundTruth <- ZIO.fromEither(
                       MetricHelpers.scoringText(example.get(groundTruthField), groundTruthField, "Example")
                     )
      systemResponse <- ZIO.fromEither(
                          MetricHelpers.scoringText(prediction.get(responseField), responseField, "Prediction")
                        )
      context           <- ZIO.fromEither(MetricHelpers.scoringText(prediction.get(contextField), contextField, "Prediction"))
      completenessInputs = DynamicValues.recordFromEntries(Seq(
                             "question"        -> DynamicValues.fromAny(question),
                             "ground_truth"    -> DynamicValues.fromAny(groundTruth),
                             "system_response" -> DynamicValues.fromAny(systemResponse)
                           ))
      groundednessInputs = DynamicValues.recordFromEntries(Seq(
                             "question"          -> DynamicValues.fromAny(question),
                             "retrieved_context" -> DynamicValues.fromAny(context),
                             "system_response"   -> DynamicValues.fromAny(systemResponse)
                           ))
      completenessScore <- AutoEvaluation.runJudge(completeness, completenessInputs, Seq("completeness"))
      groundednessScore <- AutoEvaluation.runJudge(groundedness, groundednessInputs, Seq("groundedness"))
    yield AutoEvaluation.f1Score(groundednessScore.head, completenessScore.head)
