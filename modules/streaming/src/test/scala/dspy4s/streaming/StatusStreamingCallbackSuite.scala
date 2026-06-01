package dspy4s.streaming

import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.streaming.contracts.StatusEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import zio.blocks.schema.DynamicValue
import munit.FunSuite

class StatusStreamingCallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private def drain(queue: StreamingQueue[StreamEvent]): Vector[StreamEvent] =
    queue.close()
    val iter = queue.asIterator
    val buf = scala.collection.mutable.ArrayBuffer.empty[StreamEvent]
    while iter.hasNext do buf += iter.next()
    buf.toVector

  test("status callback emits tool start and end messages") {
    val queue = StreamingQueue[StreamEvent](16)
    val callback = new StatusStreamingCallback(StatusMessageProvider.default, queue)

    given RuntimeContext = RuntimeEnvironment.current
    callback.onEvent(ToolStartEvent(toolName = "search", args = DynamicValues.recordFromEntries(Seq("q" := "hi"))))
    callback.onEvent(ToolEndEvent(toolName = "search", output = Right(DynamicValues.fromAny("Brussels"))))

    val events = drain(queue)
    assertEquals(events.size, 2)
    assertEquals(events(0).asInstanceOf[StatusEvent].message, "Calling tool search...")
    assertEquals(
      events(1).asInstanceOf[StatusEvent].message,
      "Tool calling finished! Querying the LLM with tool calling results..."
    )
  }

  test("status callback skips tool named finish") {
    val queue = StreamingQueue[StreamEvent](16)
    val callback = new StatusStreamingCallback(StatusMessageProvider.default, queue)

    given RuntimeContext = RuntimeEnvironment.current
    callback.onEvent(ToolStartEvent(toolName = "finish", args = DynamicValue.Record.empty))

    assertEquals(drain(queue).size, 0)
  }

  test("status callback skips completed tool outputs") {
    val queue = StreamingQueue[StreamEvent](16)
    val callback = new StatusStreamingCallback(StatusMessageProvider.default, queue)

    given RuntimeContext = RuntimeEnvironment.current
    callback.onEvent(ToolEndEvent(toolName = "anything", output = Right(DynamicValues.fromAny("Completed."))))

    assertEquals(drain(queue).size, 0)
  }

  test("default provider emits no message for module/lm events") {
    val queue = StreamingQueue[StreamEvent](16)
    val callback = new StatusStreamingCallback(StatusMessageProvider.default, queue)

    given RuntimeContext = RuntimeEnvironment.current
    callback.onEvent(ModuleStartEvent("predict", DynamicValues.record("q" := "x")))
    callback.onEvent(ModuleEndEvent("predict", Right("y")))
    callback.onEvent(LmStartEvent("model-id", DynamicValue.Record.empty))
    callback.onEvent(LmEndEvent("model-id", Right("response")))

    assertEquals(drain(queue).size, 0)
  }

  test("custom provider emits messages for module and lm events") {
    val provider = new StatusMessageProvider:
      override def moduleStart(instanceName: String, inputs: DynamicValue.Record): Option[String] =
        Some(s"Module $instanceName starting")
      override def lmStart(modelId: String, inputs: DynamicValue.Record): Option[String] =
        Some(s"Calling $modelId")

    val queue = StreamingQueue[StreamEvent](16)
    val callback = new StatusStreamingCallback(provider, queue)

    given RuntimeContext = RuntimeEnvironment.current
    callback.onEvent(ModuleStartEvent("predict", DynamicValue.Record.empty))
    callback.onEvent(LmStartEvent("gpt-4", DynamicValue.Record.empty))

    val events = drain(queue)
    assertEquals(events.size, 2)
    assertEquals(events(0).asInstanceOf[StatusEvent].message, "Module predict starting")
    assertEquals(events(1).asInstanceOf[StatusEvent].message, "Calling gpt-4")
  }
