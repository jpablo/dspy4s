package dspy4s.core

import dspy4s.core.contracts.{DynamicValues, HistoryEntry, TraceEntry}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import zio.blocks.schema.DynamicValue

/** Laws for the attempt-isolation helpers shared by BestOfN / Refine. The migrated loops are covered by
  * TypedBestOfNSuite / RefinePerModuleAdviceSuite; this pins the primitive. */
class IsolatedAttemptSuite extends munit.FunSuite:

  private def hist(n: Int): HistoryEntry   = HistoryEntry("c", DynamicValues.record("n" := n))
  private def tr(name: String): TraceEntry = TraceEntry(name, DynamicValue.Record.empty, DynamicValue.Record.empty)

  test("isolatedAttempt captures the body's trace/history and does not leak them") {
    RuntimeEnvironment.resetForTests()
    val (result, trace, history) = RuntimeEnvironment.isolatedAttempt(RuntimeEnvironment.current) {
      RuntimeEnvironment.appendTrace(tr("step"))
      RuntimeEnvironment.appendHistory(hist(1))
      42
    }
    assertEquals(result, 42)
    assertEquals(trace.map(_.component), Vector("step"))
    assertEquals(history.map(_.component), Vector("c"))
    // Isolation: the captured entries do not bubble into the enclosing context on their own.
    assertEquals(RuntimeEnvironment.current.trace, Vector.empty[TraceEntry])
    assertEquals(RuntimeEnvironment.current.history, Vector.empty[HistoryEntry])
  }

  test("propagateAttempt replays the captured trace/history into the current context, in order") {
    RuntimeEnvironment.resetForTests()
    val (_, trace, history) = RuntimeEnvironment.isolatedAttempt(RuntimeEnvironment.current) {
      RuntimeEnvironment.appendHistory(hist(1))
      RuntimeEnvironment.appendHistory(hist(2))
      ()
    }
    assertEquals(RuntimeEnvironment.current.history, Vector.empty[HistoryEntry]) // not propagated yet
    RuntimeEnvironment.propagateAttempt(trace, history)
    assertEquals(RuntimeEnvironment.current.history, history)
  }
