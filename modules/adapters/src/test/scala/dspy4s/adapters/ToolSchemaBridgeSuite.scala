package dspy4s.adapters

import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.ToolCall
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

  test("toOpenAiTools renders function schema with required fields") {
    val tools = Vector(
      ToolSpec(
        name = "search",
        description = Some("Search documents"),
        parameters = Vector(
          ToolParameterSpec(name = "query", typeRef = TypeRef.string, description = Some("Search query"), required = true),
          ToolParameterSpec(name = "top_k", typeRef = TypeRef.int, description = Some("Result count"), required = false)
        )
      )
    )

    val rendered = ToolSchemaBridge.toOpenAiTools(tools)

    assertEquals(rendered.size, 1)
    val function = rendered.head("function").asInstanceOf[Map[String, Any]]
    val parameters = function("parameters").asInstanceOf[Map[String, Any]]
    val properties = parameters("properties").asInstanceOf[Map[String, Map[String, String]]]
    val required = parameters("required").asInstanceOf[Vector[String]]

    assertEquals(function("name"), "search")
    assertEquals(properties("query")("type"), "string")
    assertEquals(properties("top_k")("type"), "number")
    assertEquals(required, Vector("query"))
  }

  test("toOpenAiToolsDynamic builds the OpenAI tools array as a DynamicValue for the requestOptions seam") {
    val tools = Vector(
      ToolSpec(
        name = "search",
        description = Some("Search documents"),
        parameters = Vector(
          ToolParameterSpec(name = "query", typeRef = TypeRef.string, description = Some("Search query"), required = true),
          ToolParameterSpec(name = "top_k", typeRef = TypeRef.int, description = Some("Result count"), required = false)
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
    assertEquals(DynamicValues.renderText(field(field(props, "top_k"), "type")), "number")
    // Only `required = true` parameters appear in the JSON-schema `required` list.
    assertEquals(elementsOf(field(params, "required")).map(DynamicValues.renderText), Vector("query"))
  }

  test("fromOutput maps lm tool calls to adapter tool call data") {
    val output = LmOutput(
      text = "",
      toolCalls = Vector(
        ToolCall(name = "search", args = DynamicValues.recordFromEntries(Seq("query" := "capital of belgium"))),
        ToolCall(name = "lookup", args = DynamicValues.recordFromEntries(Seq("id" := 42)))
      )
    )

    val calls = ToolSchemaBridge.fromOutput(output)

    assertEquals(calls.map(_.name), Vector("search", "lookup"))
    assertEquals(DynamicValues.recordToMap(calls.head.args)("query"), "capital of belgium": Any)
    // Int stays Int through the DynamicValue round-trip (the old String-flattening cache would lose this).
    assertEquals(DynamicValues.recordToMap(calls.last.args)("id"), 42: Any)
  }
