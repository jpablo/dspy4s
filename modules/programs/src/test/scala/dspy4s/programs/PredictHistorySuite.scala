package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.History
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ToolCallResult
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.MessageRole
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import scala.collection.mutable.ArrayBuffer

final case class ChatTurn(question: String, history: History) derives Schema
final case class ChatReply(answer: String) derives Schema
final case class AgentReply(toolCalls: Vector[ToolCall]) derives Schema

/** `Predict` with a conversation history, from the typed input to the messages of the request (PORT_GAPS G-28). */
class PredictHistorySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Records each request and replays scripted outputs. */
  private final class RecordingLm(outputs: Vector[LmOutput]) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "recording"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val output = outputs(math.min(requests.size, outputs.size - 1))
      requests += request
      Right(LmResponse(outputs = Vector(output)))

  private val tools: Vector[ToolSpec] = Vector(
    ToolSpec(
      "search",
      Some("Search the web"),
      Vector(ToolParameterSpec("query", TypeRef.string, None, required = true))
    )
  )

  private val search: ToolCall =
    ToolCall(name = "search", args = DynamicValues.record("query" := "cats"), id = Some("call_1"))

  test("Predict replays a typed History input as earlier turns of the request") {
    val lm      = new RecordingLm(Vector(LmOutput(text = "[[ ## answer ## ]]\n6\n\n[[ ## completed ## ]]")))
    val predict = Predict(signature = Signature.derived[ChatTurn, ChatReply]("QA"))
    val history = History.empty :+
      DynamicValues.record("question" := "What is 1+1?", "answer" := "2") :+
      DynamicValues.record("question" := "What is 2+2?", "answer" := "4")

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(predict(ChatTurn("What is 3+3?", history)).map(_.output), Right(ChatReply("6")))
    }

    val messages = lm.requests.head.messages
    import MessageRole.{Assistant, System, User}
    assertEquals(messages.map(_.role), Vector(System, User, Assistant, User, Assistant, User))
    assert(messages(1).text.exists(_.contains("What is 1+1?")), messages(1).toString)
    assert(messages(2).text.exists(_.contains("2")), messages(2).toString)
    assert(messages(4).text.exists(_.contains("4")), messages(4).toString)
    val current = messages.last.text.getOrElse("")
    assert(current.contains("What is 3+3?"), current)
    assert(!current.contains("history") && !current.contains("What is 1+1?"), current)
  }

  test("a native tool call keeps its provider id in the typed output and in the raw prediction") {
    val lm      = new RecordingLm(Vector(LmOutput(text = "", toolCalls = Vector(search))))
    val predict = Predict(signature = Signature.derived[ChatTurn, AgentReply]("Agent"), tools = tools)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter(useNativeFunctionCalling = true)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val prediction       = predict(ChatTurn("Find cats.", History.empty))
      assertEquals(prediction.map(_.output.toolCalls), Right(Vector(search)))
      assertEquals(
        prediction.toOption.flatMap(p => DynamicValues.recordGet(p.raw.values, "tool_calls")).flatMap(
          ToolCall.fromDynamic
        ),
        Some(Vector(search))
      )
    }
  }

  test("a tool call and its result from one call go back to the model in the next call") {
    val lm = new RecordingLm(Vector(
      LmOutput(text = "", toolCalls = Vector(search)),
      LmOutput(text = "", toolCalls = Vector(search.copy(id = Some("call_2"))))
    ))
    val predict = Predict(signature = Signature.derived[ChatTurn, AgentReply]("Agent"), tools = tools)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter(useNativeFunctionCalling = true)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val first            = predict(ChatTurn("Find cats.", History.empty)).toOption.get
      val results          =
        ToolCallResult.forCalls(first.output.toolCalls, Vector(DynamicValues.fromAny("3 cats"))).toOption.get
      // One history message: the request of the first call, its tool calls, and the results of those calls.
      val message = History.withToolCallResults(
        DynamicValues.record(
          "question"  := "Find cats.",
          "toolCalls" -> DynamicValue.Sequence(Chunk.from(first.output.toolCalls.map(_.toRecord)))
        ),
        results
      )
      assert(predict(ChatTurn("How many?", History.empty :+ message)).isRight)
    }

    val messages = lm.requests(1).messages
    import MessageRole.{Assistant, System, Tool, User}
    assertEquals(messages.map(_.role), Vector(System, User, Assistant, Tool, User))
    assertEquals(messages(2).toolCalls, Vector(search))
    assertEquals(
      (messages(3).toolCallId, messages(3).name, messages(3).text),
      (Some("call_1"), Some("search"), Some("3 cats"))
    )
    assert(messages(4).text.exists(_.contains("How many?")), messages(4).toString)
  }
