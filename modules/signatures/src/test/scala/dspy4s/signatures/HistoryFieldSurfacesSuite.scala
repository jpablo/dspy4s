package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, History, TypeRef}
import munit.FunSuite
import zio.blocks.schema.Schema

// A record with the field name of `History` and a different type.
final case class HfLog(messages: Vector[String]) derives Schema
final case class HfInput(question: String, log: HfLog) derives Schema
final case class HfOutput(answer: String) derives Schema

// A declaration for `Signature.from`. The method is never called.
def hfAnswer(question: String, history: History): String = s"$question (${history.messages.size} earlier turns)"

/** Each surface that makes a signature gives a `History` field the history type reference (PORT_GAPS G-28).
  * `HistorySignatureSuite` covers `Signature.derived` and a `Spec` trait.
  */
class HistoryFieldSurfacesSuite extends FunSuite:

  private val oneTurn: History = History.empty :+ DynamicValues.record("question" := "What is 1+1?", "answer" := "2")

  private val expectedInputs: Vector[(String, TypeRef)] =
    Vector("question" -> TypeRef.string, "history" -> TypeRef.history)

  test("the builder gives a History field the history type reference") {
    val layout =
      Signature.builder("QA").input[String]("question").input[History]("history").output[String]("answer").build
    assertEquals(layout.inputFields.map(f => f.name -> f.typeRef), expectedInputs)
  }

  test("the string DSL gives a History field the History type and the history type reference") {
    val signature = Signature.fromString("question, history: History -> answer")
    assertEquals(signature.layout.inputFields.map(f => f.name -> f.typeRef), expectedInputs)
    // The input is a named tuple with a `History` element.
    val encoded = signature.inputShape.encode((question = "What is 2+2?", history = oneTurn))
    assertEquals(History.fromInput(encoded, "history"), Some(oneTurn))
    assertEquals(signature.inputShape.decode(encoded).map(_.history), Right(oneTurn))
  }

  test("the string DSL does not accept History as the type of an output field") {
    val errors = compileErrors("""Signature.fromString("question -> answer: History")""")
    assert(errors.contains("History for an input field"), errors)
  }

  test("the runtime string DSL gives a History field the history type reference") {
    val signature = Signature.fromStringDynamic("question, history: History -> answer").toOption.get
    assertEquals(signature.layout.inputFields.map(f => f.name -> f.typeRef), expectedInputs)
  }

  test("a function type gives a History parameter the history type reference") {
    val signature = Signature.fromType[(question: String, history: History) => String]
    assertEquals(signature.layout.inputFields.map(f => f.name -> f.typeRef), expectedInputs)
  }

  test("a method gives a History parameter the history type reference") {
    val signature = Signature.from(hfAnswer)
    assertEquals(signature.layout.inputFields.map(f => f.name -> f.typeRef), expectedInputs)
  }

  test("a record that is not History keeps the json type reference") {
    val signature = Signature.derived[HfInput, HfOutput]("QA")
    assertEquals(signature.layout.inputFields.find(_.name == "log").map(_.typeRef), Some(TypeRef.json))
  }
