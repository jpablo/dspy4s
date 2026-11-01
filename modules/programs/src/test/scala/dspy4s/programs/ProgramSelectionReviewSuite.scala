package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{DspyError, DynamicValues, LenientValues, RuntimeContext, ThreadCount, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.strategies.RefineFeedback
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** The findings of the code review of `Predict`, the selection programs, and the executor. Each test states the rule of
  * upstream that it pins.
  */
class ProgramSelectionReviewSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private type In  = (question: String)
  private type Out = (answer: String)

  private val qa = Signature.fromString("question -> answer")

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  private final class RecordingLm(
      reply                      : LmRequest => String,
      override val defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty
    override val id: String              = "recording"
    override val mode: LmMode            = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = synchronized(requests += request)
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(request)))))

  private def chat(fields: (String, String)*): String =
    fields.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  private def text(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def withLm[A](lm: LanguageModel, adapter: Adapter = ChatAdapter())(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def temperature(request: LmRequest): Option[Double] =
    DynamicValues.recordGet(request.options, "temperature").flatMap(LenientValues.toDouble)

  // ── The trace of a reward function ─────────────────────────────────────────────────────────────────────────────

  test("the model calls of a reward function leave no trace, as upstream best_of_n.py") {
    val lm    = new RecordingLm(_ => chat("answer" -> "x"))
    val judge = Predict(qa, name = Some("judge"))
    val best  = BestOfN[Predict[In, Out], In, Out](
      module = Predict(qa),
      n = AttemptCount(2),
      rewardFn = (_, _) =>
        given RuntimeContext = RuntimeEnvironment.current
        val _                = judge((question = "score it"))
        0.0
      ,
      threshold = 1.0
    )

    withLm(lm) {
      val result = best((question = "q"))
      assert(result.isRight, result.toString)
      val components = RuntimeEnvironment.current.trace.map(_.component)
      assert(!components.contains("judge"), components.toString)
      assert(components.contains("predict"), components.toString)
      assertEquals(lm.requests.size, 4)
    }
  }

  // ── The critic of Refine ───────────────────────────────────────────────────────────────────────────────────────

  test("the critic of Refine sees one trajectory entry for a ChainOfThought, under its name in module_names") {
    val cot   = ChainOfThought(qa)
    val names = summon[OptimizableStructure[ChainOfThought[In, Out]]].inspectNamed(cot).map(_._1)
    val lm    = new RecordingLm(request =>
      if text(request).contains("program_trajectory") then chat("discussion" -> "d", "advice" -> "N/A")
      else chat("reasoning"                                                  -> "r", "answer" -> "a")
    )
    val refine = Refine[ChainOfThought[In, Out], In, ChainOfThought.WithReasoning[Out]](
      module = cot,
      n = AttemptCount(2),
      rewardFn = (_, _) => 0.0,
      threshold = 1.0
    )

    withLm(lm) {
      assert(refine((question = "q")).isRight)
      val critic = lm.requests.map(text).find(_.contains("program_trajectory")).getOrElse(fail("no critic call"))
      assertEquals("\"module_name\"".r.findAllIn(critic).size, 1, critic)
      assert(critic.contains(s"\"module_name\": \"${names.head}\""), critic)
    }
  }

  test("parseAdvice takes the first JSON object of a reply with more than one") {
    assertEquals(
      RefineFeedback.parseAdvice("""first {"hinter": "A"} and then {see above}""", Vector("hinter", "answerer")),
      Map("hinter" -> "A")
    )
  }

  test("the critic reads advice that a JSON adapter gives as an object") {
    val lm     = new RecordingLm(_ => """{"discussion": "d", "advice": {"hinter": "A"}}""")
    val critic = Predict(RefineFeedback.signature)
    val input  = RefineFeedback.OfferFeedbackInputs("i", "t", "o", 0.0, 1.0, "hinter")

    withLm(lm, JSONAdapter()) {
      val advice = critic(ProgramCall(input = input)).map(p => RefineFeedback.parseAdvice(p.output.advice, Vector("x")))
      assertEquals(advice, Right(Map("hinter" -> "A")))
    }
  }

  // ── MultiChainComparison ───────────────────────────────────────────────────────────────────────────────────────

  private def compareReply: RecordingLm = new RecordingLm(_ => chat("rationale" -> "r", "answer" -> "a"))

  test("an attempt shows the first line of the rationale and of the answer, as upstream forward") {
    val lm  = compareReply
    val mcc = MultiChainComparison(qa, m = AttemptCount(1))
    val row = rec("rationale" := " think\nmore", "answer" := "  blue \nsecond line")

    withLm(lm) {
      assert(mcc.compare((question = "q"), Vector(RawPrediction(values = row))).isRight)
      val prompt = text(lm.requests.head)
      assert(prompt.contains("«I'm trying to think I'm not sure but my prediction is blue»"), prompt)
    }
  }

  test("a rationale value wins over a reasoning value, also when it is empty, as upstream c.get") {
    val lm  = compareReply
    val mcc = MultiChainComparison(qa, m = AttemptCount(1))
    val row = rec("rationale" := "", "reasoning" := "ignored", "answer" := "blue")

    withLm(lm) {
      assert(mcc.compare((question = "q"), Vector(RawPrediction(values = row))).isRight)
      val prompt = text(lm.requests.head)
      assert(prompt.contains("«I'm trying to  I'm not sure but my prediction is blue»"), prompt)
    }
  }

  test("an attempt with no answer or no rationale is an error and no model call, as upstream raises") {
    val lm  = compareReply
    val mcc = MultiChainComparison(qa, m = AttemptCount(1))

    withLm(lm) {
      val noAnswer    = mcc.compare((question = "q"), Vector(RawPrediction(values = rec("rationale" := "r"))))
      val noRationale = mcc.compare((question = "q"), Vector(RawPrediction(values = rec("answer" := "a"))))
      assert(noAnswer.isLeft, noAnswer.toString)
      assert(noRationale.isLeft, noRationale.toString)
      assertEquals(lm.requests.size, 0)
    }
  }

  test("the temperature of MultiChainComparison is a default that a per-call value overrides, as upstream") {
    val lm       = compareReply
    val mcc      = MultiChainComparison(qa, m = AttemptCount(1))
    val attempts = Vector(RawPrediction(values = rec("rationale" := "r", "answer" := "a")))

    withLm(lm) {
      assert(mcc.compare((question = "q"), attempts).isRight)
      assert(mcc.compare((question = "q"), attempts, config = rec("temperature" := 0.2)).isRight)
      assertEquals(lm.requests.map(temperature).toVector, Vector(Some(0.7), Some(0.2)))
    }
  }

  // ── The temperature rule of Predict ────────────────────────────────────────────────────────────────────────────

  private def sentTemperature(
      config  : DynamicValue.Record,
      defaults: DynamicValue.Record = DynamicValue.Record.empty
  ): Option[Double] =
    val lm = new RecordingLm(_ => chat("answer" -> "a"), defaults)
    withLm(lm)(assert(Predict(qa, config = config)((question = "q")).isRight))
    temperature(lm.requests.head)

  test("with n above 1, an unset or low temperature becomes 0.7, as upstream _forward_preprocess") {
    assertEquals(sentTemperature(rec("n" := 3)), Some(0.7))
    assertEquals(sentTemperature(rec("n" := 3, "temperature" := 0.1)), Some(0.7))
    assertEquals(sentTemperature(rec("n" := 3, "temperature" := 0.15)), Some(0.7))
    assertEquals(sentTemperature(DynamicValue.Record.empty, defaults = rec("n" := 3)), Some(0.7))
  }

  test("the temperature rule of Predict leaves each other case as it is") {
    assertEquals(sentTemperature(rec("n" := 3, "temperature" := 0.5)), Some(0.5))
    assertEquals(sentTemperature(rec("n" := 1)), None)
    assertEquals(sentTemperature(DynamicValue.Record.empty), None)
    // The temperature of the model counts, as upstream reads `lm.kwargs`. The request carries no option then.
    assertEquals(sentTemperature(rec("n" := 3), defaults = rec("temperature" := 0.9)), None)
  }

  // ── Aggregation ────────────────────────────────────────────────────────────────────────────────────────────────

  test("majority over an empty first record is a Left, not an exception") {
    assert(Aggregation.majority(Vector(DynamicValue.Record.empty)).isLeft)
  }

  // ── The warnings of Predict ────────────────────────────────────────────────────────────────────────────────────

  test("a record call warns about missing and extra inputs with the texts of upstream _forward_preprocess") {
    val captured = new ByteArrayOutputStream
    val lm       = new RecordingLm(_ => chat("answer" -> "a"))
    val program  = DynamicPredict(layout = Signature.fromString("question, context -> answer").layout)

    withLm(lm) {
      Console.withErr(captured) {
        val result = program(ProgramCall(input = rec("question" := "q", "extra" := "x")))
        assert(result.isRight, result.toString)
      }
    }
    val warnings = captured.toString
    assert(
      warnings.contains("Not all input fields were provided to module. Present: ['question']. Missing: ['context']."),
      warnings
    )
    assert(
      warnings.contains(
        "Input contains fields not in signature. These fields will be ignored: ['extra']. " +
          "Expected fields: ['question', 'context']."
      ),
      warnings
    )
  }

  // ── The executor ───────────────────────────────────────────────────────────────────────────────────────────────

  test("the timeout error names the limit, also for a limit under one second") {
    given RuntimeContext = RuntimeContext()
    val slow             = (_: Int) => Thread.sleep(2000)
    val result           = ParallelExecutor(numThreads = ThreadCount(1), timeout = 50.millis).execute(slow, Vector(1))
    assert(result.left.exists(_.message.contains("50 milliseconds")), result.toString)
  }
