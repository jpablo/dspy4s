package dspy4s.optimize

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.Metric
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class InferRulesSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val RuleToken = "RULE_TOKEN"
  private val answerId  = ParameterId("answer")
  private val signature = Signature.derived[Question, Answer]("Answer", instructions = "base instruction")
  private val student   = Program.predict(answerId, signature).fromRecords(signature.inputShape)
  private val dataset   = Vector(
    Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
    Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
  )

  private val metric = new Metric:
    val name: String = "exact"

    def score(
        example                  : Example,
        prediction               : RawPrediction,
        @annotation.unused events: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, Double] =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.values, "answer", "expected")
        actual   <- DynamicValues.requireString(prediction.values, "answer", "actual")
      yield if actual == expected then 1.0 else 0.0)

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "infer rules test")).map { question =>
        val answer = if request.layout.instructions.exists(_.contains(RuleToken)) then question.reverse else "wrong"
        RawPrediction(DynamicValues.record("answer" := answer))
      }

  private def run(
      inducer: ProgramWithEnv[InferRules.RuleInput, InferRules.Rules, Any]
  ) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          InferRules(
            student,
            dataset,
            inducer,
            valset = Some(dataset),
            config = InferRulesConfig(
              metric = metric,
              numCandidates = CandidateCount(1),
              numRules = RuleCount(3),
              bootstrap = BootstrapFewShotConfig(maxLabeledDemos = DemoCount(0))
            )
          ).provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

  test("program InferRules appends induced rules and selects the improved record program") {
    val inputs  = ArrayBuffer.empty[InferRules.RuleInput]
    val inducer = Program.lift[InferRules.RuleInput, InferRules.Rules] { input =>
      inputs += input
      InferRules.Rules(s"Always use the evidence. $RuleToken")
    }
    val report = run(inducer)

    val instruction = report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions).getOrElse("")
    assert(instruction.startsWith("base instruction"))
    assert(instruction.contains("Please adhere to the following rules"))
    assert(instruction.contains(RuleToken))
    assertEquals(report.metadata("best_score"), 100.0)
    assertEquals(report.metadata("num_candidates"), 2)
    assertEquals(report.metadata("num_induction_failures"), 0)
    assertEquals(inputs.map(_.parameterId).toVector, Vector("answer"))
    assertEquals(inputs.map(_.numRules).toVector, Vector(3))
  }

  test("program InferRules narrows examples after a context-window failure") {
    val sizes   = ArrayBuffer.empty[Int]
    val inducer = Program.liftEither[InferRules.RuleInput, InferRules.Rules] { input =>
      sizes += input.examples.size
      if input.examples.size > 1 then Left(ContextWindowExceededError())
      else Right(InferRules.Rules(s"Use one example. $RuleToken"))
    }
    val report = run(inducer)

    assertEquals(sizes.toVector, Vector(2, 1))
    assertEquals(report.metadata("best_score"), 100.0)
    assertEquals(report.metadata("num_induction_failures"), 0)
  }
