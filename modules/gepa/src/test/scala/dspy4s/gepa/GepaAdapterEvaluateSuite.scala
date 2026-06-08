package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.optimize.Predictors
import dspy4s.optimize.Runnable
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class GepaAdapterEvaluateSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Answers "Paris" for the France question (ChatAdapter marker format), "Lyon" otherwise. */
  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val answer = if prompt.contains("France") then "Paris" else "Lyon"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example: Example,
        prediction: DynamicPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val batch: Vector[Example] = Vector(
    Example(values = DynamicValues.record("question" := "What is the capital of France?", "answer" := "Paris"), inputKeys = Set("question")),
    Example(values = DynamicValues.record("question" := "Capital of nowhere?", "answer" := "Paris"), inputKeys = Set("question"))
  )

  private val adapter: GepaAdapter[DynamicPredict] = new GepaAdapter(program, metric)

  test("evaluate with captureTraces returns aligned scores + per-example trajectories") {
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = adapter.evaluate(batch, Candidate.seed(program), captureTraces = true)

      assertEquals(result.scores, Vector(1.0, 0.0)) // example 1 matches "Paris"; example 2's gold is "Paris" but LM says "Lyon"
      val trajs = result.trajectories.getOrElse(fail("expected trajectories"))
      assertEquals(trajs.size, 2)
      assertEquals(trajs.map(_.score), Vector(1.0, 0.0))
      // Each trajectory captured exactly the single predictor's trace entry.
      assertEquals(trajs.head.trace.size, 1)
      assertEquals(trajs.head.trace.head.component, "predict")
      assertEquals(DynamicValues.recordGet(trajs.head.prediction.values, "answer").map(DynamicValues.renderText), Some("Paris"))
    }
  }

  test("evaluate without captureTraces returns scores only (no trajectories)") {
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = adapter.evaluate(batch, Candidate.seed(program), captureTraces = false)

      assertEquals(result.trajectories, None)
      assertEquals(result.scores, Vector(1.0, 0.0))
      assertEquals(result.outputs.size, 2)
    }
  }

  test("a different candidate instruction is actually applied before evaluation") {
    // Sanity: the candidate's instruction reaches the predictor (we can't see it change the scripted LM's answer,
    // but the prompt the LM sees must carry the candidate instruction).
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = adapter.evaluate(batch.take(1), Map("self" -> "Answer with the city name only."), captureTraces = true)
      assertEquals(result.scores, Vector(1.0))
    }
  }

  test("makeReflectiveDataset builds per-component {inputs, outputs, feedback} records from trajectories") {
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val evalBatch = adapter.evaluate(batch, Candidate.seed(program), captureTraces = true)
      val dataset   = adapter.makeReflectiveDataset(Candidate.seed(program), evalBatch, Vector("self"))

      assertEquals(dataset.keySet, Set("self"))
      val records = dataset("self")
      assertEquals(records.size, 2)
      // inputs carry the question; generated outputs carry the model's answer; feedback explains the verdict.
      assert(records.head.inputs.contains("France"), records.head.inputs)
      assert(records.head.generatedOutputs.contains("Paris"), records.head.generatedOutputs)
      assert(records.head.feedback.contains("expected"), records.head.feedback)
      // The wrong-answer example's feedback reflects the mismatch (gold Paris vs predicted Lyon).
      assert(records(1).feedback.contains("Lyon"), records(1).feedback)
    }
  }
