package dspy4s.core

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class RuntimeEnvironmentSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("withContext restores previous context after scope") {
    val baseAsync = RuntimeEnvironment.current.asyncTaskId
    val scopedContext = RuntimeContext(asyncTaskId = Some("scoped"))

    RuntimeEnvironment.withContext(scopedContext) {
      assertEquals(RuntimeEnvironment.current.asyncTaskId, Some("scoped"))
    }

    assertEquals(RuntimeEnvironment.current.asyncTaskId, baseAsync)
  }

  test("withSettings applies only inside scope") {
    RuntimeEnvironment.withSettings(RuntimeContext(asyncTaskId = Some("value"))) {
      assertEquals(RuntimeEnvironment.current.asyncTaskId, Some("value"))
    }

    assertEquals(RuntimeEnvironment.current.asyncTaskId, None)
  }

  test("emit dispatches callback events") {
    val received = ArrayBuffer.empty[CallbackEvent]

    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        received += event

    val context = RuntimeContext(callbacks = Vector(callback))

    RuntimeEnvironment.withContext(context) {
      RuntimeEnvironment.emit(ModuleStartEvent("predict", DynamicValues.record("q" := "hi")))
    }

    assertEquals(received.size, 1)
    assert(received.head.isInstanceOf[ModuleStartEvent])
  }

  test("configure sets global defaults visible in current context") {
    val configured = RuntimeEnvironment.configure(RuntimeContext(asyncTaskId = Some("global")))
    assertEquals(configured, Right(()))
    assertEquals(RuntimeEnvironment.current.asyncTaskId, Some("global"))
  }

  test("globally-configured captureFailureTraces and callbackMetadata reach the current context (fillFrom)") {
    // Regression: fillFrom omitted these two fields, so a global `configure` of them was silently lost in `current`.
    val meta = DynamicValues.record("run" := "abc")
    val _ = RuntimeEnvironment.configure(RuntimeContext(captureFailureTraces = true, callbackMetadata = meta))
    assertEquals(RuntimeEnvironment.current.captureFailureTraces, true)
    assertEquals(RuntimeEnvironment.current.callbackMetadata, meta)
  }

  test("withSettings overrides global setting only inside scope") {
    val _ = RuntimeEnvironment.configure(RuntimeContext(asyncTaskId = Some("global")))

    RuntimeEnvironment.withSettings(RuntimeContext(asyncTaskId = Some("local"))) {
      assertEquals(RuntimeEnvironment.current.asyncTaskId, Some("local"))
    }

    assertEquals(RuntimeEnvironment.current.asyncTaskId, Some("global"))
  }

  test("configure is rejected from non-owner thread") {
    val first = RuntimeEnvironment.configure(RuntimeContext(asyncTaskId = Some("owner")))
    assertEquals(first, Right(()))

    val threadResult = ArrayBuffer.empty[Either[Throwable, Either[?, ?]]]
    val worker = Thread(
      new Runnable:
        override def run(): Unit =
          try
            threadResult += Right(RuntimeEnvironment.configure(RuntimeContext(asyncTaskId = Some("other"))))
          catch
            case error: Throwable => threadResult += Left(error)
    )

    worker.start()
    worker.join()

    assertEquals(threadResult.size, 1)
    val result = threadResult.head match
      case Right(value) => value
      case Left(error)  => fail(s"Unexpected thread failure: $error")
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
  }

  test("configure is allowed repeatedly within the same async task id") {
    val result = RuntimeEnvironment.withAsyncTask("task-a") {
      val first = RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(1)))
      val second = RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(2)))
      (first, second, RuntimeEnvironment.current.numThreads)
    }

    assertEquals(result._1, Right(()))
    assertEquals(result._2, Right(()))
    assertEquals(result._3, Some(2))
  }

  test("configure is rejected from different async task ids on the same thread") {
    RuntimeEnvironment.withAsyncTask("task-a") {
      assertEquals(RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(1))), Right(()))
    }

    val second = RuntimeEnvironment.withAsyncTask("task-b") {
      RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(2)))
    }

    assert(second.isLeft)
    assert(second.left.toOption.get.isInstanceOf[ConfigurationError])
  }

  test("withActiveCall maintains nested call stack and restores after scope") {
    assertEquals(RuntimeEnvironment.activeCallStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallDepth, 0)
    assertEquals(RuntimeEnvironment.activeCallId, None)

    RuntimeEnvironment.withActiveCall("parent") {
      assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallDepth, 1)
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))

      RuntimeEnvironment.withActiveCall("child") {
        assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent", "child"))
        assertEquals(RuntimeEnvironment.activeCallDepth, 2)
        assertEquals(RuntimeEnvironment.activeCallId, Some("child"))
      }

      assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallDepth, 1)
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))
    }

    assertEquals(RuntimeEnvironment.activeCallStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallDepth, 0)
    assertEquals(RuntimeEnvironment.activeCallId, None)
  }

  test("appendHistory honors max history size and disable history settings") {
    RuntimeEnvironment.withSettings(RuntimeContext(maxHistorySize = Some(2))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 1)))
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 2)))
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 3)))

      val history = RuntimeEnvironment.current.history
      assertEquals(history.size, 2)
      assertEquals(DynamicValues.recordToMap(history.head.payload)("n"), 2: Any)
      assertEquals(DynamicValues.recordToMap(history.last.payload)("n"), 3: Any)
    }

    RuntimeEnvironment.withSettings(RuntimeContext(disableHistory = Some(true))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 4)))
      assertEquals(RuntimeEnvironment.current.history, Vector.empty)
    }
  }
