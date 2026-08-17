package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeError, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.{ToolCallRequest, ToolCallResult}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class FunctionalStrategiesSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)
  private final case class RetryInput(question: String, hint: String)
  private final case class RetryAnswer(answer: String)
  private final case class CritiqueInput(answer: String)
  private final case class Advice(hint: String)
  private final case class PotQuestion(question: String)
  private final case class PotAnswer(answer: String)
  private final case class AgentQuestion(question: String)
  private final case class AgentAnswer(answer: String)
  private final case class CodeQuestion(question: String)
  private final case class CodeAnswer(answer: String)
  private final case class RlmQuestion(context: String, question: String)
  private final case class RlmAnswer(answer: String)

  private val taskId        = ParameterId("retry-task")
  private val feedbackId    = ParameterId("retry-feedback")
  private val compareId     = ParameterId("compare")
  private val generatorId   = ParameterId("pot-generator")
  private val regeneratorId = ParameterId("pot-regenerator")
  private val answererId    = ParameterId("pot-answerer")

  private final class RetryBackend extends PredictionBackend:
    val calls: ArrayBuffer[(ParameterId, Option[Int])] = ArrayBuffer.empty

    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      calls += request.parameterId -> request.rolloutId
      if request.parameterId == taskId then
        val hint = DynamicValues.recordGet(request.inputs, "hint").map(DynamicValues.renderText).getOrElse("")
        val text = if hint == "check the evidence" then "correct" else "wrong"
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := text)))
      else ZIO.succeed(RawPrediction(DynamicValues.record("hint" := "check the evidence")))

  private final class ComparisonBackend extends PredictionBackend:
    val requests: ArrayBuffer[PredictionRequest] = ArrayBuffer.empty

    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      requests += request
      ZIO.succeed(RawPrediction(DynamicValues.record(
        "rationale" := "combined evidence",
        "answer"    := "blue"
      )))

  private final class ProgramOfThoughtBackend extends PredictionBackend:
    val requests: ArrayBuffer[PredictionRequest] = ArrayBuffer.empty

    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      requests += request
      if request.parameterId == generatorId then
        ZIO.succeed(RawPrediction(DynamicValues.record("code" := "bad code")))
      else if request.parameterId == regeneratorId then
        ZIO.succeed(RawPrediction(DynamicValues.record("code" := "good code")))
      else
        ZIO.fromEither(DynamicValues.requireString(request.inputs, "codeOutput", "answerer test")).map { output =>
          RawPrediction(DynamicValues.record("answer" := s"answer:$output"))
        }

  private def run[O](program: Program[RetryInput, O], input: RetryInput, backend: PredictionBackend): Execution[O] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, input).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

  private def feedbackRetry(maxAttempts: Int)(
      accept: FeedbackRetry.Attempt[RetryInput, RetryAnswer] => Either[DspyError, Boolean]
  ): Program[RetryInput, RetryAnswer] =
    val task = Program.predict(
      taskId,
      Signature.derived[RetryInput, RetryAnswer]("RetryTask"),
      name = "retry_task"
    )
    val critic = Program.predict(
      feedbackId,
      Signature.derived[CritiqueInput, Advice]("RetryFeedback"),
      name = "retry_feedback"
    )
    val feedback = (
      Program.identity[FeedbackRetry.Attempt[RetryInput, RetryAnswer]] &&&
        critic.contramap[FeedbackRetry.Attempt[RetryInput, RetryAnswer]](attempt =>
          CritiqueInput(attempt.prediction.output.answer)
        )
    ).map { case (attempt, advice) => attempt.input.copy(hint = advice.hint) }

    FeedbackRetry(task, feedback, maxAttempts)(accept)

  private def programOfThought[R](
      executor   : ProgramWithEnv[String, CodeExecutionResult, R],
      maxAttempts: Int = 2
  ): ProgramWithEnv[PotQuestion, PotAnswer, PredictionBackend & R] =
    val generator = Program.predict(
      generatorId,
      Signature.derived[PotQuestion, ProgramOfThought.GeneratedCode]("GenerateCode"),
      name = "pot_generator"
    )
    val regenerator = Program.predict(
      regeneratorId,
      Signature.derived[ProgramOfThought.RetryInput[PotQuestion], ProgramOfThought.GeneratedCode]("RegenerateCode"),
      name = "pot_regenerator"
    )
    val answerer = Program.predict(
      answererId,
      Signature.derived[ProgramOfThought.AnswerInput[PotQuestion], PotAnswer]("AnswerCode"),
      name = "pot_answerer"
    )
    ProgramOfThought(generator, regenerator, executor, answerer, maxAttempts)

  test("chain of thought is one signature transformation and one prediction node") {
    val base    = Signature.derived[Question, Answer]("Answer", instructions = "reason first")
    val program = ChainOfThought(ParameterId("answer"), base)
    val backend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record(
          "reasoning" := "the evidence",
          "answer"    := "the result"
        )))

    val prediction = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.run(program, Question("why?")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(prediction.output.reasoning, "the evidence")
    assertEquals(prediction.output.answer, "the result")
    assertEquals(program.parameters.all.map(_.id), Vector(ParameterId("answer")))
    assertEquals(ProgramGraph.from(program).nodes.map(_.kind), Vector("predict"))
  }

  test("feedback retry is composed from visible choice and bounded iteration") {
    val program = feedbackRetry(maxAttempts = 3)(attempt => Right(attempt.prediction.output.answer == "correct"))
    val backend = RetryBackend()
    val result  = run(program, RetryInput("question", ""), backend)

    assertEquals(result.outcome.map(_.output), Right(RetryAnswer("correct")))
    assertEquals(
      backend.calls.toVector,
      Vector(
        taskId     -> Some(0),
        feedbackId -> None,
        taskId     -> Some(1)
      )
    )
    assertEquals(program.parameters.all.map(_.id), Vector(taskId, feedbackId))
    assert(ProgramGraph.from(program).nodes.exists(_.kind == "iterate"))
    assert(ProgramGraph.from(program).nodes.exists(_.kind == "choice"))
    assert(ProgramGraph.from(program).nodes.exists(_.kind == "local_input"))
    assertEquals(
      result.events.collect { case ProgramEvent.Started(_, _, component, _, _) => component },
      Vector("retry_task", "retry_feedback", "retry_task")
    )
  }

  test("feedback retry does not run feedback after its final rejected attempt") {
    val program = feedbackRetry(maxAttempts = 2)(_ => Right(false))
    val backend = RetryBackend()
    val result  = run(program, RetryInput("question", ""), backend)

    assert(result.outcome match
      case Left(RuntimeError("feedback_retry", _)) => true
      case _                                       => false)
    assertEquals(backend.calls.count(_._1 == taskId), 2)
    assertEquals(backend.calls.count(_._1 == feedbackId), 1)
  }

  test("multi-chain comparison is input preparation followed by one visible prediction") {
    val base    = Signature.derived[Question, Answer]("CompareAnswer")
    val program = MultiChainComparison(compareId, base, m = 2)
    val input   = MultiChainComparison.Input(
      Question("What color is the sky?"),
      Vector(
        RawPrediction(DynamicValues.record("reasoning" := "clear days", "answer" := "blue")),
        RawPrediction(DynamicValues.record("rationale" := "plants", "answer" := "green"))
      )
    )
    val backend   = ComparisonBackend()
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(
              program,
              input,
              RunOptions(config = DynamicValues.record("temperature" := 0.2))
            )
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output.rationale), Right("combined evidence"))
    assertEquals(execution.outcome.map(_.output.answer), Right("blue"))
    assertEquals(backend.requests.size, 1)
    val request = backend.requests.head
    assertEquals(
      request.layout.inputFields.map(_.name),
      Vector("question", "reasoning_attempt_1", "reasoning_attempt_2")
    )
    assertEquals(request.layout.outputFields.map(_.name), Vector("rationale", "answer"))
    assertEquals(
      DynamicValues.requireString(request.inputs, "reasoning_attempt_1", "test"),
      Right("«I'm trying to clear days I'm not sure but my prediction is blue»")
    )
    assertEquals(DynamicValues.recordGet(request.config, "temperature"), Some(DynamicValues.fromAny(0.2)))
    assertEquals(program.parameters.all.map(_.id), Vector(compareId))
    assertEquals(ProgramGraph.from(program).nodes.map(_.kind), Vector("and_then", "lift_either", "predict"))

    val rejected = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(program, MultiChainComparison.Input(Question("?"), Vector(RawPrediction.empty)))
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }
    assert(rejected.outcome.isLeft)
    assertEquals(backend.requests.size, 1)
  }

  test("program of thought keeps generation, execution, retry, and answer structure visible") {
    val executor = Program.lift[String, CodeExecutionResult] {
      case "good code" => CodeExecutionResult.Succeeded("42")
      case _           => CodeExecutionResult.Failed("execution failed")
    }
    val program   = programOfThought(executor)
    val backend   = ProgramOfThoughtBackend()
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(program, PotQuestion("six times seven"))
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(PotAnswer("answer:42")))
    assertEquals(
      backend.requests.map(request => request.parameterId -> request.rolloutId).toVector,
      Vector(
        generatorId   -> Some(0),
        regeneratorId -> Some(1),
        answererId    -> None
      )
    )
    assertEquals(
      DynamicValues.requireString(backend.requests(1).inputs, "previousCode", "test"),
      Right("bad code")
    )
    assertEquals(
      DynamicValues.requireString(backend.requests(1).inputs, "error", "test"),
      Right("execution failed")
    )
    assertEquals(program.parameters.all.map(_.id), Vector(generatorId, regeneratorId, answererId))
    assertEquals(ProgramGraph.from(program).nodes.count(_.kind == "iterate"), 1)
    assertEquals(ProgramGraph.from(program).nodes.count(_.kind == "choice"), 2)
  }

  test("program of thought fails after the final execution error and does not run the answerer") {
    val executor = Program.lift[String, CodeExecutionResult](_ =>
      CodeExecutionResult.Failed("still broken")
    )
    val program   = programOfThought(executor)
    val backend   = ProgramOfThoughtBackend()
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramRunner
            .runJournaled(program, PotQuestion("question"))
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

    assert(execution.outcome match
      case Left(RuntimeError("program_of_thought", message)) => message.contains("still broken")
      case _                                                 => false)
    assertEquals(backend.requests.map(_.parameterId).toVector, Vector(generatorId, regeneratorId))
  }

  test("program of thought composes prediction and code service requirements") {
    val program: ProgramWithEnv[
      PotQuestion,
      PotAnswer,
      PredictionBackend & CodeExecutionBackend
    ]                     = programOfThought(Program.executeCode)
    val predictionBackend = ProgramOfThoughtBackend()
    val codeBackend       = new CodeExecutionBackend:
      def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        ZIO.succeed(
          if code == "good code" then CodeExecutionResult.Succeeded("42")
          else CodeExecutionResult.Failed("execution failed")
        )
    val environment = ZEnvironment[PredictionBackend](predictionBackend) ++
      ZEnvironment[CodeExecutionBackend](codeBackend)
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, PotQuestion("question")).provideEnvironment(environment))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(PotAnswer("answer:42")))
    assertEquals(ProgramGraph.from(program).nodes.count(_.kind == "execute_code"), 1)
  }

  test("react keeps typed control, tool invocation, failure capture, and extraction visible") {
    val generator = Program.lift[ReAct.StepInput[AgentQuestion], ReAct.Step] { input =>
      if input.trajectory.isEmpty then
        ReAct.Step(
          "look up the answer",
          ReAct.Action.Invoke(ToolCallRequest("search", DynamicValues.record("query" := input.input.question)))
        )
      else ReAct.Step("enough evidence", ReAct.Action.Finish())
    }
    val extractor = Program.lift[ReAct.ExtractInput[AgentQuestion], AgentAnswer] { input =>
      val answer = input.trajectory.collectFirst {
        case ReAct.TrajectoryEntry(_, _, _, ReAct.Observation.Succeeded(value)) => DynamicValues.renderText(value)
      }.getOrElse("missing")
      AgentAnswer(answer)
    }
    val program: ProgramWithEnv[AgentQuestion, AgentAnswer, ToolBackend] =
      ReAct(generator, Program.invokeTool, extractor, maxIterations = 3)
    val calls   = ArrayBuffer.empty[ToolCallRequest]
    val backend = new ToolBackend:
      def invoke(request: ToolCallRequest): ZIO[Any, DspyError, ToolCallResult] =
        calls += request
        ZIO.succeed(ToolCallResult(request.name, Right(DynamicValues.fromAny("Brussels"))))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, AgentQuestion("capital?")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(AgentAnswer("Brussels")))
    assertEquals(calls.map(_.name).toVector, Vector("search"))
    val kinds = ProgramGraph.from(program).nodes.map(_.kind)
    assert(kinds.contains("iterate"))
    assert(kinds.contains("choice"))
    assert(kinds.contains("attempt"))
    assert(kinds.contains("invoke_tool"))
  }

  test("react records a tool service failure and continues to extraction") {
    val generator = Program.lift[ReAct.StepInput[AgentQuestion], ReAct.Step] { input =>
      if input.trajectory.isEmpty then
        ReAct.Step("try a tool", ReAct.Action.Invoke(ToolCallRequest("missing", DynamicValue.Record.empty)))
      else ReAct.Step("stop after failure", ReAct.Action.Finish())
    }
    val extractor = Program.lift[ReAct.ExtractInput[AgentQuestion], AgentAnswer] { input =>
      val message = input.trajectory.collectFirst {
        case ReAct.TrajectoryEntry(_, _, _, ReAct.Observation.Failed(error)) => error.message
      }.getOrElse("missing")
      AgentAnswer(message)
    }
    val program = ReAct(generator, Program.invokeTool, extractor, maxIterations = 2)
    val backend = new ToolBackend:
      def invoke(@annotation.unused request: ToolCallRequest): ZIO[Any, DspyError, ToolCallResult] =
        ZIO.fail(NotFoundError("tool", "not registered"))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, AgentQuestion("question")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(AgentAnswer("not registered")))
  }

  test("codeact records parse failure, executes code, and extracts through visible structure") {
    val generator = Program.lift[CodeAct.StepInput[CodeQuestion], CodeAct.Step] { input =>
      if input.trajectory.isEmpty then CodeAct.Step("bad", finished = true)
      else CodeAct.Step("print(42)", finished = true)
    }
    val extracted = ArrayBuffer.empty[CodeAct.ExtractInput[CodeQuestion]]
    val extractor = Program.lift[CodeAct.ExtractInput[CodeQuestion], CodeAnswer] { input =>
      extracted += input
      val answer = input.trajectory.collectFirst {
        case CodeAct.TrajectoryEntry(_, _, _, _, CodeAct.Observation.Succeeded(output)) => output
      }.getOrElse("missing")
      CodeAnswer(answer)
    }
    val parser: String => Either[DspyError, String] = {
      case "bad" => Left(RuntimeError("code_parse", "invalid code"))
      case code  => Right(code)
    }
    val program: ProgramWithEnv[CodeQuestion, CodeAnswer, CodeExecutionBackend] =
      CodeAct(generator, Program.executeCode, extractor, maxIterations = 3, parseCode = parser)
    val calls   = ArrayBuffer.empty[String]
    val backend = new CodeExecutionBackend:
      def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        calls += code
        ZIO.succeed(CodeExecutionResult.Succeeded("42"))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, CodeQuestion("six times seven")).provideEnvironment(
          ZEnvironment(backend)
        ))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(CodeAnswer("42")))
    assertEquals(calls.toVector, Vector("print(42)"))
    assertEquals(extracted.head.trajectory.size, 2)
    assert(extracted.head.trajectory.head.observation match
      case CodeAct.Observation.Failed("invalid code") => true
      case _                                          => false)
    val kinds = ProgramGraph.from(program).nodes.map(_.kind)
    assert(kinds.contains("iterate"))
    assert(kinds.contains("choice"))
    assert(kinds.contains("execute_code"))
  }

  test("codeact keeps code infrastructure failure in the typed error channel") {
    val generator = Program.lift[CodeAct.StepInput[CodeQuestion], CodeAct.Step](_ =>
      CodeAct.Step("print(42)", finished = true)
    )
    val extractor = Program.lift[CodeAct.ExtractInput[CodeQuestion], CodeAnswer](_ => CodeAnswer("unexpected"))
    val program   = CodeAct(generator, Program.executeCode, extractor, maxIterations = 1, parseCode = Right(_))
    val backend   = new CodeExecutionBackend:
      def execute(@annotation.unused code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        ZIO.fail(RuntimeError("code_service", "offline"))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, CodeQuestion("question")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assert(execution.outcome match
      case Left(RuntimeError("code_service", "offline")) => true
      case _                                             => false)
  }

  test("rlm ends on a typed submission without running its fallback extractor") {
    val generator = Program.lift[RLM.ActionInput[RlmQuestion], RLM.ActionStep] { input =>
      if input.history.isEmpty then RLM.ActionStep("inspect", "inspect context")
      else RLM.ActionStep("submit", "submit answer")
    }
    val executor = Program.lift[RLM.ExecutionInput[RlmQuestion], RLM.ExecutionResult[RlmAnswer]] { input =>
      if input.code == "inspect context" then RLM.ExecutionResult.Observed("found 42", isError = false)
      else RLM.ExecutionResult.Submitted(RlmAnswer("42"))
    }
    val extractor = Program.liftEither[RLM.ExtractInput[RlmQuestion], RlmAnswer](_ =>
      Left(RuntimeError("rlm_test", "fallback must not run"))
    )
    val program   = RLM(generator, executor, extractor, maxIterations = 3, parseCode = Right(_))
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, RlmQuestion("answer=42", "what is it?")))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(RlmAnswer("42")))
    val kinds = ProgramGraph.from(program).nodes.map(_.kind)
    assert(kinds.contains("iterate"))
    assertEquals(kinds.count(_ == "choice"), 2)
  }

  test("rlm uses the visible fallback after the bounded code loop") {
    val generator = Program.lift[RLM.ActionInput[RlmQuestion], RLM.ActionStep](input =>
      RLM.ActionStep(s"inspect ${input.iteration}", "print(context)")
    )
    val executor = Program.executeCode
      .contramap[RLM.ExecutionInput[RlmQuestion]](_.code)
      .map[RLM.ExecutionResult[RlmAnswer]] {
        case CodeExecutionResult.Succeeded(output) => RLM.ExecutionResult.Observed(output, isError = false)
        case CodeExecutionResult.Failed(error)     => RLM.ExecutionResult.Observed(error, isError = true)
      }
    val extractor = Program.lift[RLM.ExtractInput[RlmQuestion], RlmAnswer](input =>
      RlmAnswer(s"fallback:${input.history.size}")
    )
    val program: ProgramWithEnv[RlmQuestion, RlmAnswer, CodeExecutionBackend] =
      RLM(generator, executor, extractor, maxIterations = 2, parseCode = Right(_))
    val calls   = ArrayBuffer.empty[String]
    val backend = new CodeExecutionBackend:
      def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        calls += code
        ZIO.succeed(CodeExecutionResult.Succeeded("observed"))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, RlmQuestion("long", "question")).provideEnvironment(
          ZEnvironment(backend)
        ))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(RlmAnswer("fallback:2")))
    assertEquals(calls.toVector, Vector("print(context)", "print(context)"))
    assert(ProgramGraph.from(program).nodes.exists(_.kind == "execute_code"))
  }

  test("ensemble reduces typed member evidence and keeps all predictors visible") {
    val ids      = Vector(ParameterId("vote-1"), ParameterId("vote-2"), ParameterId("vote-3"))
    val members  = ids.map(id => Program.predict(id, Signature.derived[Question, Answer](s"Vote-${id.value}")))
    val evidence = ArrayBuffer.empty[String]
    val program  = Ensemble(members) { predictions =>
      evidence ++= predictions.flatMap(_.raw.asString("answer").toOption)
      val winner = predictions.map(_.output.answer).groupBy(identity).maxBy(_._2.size)._1
      Right(Answer(winner))
    }
    val backend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        val answer = if request.parameterId == ids.last then "red" else "blue"
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := answer)))

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(program, Question("color?")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(Answer("blue")))
    assertEquals(evidence.toVector, Vector("blue", "blue", "red"))
    assertEquals(program.parameters.all.map(_.id), ids)
    val kinds = ProgramGraph.from(program).nodes.map(_.kind)
    assertEquals(kinds.count(_ == "predict"), 3)
    assert(kinds.contains("collect_all"))
  }
