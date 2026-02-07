package dspy4s.core

import dspy4s.core.contracts.AdapterEndEvent
import dspy4s.core.contracts.AdapterStartEvent
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class CallbackDispatcherSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("withModule emits start and end events for successful calls") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val result = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withModule("predict", Map("question" -> "hi")) {
        Right("ok")
      }
    }

    assertEquals(result, Right("ok"))
    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
    val moduleEnd = events.last.asInstanceOf[ModuleEndEvent]
    assertEquals(moduleEnd.output, Right("ok"))
  }

  test("withModule emits end event and rethrows on exceptions") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    intercept[IllegalStateException] {
      RuntimeEnvironment.withCallbacks(Vector(callback)) {
        CallbackDispatcher.withModule("predict", Map("question" -> "boom")) {
          throw IllegalStateException("boom")
        }
      }
    }

    assertEquals(events.size, 2)
    assert(events.head.isInstanceOf[ModuleStartEvent])
    val end = events.last.asInstanceOf[ModuleEndEvent]
    assert(end.output.isLeft)
    assert(end.output.left.toOption.get.isInstanceOf[RuntimeError])
  }

  test("withLm and withAdapter emit typed callback events") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      val lmResult = CallbackDispatcher.withLm("gpt-test", Map("prompt" -> "hello")) {
        Right(Map("text" -> "world"))
      }
      val adapterResult = CallbackDispatcher.withAdapter("json", Map("input" -> "raw")) {
        Right(Map("output" -> "parsed"))
      }

      assert(lmResult.isRight)
      assert(adapterResult.isRight)
    }

    assertEquals(events.size, 4)
    assert(events(0).isInstanceOf[LmStartEvent])
    assert(events(1).isInstanceOf[LmEndEvent])
    assert(events(2).isInstanceOf[AdapterStartEvent])
    assert(events(3).isInstanceOf[AdapterEndEvent])
  }
