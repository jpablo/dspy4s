package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  :=, CodeResult, DspyError, DynamicValues, LmUsage, ReplCodeInterpreter, RuntimeContext, SandboxTool
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.{ChainOfThought, ReAct, ReActV2, RLM}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger

/** The LM usage of the result of an agent run: the sum of the usage of each model call of the run. Upstream
  * `Module.__call__` puts the total usage of the call on the returned `Prediction` (`dspy/primitives/module.py`,
  * `track_usage` and `_set_lm_usage`), and each LM call of the run adds its usage to the tracker
  * (`dspy/clients/execution.py`, `finalize`). For RLM, this includes the sub-LM calls of `llm_query` and
  * `llm_query_batched` (`dspy/predict/rlm.py`, `_make_llm_tools`).
  */
class ProgramRunUsageSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** The usage of call `n` (from 1) of a model with the factor `scale`. Each call has a different usage, so that a sum
    * shows each call one time.
    */
  private def usageOf(n: Long, scale: Long = 1L): LmUsage =
    LmUsage(totalTokens = 11L * n * scale, promptTokens = n * scale, completionTokens = 10L * n * scale)

  private def sumOf(usages: LmUsage*): LmUsage = usages.foldLeft(LmUsage.empty)(_.combine(_))

  /** A model that gives back `texts` in order, and the usage [[usageOf]] for each call. Past the end it gives an empty
    * text.
    */
  private final class UsageScript(texts: Vector[String], scale: Long = 1L) extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = s"usage-script-$scale"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val n = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(texts.lift(n - 1).getOrElse(""))), usage = Some(usageOf(n, scale))))

  /** The text of a ChatAdapter reply with the fields `entries`. */
  private def chat(entries: (String, String)*): String =
    entries.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  private def run[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private val qa = Signature.fromString("question -> answer")

  private val rlmSignature = Signature.fromString("context, query -> answer")

  private val lookup: ToolFunction = ToolFunction.of("lookup", "Look up a query.")(_ => "found it")

  /** The JSON text of the tool calls of a ReActV2 step. */
  private def toolCalls(entries: (String, String)*): String =
    entries.map((name, args) => s"""{"name": "$name", "args": $args}""").mkString("""{"tool_calls": [""", ", ", "]}")

  // ── ReActV2 ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("ReActV2: the result has the sum of the usage of each step call") {
    val lm = UsageScript(Vector(
      chat("next_thought" -> "look", "tool_calls" -> toolCalls("lookup" -> """{"query": "q"}""")),
      chat("next_thought" -> "done", "tool_calls" -> toolCalls("submit" -> """{"answer": "Paris"}"""))
    ))
    run(lm) {
      val prediction = ReActV2(qa, Vector(lookup))((question = "capital?")).fold(e => fail(e.message), identity)
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(lm.calls.get(), 2)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2))))
    }
  }

  test("ReActV2: the result of a forced submit has the usage of the loop calls and of the forced call") {
    val lm = UsageScript(Vector(
      chat("next_thought" -> "look", "tool_calls" -> toolCalls("lookup" -> """{"query": "q"}""")),
      chat("next_thought" -> "done", "tool_calls" -> toolCalls("submit" -> """{"answer": "Paris"}"""))
    ))
    run(lm) {
      val agent      = ReActV2(qa, Vector(lookup), maxIterations = IterationLimit(1))
      val prediction = agent((question = "capital?")).fold(e => fail(e.message), identity)
      assertEquals(ReActV2.terminationReason(prediction), Some(ReActV2.TerminationReason.ForcedSubmit))
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2))))
    }
  }

  // ── ReAct ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("ReAct: the result has the sum of the usage of each react call and of the extractor call") {
    val lm = UsageScript(Vector(
      chat("next_thought" -> "look", "next_tool_name" -> "lookup", "next_tool_args" -> """{"query": "q"}"""),
      chat("next_thought" -> "done", "next_tool_name" -> "finish", "next_tool_args" -> "{}"),
      chat("reasoning"    -> "it is Paris", "answer"  -> "Paris")
    ))
    run(lm) {
      val prediction = ReAct(qa, Vector(lookup))((question = "capital?")).fold(e => fail(e.message), identity)
      assertEquals(prediction.output.answer, "Paris")
      assertEquals(lm.calls.get(), 3)
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2), usageOf(3))))
    }
  }

  test("ChainOfThought: the result has the usage of its one call") {
    val lm = UsageScript(Vector(chat("reasoning" -> "it is Paris", "answer" -> "Paris")))
    run(lm) {
      val prediction = ChainOfThought(qa)((question = "capital?")).fold(e => fail(e.message), identity)
      assertEquals(prediction.raw.lmUsage, Some(usageOf(1)))
    }
  }

  // ── RLM ─────────────────────────────────────────────────────────────────────────────────────────────────────

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

  test("RLM: a SUBMIT result has the usage of each action call and of each sub-LM call") {
    val lm = UsageScript(Vector(
      chat("reasoning" -> "ask the sub-LM", "code" -> "query"),
      chat("reasoning" -> "done", "code"           -> "submit")
    ))
    val subLm = UsageScript(Vector.empty, scale = 100L)
    run(lm) {
      val rlm = RLM(
        baseSignature = rlmSignature,
        subLm = Some(subLm),
        interpreterFactory = Some((tools, _) => ToolRepl(tools))
      )
      val prediction = rlm((context = "c", query = "q")).fold(e => fail(e.message), identity)
      assertEquals(prediction.output.answer, "42")
      assertEquals(subLm.calls.get(), 3)
      assertEquals(
        prediction.raw.lmUsage,
        Some(sumOf(usageOf(1), usageOf(2), usageOf(1, 100L), usageOf(2, 100L), usageOf(3, 100L)))
      )
    }
  }

  test("RLM: an extract result has the usage of each action call and of the extract call") {
    val lm = UsageScript(Vector(
      chat("reasoning" -> "look", "code" -> "print(1)"),
      chat("answer"    -> "42")
    ))
    run(lm) {
      val rlm = RLM(
        baseSignature = rlmSignature,
        maxIterations = IterationLimit(1),
        interpreterFactory = Some((tools, _) => ToolRepl(tools))
      )
      val prediction = rlm((context = "c", query = "q")).fold(e => fail(e.message), identity)
      assertEquals(prediction.output.answer, "42")
      assertEquals(prediction.raw.lmUsage, Some(sumOf(usageOf(1), usageOf(2))))
    }
  }
