package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  LenientValues,
  RuntimeContext,
  ThreadCount,
  :=
}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.{Module, ModuleLifecycle, Prediction, ProgramCall}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The temperature and the rollout id of an attempt of `BestOfN` and `Refine`. Upstream sets them on a copy of the LM
  * (`lm.copy(rollout_id=rid, temperature=1.0)`, `mod.set_lm(lm_)`, `dspy/predict/best_of_n.py`). Thus they reach each
  * predictor of the attempt, also when the program does not pass its call along, and they stay under the config of a
  * predictor and under a per-call config (`BaseLM`: `{**self.kwargs, **kwargs}`).
  */
class AttemptControlsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private type In  = (question: String)
  private type Out = (answer: String)

  private val qa = Signature.fromString("question -> answer")

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  private final class RecordingLm(reply: LmRequest => String) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty
    override val id: String              = "recording"
    override val mode: LmMode            = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = synchronized(requests += request)
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(request)))))

  private def chat(fields: (String, String)*): String =
    fields.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  private def answering: RecordingLm = new RecordingLm(_ => chat("answer" -> "a"))

  private def text(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def withLm[A](lm: LanguageModel, adapter: Adapter = ChatAdapter())(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def temperature(request: LmRequest): Option[Double] =
    DynamicValues.recordGet(request.options, "temperature").flatMap(LenientValues.toDouble)

  /** A composite that builds a new call for its predictor, so it does not pass the controls of its own call along. */
  private final case class NewCall(inner: Predict[In, Out]) extends Module[In, Out]:
    override val moduleName: String                       = "new_call"
    override protected val lifecycle: ModuleLifecycle[In] = ModuleLifecycle.typedWithoutInputs

    override protected def forward(call: ProgramCall[In])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
      inner(ProgramCall(call.input))

  /** A composite that runs a dynamic predictor on the worker threads of [[Parallel]]. */
  private final class FanOut(inner: DynamicPredict) extends Module[In, Out]:
    override val moduleName: String                       = "fan_out"
    override protected val lifecycle: ModuleLifecycle[In] = ModuleLifecycle.typedWithoutInputs

    override protected def forward(call: ProgramCall[In])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
      val tasks = Vector("x", "y").map(question => (inner, ProgramCall(rec("question" := question))))
      Parallel(numThreads = Some(ThreadCount(2))).run(tasks).map(_ => Prediction.pure((answer = "a")))

  private def attempts[P <: Module[In, Out]](program: P, n: AttemptCount): BestOfN[P, In, Out] =
    BestOfN[P, In, Out](program, n, (_, _) => 0.0, threshold = 1.0)

  test("each attempt of a composite that builds a new call has its rollout id and the temperature 1.0") {
    val lm = answering
    withLm(lm)(assert(attempts(NewCall(Predict(qa)), AttemptCount(3))((question = "q")).isRight))
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(0), Some(1), Some(2)))
    assertEquals(lm.requests.map(temperature).toVector, Vector.fill(3)(Some(1.0)))
  }

  test("the rollout ids start at the rollout id of the call") {
    val lm = answering
    withLm(lm)(assert(attempts(NewCall(Predict(qa)), AttemptCount(2))(
      ProgramCall((question = "q"), rolloutId = Some(5))
    ).isRight))
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(5), Some(6)))
  }

  test("the attempts of Refine have the controls, and its critic does not") {
    val lm = new RecordingLm(request =>
      if text(request).contains("program_trajectory") then chat("discussion" -> "d", "advice" -> "N/A")
      else chat("answer"                                                     -> "a")
    )
    val refine = Refine[NewCall, In, Out](
      module = NewCall(Predict(qa)),
      n = AttemptCount(2),
      rewardFn = (_, _) => 0.0,
      threshold = 1.0
    )
    withLm(lm)(assert(refine((question = "q")).isRight))

    val (critic, program) = lm.requests.toVector.partition(text(_).contains("program_trajectory"))
    assertEquals(program.map(_.rolloutId), Vector(Some(0), Some(1)))
    assertEquals(program.map(temperature), Vector(Some(1.0), Some(1.0)))
    assertEquals(critic.map(_.rolloutId), Vector(None))
    assertEquals(critic.map(temperature), Vector(None))
  }

  test("the temperature of an attempt stays under the config of a predictor") {
    val lm      = answering
    val program = Predict(qa, config = rec("temperature" := 0.2))
    withLm(lm)(assert(attempts(program, AttemptCount(1))((question = "q")).isRight))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(0.2)))
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(0)))
  }

  test("the temperature of an attempt stays under a per-call config") {
    val lm = answering
    withLm(lm)(assert(attempts(Predict(qa), AttemptCount(1))(
      ProgramCall((question = "q"), config = rec("temperature" := 0.3))
    ).isRight))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(0.3)))
  }

  test("MultiChainComparison keeps its temperature in an attempt") {
    val lm  = new RecordingLm(_ => chat("rationale" -> "r", "answer" -> "a"))
    val mcc = MultiChainComparison(qa, m = AttemptCount(1))
    val row = RawPrediction(values = rec("rationale" := "r", "answer" := "a"))
    type Input  = MultiChainInput[In]
    type Output = MultiChainComparison.WithRationale[Out]
    val best = BestOfN[mcc.type, Input, Output](mcc, AttemptCount(1), (_, _) => 0.0, threshold = 1.0)
    withLm(lm)(assert(best(ProgramCall(MultiChainInput((question = "q"), Vector(row)))).isRight))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(0.7)))
  }

  test("a predictor with a bound LM gets the controls of the attempt") {
    val ambient = answering
    val bound   = answering
    withLm(ambient)(assert(attempts(NewCall(Predict(qa).withLm(bound)), AttemptCount(2))((question = "q")).isRight))
    assertEquals(ambient.requests.size, 0)
    assertEquals(bound.requests.map(_.rolloutId).toVector, Vector(Some(0), Some(1)))
    assertEquals(bound.requests.map(temperature).toVector, Vector(Some(1.0), Some(1.0)))
  }

  test("the worker threads of an attempt have the controls") {
    val lm    = answering
    val inner = DynamicPredict(layout = qa.layout)
    withLm(lm)(assert(attempts(new FanOut(inner), AttemptCount(1))((question = "q")).isRight))
    assertEquals(lm.requests.size, 2)
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(0), Some(0)))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(1.0), Some(1.0)))
  }

  test("a nested BestOfN starts at the rollout id of the outer attempt, as upstream reads the LM copy") {
    val lm    = answering
    val inner = attempts(NewCall(Predict(qa)), AttemptCount(2))
    withLm(lm)(assert(attempts(inner, AttemptCount(2))((question = "q")).isRight))
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(0), Some(1), Some(1), Some(2)))
  }

  test("the controls end with the attempt: a reward function and a later call do not have them") {
    val lm      = answering
    val judge   = Predict(qa, name = Some("judge"))
    val program = BestOfN[Predict[In, Out], In, Out](
      module = Predict(qa),
      n = AttemptCount(1),
      rewardFn = (_, _) =>
        given RuntimeContext = RuntimeEnvironment.current
        val _                = judge((question = "score it"))
        0.0
      ,
      threshold = 1.0
    )
    withLm(lm) {
      assert(program((question = "q")).isRight)
      assert(Predict(qa)((question = "later")).isRight)
    }
    assertEquals(lm.requests.map(_.rolloutId).toVector, Vector(Some(0), None, None))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(1.0), None, None))
  }

  // Upstream `_forward_preprocess`: `temperature = config.get("temperature") or defaults.get("temperature")`. The
  // temperature 1.0 of the LM copy is in `defaults`, so a config temperature of 0 does not start the rule for `n` above
  // 1. The config value then goes to the model.
  test("a config temperature of 0 in an attempt stays 0, also with n above 1") {
    val lm      = answering
    val program = Predict(qa, config = rec("n" := 3, "temperature" := 0.0))
    withLm(lm)(assert(attempts(program, AttemptCount(1))((question = "q")).isRight))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(0.0)))
  }

  test("the rule for n above 1 reads the temperature of an attempt, as upstream reads the LM copy") {
    val lm      = answering
    val program = Predict(qa, config = rec("n" := 3))
    withLm(lm)(assert(attempts(program, AttemptCount(1))((question = "q")).isRight))
    assertEquals(lm.requests.map(temperature).toVector, Vector(Some(1.0)))
  }
