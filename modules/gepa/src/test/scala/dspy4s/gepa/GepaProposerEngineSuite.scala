package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.History
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LanguageModelRef
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The instruction proposer in the GEPA engine, and the reflective records that the adapter gives to it (PORT_GAPS
  * G-45, item O3; upstream b6c8aaf1e, `dspy/teleprompt/gepa/gepa_utils.py`, and gepa 0.1.4
  * `proposer/reflective_mutation/reflective_mutation.py` `_propose_texts_batch_safe`).
  */
class GepaProposerEngineSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  private val improved = "Answer with the CITY name only."

  /** Answers "Paris" only when the prompt has the token "CITY". */
  private final class TaskLm extends LanguageModel:
    override val id: String                                                                    = "task"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val answer = if prompt.contains("CITY") then "Paris" else "WRONG"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** Gives the replies in order and repeats the last one. Records the text of each request. */
  private final class ScriptedReflectionLm(replies: String*) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = requests.synchronized {
        requests += request.messages.flatMap(_.text).mkString("\n")
        requests.size - 1
      }
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(math.min(index, replies.size - 1))))))

  /** What one call of [[ProbeProposer]] got. */
  private final case class ProbeCall(
      candidate : Candidate,
      reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
      components: Vector[OptimizableId],
      givenLm   : Option[LanguageModelRef],
      overlayLm : Option[LanguageModelRef]
  )

  /** Records each call and gives `result`. It also writes one trace entry and one history entry in its context. */
  private final class ProbeProposer(result: Either[DspyError, Map[OptimizableId, String]]) extends InstructionProposer:
    val calls: ArrayBuffer[ProbeCall] = ArrayBuffer.empty
    override def propose(
        candidate : Candidate,
        reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
        components: Vector[OptimizableId]
    )(using ctx: RuntimeContext): Either[DspyError, Map[OptimizableId, String]] =
      val _ = calls.synchronized(
        calls += ProbeCall(candidate, reflective, components, ctx.lm, RuntimeEnvironment.current.lm)
      )
      RuntimeEnvironment.appendTrace(TraceEntry("probe", DynamicValue.Record.empty, DynamicValue.Record.empty))
      RuntimeEnvironment.appendHistory(HistoryEntry("probe", DynamicValue.Record.empty))
      result

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

  private val program: DynamicPredict = predict("question -> answer", "Answer.")

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital of France ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  /** The seed validation costs 4 calls. One iteration costs 8 at most: 2 for the parent, 2 for the child, and 4 for the
    * validation of an accepted child. A budget of 12 thus holds one iteration exactly.
    */
  private val oneIteration: GepaConfig = GepaConfig(
    budget = GepaBudget.MetricCalls(MetricCallCount(12)),
    reflectionMinibatchSize = MinibatchSize(2),
    seed = 1L
  )

  /** Run `body` in a context whose language model is the task LM. */
  private def withTaskLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def optimize(
      reflectionLm: LanguageModel,
      proposer    : InstructionProposer = InstructionProposer.default,
      config      : GepaConfig          = oneIteration
  )(using RuntimeContext): Either[DspyError, GepaResult[DynamicPredict]] =
    new GepaEngine(new GepaAdapter(program, metric), reflectionLm, config, proposer)
      .optimize(Candidate.seed(program), trainset = dataset, valset = dataset)

  // ── The reflective records of the adapter ───────────────────────────────────────────────────────────────────

  test("a reflective record holds the values of the predictor call, not their text") {
    withTaskLm(new TaskLm) {
      val adapter   = new GepaAdapter(program, metric)
      val candidate = Candidate.seed(program)
      val batch     = adapter.evaluate(dataset.take(1), candidate, captureTraces = true)
      val records   =
        adapter.makeReflectiveDataset(candidate, batch, Vector(first)).fold(error => fail(error.message), identity)
      assertEquals(
        records(first),
        Vector(
          ReflectiveRecord(
            inputs = DynamicValues.record("question" := "Capital of France (1)?"),
            generatedOutputs = DynamicValues.record("answer" := "WRONG"),
            feedback = "expected 'Paris', got 'WRONG'"
          )
        )
      )
    }
  }

  test("an input of the history type moves to the key Context, and that key is first") {
    val history      = History.empty :+ DynamicValues.record("question" := "Capital of Spain?", "answer" := "Madrid")
    val historyValue = summon[Schema[History]].toDynamicValue(history)
    val chat         = predict("question, history: History -> answer", "Answer.")
    val example      = Example(
      values = DynamicValues.record("question" := "Capital of France?", "history" -> historyValue, "answer" := "Paris"),
      inputKeys = Set("question", "history")
    )
    withTaskLm(new TaskLm) {
      val adapter   = new GepaAdapter(chat, metric)
      val candidate = Candidate.seed(chat)
      val batch     = adapter.evaluate(Vector(example), candidate, captureTraces = true)
      val records   =
        adapter.makeReflectiveDataset(candidate, batch, Vector(first)).fold(error => fail(error.message), identity)
      val inputs = records(first).headOption.getOrElse(fail("no record")).inputs
      assertEquals(
        inputs,
        DynamicValues.record(ReflectiveRecord.ContextKey -> historyValue, "question" := "Capital of France?")
      )
    }
  }

  // ── The default proposer in the engine ──────────────────────────────────────────────────────────────────────

  test("the reflection LM gets the current instruction and the JSON text of the records") {
    val reflection = new ScriptedReflectionLm(reply(improved))
    val result     = withTaskLm(new TaskLm)(optimize(reflection)).fold(error => fail(error.message), identity)

    assertEquals(result.bestScore, 1.0)
    assertEquals(result.bestCandidate(first), Some(improved))
    assertEquals(reflection.requests.size, 1)
    val request = reflection.requests.head
    assert(request.contains("Answer."), request)
    assert(request.contains(""""Generated Outputs": {"answer": "WRONG"}"""), request)
    assert(request.contains(""""Feedback": "expected 'Paris', got 'WRONG'""""), request)
    assert(request.contains(""""Inputs": {"question": "Capital of France ("""), request)
    // The proposer has its own adapter: the request does not have the form of the chat adapter of the context. The
    // JSON adapter has the input markers of the chat adapter (upstream), but no completed marker.
    assert(request.contains("Outputs will be a JSON object with the following fields."), request)
    assert(!request.contains("[[ ## completed ## ]]"), request)
  }

  test("an option of the default proposer reaches the reflection LM") {
    val reflection = new ScriptedReflectionLm(reply(improved))
    val proposer   = DefaultInstructionProposer(additionalInstructions = Some("Use the imperative voice."))
    assert(withTaskLm(new TaskLm)(optimize(reflection, proposer)).isRight)
    assert(reflection.requests.head.contains("Use the imperative voice."), reflection.requests.head)
  }

  test("a proposal that fails two times gives no child, and the iteration costs its minibatch only") {
    val reflection = new ScriptedReflectionLm(reply("  "))
    val result     = withTaskLm(new TaskLm)(optimize(reflection)).fold(error => fail(error.message), identity)

    // The engine calls the proposer one more time after a failure, and then makes no proposal.
    assertEquals(reflection.requests.size, 2)
    assertEquals(result.numCandidates.toInt, 1)
    // 4 for the seed and 2 for the minibatch of the parent. No child evaluation ran.
    assertEquals(result.totalMetricCalls.toInt, 6)
    assertEquals(result.bestCandidate, Candidate.seed(program))
  }

  test("a proposal that fails one time and then passes gives a child") {
    val reflection = new ScriptedReflectionLm("This is not a JSON object.", reply(improved))
    val result     = withTaskLm(new TaskLm)(optimize(reflection)).fold(error => fail(error.message), identity)

    assertEquals(reflection.requests.size, 2)
    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.bestScore, 1.0)
    assertEquals(result.totalMetricCalls.toInt, 12)
  }

  test("with all components selected, the failure of one component drops the texts of all components") {

    /** Stage `a` answers "ok" only with FIXED_A in its prompt. Stage `b` answers "ok" only with FIXED_B. */
    final class StageLm extends LanguageModel:
      override val id: String                                                                    = "stage"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val prompt          = request.messages.flatMap(_.text).mkString("\n")
        val (field, marker) = if prompt.contains("## a ##") then ("a", "FIXED_A") else ("b", "FIXED_B")
        val value           = if prompt.contains(marker) then "ok" else "bad"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## $field ## ]]\n$value\n\n[[ ## completed ## ]]"))))

    val partialCredit: FeedbackMetric = new FeedbackMetric:
      override def name: String = "partial_credit"
      override def feedback(
          example       : Example,
          prediction    : RawPrediction,
          trace         : Vector[TraceEntry],
          component     : Option[String],
          componentTrace: Vector[TraceEntry]
      )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
        val ok = Vector("a", "b").count(name => prediction.get(name).map(DynamicValues.renderText).contains("ok"))
        Right(ScoreWithFeedback(ok * 0.5, "Each stage must answer ok."))

    val twoStage  = TwoStage(predict("question -> a", "Component A."), predict("question -> b", "Component B."))
    val questions = (1 to 4).toVector.map(i =>
      Example(values = DynamicValues.record("question" := s"Question $i?"), inputKeys = Set("question"))
    )
    // The first component gets a new text and the second gets an empty text, in each of the two calls of the proposer.
    val reflection =
      new ScriptedReflectionLm(reply("Component A. FIXED_A"), reply(" "), reply("Component A. FIXED_A"), reply(" "))
    val result = withTaskLm(new StageLm) {
      new Gepa[TwoStage](
        partialCredit,
        reflection,
        oneIteration.copy(componentSelector = ComponentSelector.All, useMerge = false)
      ).compile(twoStage, questions, questions)
    }.fold(error => fail(error.message), identity)

    assertEquals(reflection.requests.size, 4)
    assertEquals(result.numCandidates.toInt, 1)
    assertEquals(result.totalMetricCalls.toInt, 6)
    assertEquals(result.bestCandidate, Map(first -> Some("Component A."), second -> Some("Component B.")))
  }

  // ── A proposer of the caller ────────────────────────────────────────────────────────────────────────────────

  test("the engine calls the proposer one time, with the parent, the records, and the selected components") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map(first -> improved)))
    val result     = withTaskLm(new TaskLm)(optimize(reflection, proposer)).fold(error => fail(error.message), identity)

    assertEquals(proposer.calls.size, 1)
    val call = proposer.calls.head
    assertEquals(call.candidate, Candidate.seed(program))
    assertEquals(call.components, Vector(first))
    assertEquals(call.reflective.keySet, Set(first))
    assertEquals(call.reflective(first).size, 2)
    // The text of the proposer is the instruction of the child.
    assertEquals(result.bestCandidate(first), Some(improved))
    assertEquals(result.bestScore, 1.0)
    // The proposer of the caller made no call to the reflection LM.
    assertEquals(reflection.requests.size, 0)
  }

  test("the language model of the context of the proposer is the reflection LM") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map(first -> improved)))
    assert(withTaskLm(new TaskLm)(optimize(reflection, proposer)).isRight)

    val call = proposer.calls.headOption.getOrElse(fail("the proposer got no call"))
    assert(call.givenLm.exists(_ eq reflection), call.givenLm.toString)
    assert(call.overlayLm.exists(_ eq reflection), call.overlayLm.toString)
  }

  test("the trace of the proposer does not reach the caller, and its history does") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map(first -> improved)))
    withTaskLm(new TaskLm) {
      assert(optimize(reflection, proposer).isRight)
      assert(proposer.calls.nonEmpty)
      val after = RuntimeEnvironment.current
      assert(!after.trace.exists(_.component == "probe"), after.trace.map(_.component).toString)
      assert(after.history.exists(_.component == "probe"), after.history.map(_.component).toString)
      // The language model of the caller is the task LM again.
      assert(!after.lm.exists(_ eq reflection), after.lm.toString)
    }
  }

  test("a proposer that gives no text gives no proposal, and the engine does not call it again") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map.empty))
    val result     = withTaskLm(new TaskLm)(optimize(reflection, proposer)).fold(error => fail(error.message), identity)

    assertEquals(proposer.calls.size, 1)
    assertEquals(result.numCandidates.toInt, 1)
    assertEquals(result.totalMetricCalls.toInt, 6)
  }

  test("a text for a component that the candidate does not hold stops the run with an error") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map(OptimizableId(9) -> "A text for no component.")))
    val result     = withTaskLm(new TaskLm)(optimize(reflection, proposer))
    assert(result.isLeft, result.toString)
  }

  test("Gepa.compile passes the proposer to the engine") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ProbeProposer(Right(Map(first -> improved)))
    val result     = withTaskLm(new TaskLm) {
      new Gepa[DynamicPredict](metric, reflection, oneIteration, instructionProposer = proposer)
        .compile(program, dataset, dataset)
    }.fold(error => fail(error.message), identity)

    assertEquals(proposer.calls.size, 1)
    assertEquals(result.bestCandidate(first), Some(improved))
  }

  // ── An exception of the proposer ────────────────────────────────────────────────────────────────────────────
  // Upstream catches each exception of the reflection, calls the proposer one more time, and continues with no
  // proposal after a second exception (gepa `proposer/reflective_mutation/reflective_mutation.py`,
  // `_propose_texts_batch_safe`).

  /** Throws `error` in the first `failures` calls, and then gives the improved text. Each call first writes one history
    * entry.
    */
  private final class ThrowingProposer(failures: Int, error: () => Throwable) extends InstructionProposer:
    val calls: AtomicInteger = AtomicInteger(0)
    override def propose(
        candidate : Candidate,
        reflective: Map[OptimizableId, Vector[ReflectiveRecord]],
        components: Vector[OptimizableId]
    )(using RuntimeContext): Either[DspyError, Map[OptimizableId, String]] =
      RuntimeEnvironment.appendHistory(HistoryEntry("throwing", DynamicValue.Record.empty))
      if calls.incrementAndGet() <= failures then throw error()
      else Right(Map(first -> improved))

  test("an exception of the proposer is a failure, and the engine calls the proposer one more time") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ThrowingProposer(1, () => IllegalStateException("the proposer failed"))
    val result     = withTaskLm(new TaskLm)(optimize(reflection, proposer)).fold(error => fail(error.message), identity)

    assertEquals(proposer.calls.get, 2)
    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.bestCandidate(first), Some(improved))
    assertEquals(result.state.failedProposals, 0)
  }

  test("after two exceptions of the proposer, the run continues with no proposal") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ThrowingProposer(Int.MaxValue, () => IllegalStateException("the proposer failed"))
    withTaskLm(new TaskLm) {
      val result = optimize(reflection, proposer).fold(error => fail(error.message), identity)

      assertEquals(proposer.calls.get, 2)
      assertEquals(result.numCandidates.toInt, 1)
      // 4 for the seed and 2 for the minibatch of the parent. No child evaluation ran.
      assertEquals(result.totalMetricCalls.toInt, 6)
      assertEquals(result.state.failedProposals, 1)
      // The history of a call that throws goes to the caller, as the history of a call that gives a `Left`.
      assertEquals(RuntimeEnvironment.current.history.count(_.component == "throwing"), 2)
    }
  }

  test("a fatal error of the proposer goes to the caller") {
    val reflection = new ScriptedReflectionLm(reply("not used"))
    val proposer   = new ThrowingProposer(Int.MaxValue, () => InterruptedException("stop"))
    // `intercept` catches only non-fatal errors, so the test catches the fatal error itself.
    val thrown =
      try
        val _ = withTaskLm(new TaskLm)(optimize(reflection, proposer))
        None
      catch case error: InterruptedException => Some(error.getMessage)
    assertEquals(thrown, Some("stop"))
    assertEquals(proposer.calls.get, 1)
  }

  // ── A given context that is not installed ───────────────────────────────────────────────────────────────────
  // Upstream has one ambient context (`dspy.settings`, `dspy.context`) for the evaluations and for the proposer.

  test("the evaluations use the given context also when that context is not installed") {
    val reflection = new ScriptedReflectionLm(reply(improved))
    // The task LM is in the given context only. The installed context has no language model.
    given RuntimeContext = RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))
    val result           = optimize(reflection).fold(error => fail(error.message), identity)

    assertEquals(reflection.requests.size, 1)
    assertEquals(result.bestCandidate(first), Some(improved))
    assertEquals(result.bestScore, 1.0)
    // The run does not change the installed context.
    assertEquals(RuntimeEnvironment.current.lm, None)
  }

  test("Gepa.compile evaluates with the given context also when that context is not installed") {
    val reflection       = new ScriptedReflectionLm(reply("not used"))
    val proposer         = new ProbeProposer(Right(Map(first -> improved)))
    given RuntimeContext = RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))
    val result           = new Gepa[DynamicPredict](metric, reflection, oneIteration, instructionProposer = proposer)
      .compile(program, dataset, dataset)
      .fold(error => fail(error.message), identity)

    assertEquals(result.bestScore, 1.0)
    // The proposer gets the reflection LM in its given context and in the installed context.
    val call = proposer.calls.headOption.getOrElse(fail("the proposer got no call"))
    assert(call.givenLm.exists(_ eq reflection), call.givenLm.toString)
    assert(call.overlayLm.exists(_ eq reflection), call.overlayLm.toString)
  }
