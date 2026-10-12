package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.MessageRole
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.optimization.OptimizableMetadata
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** Two leaves with different signatures: `drafter` writes a draft, and `answerer` answers from it. Top-level so
  * `OptimizableStructure.derived` sees its Mirror field labels.
  */
final case class TwoStage(drafter: DynamicPredict, answerer: DynamicPredict)

object TwoStage:
  given ProgramRunner[TwoStage] with
    def run(program: TwoStage, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.drafter(call).flatMap { drafted =>
        val draft = DynamicValues.recordGet(drafted.output, "draft").getOrElse(DynamicValue.Null)
        program.answerer(call.mapInput(_ => call.input.updated("draft", draft))).map(_.raw)
      }

/** Like [[TwoStage]], but the drafter runs two times in each run. */
final case class LoopStage(drafter: DynamicPredict, answerer: DynamicPredict)

object LoopStage:
  given ProgramRunner[LoopStage] with
    def run(program: LoopStage, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      for
        _       <- program.drafter(call)
        drafted <- program.drafter(call)
        draft    = DynamicValues.recordGet(drafted.output, "draft").getOrElse(DynamicValue.Null)
        result  <- program.answerer(call.mapInput(_ => call.input.updated("draft", draft)))
      yield result.raw

/** A leaf type whose module records no predictor key. An optimizer must reject it. */
final case class KeylessLeaf(layout: SignatureLayout, demos: Vector[Example] = Vector.empty) extends DynamicModule:
  override val moduleName: String = "keyless"
  override protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    Right(RawPrediction(DynamicValues.record("answer" := "Paris")))

object KeylessLeaf:
  given OptimizableLeaf[KeylessLeaf] with
    def get(program: KeylessLeaf): OptimizableParameters =
      OptimizableParameters(program.layout.instructions, program.demos)
    def metadata(program: KeylessLeaf): OptimizableMetadata =
      OptimizableMetadata.from(program.layout, program.moduleName)
    def set(program: KeylessLeaf, updated: OptimizableParameters): KeylessLeaf =
      program.copy(layout = program.layout.withInstructions(updated.instructions), demos = updated.demos)

/** `BootstrapFewShot` against upstream `dspy/teleprompt/bootstrap.py` (PORT_GAPS G-42). The optimizer finds the calls
  * of each leaf by the predictor key of the trace entries (UPGRADE-3.2.1-to-3.4.0, decision D2).
  */
class BootstrapFewShotPerPredictorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Answers by the output field that the last user message asks for, and records each request.
    *
    * @param fail
    *   each call returns a `Left`
    * @param rightOnlyOnRetry
    *   the answer is right only when the request has a rollout id (a retry round)
    */
  private final class StageLm(fail: Boolean = false, rightOnlyOnRetry: Boolean = false) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "stage"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = requests.synchronized(requests += request)
      if fail then Left(RuntimeError("stage_lm", "the model failed"))
      else
        val ask  = request.messages.lastOption.flatMap(_.text).getOrElse("")
        val text =
          if ask.contains("[[ ## answer ## ]]") then
            val answer = if rightOnlyOnRetry && request.rolloutId.isEmpty then "WRONG" else "Paris"
            s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"
          else "[[ ## draft ## ]]\na draft\n\n[[ ## completed ## ]]"
        Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Accepts each run and records how many predictor entries the trace has. */
  private final class TraceRecordingMetric extends Metric:
    val predictorEntries: ArrayBuffer[Int] = ArrayBuffer.empty
    override def name: String              = "recording"
    override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
        RuntimeContext
    ): Either[DspyError, Double] =
      val _ = predictorEntries.synchronized(predictorEntries += trace.count(_.predictor.isDefined))
      Right(1.0)

  private def predict(layout: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get)

  private val twoStage  = TwoStage(predict("question -> draft"), predict("question, draft -> answer"))
  private val loopStage = LoopStage(predict("question -> draft"), predict("question, draft -> answer"))

  private def trainset(size: Int): Vector[Example] =
    Vector.tabulate(size)(index =>
      Example(DynamicValues.record("question" := s"Capital of France ($index)?", "answer" := "Paris"), Set("question"))
    )

  private val exactAnswer: Metric = new ExactMatch(answerField = "answer")

  private def compile[P](
      student : P,
      examples: Vector[Example],
      config  : BootstrapFewShotConfig,
      lm      : LanguageModel,
      teacher : Option[P] = None
  )(using
      dspy4s.programs.optimization.OptimizableStructure[P],
      ProgramRunner[P]
  ): Either[DspyError, OptimizationReport[P]] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      new BootstrapFewShot[P](config).compile(student, examples, teacher)
    }

  private def fields(demo: Example): Set[String] = DynamicValues.recordKeys(demo.values).toSet

  test("each leaf gets bootstrapped demos with the fields of its own signature") {
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(0)
    )
    val compiled = compile(twoStage, trainset(3), config, new StageLm).toOption.get.bestProgram

    val drafterDemos  = compiled.drafter.demos.filter(_.augmented)
    val answererDemos = compiled.answerer.demos.filter(_.augmented)
    assertEquals(drafterDemos.size, 2)
    assertEquals(answererDemos.size, 2)
    assertEquals(drafterDemos.map(fields).distinct, Vector(Set("question", "draft")))
    assertEquals(drafterDemos.map(_.inputKeys).distinct, Vector(Set("question")))
    assertEquals(answererDemos.map(fields).distinct, Vector(Set("question", "draft", "answer")))
    assertEquals(answererDemos.map(_.inputKeys).distinct, Vector(Set("question", "draft")))
  }

  test("a leaf that ran two times in one example gives one demo for that example") {
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(0)
    )
    val compiled = compile(loopStage, trainset(2), config, new StageLm).toOption.get.bestProgram
    assertEquals(compiled.drafter.demos.count(_.augmented), 2)
    assertEquals(compiled.answerer.demos.count(_.augmented), 2)
  }

  test("the labeled pool holds each example that was not bootstrapped") {
    // Each run passes, so the bootstrap stops after two examples. The other four examples are the labeled pool.
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(4)
    )
    val compiled = compile(twoStage, trainset(6), config, new StageLm).toOption.get.bestProgram
    val labeled  = compiled.answerer.demos.filterNot(_.augmented)
    assertEquals(compiled.answerer.demos.count(_.augmented), 2)
    assertEquals(labeled.size, 2)
    assertEquals(labeled.map(fields).distinct, Vector(Set("question", "answer")))
    assertEquals(compiled.drafter.demos.count(!_.augmented), 2)
  }

  test("a failed run counts as an error, and the limit stops the compile") {
    val config = BootstrapFewShotConfig(metric = Some(exactAnswer), maxErrors = ErrorLimit(2))
    val result = compile(twoStage, trainset(5), config, new StageLm(fail = true))
    assert(result.isLeft, s"expected the error limit to stop the compile, got $result")
  }

  test("the metric receives the trace of the run") {
    val metric = new TraceRecordingMetric
    val config = BootstrapFewShotConfig(metric = Some(metric), maxBootstrappedDemos = DemoCount(1))
    val _      = compile(twoStage, trainset(1), config, new StageLm)
    assertEquals(metric.predictorEntries.toVector, Vector(2))
  }

  test("a retry round uses a new rollout id and the temperature 1.0") {
    val lm     = new StageLm(rightOnlyOnRetry = true)
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(0),
      maxRounds = RoundCount(2)
    )
    val compiled = compile(twoStage, trainset(1), config, lm).toOption.get.bestProgram

    val retries = lm.requests.toVector.filter(_.rolloutId.isDefined)
    assert(retries.nonEmpty, "the second round must send a rollout id")
    assertEquals(retries.map(_.rolloutId).distinct, Vector(Some(1)))
    assertEquals(
      retries.map(request => DynamicValues.recordGet(request.options, "temperature").map(DynamicValues.toAny)).distinct,
      Vector(Some(1.0))
    )
    assertEquals(compiled.answerer.demos.count(_.augmented), 1)
  }

  test("a teacher with no demos gets labeled demos first, without the current example") {
    val lm     = new StageLm
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(3)
    )
    val _ = compile(twoStage, trainset(4), config, lm)

    // The answerer has no `draft` in a labeled example, so only the drafter shows complete labeled demos here.
    val withDemos = lm.requests.toVector.filter(_.messages.count(_.role == MessageRole.User) > 1)
    assert(withDemos.nonEmpty, "the teacher must run with labeled demos in its prompt")
    withDemos.foreach { request =>
      val users   = request.messages.filter(_.role == MessageRole.User).flatMap(_.text)
      val current = "\\(\\d+\\)".r.findFirstIn(users.last).getOrElse(fail(s"no example index in ${users.last}"))
      assert(!users.init.exists(_.contains(current)), s"the current example $current is in its own demos")
    }
  }

  test("two leaves with the same key are rejected before any run") {
    val lm     = new StageLm
    val same   = predict("question -> answer")
    val twins  = TwoStage(same, same)
    val result = compile(twins, trainset(2), BootstrapFewShotConfig(metric = Some(exactAnswer)), lm)
    result match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'drafter'") && error.message.contains("'answerer'"), error.message)
      case other => fail(s"expected a ValidationError, got $other")
    assertEquals(lm.requests.size, 0)
  }

  test("a leaf type that records no predictor key is rejected") {
    val keyless = KeylessLeaf(SignatureLayout.parse("question -> answer").toOption.get)
    val result  = compile(keyless, trainset(2), BootstrapFewShotConfig(metric = Some(exactAnswer)), new StageLm)
    result match
      case Left(error: ValidationError) => assert(error.message.contains("ModuleLifecycle"), error.message)
      case other                        => fail(s"expected a ValidationError, got $other")
  }

  test("a leaf type that records no predictor key is rejected also when the metric accepts no run") {
    // The keyless leaf answers "Paris", so the metric rejects each run. The missing key is still an error.
    val keyless  = KeylessLeaf(SignatureLayout.parse("question -> answer").toOption.get)
    val examples =
      Vector(Example(DynamicValues.record("question" := "Capital of Italy?", "answer" := "Rome"), Set("question")))
    val result = compile(keyless, examples, BootstrapFewShotConfig(metric = Some(exactAnswer)), new StageLm)
    result match
      case Left(error: ValidationError) => assert(error.message.contains("ModuleLifecycle"), error.message)
      case other                        => fail(s"expected a ValidationError, got $other")
  }

  test("a student that has demos keeps them while it runs as the teacher") {
    // Python: the teacher is `student.deepcopy()`. With `maxLabeledDemos = 0`, it gets no labeled demos.
    val lm  = new StageLm
    val own =
      Example(DynamicValues.record("question" := "Capital of Spain?", "draft" := "a Spanish draft"), Set("question"))
    val student = twoStage.copy(drafter = twoStage.drafter.copy(demos = Vector(own)))
    val config  = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(0)
    )
    val compiled = compile(student, trainset(1), config, lm).toOption.get.bestProgram

    val prompts = lm.requests.toVector.map(_.messages.flatMap(_.text).mkString("\n"))
    assert(prompts.exists(_.contains("Capital of Spain?")), "the model must see the demo of the student in a prompt")
    // The compiled student gets the bootstrapped demo. It does not keep its old demo.
    assertEquals(compiled.drafter.demos.map(_.augmented), Vector(true))
  }

  test("a teacher leaf with another signature structure is rejected, and the message names the leaf") {
    val lm      = new StageLm
    val teacher = TwoStage(predict("question -> draft"), predict("question, draft -> answer, note"))
    val result  = compile(twoStage, trainset(2), BootstrapFewShotConfig(metric = Some(exactAnswer)), lm, Some(teacher))
    result match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'answerer'") && !error.message.contains("'drafter'"), error.message)
      case other => fail(s"expected a ValidationError, got $other")
    assertEquals(lm.requests.size, 0)
  }

  test("a teacher leaf with another module name and the same signature structure is accepted") {
    val teacher = twoStage.copy(drafter = twoStage.drafter.copy(name = Some("teacher_drafter")))
    val config  = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(0)
    )
    val compiled = compile(twoStage, trainset(3), config, new StageLm, Some(teacher)).toOption.get.bestProgram
    assertEquals(compiled.drafter.demos.count(_.augmented), 2)
    assertEquals(compiled.answerer.demos.count(_.augmented), 2)
  }

  test("each leaf gets its own sample of labeled demos, without replacement") {
    // One example is bootstrapped. Each leaf then takes three of the other eight examples from one random source.
    val config = BootstrapFewShotConfig(
      metric = Some(exactAnswer),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(4)
    )
    val examples = trainset(9)
    val compiled = compile(twoStage, examples, config, new StageLm).toOption.get.bestProgram
    val samples  = Vector(compiled.drafter.demos, compiled.answerer.demos).map(_.filterNot(_.augmented))
    samples.foreach { labeled =>
      assertEquals(labeled.size, 3)
      assertEquals(labeled.distinct.size, 3)
      assert(labeled.forall(examples.drop(1).contains), s"each labeled demo must come from the pool, got $labeled")
    }
    assertNotEquals(samples(0), samples(1))
  }

  test("the trace entries of the metric do not reach the context of the caller") {
    val leakyMetric: Metric = new Metric:
      override def name: String = "leaky"
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
          RuntimeContext
      ): Either[DspyError, Double] =
        // An LM-judged metric runs a predictor, and that predictor appends to the current trace.
        RuntimeEnvironment.appendTrace(TraceEntry("judge", DynamicValues.record(), DynamicValues.record()))
        Right(1.0)

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val config           = BootstrapFewShotConfig(metric = Some(leakyMetric), maxBootstrappedDemos = DemoCount(2))
      val result           = new BootstrapFewShot[TwoStage](config).compile(twoStage, trainset(3))
      assert(result.isRight, result.toString)
      assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector.empty)
    }
  }
