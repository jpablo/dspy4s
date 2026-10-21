package dspy4s.programs

import dspy4s.adapters.{ChatAdapter, TwoStepAdapter}
import dspy4s.core.contracts.{
  :=, CodeResult, DspyError, DynamicValues, LmServerError, LmUsage, ReplCodeInterpreter, RuntimeContext, SandboxTool,
  SignatureLayout, ThreadCount
}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.{InMemoryLmCache, ManagedLanguageModel, RetryCount, RetryPolicies, UsageTracking}
import dspy4s.programs.contracts.{DynamicModule, Module, ModuleLifecycle, Prediction, ProgramCall, ToolFunction}
import dspy4s.programs.strategies.{BestOfN, ChainOfThought, DynamicPredict, Predict, ReAct, ReActV2, Refine, RLM}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** The LM usage of a program result (decision D9 of UPGRADE-3.2.1-to-3.4.0): the total of each model call that the call
  * made. Upstream `Module.__call__` opens a usage tracker for the call and puts its total on the result
  * (`dspy/primitives/module.py`, `track_usage` and `_set_lm_usage`), and each LM call adds its usage to the tracker
  * (`dspy/clients/execution.py`, `finalize`):
  *   - a model call whose reply then fails to parse counts;
  *   - a model call inside a tool counts;
  *   - each attempt of `BestOfN` and `Refine` counts;
  *   - a cache hit does not count (`finalize`: `if not result.cache_hit`);
  *   - a call that fails at the provider has no usage.
  *
  * The port keeps one total on each result, also on a nested result, and the outer total includes the nested calls one
  * time.
  */
class ProgramCallUsageSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** The usage of call `n` (from 1) of a model with the factor `scale`. Each call has a different usage, so that a sum
    * shows each call one time.
    */
  private def usageOf(n: Long, scale: Long = 1L): LmUsage =
    LmUsage(totalTokens = 11L * n * scale, promptTokens = n * scale, completionTokens = 10L * n * scale)

  private def sumOf(usages: LmUsage*): LmUsage = usages.foldLeft(LmUsage.empty)(_.combine(_))

  /** A model with no managed wrapper. It gives back `texts` in order, and the usage [[usageOf]] for each call. Past the
    * end it gives the last text. A call with a number in `failures` fails at the provider: it gives a `Left` with no
    * reply.
    */
  private final class Script(texts: Vector[String], scale: Long = 1L, failures: Set[Int] = Set.empty)
      extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = s"script-$scale"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val n = calls.incrementAndGet()
      if failures.contains(n) then Left(LmServerError("the provider is down"))
      else
        val text = texts.lift(n - 1).orElse(texts.lastOption).getOrElse("")
        Right(LmResponse(outputs = Vector(LmOutput(text)), usage = Some(usageOf(n.toLong, scale))))

  /** The text of a ChatAdapter reply with the fields `entries`. */
  private def chat(entries: (String, String)*): String =
    entries.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  /** A reply that neither the chat parser nor the JSON parser can read. */
  private val unparseable = "I think that the answer is Paris."

  private def run[A](lm: LanguageModel, trackUsage: Option[Boolean] = None)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()), trackUsage = trackUsage)
    ) {
      body(using RuntimeEnvironment.current)
    }

  private def success[A](result: Either[DspyError, A]): A = result.fold(error => fail(error.message), identity)

  private val qa = Signature.fromString("question -> answer")

  /** Two outputs, so that the chat parser has no plain-text rule for one output (decision D10). */
  private val qaNote = Signature.fromString("question -> answer, note")

  private val rlmSignature = Signature.fromString("context, query -> answer")

  /** The JSON text of the tool calls of a ReActV2 step. */
  private def toolCalls(entries: (String, String)*): String =
    entries.map((name, args) => s"""{"name": "$name", "args": $args}""").mkString("""{"tool_calls": [""", ", ", "]}")

  /** A program of two programs: it runs `first`, then `second`, and gives the result of `second`. It keeps the usage of
    * the two nested results.
    */
  private final class TwoPrograms[I, O](first: Module[I, O], second: Module[I, O]) extends Module[I, O]:
    val nested: AtomicReference[Vector[Option[LmUsage]]]                                                         = AtomicReference(Vector.empty)
    override val moduleName: String                                                                              = "both"
    override protected val lifecycle: ModuleLifecycle[I, O]                                                      = ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      for
        one <- first(call)
        two <- second(call)
      yield
        nested.set(Vector(one.raw.lmUsage, two.raw.lmUsage))
        two

  /** A program that runs `inner` on `count` inputs with [[Parallel]], on worker threads. Its result has no usage of its
    * own. It keeps the usage of each nested result.
    */
  private final class FanOut(inner: DynamicPredict, count: Int) extends DynamicModule:
    val nested: AtomicReference[Vector[Option[LmUsage]]] = AtomicReference(Vector.empty)
    override val moduleName: String                      = "fan_out"
    override protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      val tasks = (1 to count).toVector.map(i => inner -> ProgramCall(DynamicValues.record("question" := s"q$i")))
      Parallel(numThreads = Some(ThreadCount(4))).run(tasks).map { result =>
        nested.set(result.results.map(_.flatMap(_.lmUsage)))
        RawPrediction(values = DynamicValues.record("count" := result.results.flatten.size))
      }

  /** A REPL that runs a small script language in place of Python: `query` calls `llm_query` one time and
    * `llm_query_batched` with two prompts, `submit` gives the SUBMIT output `{"answer": "42"}`, and each other code
    * prints nothing.
    */
  private final class ToolRepl(tools: Vector[SandboxTool]) extends ReplCodeInterpreter:
    private def tool(name: String): SandboxTool = tools.find(_.name == name).getOrElse(fail(s"no tool $name"))

    override def execute(code: String): Either[DspyError, CodeResult] = execute(code, Map.empty)

    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      code.trim match
        case "query" =>
          val prompts = DynamicValue.Sequence(Chunk(DynamicValues.fromAny("a"), DynamicValues.fromAny("b")))
          for
            one  <- tool("llm_query").invoke(rec("prompt" := "p"))
            many <- tool("llm_query_batched").invoke(DynamicValues.recordFromEntries(Vector("prompts" -> prompts)))
          yield CodeResult(s"${DynamicValues.renderText(one)} ${DynamicValues.renderText(many)}\n", "", 0)
        case "submit" => Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "42"}""")))
        case _        => Right(CodeResult("", "", 0))

    override def close(): Unit = ()

  // ── Predict ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("Predict: the chat call before the JSON fallback counts") {
    val lm = Script(Vector(unparseable, """{"answer": "Paris", "note": "n"}"""))
    run(lm) {
      val prediction = success(Predict(qaNote)((question = "capital?")))
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(lm.calls.get(), 2)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2))))
    }
  }

  test("Predict: the extraction call of TwoStepAdapter counts") {
    val main      = Script(Vector("The capital is Paris."))
    val extractor = Script(Vector(chat("answer" -> "Paris")), scale = 100L)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(main), adapter = Some(TwoStepAdapter(extractor)))) {
      given RuntimeContext = RuntimeEnvironment.current
      val prediction       = success(Predict(qa)((question = "capital?")))
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(extractor.calls.get(), 1)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(1, 100L))))
    }
  }

  test("Predict: the result total equals the total of a user tracker, and trackUsage keeps its rule for the tracker") {
    val replies = Vector(unparseable, """{"answer": "Paris", "note": "n"}""")
    val total   = sumOf(usageOf(1), usageOf(2))
    val tracked = Script(replies)
    val managed = ManagedLanguageModel(tracked)
    run(managed) {
      val (prediction, usage) = UsageTracking.withNewTracker { tracker =>
        (success(Predict(qaNote)((question = "capital?"))), tracker.totalUsage)
      }
      assertEquals(usage, Map(tracked.id -> total))
      assertEquals(prediction.raw.lmUsage, Some(total))
    }
    // With `trackUsage = false`, the user tracker gets nothing, but the result still has its total (decision D9:
    // one usage on each result, always).
    val untracked = Script(replies)
    run(ManagedLanguageModel(untracked), trackUsage = Some(false)) {
      val (prediction, usage) = UsageTracking.withNewTracker { tracker =>
        (success(Predict(qaNote)((question = "capital?"))), tracker.totalUsage)
      }
      assertEquals(usage, Map.empty[String, LmUsage])
      assertEquals(prediction.raw.lmUsage, Some(total))
    }
  }

  test("Predict: an attempt that fails at the provider has no usage, and the retry that succeeds counts") {
    val lm      = Script(Vector(chat("answer" -> "Paris")), failures = Set(1))
    val managed = ManagedLanguageModel(lm, retryPolicy = RetryPolicies.maxRetries(RetryCount(1)))
    run(managed) {
      val (prediction, usage) = UsageTracking.withNewTracker { tracker =>
        (success(Predict(qa)((question = "capital?"))), tracker.totalUsage)
      }
      assertEquals(lm.calls.get(), 2)
      assertEquals(prediction.raw.lmUsage, Some(usageOf(2)))
      assertEquals(usage, Map(lm.id -> usageOf(2)))
    }
  }

  // ── ChainOfThought ──────────────────────────────────────────────────────────────────────────────────────────

  test("ChainOfThought: the chat call before the JSON fallback counts") {
    val lm = Script(Vector(unparseable, """{"reasoning": "it is Paris", "answer": "Paris"}"""))
    run(lm) {
      val prediction = success(ChainOfThought(qa)((question = "capital?")))
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2))))
    }
  }

  // ── ReAct ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("ReAct: the model call of a program inside a tool counts") {
    val lm = Script(Vector(
      chat("next_thought" -> "look", "next_tool_name" -> "lookup", "next_tool_args" -> """{"query": "q"}"""),
      chat("next_thought" -> "done", "next_tool_name" -> "finish", "next_tool_args" -> "{}"),
      chat("reasoning"    -> "it is Paris", "answer"  -> "Paris")
    ))
    val toolLm = Script(Vector(chat("result" -> "found it")), scale = 100L)
    val inner  = Predict(Signature.fromString("query -> result"), lm = Some(toolLm))
    val lookup = ToolFunction("lookup", "Look up a query.") { _ =>
      inner((query = "q")).map(prediction => ToolFunction.result(prediction.output.result))
    }
    run(lm) {
      val prediction = success(ReAct(qa, Vector(lookup))((question = "capital?")))
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(toolLm.calls.get(), 1)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2), usageOf(3), usageOf(1, 100L))))
    }
  }

  // ── ReActV2 ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("ReActV2: the calls of a step that fails to parse count, with the forced submit") {
    val lm = Script(Vector(
      unparseable,
      unparseable,
      chat("next_thought" -> "done", "tool_calls" -> toolCalls("submit" -> """{"answer": "Paris"}"""))
    ))
    run(lm) {
      val prediction = success(ReActV2(qa, Vector.empty)((question = "capital?")))
      assertEquals(ReActV2.terminationReason(prediction), Some(ReActV2.TerminationReason.ForcedSubmit))
      assertEquals(lm.calls.get(), 3)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2), usageOf(3))))
    }
  }

  // ── RLM ─────────────────────────────────────────────────────────────────────────────────────────────────────

  test("RLM: the sub-LM calls and the action call before the JSON fallback count") {
    val lm = Script(Vector(
      unparseable,
      """{"reasoning": "ask the sub-LM", "code": "query"}""",
      chat("reasoning" -> "done", "code" -> "submit")
    ))
    val subLm = Script(Vector("sub"), scale = 100L)
    run(lm) {
      val rlm = RLM(
        baseSignature = rlmSignature,
        subLm = Some(subLm),
        interpreterFactory = Some((tools, _) => ToolRepl(tools))
      )
      val prediction = success(rlm((context = "c", query = "q")))
      assertEquals(prediction.output.answer, "42")
      assertEquals(subLm.calls.get(), 3)
      assertEquals(
        prediction.raw.lmUsage,
        Some(sumOf(usageOf(1), usageOf(2), usageOf(3), usageOf(1, 100L), usageOf(2, 100L), usageOf(3, 100L)))
      )
    }
  }

  // ── BestOfN and Refine ──────────────────────────────────────────────────────────────────────────────────────

  test("BestOfN: each attempt counts, not only the selected attempt") {
    val lm = Script(Vector(chat("answer" -> "a"), chat("answer" -> "b"), chat("answer" -> "c")))
    run(lm) {
      val best = BestOfN(
        module = Predict(qa),
        n = AttemptCount(3),
        rewardFn = (_, prediction) => if prediction.output.answer == "b" then 0.5 else 0.0,
        threshold = 1.0
      )
      val prediction = success(best((question = "capital?")))
      assertEquals(prediction.output.answer, "b")
      assertEquals(lm.calls.get(), 3)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2), usageOf(3))))
    }
  }

  test("BestOfN: an attempt that fails at the provider adds no usage") {
    val lm = Script(Vector(chat("answer" -> "a"), "", chat("answer" -> "c")), failures = Set(2))
    run(lm) {
      val best = BestOfN(
        module = Predict(qa),
        n = AttemptCount(3),
        rewardFn = (_, _) => 0.0,
        threshold = 1.0
      )
      val prediction = success(best((question = "capital?")))
      assertEquals(lm.calls.get(), 3)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(3))))
    }
  }

  test("Refine: each attempt and each feedback call count, not only the selected attempt") {
    val lm = Script(Vector(
      chat("answer"     -> "a"),
      chat("discussion" -> "too short", "advice" -> "Give the full name."),
      chat("answer"     -> "b")
    ))
    run(lm) {
      val refine = Refine(
        module = Predict(qa),
        n = AttemptCount(2),
        rewardFn = (_, _) => 0.0,
        threshold = 1.0
      )
      val prediction = success(refine((question = "capital?")))
      assertEquals(prediction.output.answer, "a")
      assertEquals(lm.calls.get(), 3)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2), usageOf(3))))
    }
  }

  // ── Composites ──────────────────────────────────────────────────────────────────────────────────────────────

  test("a composite of two programs: each nested result has its own total, and the outer total has both") {
    val firstLm  = Script(Vector(chat("answer" -> "a")))
    val secondLm = Script(Vector(chat("answer" -> "b")), scale = 100L)
    val both     = TwoPrograms(Predict(qa, lm = Some(firstLm)), Predict(qa, lm = Some(secondLm)))
    run(firstLm) {
      val prediction = success(both((question = "capital?")))
      assertEquals(both.nested.get(), Vector(Some(usageOf(1)), Some(usageOf(1, 100L))))
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(1, 100L))))
    }
  }

  test("a cache hit gives no usage") {
    val lm      = Script(Vector(chat("answer" -> "Paris")))
    val managed = ManagedLanguageModel(lm, cache = Some(InMemoryLmCache()))
    run(managed) {
      val predict = Predict(qa)
      val first   = success(predict((question = "capital?")))
      val second  = success(predict((question = "capital?")))
      assertEquals(lm.calls.get(), 1)
      assertEquals(first.raw.lmUsage, Some(usageOf(1)))
      assertEquals(second.raw.lmUsage, None)
    }
  }

  test("a composite with a cache miss and a cache hit has the usage of the miss only") {
    val lm      = Script(Vector(chat("answer" -> "Paris")))
    val managed = ManagedLanguageModel(lm, cache = Some(InMemoryLmCache()))
    run(managed) {
      val predict    = Predict(qa)
      val both       = TwoPrograms(predict, predict)
      val prediction = success(both((question = "capital?")))
      assertEquals(lm.calls.get(), 1)
      assertEquals(both.nested.get(), Vector(Some(usageOf(1)), None))
      assertEquals(prediction.raw.lmUsage, Some(usageOf(1)))
    }
  }

  test("a parallel run: the usage of the calls on the worker threads reaches the scope of the call") {
    val count   = 16
    val lm      = Script(Vector(chat("answer" -> "x")))
    val inner   = DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get)
    val program = FanOut(inner, count)
    run(lm) {
      val prediction = success(program(ProgramCall(DynamicValues.record("question" := "all"))))
      val expected   = sumOf((1 to count).map(n => usageOf(n.toLong))*)
      assertEquals(lm.calls.get(), count)
      assertEquals(program.nested.get().flatten.foldLeft(LmUsage.empty)(_.combine(_)), expected)
      assertEquals(prediction.raw.lmUsage, Some(expected))
    }
  }
