package dspy4s.core

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.time.Instant

/** `inspectHistory` shows the LM entries as upstream `pretty_print_history` (`dspy/utils/inspect_history.py`). */
class InspectHistorySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private def record(entries: (String, Any)*): DynamicValue.Record =
    DynamicValues.fromAny(Map(entries*)) match
      case value: DynamicValue.Record => value
      case other                      => fail(s"not a record: $other")

  private def lmEntry(
      payload: DynamicValue.Record,
      time   : Instant = Instant.parse("2026-10-14T10:00:00Z")
  ): HistoryEntry =
    HistoryEntry(component = "lm:test-model", payload = payload, timestamp = time)

  private def message(role: String, content: String, extra: (String, Any)*): Map[String, Any] =
    Map("role" -> role, "content" -> content) ++ extra

  private def exchange(question: String, answer: String): DynamicValue.Record =
    record(
      "model"    -> "test-model",
      "messages" -> Vector(message("user", question)),
      "outputs"  -> Vector(Map("text" -> answer, "tool_calls" -> Vector.empty))
    )

  /** The index of each of `parts` in `text`, in the order of `parts`. Each part must come after the one before it. */
  private def assertInOrder(text: String, parts: String*): Unit =
    val _ = parts.foldLeft(-1) { (from, part) =>
      val at = text.indexOf(part, from + 1)
      assert(at > from, s"'$part' must come after index $from in:\n$text")
      at
    }

  test("inspectHistory renders the last n LM entries") {
    RuntimeEnvironment.appendHistory(lmEntry(exchange("what is 2+2?", "4")))
    RuntimeEnvironment.appendHistory(lmEntry(exchange("capital of France?", "Paris")))
    RuntimeEnvironment.appendHistory(lmEntry(exchange("color of the sky?", "blue")))

    val rendered = RuntimeEnvironment.inspectHistory(2)
    assert(!rendered.contains("what is 2+2?"), clue(rendered))
    assertInOrder(rendered, "capital of France?", "Paris", "color of the sky?", "blue")
  }

  test("inspectHistory with empty history returns a non-crashing message") {
    assertEquals(RuntimeEnvironment.inspectHistory(5), "No LM history recorded.")
  }

  test("inspectHistory caps n at the available history size") {
    RuntimeEnvironment.appendHistory(lmEntry(exchange("only question", "only answer")))
    val rendered = RuntimeEnvironment.inspectHistory(10)
    assertInOrder(rendered, "only question", "only answer")
  }

  test("an LM entry shows each message, the tool calls, and the response, in the upstream order") {
    val payload = record(
      "model"    -> "test-model",
      "messages" -> Vector(
        message("system", "You are terse.  "),
        message("user", "  What is the weather in Paris?"),
        message(
          "assistant",
          "",
          "tool_calls" -> Vector(Map("name" -> "get_weather", "args" -> Map("city" -> "Paris"), "id" -> "c1"))
        ),
        message("tool", "sunny", "tool_call_id" -> "c1", "name" -> "get_weather")
      ),
      "outputs" -> Vector(Map(
        "text"       -> "It is sunny.\n",
        "tool_calls" -> Vector(Map("name" -> "lookup", "args" -> Map.empty[String, Any]))
      ))
    )
    RuntimeEnvironment.appendHistory(lmEntry(payload))
    val rendered = RuntimeEnvironment.inspectHistory(1)
    assertInOrder(
      rendered,
      "[2026-10-14T10:00:00Z]",
      "System message:",
      "You are terse.",
      "User message:",
      "What is the weather in Paris?",
      "Assistant message:",
      "Tool calls:",
      "get_weather: ",
      "Tool message:",
      "sunny",
      "Response:",
      "It is sunny.",
      "Tool calls:",
      "lookup: {}"
    )
    val weatherLine = rendered.linesIterator.find(_.startsWith("get_weather: ")).getOrElse("")
    assert(weatherLine.contains("\"city\"") && weatherLine.contains("\"Paris\""), weatherLine)
    // The content is stripped.
    assert(rendered.linesIterator.contains("What is the weather in Paris?"), rendered)
  }

  test("only the LM entries count, and another entry is not shown") {
    RuntimeEnvironment.appendHistory(lmEntry(exchange("the LM question", "the LM answer")))
    RuntimeEnvironment.appendHistory(HistoryEntry(
      component = "module:predict",
      payload = record("messages" -> Vector(message("user", "a module note")))
    ))
    val rendered = RuntimeEnvironment.inspectHistory(1)
    assertInOrder(rendered, "the LM question", "the LM answer")
    assert(!rendered.contains("a module note"), rendered)
  }

  test("an entry with more than one output gives the upstream note") {
    val payload = record(
      "messages" -> Vector(message("user", "q")),
      "outputs"  -> Vector(
        Map("text" -> "a1", "tool_calls" -> Vector.empty),
        Map("text" -> "a2", "tool_calls" -> Vector.empty),
        Map("text" -> "a3", "tool_calls" -> Vector.empty)
      )
    )
    RuntimeEnvironment.appendHistory(lmEntry(payload))
    val rendered = RuntimeEnvironment.inspectHistory(1)
    assertInOrder(rendered, "Response:", "a1", "(and 2 other completions)")
    assert(!rendered.contains("a2"), rendered)
  }

  test("a failure entry shows its error") {
    val payload = record(
      "messages"      -> Vector(message("user", "q")),
      "error_code"    -> "auth",
      "error_message" -> "bad key"
    )
    RuntimeEnvironment.appendHistory(lmEntry(payload))
    assert(RuntimeEnvironment.inspectHistory(1).contains("Error (auth): bad key"))
  }

  test("an entry with a prompt and no messages shows the prompt as a user message") {
    RuntimeEnvironment.appendHistory(lmEntry(record(
      "prompt"  -> "Complete this text.",
      "outputs" -> Vector(Map("text" -> "Done.", "tool_calls" -> Vector.empty))
    )))
    assertInOrder(RuntimeEnvironment.inspectHistory(1), "User message:", "Complete this text.", "Response:", "Done.")
  }

  test("a message part shows its kind and its length") {
    val payload = record(
      "messages" -> Vector(message(
        "user",
        "Describe the image.",
        "parts" -> Vector(Map("kind" -> "image_url", "payload" -> "data:image/png"))
      )),
      "outputs" -> Vector(Map("text" -> "A cat.", "tool_calls" -> Vector.empty))
    )
    RuntimeEnvironment.appendHistory(lmEntry(payload))
    assert(RuntimeEnvironment.inspectHistory(1).contains("<image_url: 14 characters>"))
  }
