package dspy4s.streaming

import dspy4s.adapters.{ChatAdapter, TwoStepAdapter}
import dspy4s.core.contracts.{
  CodeResult,
  DspyError,
  DynamicValues,
  ReplCodeInterpreter,
  RuntimeContext,
  RuntimeError,
  SandboxTool,
  :=
}
import dspy4s.core.runtime.{LocalPythonInterpreter, RuntimeEnvironment}
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LmChunk, LmMode, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.programs.strategies.{DynamicPredict, RLM}
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StreamEvent, StreamListener, TokenEvent}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.util.control.NonFatal

private final case class SubModelInput(question: String) derives Schema
private final case class SubModelOutput(answer: String) derives Schema

/** The models that a predictor does not pick: the extraction model of `TwoStepAdapter`, and the sub-LM of `RLM`. The
  * sub-LM tools read the stream from the context that they keep, so the thread that runs a tool does not matter.
  */
class StreamingSubModelSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Model(override val id: String, replies: String*) extends StreamingLanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "this model must stream"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val index = calls.getAndIncrement()
      Iterator(LmChunk(text = replies(math.min(index, replies.size - 1)), finishReason = Some("stop")))

  private def collect(stream: Iterator[StreamEvent]): Vector[StreamEvent] = stream.toVector

  private def tokens(events: Vector[StreamEvent]): Vector[TokenEvent] = events.collect { case e: TokenEvent => e }

  private val answerLayout = SignatureDsl.parse("question -> answer").toOption.get

  test("the extraction model of TwoStepAdapter streams") {
    val main             = Model("main", "free text: paris")
    val extraction       = Model("extraction", "[[ ## answer ## ]]\nparis\n\n[[ ## completed ## ]]")
    given RuntimeContext = RuntimeContext(lm = Some(main), adapter = Some(TwoStepAdapter(extraction)))
    val program          = DynamicPredict(layout = answerLayout, name = Some("two"))
    val events           = collect(Streamify.streamify(program, warningSink = _ => ()).apply(rec("question" := "q")))
    assertEquals(main.calls.get(), 1, events.toString)
    assertEquals(extraction.calls.get(), 1, events.toString)
    val text = tokens(events).map(_.chunk).mkString
    assert(text.contains("free text"), events.toString)
    assert(text.contains("[[ ## answer ## ]]"), events.toString)
    assertEquals(tokens(events).map(_.predictName).toSet, Set("two"), events.toString)
    assert(events.exists(_.isInstanceOf[PredictionEvent]), events.toString)
  }

  /** A REPL whose code `query` calls the tools. Each tool call runs on the given runner. */
  private final class ToolRepl(tools: Vector[SandboxTool], runner: (() => Unit) => Unit) extends ReplCodeInterpreter:
    private def tool(name: String): SandboxTool = tools.find(_.name == name).getOrElse(fail(s"no tool $name"))

    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      code.trim match
        case "query" =>
          val prompts = DynamicValue.Sequence(Chunk(DynamicValues.fromAny("a"), DynamicValues.fromAny("b")))
          val result  = AtomicReference[Either[DspyError, CodeResult]]()
          runner { () =>
            result.set(
              for
                one  <- tool("llm_query").invoke(rec("prompt" := "p"))
                many <- tool("llm_query_batched").invoke(DynamicValues.recordFromEntries(Vector("prompts" -> prompts)))
              yield CodeResult(s"${DynamicValues.renderText(one)} ${DynamicValues.renderText(many)}\n", "", 0)
            )
          }
          result.get()
        case "submit" => Right(CodeResult("", "", 0, finalOutput = Some("""{"answer": "42"}""")))
        case _        => Right(CodeResult("", "", 0))

    override def close(): Unit = ()

  private def reply(fields: (String, String)*): String =
    fields.map((name, value) => s"[[ ## $name ## ]]\n$value\n\n").mkString + "[[ ## completed ## ]]"

  private def script =
    Vector(
      reply("reasoning" -> "ask", "code"  -> "query"),
      reply("reasoning" -> "done", "code" -> "submit")
    )

  private def rlmEvents(
      runner   : (() => Unit) => Unit,
      listeners: Vector[StreamListener] = Vector.empty
  ): (Vector[StreamEvent], Model) =
    val action           = Model("action", script*)
    val sub              = Model("sub", "sub text")
    given RuntimeContext = RuntimeContext(lm = Some(action), adapter = Some(ChatAdapter()))
    val program          = RLM(
      baseSignature = Signature.derived[SubModelInput, SubModelOutput]("SubModel"),
      subLm = Some(sub),
      interpreterFactory = Some((tools, _) => ToolRepl(tools, runner))
    )
    val stream = Streamify.streamify(program, streamListeners = listeners, warningSink = _ => ())
    (collect(stream(rec("question" := "q"))), sub)

  test("the sub-LM of RLM streams, on the thread of execute") {
    val (events, sub) = rlmEvents(run => run())
    assertEquals(sub.calls.get(), 3, events.toString)
    val subTokens = tokens(events).filter(_.chunk == "sub text")
    assertEquals(subTokens.size, 3, events.toString)
    assertEquals(subTokens.map(_.predictName).toSet, Set(""), events.toString)
    assert(events.exists(_.isInstanceOf[PredictionEvent]), events.toString)
  }

  test("the sub-LM of RLM streams, on a bare thread with no installed context") {
    val seenSink      = AtomicReference[Option[Any]]()
    val (events, sub) = rlmEvents { run =>
      val thread = new Thread(() =>
        seenSink.set(RuntimeEnvironment.current.streamSink)
        run()
      )
      thread.start()
      thread.join()
    }
    assertEquals(seenSink.get(), None, "the bare thread has no installed sink")
    assertEquals(sub.calls.get(), 3, events.toString)
    assertEquals(tokens(events).count(_.chunk == "sub text"), 3, events.toString)
  }

  test("with a listener, the tokens of the sub-LM do not reach the listener") {
    val (events, sub) = rlmEvents(run => run(), Vector(StreamListener("reasoning", allowReuse = true)))
    assertEquals(sub.calls.get(), 3, events.toString)
    assertEquals(tokens(events).count(_.chunk == "sub text"), 0, events.toString)
    assertEquals(tokens(events).map(_.predictName).toSet, Set("rlm_action"), events.toString)
  }

  private lazy val pythonAvailable: Boolean =
    try
      val process = new ProcessBuilder("python3", "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)")
        .start()
      process.waitFor() == 0
    catch case NonFatal(_) => false

  test("the sub-LM of RLM streams with the local Python interpreter (a real tool thread)") {
    assume(pythonAvailable, "python3 3.10+ not installed")
    val action = Model(
      "action",
      reply("reasoning" -> "ask", "code"  -> "print(llm_query('p'))\nprint(llm_query_batched(['a', 'b']))"),
      reply("reasoning" -> "done", "code" -> "SUBMIT(answer='42')")
    )
    val sub              = Model("sub", "sub text")
    given RuntimeContext = RuntimeContext(lm = Some(action), adapter = Some(ChatAdapter()))
    val program          = RLM(
      baseSignature = Signature.derived[SubModelInput, SubModelOutput]("SubModel"),
      subLm = Some(sub),
      interpreterFactory = Some(LocalPythonInterpreter.factory())
    )
    val events = collect(Streamify.streamify(program, warningSink = _ => ()).apply(rec("question" := "q")))
    val errors = events.collect { case ErrorEvent(error, _) => error.message }
    assertEquals(errors, Vector.empty, events.toString)
    assertEquals(sub.calls.get(), 3, events.toString)
    assertEquals(tokens(events).count(_.chunk == "sub text"), 3, events.toString)
  }
