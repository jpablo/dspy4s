package dspy4s.core

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.HistoryLimit
import dspy4s.core.contracts.LanguageModelRef
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

/** `RuntimeEnvironment.withInstalled`: the helper that installs the given context of an entry point
  * (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS G-50). The given context wins for each field that it sets, and each
  * other field comes from the installed context (upstream `dspy.context(**kwargs)`).
  */
class InstalledContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private object GivenLm     extends LanguageModelRef
  private object InstalledLm extends LanguageModelRef

  private object Handler extends CallbackHandler:
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = ()

  private def traceEntry(name: String): TraceEntry =
    TraceEntry(name, DynamicValues.record(), DynamicValues.record())

  test("the given context is installed in the call, and the previous settings come back after it") {
    val givenContext = RuntimeContext(lm = Some(GivenLm), callbacks = Some(Vector(Handler)))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(InstalledLm))) {
      RuntimeEnvironment.withInstalled(givenContext) {
        assert(RuntimeEnvironment.current.lm.contains(GivenLm))
        assertEquals(RuntimeEnvironment.current.callbacks, Vector(Handler))
      }
      assert(RuntimeEnvironment.current.lm.contains(InstalledLm))
      assertEquals(RuntimeEnvironment.current.callbacks, Vector.empty)
    }
  }

  test("a field that the given context does not set comes from the installed context") {
    val givenContext = RuntimeContext(lm = Some(GivenLm))
    val installed    =
      RuntimeContext(lm = Some(InstalledLm), callbacks = Some(Vector(Handler)), numThreads = Some(ThreadCount(3)))

    RuntimeEnvironment.withSettings(installed) {
      RuntimeEnvironment.withInstalled(givenContext) {
        assert(RuntimeEnvironment.current.lm.contains(GivenLm))
        assertEquals(RuntimeEnvironment.current.callbacks, Vector(Handler))
        assertEquals(RuntimeEnvironment.current.numThreads, Some(ThreadCount(3)))
      }
      assert(RuntimeEnvironment.current.lm.contains(InstalledLm))
    }
  }

  test("an installed context gives no new install") {
    val outer = RuntimeEnvironment.current
    RuntimeEnvironment.withInstalled(outer) {
      assert(RuntimeEnvironment.current eq outer)
    }

    val givenContext = RuntimeContext(lm = Some(GivenLm))
    RuntimeEnvironment.withSettings(RuntimeContext(callbacks = Some(Vector(Handler)))) {
      RuntimeEnvironment.withInstalled(givenContext) {
        val installed = RuntimeEnvironment.current
        RuntimeEnvironment.withInstalled(givenContext) {
          assert(RuntimeEnvironment.current eq installed)
        }
        // The context that a caller reads from the installed context is also installed.
        RuntimeEnvironment.withInstalled(installed) {
          assert(RuntimeEnvironment.current eq installed)
        }
      }
    }
  }

  test("a given context whose fields have the installed values gives no new install") {
    val callbacks = Vector(Handler)
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(GivenLm), callbacks = Some(callbacks), numThreads = Some(ThreadCount(2)))
    ) {
      val installed = RuntimeEnvironment.current
      // Other `Option` and context objects, with the same model, the same callback list, and an equal thread count.
      RuntimeEnvironment.withInstalled(RuntimeContext(lm = Some(GivenLm), numThreads = Some(ThreadCount(2)))) {
        assert(RuntimeEnvironment.current eq installed)
      }
      RuntimeEnvironment.withInstalled(RuntimeContext(callbacks = Some(callbacks))) {
        assert(RuntimeEnvironment.current eq installed)
      }
      // A field with another value gives an install.
      RuntimeEnvironment.withInstalled(RuntimeContext(numThreads = Some(ThreadCount(4)))) {
        assert(!(RuntimeEnvironment.current eq installed))
        assertEquals(RuntimeEnvironment.current.numThreads, Some(ThreadCount(4)))
        assert(RuntimeEnvironment.current.lm.contains(GivenLm))
      }
    }
  }

  test("the trace and the history of the call reach the caller, also when the call throws") {
    RuntimeEnvironment.appendTrace(traceEntry("before"))
    RuntimeEnvironment.withInstalled(RuntimeContext(lm = Some(GivenLm))) {
      // The call sees the trace of the caller.
      assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector("before"))
      RuntimeEnvironment.appendTrace(traceEntry("inside"))
      RuntimeEnvironment.appendHistory(HistoryEntry("inside", DynamicValues.record()))
    }
    val _ = intercept[IllegalStateException] {
      RuntimeEnvironment.withInstalled(RuntimeContext(lm = Some(GivenLm))) {
        RuntimeEnvironment.appendTrace(traceEntry("thrown"))
        throw IllegalStateException("stop")
      }
    }

    assertEquals(RuntimeEnvironment.current.trace.map(_.component), Vector("before", "inside", "thrown"))
    assertEquals(RuntimeEnvironment.current.history.map(_.component), Vector("inside"))
    assertEquals(RuntimeEnvironment.current.lm, None)
  }

  test("the call keeps the dynamic scope of the thread") {
    val givenContext = RuntimeContext(lm = Some(GivenLm), asyncTaskId = Some("other"), callStack = Vector("stale"))

    RuntimeEnvironment.withActiveCall("outer") {
      RuntimeEnvironment.withInstalled(givenContext) {
        assertEquals(RuntimeEnvironment.current.callStack, Vector("outer"))
        assertEquals(RuntimeEnvironment.current.asyncTaskId, None)
      }
    }
  }

  test("the history settings of the given context apply in the call") {
    RuntimeEnvironment.withInstalled(RuntimeContext(maxHistorySize = Some(HistoryLimit(1)))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("first", DynamicValues.record()))
      RuntimeEnvironment.appendHistory(HistoryEntry("second", DynamicValues.record()))
    }
    RuntimeEnvironment.withInstalled(RuntimeContext(disableHistory = Some(true))) {
      RuntimeEnvironment.appendHistory(HistoryEntry("third", DynamicValues.record()))
    }

    assertEquals(RuntimeEnvironment.current.history.map(_.component), Vector("second"))
  }

  test("a given context with an empty callback list turns off the installed callbacks in the call") {
    RuntimeEnvironment.withCallbacks(Vector(Handler)) {
      val inside = RuntimeEnvironment.withInstalled(RuntimeContext(callbacks = Some(Vector.empty))) {
        RuntimeEnvironment.current.callbacks
      }
      assertEquals(inside, Vector.empty)
      assertEquals(RuntimeEnvironment.current.callbacks, Vector(Handler))
    }
  }
