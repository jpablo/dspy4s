package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.contracts.Embedder
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.retrievers.NeighborCount
import dspy4s.programs.retrievers.NonEmptyTrainset
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

/** A configuration error of the bootstrap is an error of each optimizer that calls `BootstrapFewShot`
  * (UPGRADE-3.2.1-to-3.4.0, decision D2). Two leaves with the same predictor key, and a leaf type that records no key,
  * give a `ValidationError`. The optimizer returns that error. It does not continue with fewer candidates.
  */
class OptimizerConfigurationErrorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class CountingLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = new AtomicInteger(0)
    override val id: String                                                                    = "counting"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  private val qa = SignatureLayout.parse("question -> answer").toOption.get

  /** Two leaves with the same module name and the same signature, so they have the same predictor key. */
  private val twins = TwoStage(DynamicPredict(layout = qa), DynamicPredict(layout = qa))

  /** A leaf that answers "Paris" and records no predictor key. */
  private val keyless = KeylessLeaf(qa)

  private val metric = new ExactMatch(answerField = "answer")

  private val examples: Vector[Example] = Vector.tabulate(4)(index =>
    Example(DynamicValues.record("question" := s"Capital of France ($index)?", "answer" := "Paris"), Set("question"))
  )

  private val embedder: Embedder = Embedder.fromFunction("flat")(texts => texts.map(_ => Vector(1.0f, 0.0f)))

  /** Run `body` with a counting LM. The result must be a `ValidationError`. Return its message and the LM call count.
    */
  private def rejected[A](body: RuntimeContext ?=> Either[DspyError, A]): (String, Int) =
    val lm     = new CountingLm
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      body
    }
    result match
      case Left(error: ValidationError) => (error.message, lm.calls.get())
      case other                        => fail(s"expected a ValidationError, got $other")

  private def assertTwinsRejected[A](body: RuntimeContext ?=> Either[DspyError, A]): Unit =
    val (message, calls) = rejected(body)
    assert(message.contains("'drafter'") && message.contains("'answerer'"), message)
    assertEquals(calls, 0)

  private def assertKeylessRejected[A](body: RuntimeContext ?=> Either[DspyError, A]): Unit =
    val (message, _) = rejected(body)
    assert(message.contains("ModuleLifecycle"), message)

  private def miproV2[P: {OptimizableStructure, ProgramRunner}] =
    new MIPROv2[P](MIPROv2Config(metric = metric, numCandidates = CandidateCount(2), numTrials = TrialCount(2)))

  private def randomSearch[P: {OptimizableStructure, ProgramRunner}] =
    new BootstrapFewShotWithRandomSearch[P](RandomSearchConfig(
      metric = metric,
      numCandidates = SearchCandidateCount(2)
    ))

  private def inferRules[P: {OptimizableStructure, ProgramRunner}] =
    new InferRules[P](InferRulesConfig(metric = metric, numCandidates = CandidateCount(2)))

  private def knnFewShot[P: {OptimizableStructure, ProgramRunner}] =
    new KNNFewShot[P](NeighborCount(2), NonEmptyTrainset.applyUnsafe(examples), embedder)

  // ── Two leaves with the same key ──

  test("MIPROv2 rejects two leaves with the same key before any LM call") {
    assertTwinsRejected(miproV2[TwoStage].compile(twins, examples))
  }

  test("BootstrapFewShotWithRandomSearch rejects two leaves with the same key before any LM call") {
    assertTwinsRejected(randomSearch[TwoStage].compile(twins, examples))
  }

  test("InferRules rejects two leaves with the same key before any LM call") {
    assertTwinsRejected(inferRules[TwoStage].compile(twins, examples))
  }

  test("InferRules rejects two leaves with the same key also when the bootstrap gets no example") {
    // With one example and no valset, the 50/50 split gives the bootstrap an empty trainset.
    assertTwinsRejected(inferRules[TwoStage].compile(twins, examples.take(1)))
  }

  test("KNNFewShot rejects two leaves with the same key at compile, before any LM call") {
    assertTwinsRejected(knnFewShot[TwoStage].compile(twins))
  }

  // ── A leaf type that records no key ──

  test("MIPROv2 rejects a leaf type that records no predictor key") {
    assertKeylessRejected(miproV2[KeylessLeaf].compile(keyless, examples))
  }

  test("BootstrapFewShotWithRandomSearch rejects a leaf type that records no predictor key") {
    assertKeylessRejected(randomSearch[KeylessLeaf].compile(keyless, examples))
  }

  test("InferRules rejects a leaf type that records no predictor key") {
    assertKeylessRejected(inferRules[KeylessLeaf].compile(keyless, examples))
  }

  test("KNNFewShot rejects a leaf type that records no predictor key when the program runs") {
    val query = ProgramCall(input = DynamicValues.record("question" := "Capital of France?"))
    assertKeylessRejected(knnFewShot[KeylessLeaf].compile(keyless).flatMap(program => program(query)))
  }

  // ── Another failure of a bootstrap run ──

  test("BootstrapFewShotWithRandomSearch continues when each bootstrap run stops at its error limit") {
    // The teacher throws in each run. The error limit gives a `RuntimeError`, which is not a configuration error. The
    // search continues with the zero-shot candidate (seed -3) and the labeled candidate (seed -2).
    val student   = ScriptedPredictProgram(Map.empty, qa)
    val teacher   = student.copy(failsWith = Some(new RuntimeException("boom")))
    val optimizer = new BootstrapFewShotWithRandomSearch[ScriptedPredictProgram](
      RandomSearchConfig(metric = metric, numCandidates = SearchCandidateCount(2), maxErrors = ErrorLimit(2))
    )
    given RuntimeContext = RuntimeEnvironment.current
    val report           = optimizer.compile(student, examples, teacher = Some(teacher)).toOption.get
    assertEquals(report.candidates.flatMap(_.metadata.get("seed")).toSet, Set[Any](-3, -2))
  }
