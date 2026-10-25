package dspy4s.core

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.HistoryLimit
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

class RuntimeEnvironmentSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("withContext restores previous context after scope") {
    val baseAsync     = RuntimeEnvironment.current.asyncTaskId
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

    val context = RuntimeContext(callbacks = Some(Vector(callback)))

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
    val _    =
      RuntimeEnvironment.configure(RuntimeContext(captureFailureTraces = Some(true), callbackMetadata = Some(meta)))
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
    val worker       = Thread(
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
      val first  = RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(ThreadCount(1))))
      val second = RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(ThreadCount(2))))
      (first, second, RuntimeEnvironment.current.numThreads)
    }

    assertEquals(result._1, Right(()))
    assertEquals(result._2, Right(()))
    assertEquals(result._3, Some(ThreadCount(2)))
  }

  test("configure is rejected from different async task ids on the same thread") {
    RuntimeEnvironment.withAsyncTask("task-a") {
      assertEquals(RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(ThreadCount(1)))), Right(()))
    }

    val second = RuntimeEnvironment.withAsyncTask("task-b") {
      RuntimeEnvironment.configure(RuntimeContext(numThreads = Some(ThreadCount(2))))
    }

    assert(second.isLeft)
    assert(second.left.toOption.get.isInstanceOf[ConfigurationError])
  }

  test("withActiveCall maintains nested call stack and restores after scope") {
    assertEquals(RuntimeEnvironment.current.callStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallId, None)

    RuntimeEnvironment.withActiveCall("parent") {
      assertEquals(RuntimeEnvironment.current.callStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))

      RuntimeEnvironment.withActiveCall("child") {
        assertEquals(RuntimeEnvironment.current.callStack, Vector("parent", "child"))
        assertEquals(RuntimeEnvironment.activeCallId, Some("child"))
      }

      assertEquals(RuntimeEnvironment.current.callStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))
    }

    assertEquals(RuntimeEnvironment.current.callStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallId, None)
  }

  test("appendHistory honors max history size and disable history settings") {
    RuntimeEnvironment.withSettings(RuntimeContext(maxHistorySize = Some(HistoryLimit(2)))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 1)))
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 2)))
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 3)))

      val history = RuntimeEnvironment.current.history
      assertEquals(history.size, 2)
      assertEquals(DynamicValues.recordToMap(history.head.payload)("n"), 2: Any)
      assertEquals(DynamicValues.recordToMap(history.last.payload)("n"), 3: Any)
    }

    // The history that the first block adds stays after the block (upstream `dspy.context`).
    val before = RuntimeEnvironment.current.history
    assertEquals(before.size, 2)
    RuntimeEnvironment.withSettings(RuntimeContext(disableHistory = Some(true))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("lm", DynamicValues.record("n" := 4)))
      assertEquals(RuntimeEnvironment.current.history, before, "a disabled history adds no entry")
    }
  }

  test("the flat copy keeps the predictor extent of the scope") {
    val layout  = dspy4s.core.contracts.SignatureLayout.parse("question -> answer").fold(e => fail(e.message), identity)
    val key     = dspy4s.core.contracts.PredictorKey.from("predict", layout)
    val context = RuntimeContext().withScope(dspy4s.core.contracts.RuntimeScope(predictor = Some(key)))
    assert(context.copy(disableHistory = Some(true)).scope.withinPredictor, "copy lost scope.withinPredictor")
  }

  test("the interpreter factory is a service: a scope fills it from the defaults, and copy keeps it") {
    val factory: dspy4s.core.contracts.ReplInterpreterFactory =
      (_, _) => throw new IllegalStateException("the factory is not called in this test")
    val defaults = RuntimeContext(interpreterFactory = Some(factory))

    assertEquals(RuntimeContext().interpreterFactory, None)
    assert(RuntimeContext().fillFrom(defaults).interpreterFactory.exists(_ eq factory))
    assert(defaults.copy(disableHistory = Some(true)).interpreterFactory.exists(_ eq factory))
    RuntimeEnvironment.withSettings(defaults) {
      assert(RuntimeEnvironment.current.interpreterFactory.exists(_ eq factory))
    }
    assertEquals(RuntimeEnvironment.current.interpreterFactory, None)
  }

  test("withCallbacks with an empty list turns off the callbacks of the global context") {
    val received = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        received += event
    val _ = RuntimeEnvironment.configure(RuntimeContext(callbacks = Some(Vector(callback))))
    RuntimeEnvironment.withCallbacks(Vector.empty) {
      RuntimeEnvironment.emit(ModuleStartEvent("predict", DynamicValues.record("q" := "hi")))
    }
    assertEquals(received.size, 0, "upstream dspy.context(callbacks=[]) turns the callbacks off")
  }

  test("withSettings, withCallbacks, and withAsyncTask keep the trace and the history of their block") {
    val trace   = TraceEntry("component", DynamicValue.Record.empty, DynamicValue.Record.empty)
    val history = HistoryEntry("lm:model", DynamicValue.Record.empty)
    RuntimeEnvironment.withSettings(RuntimeContext(asyncTaskId = Some("block"))) {
      RuntimeEnvironment.appendTrace(trace)
      RuntimeEnvironment.appendHistory(history)
    }
    RuntimeEnvironment.withCallbacks(Vector.empty)(RuntimeEnvironment.appendTrace(trace))
    RuntimeEnvironment.withAsyncTask("task")(RuntimeEnvironment.appendTrace(trace))
    assertEquals(RuntimeEnvironment.current.trace, Vector(trace, trace, trace))
    assertEquals(RuntimeEnvironment.current.history, Vector(history))
  }

  test("withSettings with captureFailureTraces = Some(false) turns off the failure traces of the global context") {
    val _      = RuntimeEnvironment.configure(RuntimeContext(captureFailureTraces = Some(true)))
    val inside = RuntimeEnvironment.withSettings(RuntimeContext(captureFailureTraces = Some(false))) {
      RuntimeEnvironment.current.captureFailureTraces
    }
    assertEquals(inside, false)
    assertEquals(RuntimeEnvironment.current.captureFailureTraces, true)
  }

  test("withSettings with an empty callbackMetadata record hides the callback metadata of the global context") {
    val meta   = DynamicValues.record("run" := "abc")
    val _      = RuntimeEnvironment.configure(RuntimeContext(callbackMetadata = Some(meta)))
    val inside = RuntimeEnvironment.withSettings(RuntimeContext(callbackMetadata = Some(DynamicValue.Record.empty))) {
      RuntimeEnvironment.current.callbackMetadata
    }
    assertEquals(inside, DynamicValue.Record.empty)
    assertEquals(RuntimeEnvironment.current.callbackMetadata, meta)
  }

  test("withSettings adds the trace of its block to the trace of the caller, also when the block throws") {
    val before = TraceEntry("before", DynamicValue.Record.empty, DynamicValue.Record.empty)
    val inside = TraceEntry("inside", DynamicValue.Record.empty, DynamicValue.Record.empty)
    RuntimeEnvironment.appendTrace(before)
    val seen = RuntimeEnvironment.withSettings(RuntimeContext(asyncTaskId = Some("block"))) {
      RuntimeEnvironment.appendTrace(inside)
      RuntimeEnvironment.current.trace
    }
    val _ = intercept[IllegalStateException] {
      RuntimeEnvironment.withSettings(RuntimeContext(asyncTaskId = Some("block"))) {
        RuntimeEnvironment.appendTrace(inside)
        throw new IllegalStateException("boom")
      }
    }
    assertEquals(seen, Vector(before, inside), "the block sees the trace of the caller")
    assertEquals(RuntimeEnvironment.current.trace, Vector(before, inside, inside))
    assertEquals(RuntimeEnvironment.current.asyncTaskId, None)
  }
