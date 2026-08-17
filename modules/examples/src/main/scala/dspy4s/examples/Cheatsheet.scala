/** DSPy cheatsheet, expressed with the functional dspy4s API.
  *
  * Source: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/cheatsheet.md
  *
  * The Python comments stay next to the Scala construction so that readers can compare both APIs. A `Program` is an
  * immutable description. `ProgramRunner` and explicit backends execute it.
  */
package dspy4s.examples

import dspy4s.core.contracts.{DspyError, DynamicValues, ErrorLimit, TypeRef, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.{Evaluate, EvaluateOptions, Metric}
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.metrics.FunctionMetric
import dspy4s.optimize.*
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.*
import dspy4s.programs.contracts.Tool
import dspy4s.signatures.Signature
import zio.{IO, ZIO}
import zio.blocks.schema.Schema

final case class CheatQuestion(question: String) derives Schema
final case class CheatAnswer(answer: String) derives Schema
final case class CheatCode(code: Option[String]) derives Schema
final case class CheatRetry(question: String, previousCode: String, error: String) derives Schema
final case class CheatCodeAnswer(question: String, finalCode: String, codeOutput: String) derives Schema

object Cheatsheet:

  val signature: Signature[CheatQuestion, CheatAnswer] =
    Signature.derived[CheatQuestion, CheatAnswer]("BasicQA", "Answer with a short factoid answer.")

  // Python: dspy.Predict("question -> answer")
  val predict: Program[CheatQuestion, CheatAnswer] =
    Program.predict(ParameterId("cheatsheet/predict"), signature)

  // Python: predict(question="1+1", config={"rollout_id": 1, "temperature": 1.0})
  def predictWithConfig(question: String)(using PredictionBackend): Either[DspyError, String] =
    Demo
      .run(
        predict,
        CheatQuestion(question),
        RunOptions(config = DynamicValues.record("temperature" := 1.0), rolloutId = Some(1))
      )
      .map(_.output.answer)

  // Python: dspy.ChainOfThought(BasicQA)
  val chainOfThought: Program[CheatQuestion, ChainOfThought.WithReasoning[CheatAnswer]] =
    ChainOfThought(ParameterId("cheatsheet/cot"), signature)

  /** Python: dspy.ProgramOfThought(BasicQA).
    *
    * The Scala constructor makes generation, retry, code execution, and answer extraction visible child programs.
    */
  val programOfThought: ProgramWithEnv[CheatQuestion, CheatAnswer, PredictionBackend & CodeExecutionBackend] =
    val generate = Program
      .predict(
        ParameterId("cheatsheet/pot-generate"),
        Signature.derived[CheatQuestion, CheatCode]("GeneratePython", "Write Python code that computes the answer.")
      )
      .map(value => ProgramOfThought.GeneratedCode(value.code))
    val retry = Program
      .predict(
        ParameterId("cheatsheet/pot-retry"),
        Signature.derived[CheatRetry, CheatCode]("RepairPython", "Repair the Python code after the reported error.")
      )
      .contramap[ProgramOfThought.RetryInput[CheatQuestion]](input =>
        CheatRetry(input.input.question, input.previousCode, input.error)
      )
      .map(value => ProgramOfThought.GeneratedCode(value.code))
    val answer = Program
      .predict(
        ParameterId("cheatsheet/pot-answer"),
        Signature.derived[CheatCodeAnswer, CheatAnswer]("AnswerFromCode", "Answer from the executed code output.")
      )
      .contramap[ProgramOfThought.AnswerInput[CheatQuestion]](input =>
        CheatCodeAnswer(input.input.question, input.finalCode, input.codeOutput)
      )
    ProgramOfThought(generate, retry, Program.executeCode, answer, maxAttempts = 3)

  // Python: dspy.ReAct(BasicQA). A tool-free ReAct finishes immediately and then extracts an answer.
  val react: ProgramWithEnv[CheatQuestion, CheatAnswer, PredictionBackend & ToolBackend] =
    val generator = Program.lift[ReAct.StepInput[CheatQuestion], ReAct.Step](_ =>
      ReAct.Step("No host tool is required.", ReAct.Action.Finish())
    )
    val extractor = predict.contramap[ReAct.ExtractInput[CheatQuestion]](_.input)
    ReAct(generator, Program.invokeTool, extractor, maxIterations = 1)

  /** Python: dspy.CodeAct("n -> factorial").
    *
    * The generator and extractor are explicit. The execution capability is supplied only when the program runs.
    */
  val codeAct: ProgramWithEnv[CheatQuestion, CheatAnswer, PredictionBackend & CodeExecutionBackend] =
    val generator = Program
      .predict(
        ParameterId("cheatsheet/code-act-generate"),
        Signature.derived[CheatQuestion, CheatCode]("CodeActStep", "Write executable Python code.")
      )
      .contramap[CodeAct.StepInput[CheatQuestion]](_.input)
      .map(value => CodeAct.Step(value.code.getOrElse(""), finished = true))
    val extractor = predict.contramap[CodeAct.ExtractInput[CheatQuestion]](_.input)
    CodeAct(generator, Program.executeCode, extractor, maxIterations = 3)

  // Python: dspy.Parallel(num_threads=2)([(predict, ex1), (predict, ex2)])
  val parallel: Program[Unit, Vector[CheatAnswer]] = Program.collectAllPar(
    Vector(
      predict.contramap[Unit](_ => CheatQuestion("1+1")),
      predict.contramap[Unit](_ => CheatQuestion("2+2"))
    ),
    parallelism = 2
  )

  private def parseIntegerAnswer(answer: String): Int =
    answer.trim.split("\\s+").reverseIterator
      .find(_.exists(_.isDigit))
      .map(_.takeWhile(_ != '.').filter(_.isDigit))
      .flatMap(_.toIntOption)
      .getOrElse(0)

  // Python: def gsm8k_metric(gold, pred, trace=None): ...
  val gsm8kMetric: FunctionMetric = FunctionMetric.bool("gsm8k_metric") { (gold, prediction) =>
    val expected = gold.get("answer").map(DynamicValues.renderText).getOrElse("")
    val actual   = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
    parseIntegerAnswer(expected) == parseIntegerAnswer(actual)
  }

  // Python: Evaluate(devset=devset, metric=metric, num_threads=4)(program)
  def evaluate(
      program: RecordProgram[CheatQuestion, CheatAnswer],
      devset : Vector[Example],
      metric : Metric
  ): ZIO[PredictionBackend, Nothing, EvaluationResult] =
    Evaluate(program, devset, metric, EvaluateOptions(parallelism = 4))

  def student: RecordProgram[CheatQuestion, CheatAnswer] =
    Program.predict(ParameterId("cheatsheet/student"), signature).fromRecords(signature.inputShape)

  // Python: LabeledFewShot(k=8).compile(student, trainset)
  def labeledFewShot(trainset: Vector[Example]): IO[DspyError, RecordProgram[CheatQuestion, CheatAnswer]] =
    LabeledFewShot(student, trainset, LabeledFewShotConfig(k = DemoCount(8))).map(_.bestProgram)

  // Python: BootstrapFewShot(...).compile(student, trainset)
  def bootstrapFewShot(
      metric  : Metric,
      trainset: Vector[Example]
  ): ZIO[PredictionBackend, DspyError, RecordProgram[CheatQuestion, CheatAnswer]] =
    BootstrapFewShot(
      student,
      trainset,
      config = BootstrapFewShotConfig(
        metric = Some(metric),
        maxBootstrappedDemos = DemoCount(4),
        maxLabeledDemos = DemoCount(16),
        maxRounds = RoundCount(1),
        maxErrors = ErrorLimit(10)
      )
    ).map(_.bestProgram)

  // Python: BootstrapFewShotWithRandomSearch(...).compile(student, trainset)
  def bootstrapRandomSearch(
      metric  : Metric,
      trainset: Vector[Example],
      devset  : Vector[Example]
  ): ZIO[PredictionBackend, DspyError, RecordProgram[CheatQuestion, CheatAnswer]] =
    BootstrapRandomSearch(
      student,
      trainset,
      valset = Some(devset),
      config = BootstrapRandomSearchConfig(
        metric = metric,
        maxBootstrappedDemos = DemoCount(2),
        numCandidates = SearchCandidateCount(8)
      )
    ).map(_.bestProgram)

  // Python: COPRO(metric=metric).compile(student, trainset)
  def copro[RP](
      metric  : Metric,
      trainset: Vector[Example],
      proposer: ProgramWithEnv[COPRO.ProposalInput, COPRO.Proposal, RP]
  ): ZIO[PredictionBackend & RP, DspyError, OptimizationReport[RecordProgram[CheatQuestion, CheatAnswer]]] =
    COPRO(student, trainset, proposer, config = COPROConfig(metric = metric))

  // Python: MIPROv2(metric=metric).compile(student, trainset)
  def mipro[RP](
      metric  : Metric,
      trainset: Vector[Example],
      devset  : Vector[Example],
      proposer: ProgramWithEnv[MIPROv2.ProposalInput, MIPROv2.Proposal, RP]
  ): ZIO[PredictionBackend & RP, DspyError, OptimizationReport[RecordProgram[CheatQuestion, CheatAnswer]]] =
    MIPROv2(student, trainset, proposer, valset = Some(devset), config = MIPROv2Config(metric = metric))

  // Python: KNNFewShot(k=3, trainset=trainset, vectorizer=embedder).compile(student)
  // Retrieval is a typed child program. This example uses a supplied selector instead of a global retriever.
  def knnFewShot[R](
      neighbors: ProgramWithEnv[CheatQuestion, Vector[Example], R]
  ): RecordProgramWithEnv[CheatQuestion, CheatAnswer, PredictionBackend & R] =
    KNNFewShot(student, neighbors)

  // Python: program.save(path); fresh.load(path=path)
  def save(program: RecordProgram[CheatQuestion, CheatAnswer], path: String): Either[DspyError, Unit] =
    ProgramPersistence.save(program, path)

  def load(path: String): Either[DspyError, RecordProgram[CheatQuestion, CheatAnswer]] =
    ProgramPersistence.load(student, path)

  // Python: Ensemble(reduce_fn=dspy.majority).compile([prog1, prog2, prog3])
  def ensemble(members: Vector[Program[CheatQuestion, CheatAnswer]]): Program[CheatQuestion, CheatAnswer] =
    Ensemble(members) { predictions =>
      predictions
        .groupBy(_.output.answer)
        .maxByOption(_._2.size)
        .map((answer, _) => CheatAnswer(answer))
        .toRight(dspy4s.core.contracts.RuntimeError("ensemble", "No successful prediction"))
    }

  // Python: dspy.Tool(search_web)
  val searchWeb: Tool = Tool.fromEither(
    "search_web",
    "Search the web for information.",
    Vector("query" -> TypeRef.string)
  )(arguments =>
    DynamicValues.requireString(arguments, "query", "search_web")
      .map(query => DynamicValues.fromAny(s"Search results for: $query"))
  )

  // Python: BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0)
  val bestOfN: Program[CheatQuestion, CheatAnswer] = Program.bestOfN(predict, attempts = 3, threshold = Some(1.0)) {
    (_, prediction) => Right(if prediction.output.answer.trim.split("\\s+").length == 1 then 1.0 else 0.0)
  }

  // Python: Refine(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0)
  val refine: Program[CheatQuestion, CheatAnswer] =
    val critic = Program.lift[Refine.Attempt[CheatQuestion, CheatAnswer], Refine.Advice](_ =>
      Refine.Advice(Map(ParameterId("cheatsheet/predict") -> "Return one word only."))
    )
    Refine(predict, critic, maxAttempts = 3, threshold = 1.0) { (_, prediction) =>
      Right(if prediction.output.answer.trim.split("\\s+").length == 1 then 1.0 else 0.0)
    }

  /** Snippets without direct core equivalents remain explicit comparison notes:
    *
    *   - `BootstrapFinetune`, Optuna, and SIMBA are not implemented.
    *   - `asyncify` maps to running the ZIO returned by `ProgramRunner` as a future.
    *   - `streamify` maps to `ProgramEventStream.run` in the streaming module.
    *   - Cache and usage controls wrap the explicit language model. See the cache tutorial.
    */

// Pure surface check. Run with: sbt "examples/runMain dspy4s.examples.cheatsheetMain"
@main def cheatsheetMain(): Unit =
  println("gsm8k_metric: " + Cheatsheet.gsm8kMetric.name)
  println("program parameter IDs: " + Cheatsheet.student.program.parameters.all.map(_.id.value))
