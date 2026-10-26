package dspy4s.adapters

import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.TypeRef
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

  test("toOpenAiToolsDynamic builds the OpenAI tools array with the JSON type of each parameter") {
    val tools = Vector(
      ToolSpec(
        name = "search",
        description = Some("Search documents"),
        parameters = Vector(
          ToolParameterSpec(
            name = "query",
            typeRef = TypeRef.string,
            description = Some("Search query"),
            required = true
          ),
          ToolParameterSpec(
            name = "top_k",
            typeRef = TypeRef.int,
            description = Some("Result count"),
            required = false
          ),
          ToolParameterSpec(name = "tags", typeRef = TypeRef.list, required = false)
        )
      )
    )

    val elements = elementsOf(ToolSchemaBridge.toOpenAiToolsDynamic(tools))
    assertEquals(elements.size, 1)

    val tool     = elements.head
    val function = field(tool, "function")
    val params   = field(function, "parameters")
    val props    = field(params, "properties")

    assertEquals(DynamicValues.renderText(field(tool, "type")), "function")
    assertEquals(DynamicValues.renderText(field(function, "name")), "search")
    assertEquals(DynamicValues.renderText(field(function, "description")), "Search documents")
    assertEquals(DynamicValues.renderText(field(params, "type")), "object")
    assertEquals(DynamicValues.renderText(field(field(props, "query"), "type")), "string")
    // The JSON types of upstream `Tool._parse_function` (`TypeRef.jsonType`).
    assertEquals(DynamicValues.renderText(field(field(props, "top_k"), "type")), "integer")
    assertEquals(DynamicValues.renderText(field(field(props, "tags"), "type")), "array")
    // Only `required = true` parameters appear in the JSON-schema `required` list.
    assertEquals(elementsOf(field(params, "required")).map(DynamicValues.renderText), Vector("query"))
  }

  test("toOpenAiToolsDynamic sends the input schema of a spec as the parameters, and keeps the other specs") {
    val schema = DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.record("query" -> DynamicValues.record("type" := "string")),
      "required"   := List("query")
    )
    val rendered = elementsOf(ToolSchemaBridge.toOpenAiToolsDynamic(Vector(
      ToolSpec("search", Some("Search documents"), inputSchema = Some(schema)),
      ToolSpec("lookup", parameters = Vector(ToolParameterSpec("id", TypeRef.int)))
    )))
    val parameters = rendered.map(tool => field(field(tool, "function"), "parameters"))
    assertEquals(parameters.head, schema: DynamicValue)
    assertEquals(
      parameters.last,
      DynamicValues.record(
        "type"       := "object",
        "properties" -> DynamicValues.record("id" -> DynamicValues.record("type" := "integer", "description" := "")),
        "required"   := List("id")
      ): DynamicValue
    )
  }
