/** Live GEPA smoke test over an instruction-sensitive classification task.
  *
  * Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"
  */
package dspy4s.examples.verify

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.examples.Demo
import dspy4s.gepa.{Gepa, GepaConfig, InstructionProposer, MetricCallCount, MinibatchSize}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.{IO, ZIO}
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger

object GepaSmokeTest:

  final case class TextInput(text: String) derives Schema
  final case class LabelOutput(label: String) derives Schema
  final case class ReflectionInput(currentInstruction: String, records: String) derives Schema
  final case class ReflectionOutput(instruction: String) derives Schema

  val taskId: ParameterId              = ParameterId("gepa-smoke/classifier")
  val vagueBaselineInstruction: String = "Answer the question."

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

  val trainset: Vector[Example] = withNum.take(5).map(example(_, "HAS_NUM")) ++
    noNum.take(5).map(example(_, "NO_NUM"))
  val valset: Vector[Example] = withNum.drop(5).map(example(_, "HAS_NUM")) ++
    noNum.drop(5).map(example(_, "NO_NUM"))

  def student: RecordProgram[TextInput, LabelOutput] =
    val signature = Signature.derived[TextInput, LabelOutput]("DigitClassifier", vagueBaselineInstruction)
    Program
      .predictStable(taskId, signature, config = DynamicValues.record("temperature" := 0.0))
      .fromRecords(signature.inputShape)

  val metric: FeedbackMetric = new FeedbackMetric:
    val name: String = "label_match"

    def feedback(
        example        : Example,
        prediction     : RawPrediction,
        events         : Vector[ProgramEvent],
        component      : Option[ParameterId],
        componentEvents: Vector[ProgramEvent]
    ): IO[DspyError, ScoreWithFeedback] =
      val text     = example.get("text").map(DynamicValues.renderText).getOrElse("")
      val gold     = example.get("label").map(DynamicValues.renderText).getOrElse("")
      val actual   = prediction.get("label").map(DynamicValues.renderText).getOrElse("")
      val correct  = actual.trim.equalsIgnoreCase(gold.trim)
      val feedback =
        if correct then s"Correct: $actual"
        else
          s"For '$text', the correct label is '$gold', but the program produced '$actual'. " +
            "Use exactly HAS_NUM when the text contains a digit, and NO_NUM otherwise."
      ZIO.succeed(ScoreWithFeedback(if correct then 1.0 else 0.0, feedback))

  val reflector: Program[InstructionProposer.Input, InstructionProposer.Output] = Program
    .predict(
      Signature.derived[ReflectionInput, ReflectionOutput](
        "InstructionReflector",
        "Rewrite the instruction from the current instruction and scored failure records."
      )
    )
    .contramap[InstructionProposer.Input](input =>
      ReflectionInput(input.currentInstruction, input.records.mkString("\n\n"))
    )
    .map(output => InstructionProposer.Output(output.instruction))

  final class CountingBackend(delegate: PredictionBackend) extends PredictionBackend:
    private val counter                      = new AtomicInteger(0)
    def count: Int                           = counter.get()
    def generate(request: PredictionRequest) =
      val _ = counter.incrementAndGet()
      delegate.generate(request)
    override def generateStreaming(request: PredictionRequest, emit: PredictionChunk => zio.UIO[Unit]) =
      val _ = counter.incrementAndGet()
      delegate.generateStreaming(request, emit)

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

@main def gepaSmokeMain(): Unit =
  import GepaSmokeTest.*

  val model         = sys.env.getOrElse("OPENAI_MODEL", "gpt-4o-mini")
  val metricCalls   = envInt("GEPA_METRIC_CALLS", 60)
  val minibatchSize = envInt("GEPA_MINIBATCH", 3)

  OpenAiLanguageModel.fromEnv(model) match
    case Left(error) => println(s"[gepa-smoke] Skipping because no live LM is available: ${error.message}")
    case Right(lm)   =>
      given context: RuntimeContext  = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
      val live                       = new LivePredictionBackend(lm, ChatAdapter(), context)
      given backend: CountingBackend = new CountingBackend(live)

      val effect = Gepa(
        student = student,
        trainset = trainset,
        valset = valset,
        metric = metric,
        reflector = reflector,
        config = GepaConfig(
          maxMetricCalls = MetricCallCount.applyUnsafe(metricCalls),
          reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatchSize),
          stopOnPerfectScore = true,
          seed = 0L
        )
      )

      println(s"[gepa-smoke] model=$model, budget=$metricCalls, minibatch=$minibatchSize")
      Demo.runEffect(effect) match
        case Left(error)   => println(s"[gepa-smoke] failed: ${error.message}")
        case Right(result) =>
          val instruction = result.bestProgram.program.parameters
            .get(taskId)
            .flatMap(_.instructions)
            .getOrElse("(none)")
          println(s"[gepa-smoke] ${backend.count} LM calls; ${result.numCandidates} candidates")
          println(f"[gepa-smoke] best validation score: ${result.bestScore}%.3f")
          println(s"[gepa-smoke] discovered instruction: $instruction")
