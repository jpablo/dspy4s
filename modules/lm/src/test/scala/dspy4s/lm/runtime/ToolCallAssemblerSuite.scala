package dspy4s.lm.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.lm.contracts.LmToolCallDelta
import munit.FunSuite

class ToolCallAssemblerSuite extends FunSuite:

  // Args are now a DynamicValue.Record; project back to a Map for value comparison.
  private def argsMap(call: dspy4s.core.contracts.ToolCall): Map[String, Any] =
    DynamicValues.recordToMap(call.args)

  test("assembles a single tool call from openai-style frame sequence") {
    val deltas = Vector(
      LmToolCallDelta(index = 0, id = Some("call_1"), name = Some("get_weather"), argumentsFragment = Some("")),
      LmToolCallDelta(index = 0, argumentsFragment = Some("""{"loc""")),
      LmToolCallDelta(index = 0, argumentsFragment = Some("""ation":"Paris"}"""))
    )
    val calls = ToolCallAssembler.assemble(deltas)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name, "get_weather")
    assertEquals(argsMap(calls.head), Map[String, Any]("location" -> "Paris"))
  }

  test("preserves first-seen order across multiple indices") {
    val deltas = Vector(
      LmToolCallDelta(index = 1, name = Some("second"), argumentsFragment = Some("{}")),
      LmToolCallDelta(index = 0, name = Some("first"), argumentsFragment = Some("{}")),
      LmToolCallDelta(index = 0, argumentsFragment = Some(""))
    )
    val calls = ToolCallAssembler.assemble(deltas)
    assertEquals(calls.map(_.name), Vector("second", "first"))
  }

  test("drops entries that never received a name") {
    val deltas = Vector(
      LmToolCallDelta(index = 0, argumentsFragment = Some("""{"a":1}"""))
    )
    assertEquals(ToolCallAssembler.assemble(deltas), Vector.empty)
  }

  test("merges name from later delta when earlier one only had arguments") {
    val deltas = Vector(
      LmToolCallDelta(index = 0, argumentsFragment = Some("""{"a":""")),
      LmToolCallDelta(index = 0, name = Some("late_name"), argumentsFragment = Some("""1}"""))
    )
    val calls = ToolCallAssembler.assemble(deltas)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name, "late_name")
    assertEquals(argsMap(calls.head), Map[String, Any]("a" -> 1L))
  }

  test("falls back to input wrapper when arguments are not valid JSON") {
    val deltas = Vector(
      LmToolCallDelta(index = 0, name = Some("notify"), argumentsFragment = Some("plain text"))
    )
    val calls = ToolCallAssembler.assemble(deltas)
    assertEquals(argsMap(calls.head), Map[String, Any]("input" -> "plain text"))
  }

  test("empty arguments yield empty args map") {
    val deltas = Vector(LmToolCallDelta(index = 0, name = Some("ping")))
    val calls = ToolCallAssembler.assemble(deltas)
    assertEquals(argsMap(calls.head), Map.empty[String, Any])
  }
