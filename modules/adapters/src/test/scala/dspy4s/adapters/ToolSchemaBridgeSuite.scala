package dspy4s.adapters

import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.ToolCall
import munit.FunSuite

class ToolSchemaBridgeSuite extends FunSuite:
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

  test("fromOutput maps lm tool calls to adapter tool call data") {
    val output = LmOutput(
      text = "",
      toolCalls = Vector(
        ToolCall(name = "search", args = Map("query" -> "capital of belgium")),
        ToolCall(name = "lookup", args = Map("id" -> 42))
      )
    )

    val calls = ToolSchemaBridge.fromOutput(output)

    assertEquals(calls.map(_.name), Vector("search", "lookup"))
    assertEquals(calls.head.args("query"), "capital of belgium")
    assertEquals(calls.last.args("id"), 42)
  }
