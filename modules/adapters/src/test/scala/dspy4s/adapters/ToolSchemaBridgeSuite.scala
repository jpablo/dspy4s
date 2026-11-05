package dspy4s.adapters

import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class ToolSchemaBridgeSuite extends FunSuite:

  /** Read `key` off a record-shaped DynamicValue, failing the test with context if absent or not a record. */
  private def field(value: DynamicValue, key: String): DynamicValue =
    value match
      case r: DynamicValue.Record => DynamicValues.recordGet(r, key).getOrElse(fail(s"missing key '$key' in $r"))
      case other                  => fail(s"expected a record to read '$key' from, got $other")

  private def elementsOf(value: DynamicValue): Vector[DynamicValue] =
    value match
      case DynamicValue.Sequence(els) => els.iterator.toVector
      case other                      => fail(s"expected a sequence, got $other")

  test("toOpenAiToolsDynamic builds the OpenAI tools array, with the input schema of each spec as the parameters") {
    val schema = DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.record("query" -> DynamicValues.record("type" := "string")),
      "required"   := List("query")
    )
    val rendered = elementsOf(ToolSchemaBridge.toOpenAiToolsDynamic(Vector(
      ToolSpec("search", Some("Search documents"), schema),
      ToolSpec("lookup", inputSchema = DynamicValue.Record.empty)
    )))
    assertEquals(rendered.size, 2)

    val function = field(rendered.head, "function")
    assertEquals(DynamicValues.renderText(field(rendered.head, "type")), "function")
    assertEquals(DynamicValues.renderText(field(function, "name")), "search")
    assertEquals(DynamicValues.renderText(field(function, "description")), "Search documents")
    assertEquals(field(function, "parameters"), schema: DynamicValue)

    // A tool with no description has an empty description.
    assertEquals(DynamicValues.renderText(field(field(rendered.last, "function"), "description")), "")
  }
