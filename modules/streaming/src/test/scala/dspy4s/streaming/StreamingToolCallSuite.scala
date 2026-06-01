package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class StreamingToolCallSuite extends FunSuite:

  private final class ScriptedStreamingLm(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = chunks.map(_.text).mkString))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      chunks.iterator

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  test("wrapper accumulates tool-call deltas across chunks into LmResponse") {
    val chunks = Vector(
      LmChunk(toolCalls = Vector(LmToolCallDelta(0, id = Some("call_1"), name = Some("get_weather"), argumentsFragment = Some("")))),
      LmChunk(toolCalls = Vector(LmToolCallDelta(0, argumentsFragment = Some("""{"city":"""")))),
      LmChunk(toolCalls = Vector(LmToolCallDelta(0, argumentsFragment = Some("""Paris"}""")))),
      LmChunk(text = "", finishReason = Some("tool_calls"))
    )
    val queue = StreamingQueue[StreamEvent]()
    val wrapper = StreamingLanguageModelWrapper(new ScriptedStreamingLm(chunks), queue)

    given RuntimeContext = RuntimeEnvironment.current
    val response = wrapper.call(LmRequest(model = "m")).toOption.get
    queue.close()

    val output = response.outputs.head
    assertEquals(output.text, "")
    assertEquals(output.toolCalls.size, 1)
    assertEquals(output.toolCalls.head.name, "get_weather")
    assertEquals(DynamicValues.recordToMap(output.toolCalls.head.args), Map[String, Any]("city" -> "Paris"))
  }

  test("wrapper does not emit TokenEvents for tool-call-only chunks") {
    val chunks = Vector(
      LmChunk(toolCalls = Vector(LmToolCallDelta(0, name = Some("ping"), argumentsFragment = Some("{}")))),
      LmChunk(text = "", finishReason = Some("tool_calls"))
    )
    val queue = StreamingQueue[StreamEvent]()
    val wrapper = StreamingLanguageModelWrapper(new ScriptedStreamingLm(chunks), queue)

    given RuntimeContext = RuntimeEnvironment.current
    val _ = wrapper.call(LmRequest(model = "m"))
    queue.close()

    val drained = ArrayBuffer.empty[StreamEvent]
    val iter = queue.asIterator
    while iter.hasNext do drained += iter.next()
    assertEquals(drained.collect { case e: TokenEvent => e }.size, 0)
  }

  test("wrapper assembles multiple parallel tool calls by index") {
    val chunks = Vector(
      LmChunk(toolCalls = Vector(
        LmToolCallDelta(0, id = Some("call_a"), name = Some("alpha"), argumentsFragment = Some("""{"x":1}""")),
        LmToolCallDelta(1, id = Some("call_b"), name = Some("beta"), argumentsFragment = Some(""))
      )),
      LmChunk(toolCalls = Vector(LmToolCallDelta(1, argumentsFragment = Some("""{"y":2}""")))),
      LmChunk(finishReason = Some("tool_calls"))
    )
    val queue = StreamingQueue[StreamEvent]()
    val wrapper = StreamingLanguageModelWrapper(new ScriptedStreamingLm(chunks), queue)

    given RuntimeContext = RuntimeEnvironment.current
    val response = wrapper.call(LmRequest(model = "m")).toOption.get
    queue.close()

    val calls = response.outputs.head.toolCalls
    assertEquals(calls.size, 2)
    assertEquals(calls.map(_.name).toSet, Set("alpha", "beta"))
    val byName = calls.map(c => c.name -> DynamicValues.recordToMap(c.args)).toMap
    assertEquals(byName("alpha"), Map[String, Any]("x" -> 1L))
    assertEquals(byName("beta"), Map[String, Any]("y" -> 2L))
  }
