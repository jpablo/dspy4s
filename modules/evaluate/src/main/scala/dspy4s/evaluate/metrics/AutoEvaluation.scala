package dspy4s.evaluate.metrics

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.TypeRef
import dspy4s.evaluate.contracts.Metric
import dspy4s.programs.ChainOfThought
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** LLM-judged "auto-evaluation" metrics, ported from `dspy/evaluate/auto_evaluation.py` (dspy 3.1.3).
  *
  * These are the first metrics that *call a language model* during scoring — enabled by threading a `RuntimeContext`
  * through [[Metric.score]] (PORT_GAPS G-6). Each runs a `ChainOfThought`-style judge sub-program over a small
  * signature, resolving the LM/adapter from the ambient `RuntimeContext`.
  *
  * ==Deltas from Python==
  *
  *   - The judge sub-program is built from a runtime [[SignatureLayout]] driving a [[DynamicPredict]] (with a leading
  *     `reasoning` field prepended via [[ChainOfThought.augmentLayout]], exactly as Python's `ChainOfThought` does),
  *     rather than a statically-typed `Signature`/`Module`. dspy4s metrics live in the `evaluate` module and score over
  *     untyped data bags, so there is no static `I`/`O` to carry; the dynamic layer is the right substrate.
  *   - Field names are configurable. Defaults follow upstream's `example.question` / `example.response` (ground truth)
  *     / `pred.response` (system response). dspy4s has no attribute access, so the values are pulled by string key from
  *     the [[Example]] and [[RawPrediction]] records.
  *   - Python's `forward(..., trace=None)` returns the raw float during evaluation and `score >= threshold` (a bool)
  *     during bootstrapping. dspy4s metrics always return a `Double`; the `threshold` is retained as a configurable
  *     field for parity / future use but the score is returned as-is (callers apply thresholds at the optimizer layer,
  *     as the builtin metrics do).
  *   - The decompositional `SemanticF1(decompositional = true)` variant uses the same recall/precision outputs but a
  *     richer instruction + intermediate key-idea fields, mirroring `DecompositionalSemanticRecallPrecision`.
  */
object AutoEvaluation:

  /** Harmonic mean of `precision` and `recall`, each clamped to `[0, 1]`; `0.0` if either clamped value is `0`. Mirrors
    * Python's `f1_score(precision, recall)`.
    */
  def f1Score(precision: Double, recall: Double): Double =
    val p = math.max(0.0, math.min(1.0, precision))
    val r = math.max(0.0, math.min(1.0, recall))
    if p + r == 0.0 then 0.0 else 2.0 * (p * r) / (p + r)

  private[metrics] def input(name: String): FieldSpec = FieldSpec(name = name)
  private[metrics] def output(name: String, desc: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.double, description = Some(desc))
  private[metrics] def textOutput(name: String, desc: String): FieldSpec =
    FieldSpec(name = name, typeRef = TypeRef.string, description = Some(desc))

  /** Build the `reasoning`-augmented judge predictor for a layout (the analogue of Python's
    * `ChainOfThought(signature)`).
    */
  private[metrics] def judge(layout: SignatureLayout): DynamicPredict =
    DynamicPredict(layout = ChainOfThought.augmentLayout(layout), name = Some("judge"))

  /** Run a judge predictor ONCE with the given input record and read one or more `[0, 1]` Double output fields from
    * that single prediction (returned in `readFields` order). One completion supplies every requested field — calling
    * the judge once per field would double the LM cost AND pair numbers from different, possibly disagreeing
    * completions. Parse failures (missing / non-numeric field) surface as `Left`.
    */
  private[metrics] def runJudge(
      predictor: DynamicPredict,
      inputs: DynamicValue.Record,
      readFields: Seq[String]
  )(using RuntimeContext): Either[DspyError, Vector[Double]] =
    for
      prediction <- predictor(ProgramCall(input = inputs, traceEnabled = false))
      values <- readFields.foldLeft[Either[DspyError, Vector[Double]]](Right(Vector.empty)) { (acc, field) =>
        for
          soFar <- acc
          value <- prediction.raw.asDouble(field)
        yield soFar :+ value
      }
    yield values

object SemanticF1:
  // Mirrors `SemanticRecallPrecision` (non-decompositional).
  private val recallDesc    = "fraction (out of 1.0) of ground truth covered by the system response"
  private val precisionDesc = "fraction (out of 1.0) of system response covered by the ground truth"

  private val baseInstructions =
    "Compare a system's response to the ground truth to compute its recall and precision. If asked to reason, " +
      "enumerate key ideas in each response, and whether they are present in the other response."

  private val decompositionalInstructions =
    "Compare a system's response to the ground truth to compute recall and precision of key ideas. You will " +
      "first enumerate key ideas in each response, discuss their overlap, and then report recall and precision."

/** `SemanticF1` — judges recall and precision of a system response against the ground truth via an LM, then returns
  * `f1_score(precision, recall)`. Port of `dspy.evaluate.SemanticF1`.
  */
final case class SemanticF1(
    decompositional: Boolean = false,
    threshold: Double = 0.66,
    questionField: String = "question",
    groundTruthField: String = "response",
    responseField: String = "response"
) extends Metric:
  val name: String = "semantic_f1"

  private val layout: SignatureLayout =
    val inputs = Vector(
      AutoEvaluation.input("question"),
      AutoEvaluation.input("ground_truth"),
      AutoEvaluation.input("system_response")
    )
    val keyIdeaFields =
      if decompositional then
        Vector(
          AutoEvaluation.textOutput("ground_truth_key_ideas", "enumeration of key ideas in the ground truth"),
          AutoEvaluation.textOutput("system_response_key_ideas", "enumeration of key ideas in the system response"),
          AutoEvaluation.textOutput("discussion", "discussion of the overlap between ground truth and system response")
        )
      else Vector.empty
    val outputs = keyIdeaFields ++ Vector(
      AutoEvaluation.output("recall", SemanticF1.recallDesc),
      AutoEvaluation.output("precision", SemanticF1.precisionDesc)
    )
    val instructions = if decompositional then SemanticF1.decompositionalInstructions else SemanticF1.baseInstructions
    SignatureLayout.of(
      name = "SemanticRecallPrecision",
      inputFields = inputs,
      outputFields = outputs,
      instructions = Some(instructions)
    )

  // Built once per metric instance — the judge layout never changes between score() calls.
  private val judgePredictor: DynamicPredict = AutoEvaluation.judge(layout)

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    for
      question       <- MetricHelpers.scoringText(example.get(questionField), questionField, "Example")
      groundTruth    <- MetricHelpers.scoringText(example.get(groundTruthField), groundTruthField, "Example")
      systemResponse <- MetricHelpers.scoringText(prediction.get(responseField), responseField, "Prediction")
      inputs = DynamicValues.recordFromEntries(Seq(
        "question"        -> DynamicValues.fromAny(question),
        "ground_truth"    -> DynamicValues.fromAny(groundTruth),
        "system_response" -> DynamicValues.fromAny(systemResponse)
      ))
      scores <- AutoEvaluation.runJudge(judgePredictor, inputs, Seq("recall", "precision"))
    yield AutoEvaluation.f1Score(precision = scores(1), recall = scores(0))

object CompleteAndGrounded:
  private val completenessInstructions =
    "Estimate the completeness of a system's responses, against the ground truth. You will first enumerate key " +
      "ideas in each response, discuss their overlap, and then report completeness."

  private val groundednessInstructions =
    "Estimate the groundedness of a system's responses, against real retrieved documents written by people. You " +
      "will first enumerate whatever non-trivial or check-worthy claims are made in the system response, and then " +
      "discuss the extent to which some or all of them can be deduced from the retrieved context and basic " +
      "commonsense."

/** `CompleteAndGrounded` — combines an `AnswerCompleteness` judgement (system response vs ground truth) with an
  * `AnswerGroundedness` judgement (system response vs retrieved context) into `f1_score(groundedness, completeness)`.
  * Port of `dspy.evaluate.CompleteAndGrounded`.
  *
  * Delta: the groundedness half needs a retrieved-context field on the prediction (Python's `pred.context`). dspy4s has
  * no retriever, so `contextField` is pulled by key from the prediction record and must be supplied by the program
  * under evaluation; absent it, scoring returns `Left`.
  */
final case class CompleteAndGrounded(
    threshold: Double = 0.66,
    questionField: String = "question",
    groundTruthField: String = "response",
    responseField: String = "response",
    contextField: String = "context"
) extends Metric:
  val name: String = "complete_and_grounded"

  private val completenessLayout: SignatureLayout =
    SignatureLayout.of(
      name = "AnswerCompleteness",
      inputFields = Vector(
        AutoEvaluation.input("question"),
        AutoEvaluation.input("ground_truth"),
        AutoEvaluation.input("system_response")
      ),
      outputFields = Vector(
        AutoEvaluation.textOutput("ground_truth_key_ideas", "enumeration of key ideas in the ground truth"),
        AutoEvaluation.textOutput("system_response_key_ideas", "enumeration of key ideas in the system response"),
        AutoEvaluation.textOutput("discussion", "discussion of the overlap between ground truth and system response"),
        AutoEvaluation.output("completeness", "fraction (out of 1.0) of ground truth covered by the system response")
      ),
      instructions = Some(CompleteAndGrounded.completenessInstructions)
    )

  private val groundednessLayout: SignatureLayout =
    SignatureLayout.of(
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

  // Built once per metric instance — the judge layouts never change between score() calls. These stay two
  // separate LM calls (unlike SemanticF1) because they judge against different layouts and inputs.
  private val completenessPredictor: DynamicPredict = AutoEvaluation.judge(completenessLayout)
  private val groundednessPredictor: DynamicPredict = AutoEvaluation.judge(groundednessLayout)

  override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
      RuntimeContext
  ): Either[DspyError, Double] =
    for
      question       <- MetricHelpers.scoringText(example.get(questionField), questionField, "Example")
      groundTruth    <- MetricHelpers.scoringText(example.get(groundTruthField), groundTruthField, "Example")
      systemResponse <- MetricHelpers.scoringText(prediction.get(responseField), responseField, "Prediction")
      context        <- MetricHelpers.scoringText(prediction.get(contextField), contextField, "Prediction")
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
      completeness <- AutoEvaluation.runJudge(completenessPredictor, completenessInputs, Seq("completeness"))
      groundedness <- AutoEvaluation.runJudge(groundednessPredictor, groundednessInputs, Seq("groundedness"))
    yield AutoEvaluation.f1Score(groundedness.head, completeness.head)
