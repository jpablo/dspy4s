package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, History, ToolCall, TypeRef}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

// Top-level fixtures (the derivations need them outside the suite).
final case class HsInput(question: String, history: History) derives Schema
final case class HsOutput(answer: String) derives Schema
final case class HsPlan(toolCalls: Vector[ToolCall]) derives Schema
final case class HsNote(title: String, detail: Option[String]) derives Schema
final case class HsNotes(notes: Vector[HsNote], summary: Option[String]) derives Schema

trait HsSpec extends Spec:
  def question: InputField[String]
  def history: InputField[History]
  def answer: OutputField[String]

/** A `History` input field of a signature, and the typed decode of a tool call with no id (PORT_GAPS G-28). */
class HistorySignatureSuite extends FunSuite:

  private val twoTurns: History = History.empty :+
    DynamicValues.record("question" := "What is 1+1?", "answer" := "2") :+
    DynamicValues.record("question" := "What is 2+2?", "answer" := "4")

  test("a History field of a derived signature has the history type reference") {
    val signature = Signature.derived[HsInput, HsOutput]("QA")
    assertEquals(
      signature.layout.inputFields.map(f => f.name -> f.typeRef),
      Vector("question" -> TypeRef.string, "history" -> TypeRef.history)
    )
  }

  test("a History field of a spec trait has the history type reference") {
    val signature = Signature.of[HsSpec]
    assertEquals(signature.layout.inputFields.find(_.name == "history").map(_.typeRef), Some(TypeRef.history))
  }

  test("the input shape encodes a History value that History.fromInput reads again") {
    val signature = Signature.derived[HsInput, HsOutput]("QA")
    val encoded   = signature.inputShape.encode(HsInput("What is 3+3?", twoTurns))
    assertEquals(History.fromInput(encoded, "history"), Some(twoTurns))
    assertEquals(signature.inputShape.decode(encoded), Right(HsInput("What is 3+3?", twoTurns)))
  }

  test("the typed decode reads a tool call record that has no id") {
    val shape  = Shape.derived[HsPlan]
    val search = ToolCall(name = "search", args = DynamicValues.record("query" := "cats"))
    // The record form of a call with no id is `{name, args}`: the form that a model writes as text.
    val withoutId = DynamicValues.record("toolCalls" -> DynamicValue.Sequence(Chunk(search.toRecord)))
    assertEquals(shape.decode(withoutId), Right(HsPlan(Vector(search))))

    val withId =
      DynamicValues.record("toolCalls" -> DynamicValue.Sequence(Chunk(search.copy(id = Some("call_1")).toRecord)))
    assertEquals(shape.decode(withId), Right(HsPlan(Vector(search.copy(id = Some("call_1"))))))
  }

  test("the typed decode gives None for an optional field that is absent from a nested record") {
    val shape  = Shape.derived[HsNotes]
    val record = DynamicValues.record(
      "notes"   -> DynamicValue.Sequence(Chunk(DynamicValues.record("title" := "first"))),
      "summary" := "done"
    )
    assertEquals(shape.decode(record), Right(HsNotes(Vector(HsNote("first", None)), Some("done"))))
  }
