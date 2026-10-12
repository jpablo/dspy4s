package dspy4s.core

import dspy4s.core.contracts.AdapterEndEvent
import dspy4s.core.contracts.AdapterStartEvent
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.boundary
import scala.util.control.ControlThrowable

class CallbackDispatcherSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("withModule emits start and end events for successful calls") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val result = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "hi")) {
        Right("ok")
      }
    }

    assertEquals(result, Right("ok"))
    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
    val moduleEnd = events.last.asInstanceOf[ModuleEndEvent]
    assertEquals(moduleEnd.output, Right("ok"))
  }

  test("withModule emits end event and rethrows on exceptions") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = intercept[IllegalStateException] {
      RuntimeEnvironment.withCallbacks(Vector(callback)) {
        CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "boom")) {
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

  test("a throwing end observer is isolated, attempted once, and does not hide the result") {
    val endAttempts = AtomicInteger(0)
    val observed    = ArrayBuffer.empty[CallbackEvent]
    val throwing    = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        event match
          case _: ModuleEndEvent =>
            endAttempts.incrementAndGet()
            throw IllegalStateException("end observer failed")
          case _ => ()
    val later = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = observed += event

    val result = RuntimeEnvironment.withCallbacks(Vector(throwing, later)) {
      CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "hi")) {
        Right("ok")
      }
    }

    assertEquals(result, Right("ok"))
    assertEquals(endAttempts.get(), 1)
    assertEquals(observed.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
  }

  test("a throwing start observer does not prevent the body or later observers") {
    val bodyRuns = AtomicInteger(0)
    val observed = ArrayBuffer.empty[CallbackEvent]
    val throwing = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        event match
          case _: ModuleStartEvent => throw IllegalStateException("start observer failed")
          case _                   => ()
    val later = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = observed += event

    val result = RuntimeEnvironment.withCallbacks(Vector(throwing, later)) {
      CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "hi")) {
        bodyRuns.incrementAndGet()
        Right("ok")
      }
    }

    assertEquals(result, Right("ok"))
    assertEquals(bodyRuns.get(), 1)
    assertEquals(observed.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
  }

  test("withLm and withAdapter emit structured callback events") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      val lmResult = CallbackDispatcher.withLm("gpt-test", DynamicValues.record("prompt" := "hello")) {
        Right(Map("text" -> "world"))
      }
      val adapterResult = CallbackDispatcher.withAdapter("json", DynamicValues.record("input" := "raw")) {
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
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withModule("parent", DynamicValues.record("q" := "x")) {
        CallbackDispatcher.withModule("child", DynamicValues.record("q" := "x")) {
          Right("child")
        }.map(_ => "done")
      }
    }

    assertEquals(events.size, 4)
    val parentStart = events(0).asInstanceOf[ModuleStartEvent]
    val childStart  = events(1).asInstanceOf[ModuleStartEvent]
    val childEnd    = events(2).asInstanceOf[ModuleEndEvent]
    val parentEnd   = events(3).asInstanceOf[ModuleEndEvent]

    assertEquals(parentStart.parentCallId, None)
    assertEquals(parentEnd.parentCallId, None)
    assertEquals(parentStart.callId, parentEnd.callId)
    assertEquals(childStart.parentCallId, Some(parentStart.callId))
    assertEquals(childEnd.parentCallId, Some(parentStart.callId))
    assertEquals(childStart.callId, childEnd.callId)
  }

  test("callback parent call id is preserved across propagated future work") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given ExecutionContext = ExecutionContext.global

      CallbackDispatcher.withModule("outer", DynamicValues.record("q" := "x")) {
        Await.result(
          ContextPropagation.future {
            CallbackDispatcher.withLm("gpt-test", DynamicValues.record("prompt" := "hello")) {
              Right(Map("text" -> "ok"))
            }
          },
          3.seconds
        )
      }
    }

    assertEquals(events.size, 4)
    val outerStart = events(0).asInstanceOf[ModuleStartEvent]
    val lmStart    = events(1).asInstanceOf[LmStartEvent]
    val lmEnd      = events(2).asInstanceOf[LmEndEvent]
    val outerEnd   = events(3).asInstanceOf[ModuleEndEvent]

    assertEquals(outerStart.parentCallId, None)
    assertEquals(lmStart.parentCallId, Some(outerStart.callId))
    assertEquals(lmEnd.parentCallId, Some(outerStart.callId))
    assertEquals(outerStart.callId, outerEnd.callId)
  }

  test("withTool emits tool start and end events with call ids") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      CallbackDispatcher.withTool("search", DynamicValues.recordFromEntries(Seq("query" := "scala"))) {
        Right(DynamicValues.recordFromEntries(Seq("ok" := true)))
      }
    }

    assertEquals(events.size, 2)
    val start = events.head.asInstanceOf[ToolStartEvent]
    val end   = events.last.asInstanceOf[ToolEndEvent]
    assertEquals(start.toolName, "search")
    assertEquals(end.toolName, "search")
    assertEquals(start.callId, end.callId)
    assertEquals(start.parentCallId, None)
    assertEquals(end.parentCallId, None)
  }

  test("active call stack is preserved across propagated future callbacks") {
    val snapshots = ArrayBuffer.empty[Vector[String]]

    given ExecutionContext = ExecutionContext.global

    val _ = CallbackDispatcher.withModule("outer", DynamicValues.record("q" := "x")) {
      snapshots += RuntimeEnvironment.activeCallStack

      Await.result(
        ContextPropagation.future {
          snapshots += RuntimeEnvironment.activeCallStack
          val _ = CallbackDispatcher.withTool("search", DynamicValues.recordFromEntries(Seq("query" := "scala"))) {
            snapshots += RuntimeEnvironment.activeCallStack
            Right(DynamicValues.fromAny("ok"))
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

  // PORT_GAPS G-40: `InterruptedException` is not `NonFatal`, and the parallel executor interrupts its workers.

  test("withModule emits the end event when the body is interrupted") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    // munit `intercept` does not catch an `InterruptedException`, so the test catches it directly.
    val interrupted =
      try
        val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
          CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "interrupt")) {
            throw new InterruptedException("worker interrupted")
          }
        }
        false
      catch case _: InterruptedException => true
    assert(interrupted, "the InterruptedException must reach the caller")

    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
    events.last match
      case end: ModuleEndEvent => assert(end.output.isLeft, s"the end event must report a failure: ${end.output}")
      case other               => fail(s"expected a ModuleEndEvent, got $other")
  }

  test("withTool emits the end event when the body is interrupted") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val interrupted =
      try
        val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
          CallbackDispatcher.withTool(toolName = "search", args = DynamicValues.record("q" := "x")) {
            throw new InterruptedException("worker interrupted")
          }
        }
        false
      catch case _: InterruptedException => true
    assert(interrupted, "the InterruptedException must reach the caller")

    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ToolStartEvent", "ToolEndEvent"))
  }

  test("an interrupted body rethrows the same exception instance") {
    val bodyError = new InterruptedException("worker interrupted")
    val caught    =
      try
        val _ = CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "interrupt")) {
          throw bodyError
        }
        None
      catch case error: InterruptedException => Some(error)
    assert(caught.exists(_ eq bodyError), s"expected the body exception, got $caught")
  }

  // The interrupt fix does not change the other throwables that `NonFatal` rejects, and it does not change `Break`.

  test("a ControlThrowable passes through a scope without an end event") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val passed =
      try
        val _ = RuntimeEnvironment.withCallbacks(Vector(callback)) {
          CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "control")) {
            throw new ControlThrowable {}
          }
        }
        false
      catch case _: ControlThrowable => true
    assert(passed, "the ControlThrowable must reach the caller")

    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent"))
  }

  test("a boundary break through a scope keeps its end event") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val result = RuntimeEnvironment.withCallbacks(Vector(callback)) {
      boundary[Either[DspyError, String]] {
        CallbackDispatcher.withModule("predict", DynamicValues.record("question" := "break")) {
          boundary.break(Right("early"))
        }
      }
    }

    assertEquals(result, Right("early"))
    assertEquals(events.map(_.getClass.getSimpleName).toVector, Vector("ModuleStartEvent", "ModuleEndEvent"))
  }
