package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.LegacyProgramRunner
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Two-predictor pipeline: `hinter` produces a hint, `answerer` answers using it. Top-level so
  * `OptimizableStructure.derived` sees its Mirror field labels ("hinter", "answerer").
  */
final case class Pipeline(hinter: DynamicPredict, answerer: DynamicPredict)

object Pipeline:
  /** Run hinter → feed its hint into answerer. Two Module calls → two trace entries (in this order). */
  given LegacyProgramRunner[Pipeline] with
    def run(program: Pipeline, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.hinter(call).flatMap { hintPred =>
        val hint         = DynamicValues.recordGet(hintPred.output, "hint").getOrElse(DynamicValue.Null)
        val answerInputs = call.input.updated("hint", hint)
        program.answerer(call.mapInput(_ => answerInputs)).map(_.raw)
      }

class GepaMultiPredictorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Instruction-sensitive two-stage task. Stage 1 (output `hint`) emits "good_hint" only when its instruction carries
    * TOKEN1. Stage 2 (output `answer`) emits "Paris" only when its instruction carries TOKEN2 AND it received
    * "good_hint". So BOTH predictors must be improved for a correct final answer.
    */
  private final class PipelineLm extends LanguageModel:
    override val id: String                                                                    = "pipeline"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      if prompt.contains("## answer ##") then
        val answer = if prompt.contains("TOKEN2") && prompt.contains("good_hint") then "Paris" else "WRONG"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))
      else
        val hint = if prompt.contains("TOKEN1") then "good_hint" else "bad_hint"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## hint ## ]]\n$hint\n\n[[ ## completed ## ]]"))))

  /** Reflection LM: per component (detected from its current instruction), proposes the matching magic token. */
  private final class ReflectionLm extends LanguageModel:
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val instr  =
        if prompt.contains("Stage one") || prompt.contains("TOKEN1") then "Use TOKEN1 to produce a good_hint."
        else "Use TOKEN2 with the good_hint to answer."
      Right(LmResponse(outputs = Vector(LmOutput(text = s"```\n$instr\n```"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val pipeline: Pipeline = Pipeline(
    hinter = predict("question -> hint", "Stage one."),
    answerer = predict("question, hint -> answer", "Stage two.")
  )

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  test("the pipeline's two predictors have stable IDs and retain field labels for display") {
    assertEquals(Candidate.seed(pipeline).keySet, Set(OptimizableId(0), OptimizableId(1)))
    assertEquals(Candidate.named(pipeline, Candidate.seed(pipeline)).map(_._1), Vector("hinter", "answerer"))
  }

  test("GEPA evolves BOTH predictors of a two-stage program to lift the score") {
    // These two predictors are interdependent (the answer needs BOTH fixed), so update all components at once;
    // round-robin (the default) would stall since fixing one alone never improves the minibatch.
    val gepa = new Gepa[Pipeline](
      metric,
      new ReflectionLm,
      GepaConfig(
        maxMetricCalls = MetricCallCount(30),
        reflectionMinibatchSize = MinibatchSize(2),
        componentSelector = ComponentSelector.All,
        seed = 1L
      )
    )

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = gepa.compile(pipeline, trainset = dataset, valset = dataset)

      assertEquals(result.bestScore, 1.0)
      // Both components were evolved to carry their required token (per-component reflection + association worked).
      assert(
        result.bestCandidate(OptimizableId(0)).exists(_.contains("TOKEN1")),
        result.bestCandidate(OptimizableId(0))
      )
      assert(
        result.bestCandidate(OptimizableId(1)).exists(_.contains("TOKEN2")),
        result.bestCandidate(OptimizableId(1))
      )
    }
  }
