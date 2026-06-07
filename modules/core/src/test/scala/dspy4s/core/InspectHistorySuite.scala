package dspy4s.core

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

class InspectHistorySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private def record(prompt: String, completion: String): HistoryEntry =
    HistoryEntry(
      component = "lm:test-model",
      payload = DynamicValues.record(
        "model" := "test-model",
        "prompt" := prompt,
        "completion" := completion
      )
    )

  test("inspectHistory renders the last n interactions with prompt text and model output") {
    RuntimeEnvironment.appendHistory(record("what is 2+2?", "4"))
    RuntimeEnvironment.appendHistory(record("capital of France?", "Paris"))
    RuntimeEnvironment.appendHistory(record("color of the sky?", "blue"))

    val rendered = RuntimeEnvironment.inspectHistory(2)

    // Only the last 2 interactions show up.
    assert(!rendered.contains("what is 2+2?"), clue(rendered))
    assert(rendered.contains("capital of France?"), clue(rendered))
    assert(rendered.contains("Paris"), clue(rendered))
    assert(rendered.contains("color of the sky?"), clue(rendered))
    assert(rendered.contains("blue"), clue(rendered))
    // The model is surfaced too.
    assert(rendered.contains("test-model"), clue(rendered))
  }

  test("inspectHistory with empty history returns a non-crashing message") {
    val rendered = RuntimeEnvironment.inspectHistory(5)
    assert(rendered.nonEmpty, clue(rendered))
  }

  test("inspectHistory caps n at the available history size") {
    RuntimeEnvironment.appendHistory(record("only question", "only answer"))
    val rendered = RuntimeEnvironment.inspectHistory(10)
    assert(rendered.contains("only question"), clue(rendered))
    assert(rendered.contains("only answer"), clue(rendered))
  }
