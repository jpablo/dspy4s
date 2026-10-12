package dspy4s.core

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.History
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ToolCallResult
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

/** The contract types for tool call ids, tool results, and the conversation history (PORT_GAPS G-28; upstream
  * `dspy/adapters/types/tool.py` and `dspy/adapters/types/history.py`).
  */
class ToolCallHistorySuite extends FunSuite:

  private val search: ToolCall =
    ToolCall(name = "search", args = DynamicValues.record("query" := "cats"), id = Some("call_1"))

  private def roundTrip[A](value: A)(using schema: Schema[A]): Either[String, A] =
    schema.fromDynamicValue(schema.toDynamicValue(value)).left.map(_.toString)

  test("a tool call keeps its id through the schema") {
    assertEquals(roundTrip(search), Right(search))
    assertEquals(roundTrip(search.copy(id = None)), Right(search.copy(id = None)))
  }

  test("a tool call has a record form, with the id only when the call has one") {
    val args = DynamicValues.record("query" := "cats")
    assertEquals(search.toRecord, DynamicValues.record("id" := "call_1", "name" := "search", "args" -> args))
    assertEquals(search.copy(id = None).toRecord, DynamicValues.record("name" := "search", "args" -> args))
    assertEquals(ToolCall.fromRecord(search.toRecord), Some(search))
    assertEquals(ToolCall.fromRecord(search.copy(id = None).toRecord), Some(search.copy(id = None)))
  }

  test("ToolCall.fromRecord accepts the forms that upstream accepts") {
    val args = DynamicValues.record("query" := "cats")
    // A model that writes a tool call as text gives the name and the arguments only.
    assertEquals(
      ToolCall.fromRecord(DynamicValues.record("name" := "search", "arguments" -> args)),
      Some(search.copy(id = None))
    )
    // The provider form, with the arguments as a JSON string.
    val provider = DynamicValues.record(
      "id"       := "call_1",
      "type"     := "function",
      "function" -> DynamicValues.record("name" := "search", "arguments" := """{"query":"cats"}""")
    )
    assertEquals(ToolCall.fromRecord(provider), Some(search))
    // The Responses API names the id `call_id`.
    assertEquals(
      ToolCall.fromRecord(DynamicValues.record("call_id" := "call_1", "name" := "search", "args" -> args)),
      Some(search)
    )
    assertEquals(ToolCall.fromRecord(DynamicValues.record("args" -> args)), None)
  }

  test("ToolCall.fromDynamic reads a sequence of call records") {
    val value = DynamicValue.Sequence(zio.blocks.chunk.Chunk(search.toRecord, search.copy(id = None).toRecord))
    assertEquals(ToolCall.fromDynamic(value), Some(Vector(search, search.copy(id = None))))
    assertEquals(ToolCall.fromDynamic(DynamicValues.fromAny("search")), None)
    assertEquals(ToolCall.fromDynamic(DynamicValue.Sequence(zio.blocks.chunk.Chunk(DynamicValues.fromAny("x")))), None)
  }

  test("a tool result has a record form with the upstream keys") {
    val result = ToolCallResult(name = "search", value = DynamicValues.fromAny("cat"), callId = Some("call_1"))
    assertEquals(
      result.toRecord,
      DynamicValues.record("call_id" := "call_1", "name" := "search", "value" := "cat", "is_error" := false)
    )
    assertEquals(ToolCallResult.fromRecord(result.toRecord), Some(result))
    // `call_id` and `is_error` can be absent.
    assertEquals(
      ToolCallResult.fromRecord(DynamicValues.record("name" := "search", "value" := "cat")),
      Some(ToolCallResult(name = "search", value = DynamicValues.fromAny("cat")))
    )
    assertEquals(ToolCallResult.fromRecord(DynamicValues.record("name" := "search")), None)
  }

  test("ToolCallResult.forCalls gives each result the name and the id of its call") {
    val lookup = ToolCall(name = "lookup", args = DynamicValue.Record.empty, id = None)
    val values = Vector[DynamicValue](DynamicValues.fromAny("cat result"), DynamicValues.record("items" := 3))
    assertEquals(
      ToolCallResult.forCalls(Vector(search, lookup), values, isErrors = Vector(false, true)),
      Right(Vector(
        ToolCallResult(name = "search", value = values(0), callId = Some("call_1"), isError = false),
        ToolCallResult(name = "lookup", value = values(1), callId = None, isError = true)
      ))
    )
    assertEquals(
      ToolCallResult.forCalls(Vector(search), Vector(values(0))).map(_.map(_.isError)),
      Right(Vector(false))
    )
  }

  test("ToolCallResult.forCalls rejects lengths that do not agree") {
    val value = DynamicValues.fromAny("x")
    assert(ToolCallResult.forCalls(Vector(search), Vector.empty).left.exists(_.isInstanceOf[ValidationError]))
    assert(
      ToolCallResult.forCalls(Vector(search), Vector(value), isErrors = Vector(true, false))
        .left.exists(_.isInstanceOf[ValidationError])
    )
  }

  test("a history keeps its messages through the schema, and an input record gives it back") {
    val history = History.empty :+
      DynamicValues.record("question" := "What is 1+1?", "answer" := "2") :+
      DynamicValues.record("question" := "What is 2+2?", "answer" := "4")
    assertEquals(roundTrip(history), Right(history))

    val inputs =
      DynamicValues.record("question" := "What is 3+3?", "history" -> summon[Schema[History]].toDynamicValue(history))
    assertEquals(History.fromInput(inputs, "history"), Some(history))
    assertEquals(History.fromInput(inputs, "question"), None)
    assertEquals(History.fromInput(inputs, "absent"), None)
  }

  test("a history message holds the results of its tool calls under a reserved key") {
    val results =
      Vector(ToolCallResult(name = "search", value = DynamicValues.fromAny("cat result"), callId = Some("call_1")))
    val message = History.withToolCallResults(DynamicValues.record("question" := "Q1"), results)
    assertEquals(History.toolCallResults(message), results)
    assert(DynamicValues.recordGet(message, History.ToolCallResultsKey).isDefined)
    assertEquals(History.toolCallResults(DynamicValues.record("question" := "Q1")), Vector.empty[ToolCallResult])
  }

  test("the history type has a type reference and a display name") {
    assertEquals(TypeRef.fromToken("history"), TypeRef.history)
    assertEquals(TypeRef.fromToken("History"), TypeRef.history)
    assertEquals(TypeRef.history.pythonTypeName, Some("History"))
  }
