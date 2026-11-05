package dspy4s.evaluate.metrics

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.TypeRef
import dspy4s.evaluate.contracts.Metric
import dspy4s.programs.strategies.ChainOfThought
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** The metrics that use a language model as a judge, from `dspy/evaluate/auto_evaluation.py` (dspy 3.1.3).
  *
  * [[Metric.score]] gets a `RuntimeContext`, so these metrics can call a language model (PORT_GAPS G-6). Each metric
  * runs a judge in the form of `ChainOfThought` on a small signature, with the LM and the adapter of the context.
  *
  * ==Deltas from Python==
  *
  *   - The judge sub-program is built from a runtime [[SignatureLayout]] driving a [[DynamicPredict]] (with a leading
  *     `reasoning` field prepended via [[ChainOfThought.augmentLayout]], exactly as Python's `ChainOfThought` does),
  *     rather than a `Signature`/`Module` with statically known I/O. dspy4s metrics live in the `evaluate` module and
  *     score over runtime data bags, so there is no static `I`/`O` to carry; the dynamic layer is the right substrate.
  *   - Field names are configurable. Defaults follow upstream's `example.question` / `example.response` (ground truth)
  *     / `pred.response` (system response). dspy4s has no attribute access, so the values are pulled by string key from
  *     the [[Example]] and [[RawPrediction]] records. A value goes to the judge with no conversion, as upstream.
  *   - Python's `forward(..., trace=None)` returns the raw float for no trace, and `score >= threshold` (a bool) for a
  *     trace. dspy4s metrics always return a `Double`: the score for an empty trace, and else `1.0` when the score
  *     reaches the `threshold` and `0.0` when it does not.
  */
object AutoEvaluation:

  /** Harmonic mean of `precision` and `recall`, each clamped to `[0, 1]`; `0.0` if either clamped value is `0`. Mirrors
    * Python's `f1_score(precision, recall)`.
    */
  def f1Score(precision: Double, recall: Double): Double =
    val p = math.max(0.0, math.min(1.0, precision))
    val r = math.max(0.0, math.min(1.0, recall))
    if p + r == 0.0 then 0.0 else 2.0 * (p * r) / (p + r)

  private[metrics] def input(name: String): FieldSpec                = FieldSpec(name = name)
  private[metrics] def output(name: String, desc: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.double, description = Some(desc))
  private[metrics] def textOutput(name: String, desc: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.string, description = Some(desc))

  /** The inputs of each layout that compares a system response with the ground truth. */
  private[metrics] val comparisonInputs: Vector[FieldSpec] =
    Vector(input("question"), input("ground_truth"), input("system_response"))

  /** The key ideas of the two responses and the discussion of their overlap, for the layouts that have them. */
  private[metrics] val keyIdeaOutputs: Vector[FieldSpec] = Vector(
    textOutput("ground_truth_key_ideas", "enumeration of key ideas in the ground truth"),
    textOutput("system_response_key_ideas", "enumeration of key ideas in the system response"),
    textOutput("discussion", "discussion of the overlap between ground truth and system response")
  )

  /** The description of the output for the fraction of the ground truth in the system response. */
  private[metrics] val groundTruthCoverage = "fraction (out of 1.0) of ground truth covered by the system response"

  /** The judge predictor of a layout, with the `reasoning` field (upstream `ChainOfThought(signature)`). */
  private[metrics] def judge(layout: SignatureLayout): DynamicPredict =
    DynamicPredict(layout = ChainOfThought.augmentLayout(layout), name = Some("judge"))

  /** The value of a field for a judge, with no conversion: a list stays a list and a null stays a null, as upstream
    * gives `pred.context` to the adapter. A missing field is a `NotFoundError`.
    */
  private[metrics] def judgeInput(
      value    : Option[DynamicValue],
      fieldName: String,
      owner    : String
  ): Either[DspyError, DynamicValue] =
    value.toRight(NotFoundError(fieldName, s"$owner is missing field '$fieldName'"))

  /** Runs a judge predictor one time with `inputs`, and reads the `readFields` of that one prediction, in order. One
    * completion gives each field, because more completions cost more and can give numbers that do not agree. A missing
    * field or a field that is not a number is a `Left`.
    */
  private[metrics] def runJudge(
      predictor : DynamicPredict,
      inputs    : DynamicValue.Record,
      readFields: Seq[String]
  )(using RuntimeContext): Either[DspyError, Vector[Double]] =
    predictor(ProgramCall(input = inputs, traceEnabled = false)).flatMap { prediction =>
      Traverse.either(readFields)(prediction.raw.asDouble)
    }

  /** The result of a judged metric (upstream `score if trace is None else score >= self.threshold`): the score for an
    * empty trace, and else `1.0` when the score reaches `threshold` and `0.0` when it does not.
    */
  private[metrics] def judged(score: Double, threshold: Double, trace: Vector[TraceEntry]): Double =
    if trace.isEmpty then score else if score >= threshold then 1.0 else 0.0

object SemanticF1:
  private val precisionDesc = "fraction (out of 1.0) of system response covered by the ground truth"

  // Upstream `SemanticRecallPrecision`.
  private val baseInstructions =
    "Compare a system's response to the ground truth to compute its recall and precision.\n" +
      "If asked to reason, enumerate key ideas in each response, and whether they are present in the other response."

  // Upstream `DecompositionalSemanticRecallPrecision`.
  private val decompositionalInstructions =
    "Compare a system's response to the ground truth to compute recall and precision of key ideas.\n" +
      "You will first enumerate key ideas in each response, discuss their overlap, and then report recall and precision."

/** Upstream `dspy.evaluate.SemanticF1`. An LM judges the recall and the precision of a system response against the
  * ground truth. The result is `f1_score(precision, recall)`, as [[AutoEvaluation.judged]] gives it.
  */
final case class SemanticF1(
    decompositional : Boolean = false,
    threshold       : Double  = 0.66,
    questionField   : String  = "question",
    groundTruthField: String  = "response",
    responseField   : String  = "response"
) extends Metric:
  val name: String = "semantic_f1"

  private val layout: SignatureLayout =
    val scores = Vector(
      AutoEvaluation.output("recall", AutoEvaluation.groundTruthCoverage),
      AutoEvaluation.output("precision", SemanticF1.precisionDesc)
    )
    if decompositional then
      SignatureLayout.of(
        name = "DecompositionalSemanticRecallPrecision",
        inputFields = AutoEvaluation.comparisonInputs,
        outputFields = AutoEvaluation.keyIdeaOutputs ++ scores,
        instructions = Some(SemanticF1.decompositionalInstructions)
      )
    else
      SignatureLayout.of(
        name = "SemanticRecallPrecision",
        inputFields = AutoEvaluation.comparisonInputs,
        outputFields = scores,
        instructions = Some(SemanticF1.baseInstructions)
      )

  // One predictor for each metric instance, because the layout does not change.
  private val judgePredictor: DynamicPredict = AutoEvaluation.judge(layout)

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    for
      question       <- AutoEvaluation.judgeInput(example.get(questionField), questionField, "Example")
      groundTruth    <- AutoEvaluation.judgeInput(example.get(groundTruthField), groundTruthField, "Example")
      systemResponse <- AutoEvaluation.judgeInput(prediction.get(responseField), responseField, "Prediction")
      inputs          = DynamicValues.record(
                 "question"        -> question,
                 "ground_truth"    -> groundTruth,
                 "system_response" -> systemResponse
               )
      scores <- AutoEvaluation.runJudge(judgePredictor, inputs, Seq("recall", "precision"))
    yield AutoEvaluation.judged(AutoEvaluation.f1Score(precision = scores(1), recall = scores(0)), threshold, trace)

object CompleteAndGrounded:
  // Upstream `AnswerCompleteness`.
  private val completenessInstructions =
    "Estimate the completeness of a system's responses, against the ground truth.\n" +
      "You will first enumerate key ideas in each response, discuss their overlap, and then report completeness."

  // Upstream `AnswerGroundedness`.
  private val groundednessInstructions =
    "Estimate the groundedness of a system's responses, against real retrieved documents written by people.\n" +
      "You will first enumerate whatever non-trivial or check-worthy claims are made in the system response, and then\n" +
      "discuss the extent to which some or all of them can be deduced from the retrieved context and basic commonsense."

/** Upstream `dspy.evaluate.CompleteAndGrounded`. An LM judges the completeness of a system response against the ground
  * truth (`AnswerCompleteness`) and its groundedness against the retrieved context (`AnswerGroundedness`). The result
  * is `f1_score(groundedness, completeness)`, as [[AutoEvaluation.judged]] gives it.
  *
  * The groundedness judge needs the retrieved context (Python `pred.context`). dspy4s has no retriever, so the program
  * must put the context in the field `contextField` of the prediction. With no such field, the result is a `Left`.
  */
final case class CompleteAndGrounded(
    threshold       : Double = 0.66,
    questionField   : String = "question",
    groundTruthField: String = "response",
    responseField   : String = "response",
    contextField    : String = "context"
) extends Metric:
  val name: String = "complete_and_grounded"

  private val completenessLayout: SignatureLayout = SignatureLayout.of(
    name = "AnswerCompleteness",
    inputFields = AutoEvaluation.comparisonInputs,
    outputFields = AutoEvaluation.keyIdeaOutputs :+
      AutoEvaluation.output("completeness", AutoEvaluation.groundTruthCoverage),
    instructions = Some(CompleteAndGrounded.completenessInstructions)
  )

  private val groundednessLayout: SignatureLayout = SignatureLayout.of(
    name = "AnswerGroundedness",
    inputFields = Vector(
      AutoEvaluation.input("question"),
      AutoEvaluation.input("retrieved_context"),
      AutoEvaluation.input("system_response")
    ),
    outputFields = Vector(
      AutoEvaluation.textOutput(
        "system_response_claims",
        "enumeration of non-trivial or check-worthy claims in the system response"
      ),
      AutoEvaluation.textOutput("discussion", "discussion of how supported the claims are by the retrieved context"),
      AutoEvaluation.output(
        "groundedness",
        "fraction (out of 1.0) of system response supported by the retrieved context"
      )
    ),
    instructions = Some(CompleteAndGrounded.groundednessInstructions)
  )

  // One predictor for each layout of a metric instance, because the layouts do not change. These are two LM calls
  // (SemanticF1 has one), because they have different layouts and inputs.
  private val completenessPredictor: DynamicPredict = AutoEvaluation.judge(completenessLayout)
  private val groundednessPredictor: DynamicPredict = AutoEvaluation.judge(groundednessLayout)

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    for
      question          <- AutoEvaluation.judgeInput(example.get(questionField), questionField, "Example")
      groundTruth       <- AutoEvaluation.judgeInput(example.get(groundTruthField), groundTruthField, "Example")
      systemResponse    <- AutoEvaluation.judgeInput(prediction.get(responseField), responseField, "Prediction")
      context           <- AutoEvaluation.judgeInput(prediction.get(contextField), contextField, "Prediction")
      completenessInputs = DynamicValues.record(
                             "question"        -> question,
                             "ground_truth"    -> groundTruth,
                             "system_response" -> systemResponse
                           )
      groundednessInputs = DynamicValues.record(
                             "question"          -> question,
                             "retrieved_context" -> context,
                             "system_response"   -> systemResponse
                           )
      completeness <- AutoEvaluation.runJudge(completenessPredictor, completenessInputs, Seq("completeness"))
      groundedness <- AutoEvaluation.runJudge(groundednessPredictor, groundednessInputs, Seq("groundedness"))
    yield AutoEvaluation.judged(AutoEvaluation.f1Score(groundedness.head, completeness.head), threshold, trace)
