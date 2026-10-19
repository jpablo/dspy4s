package dspy4s.programs

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.internal.ToolText
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The text of a tool in the prompts of `ReAct` and `RLM`: the port form of upstream `Tool.__str__` and `Tool.args`
  * (`dspy/adapters/types/tool.py`, decision D3). The arguments are JSON text.
  */
class ToolTextSuite extends FunSuite:

  private final class TestTool(
      override val name       : String,
      override val description: String,
      override val argSchema  : Vector[(String, TypeRef)],
      override val inputSchema: Option[DynamicValue.Record] = None
  ) extends ToolFunction:
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      Right(ToolFunction.result("ok"))

  private val typed = new TestTool(
    "typed",
    "One line.\nTwo lines.",
    Vector(
      "s" -> TypeRef.string,
      "i" -> TypeRef.int,
      "d" -> TypeRef.double,
      "b" -> TypeRef.bool,
      "l" -> TypeRef.list,
      "j" -> TypeRef.json,
      "h" -> TypeRef.history
    )
  )

  private val schema: DynamicValue.Record = DynamicValues.record(
    "type"       := "object",
    "properties" -> DynamicValues.record(
      "query" -> DynamicValues.record("type" := "string", "description" := "The query."),
      "limit" -> DynamicValues.record("description" := "No type.")
    ),
    "required" -> DynamicValue.Sequence(zio.blocks.chunk.Chunk.single(DynamicValues.fromAny("query")))
  )

  test("each wire type gives its JSON type, and a type with no JSON type gives an empty schema") {
    assertEquals(
      ToolText.describe(typed),
      "typed, whose description is <desc>One line.  Two lines.</desc>. It takes arguments " +
        """{"s": {"type": "string"}, "i": {"type": "integer"}, "d": {"type": "number"}, "b": {"type": "boolean"}, """ +
        """"l": {"type": "array"}, "j": {"type": "object"}, "h": {}}."""
    )
  }

  test("a tool with no description has no description part") {
    assertEquals(ToolText.describe(new TestTool("bare", "", Vector.empty)), "bare. It takes arguments {}.")
  }

  test("the arguments are the properties of the input schema when the tool has one") {
    val tool = new TestTool("search", "Search.", Vector("query" -> TypeRef.string), Some(schema))
    assertEquals(
      ToolText.describe(tool),
      "search, whose description is <desc>Search.</desc>. It takes arguments " +
        """{"query": {"type": "string", "description": "The query."}, "limit": {"description": "No type."}}."""
    )
  }

  test("the RLM parameters show the JSON type, and Any for an argument with no type") {
    assertEquals(
      ToolText.parameters(new TestTool("search", "", Vector.empty, Some(schema))),
      "query: string, limit: Any"
    )
    assertEquals(
      ToolText.parameters(new TestTool("t", "", Vector("n" -> TypeRef.int, "h" -> TypeRef.history))),
      "n: integer, h: Any"
    )
  }
