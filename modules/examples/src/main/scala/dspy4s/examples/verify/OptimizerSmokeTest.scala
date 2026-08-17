/** Live smoke test for COPRO and MIPROv2 with the functional optimizer API.
  *
  * Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.optimizerSmokeMain"
  */
package dspy4s.examples.verify

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.{Evaluate, Metric}
import dspy4s.examples.Demo
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.optimize.*
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.ZIO
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger

object OptimizerSmokeTest:

  final case class TextInput(text: String) derives Schema
  final case class LabelOutput(label: String) derives Schema
  final case class InstructionOutput(instruction: String) derives Schema
  final case class CoproPrompt(
      parameterId       : String,
      component         : String,
      currentInstruction: String,
      attempts          : String,
      round             : Int,
      candidate         : Int
  ) derives Schema
  final case class MiproPrompt(
      parameterId       : String,
      component         : String,
      currentInstruction: String,
      examples          : String,
      candidate         : Int
  ) derives Schema

  val taskId: ParameterId              = ParameterId("optimizer-smoke/classifier")
  val vagueBaselineInstruction: String = "Answer the question."

  private def example(text: String, label: String): Example =
    Example(values = DynamicValues.record("text" := text, "label" := label), inputKeys = Set("text"))

  private val withNum = Vector(
    "I bought 3 apples at the market",
    "There are 12 months in a year",
    "She ran 5 miles this morning",
    "The recipe needs 2 cups of flour",
    "We have 7 days until the trip",
    "He scored 21 points in the game",
    "The box weighs 9 kilograms",
    "They planted 40 trees last spring",
    "My phone has 64 gigabytes of storage",
    "The train leaves at 6 in the evening",
    "There were 100 people at the concert",
    "The car drove 80 kilometers"
  )
  private val noNum = Vector(
    "The sky is clear and blue today",
    "Dogs love to play in the park",
    "She enjoys reading mystery novels",
    "The coffee smells wonderful this morning",
    "Birds were singing in the trees",
    "He painted the fence a bright color",
    "We walked along the sandy beach",
    "The soup tastes a little salty",
    "They watched a film about the ocean",
    "A gentle breeze moved the curtains",
    "The library was quiet and calm",
    "Children laughed on the playground"
  )

  val trainset: Vector[Example] = withNum.take(7).zip(noNum.take(7)).flatMap { (numbered, plain) =>
    Vector(example(numbered, "HAS_NUM"), example(plain, "NO_NUM"))
  }
  val valset: Vector[Example] = withNum.drop(7).map(example(_, "HAS_NUM")) ++
    noNum.drop(7).map(example(_, "NO_NUM"))

  def student: RecordProgram[TextInput, LabelOutput] =
    val signature = Signature.derived[TextInput, LabelOutput]("DigitClassifier", vagueBaselineInstruction)
    Program
      .predictStable(taskId, signature, config = DynamicValues.record("temperature" := 0.0))
      .fromRecords(signature.inputShape)

  val metric: Metric = new Metric:
    val name: String                                                                     = "exact_label"
    def score(example: Example, prediction: RawPrediction, events: Vector[ProgramEvent]) =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.values, "label", "optimizer smoke expected label")
        actual   <- DynamicValues.requireString(prediction.values, "label", "optimizer smoke actual label")
      yield if actual.trim.equalsIgnoreCase(expected.trim) then 1.0 else 0.0)

  val coproProposer: Program[COPRO.ProposalInput, COPRO.Proposal] = Program
    .predict(
      Signature.derived[CoproPrompt, InstructionOutput](
        "CoproInstructionProposal",
        "Propose one precise task instruction. Use the required label names exactly."
      )
    )
    .contramap[COPRO.ProposalInput](input =>
      CoproPrompt(
        input.parameterId,
        input.component,
        input.currentInstruction.getOrElse(""),
        input.attempts.mkString("\n"),
        input.round,
        input.candidate
      )
    )
    .map(output => COPRO.Proposal(output.instruction))

  val miproProposer: Program[MIPROv2.ProposalInput, MIPROv2.Proposal] = Program
    .predict(
      Signature.derived[MiproPrompt, InstructionOutput](
        "MiproInstructionProposal",
        "Infer and state the classification rule from the examples. Use the required label names exactly."
      )
    )
    .contramap[MIPROv2.ProposalInput](input =>
      MiproPrompt(
        input.parameterId,
        input.component,
        input.currentInstruction.getOrElse(""),
        input.trainset.mkString("\n"),
        input.candidate
      )
    )
    .map(output => MIPROv2.Proposal(output.instruction))

  final class CountingBackend(delegate: PredictionBackend) extends PredictionBackend:
    private val counter                      = new AtomicInteger(0)
    def count: Int                           = counter.get()
    def generate(request: PredictionRequest) =
      val _ = counter.incrementAndGet()
      delegate.generate(request)
    override def generateStreaming(request: PredictionRequest, emit: PredictionChunk => zio.UIO[Unit]) =
      val _ = counter.incrementAndGet()
      delegate.generateStreaming(request, emit)

  def instructionOf(program: RecordProgram[TextInput, LabelOutput]): String =
    program.program.parameters.get(taskId).flatMap(_.instructions).getOrElse("(none)")

  def scoreOf(program: RecordProgram[TextInput, LabelOutput])(using PredictionBackend): Double =
    Demo.runEffect(Evaluate(program, valset, metric)).fold(_ => -1.0, _.score)

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

