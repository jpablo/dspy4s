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
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

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

    val _ = intercept[IllegalStateException] {
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

  test("nested module callbacks carry parent call id and stable call ids") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withModule("parent", Map("q" -> "x")) {
        CallbackDispatcher.withModule("child", Map("q" -> "x")) {
          Right("child")
        }.map(_ => "done")
      }
    }

    assertEquals(events.size, 4)
    val parentStart = events(0).asInstanceOf[ModuleStartEvent]
    val childStart = events(1).asInstanceOf[ModuleStartEvent]
    val childEnd = events(2).asInstanceOf[ModuleEndEvent]
    val parentEnd = events(3).asInstanceOf[ModuleEndEvent]

    assertEquals(parentStart.parentCallId, None)
    assertEquals(parentEnd.parentCallId, None)
    assertEquals(parentStart.callId, parentEnd.callId)
    assertEquals(childStart.parentCallId, Some(parentStart.callId))
    assertEquals(childEnd.parentCallId, Some(parentStart.callId))
    assertEquals(childStart.callId, childEnd.callId)
  }

  test("callback parent call id is preserved across propagated future work") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given ExecutionContext = ExecutionContext.global

      CallbackDispatcher.withModule("outer", Map("q" -> "x")) {
        Await.result(
          ContextPropagation.future {
            CallbackDispatcher.withLm("gpt-test", Map("prompt" -> "hello")) {
              Right(Map("text" -> "ok"))
            }
          },
          3.seconds
        )
      }
    }

    assertEquals(events.size, 4)
    val outerStart = events(0).asInstanceOf[ModuleStartEvent]
    val lmStart = events(1).asInstanceOf[LmStartEvent]
    val lmEnd = events(2).asInstanceOf[LmEndEvent]
    val outerEnd = events(3).asInstanceOf[ModuleEndEvent]

    assertEquals(outerStart.parentCallId, None)
    assertEquals(lmStart.parentCallId, Some(outerStart.callId))
    assertEquals(lmEnd.parentCallId, Some(outerStart.callId))
    assertEquals(outerStart.callId, outerEnd.callId)
  }

  test("withTool emits tool start and end events with call ids") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withTool("search", Map("query" -> "scala")) {
        Right(Map("ok" -> true))
      }
    }

    assertEquals(events.size, 2)
    val start = events.head.asInstanceOf[ToolStartEvent]
    val end = events.last.asInstanceOf[ToolEndEvent]
    assertEquals(start.toolName, "search")
    assertEquals(end.toolName, "search")
    assertEquals(start.callId, end.callId)
    assertEquals(start.parentCallId, None)
    assertEquals(end.parentCallId, None)
  }

  test("active call stack is preserved across propagated future callbacks") {
    val snapshots = ArrayBuffer.empty[Vector[String]]

    given ExecutionContext = ExecutionContext.global

    val _ = CallbackDispatcher.withModule("outer", Map("q" -> "x")) {
      snapshots += RuntimeEnvironment.activeCallStack

      Await.result(
        ContextPropagation.future {
          snapshots += RuntimeEnvironment.activeCallStack
          val _ = CallbackDispatcher.withTool("search", Map("query" -> "scala")) {
            snapshots += RuntimeEnvironment.activeCallStack
            Right("ok")
          }
          snapshots += RuntimeEnvironment.activeCallStack
          Right("done")
        },
        3.seconds
      )
    }

    assertEquals(snapshots.size, 4)
    assertEquals(snapshots(0).size, 1)
    assertEquals(snapshots(1).size, 1)
    assertEquals(snapshots(2).size, 2)
    assertEquals(snapshots(3).size, 1)
    assertEquals(RuntimeEnvironment.activeCallStack, Vector.empty)
  }