@main def optimizerSmokeMain(): Unit =
  import OptimizerSmokeTest.*

  val model   = sys.env.getOrElse("OPENAI_MODEL", "gpt-4o-mini")
  val breadth = envInt("SMOKE_BREADTH", 3)
  val trials  = envInt("SMOKE_TRIALS", 4)

  OpenAiLanguageModel.fromEnv(model) match
    case Left(error) => println(s"[optimizer-smoke] Skipping because no live LM is available: ${error.message}")
    case Right(lm)   =>
      given context: RuntimeContext  = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
      val live                       = new LivePredictionBackend(lm, ChatAdapter(), context)
      given backend: CountingBackend = new CountingBackend(live)
      val baseline                   = student
      val baselineScore              = scoreOf(baseline)

      println(s"[optimizer-smoke] model=$model, breadth=$breadth, trials=$trials")
      println(f"[optimizer-smoke] baseline score: $baselineScore%.1f%%")

      val coproEffect = COPRO(
        baseline,
        trainset,
        coproProposer,
        valset = Some(valset),
        config = COPROConfig(metric = metric, breadth = CoproBreadth.applyUnsafe(breadth), depth = RoundCount(1))
      )
      Demo.runEffect(coproEffect) match
        case Left(error)   => println(s"[optimizer-smoke] COPRO failed: ${error.message}")
        case Right(report) =>
          println(f"[optimizer-smoke] COPRO score: ${scoreOf(report.bestProgram)}%.1f%%")
          println(s"[optimizer-smoke] COPRO instruction: ${instructionOf(report.bestProgram)}")

      val miproEffect = MIPROv2(
        baseline,
        trainset,
        miproProposer,
        teacher = Some(baseline),
        valset = Some(valset),
        config = MIPROv2Config(
          metric = metric,
          numCandidates = CandidateCount.applyUnsafe(breadth),
          numTrials = TrialCount.applyUnsafe(trials),
          maxBootstrappedDemos = DemoCount(2),
          maxLabeledDemos = DemoCount(2)
        )
      )
      Demo.runEffect(miproEffect) match
        case Left(error)   => println(s"[optimizer-smoke] MIPROv2 failed: ${error.message}")
        case Right(report) =>
          println(f"[optimizer-smoke] MIPROv2 score: ${scoreOf(report.bestProgram)}%.1f%%")
          println(s"[optimizer-smoke] MIPROv2 instruction: ${instructionOf(report.bestProgram)}")

      println(s"[optimizer-smoke] ${backend.count} LM calls")
