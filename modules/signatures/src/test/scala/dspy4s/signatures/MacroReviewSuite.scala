package dspy4s.signatures

import dspy4s.core.contracts.{:=, DynamicValues, History}
import dspy4s.core.decisions.{Choice, Noul, Score}
import munit.FunSuite

trait MrBaseSpec extends Spec:
  def question: InputField[String]

trait MrQaSpec extends MrBaseSpec:
  def answer: OutputField[String]

trait MrContextSpec extends MrBaseSpec:
  def context: InputField[String]

trait MrRagSpec extends MrContextSpec:
  def answer: OutputField[String]
  def question: InputField[String]
  def note: OutputField[Option[String]]

enum MrColor:
  case Red, Blue

/** The bugs of the code review of the macro group: each test failed before its fix. */
class MacroReviewSuite extends FunSuite:

  test("fromString keeps the enum values of a Literal field and the optional flag of an Optional field") {
    val sig       = Signature.fromString("q -> sentiment: Literal['pos', 'neg'], note: Optional[str]")
    val sentiment = sig.layout.outputFields.find(_.name == "sentiment")
    assertEquals(sentiment.map(_.enumValues), Some(Vector("pos", "neg")))
    assert(sig.layout.outputFields.find(_.name == "note").exists(_.optional), "note must be optional")
    assert(
      sig.outputShape.decode(DynamicValues.record("sentiment" := "pos")).isRight,
      "a record with no optional note must decode"
    )
  }

  test("a Spec gets the fields of its parent Spec trait") {
    val sig = Signature.of[MrQaSpec]
    assertEquals(sig.layout.inputFields.map(_.name), Vector("question"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("answer"))
  }

  test("a decision return type is one result output") {
    val sig = Signature.fromType[(text: String) => Choice[MrColor]]
    assertEquals(sig.layout.outputFields.map(_.name), Vector("result"))
  }

  test("an input and an output with the same name are a compile error") {
    assert(
      compileErrors("Signature.fromType[(answer: String) => (answer: Int)]").nonEmpty,
      "the clash must be a compile error, not an exception at run time"
    )
  }

  test("the fields of each parent come first, and a field that a subtrait declares again is one field") {
    val sig = Signature.of[MrRagSpec]
    assertEquals(sig.layout.inputFields.map(_.name), Vector("question", "context"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("answer", "note"))
  }

  test("a Noul, a Score, and a History return type are one result output") {
    assertEquals(Signature.fromType[(text: String) => Noul].layout.outputFields.map(_.name), Vector("result"))
    assertEquals(Signature.fromType[(text: String) => Score[MrColor]].layout.outputFields.map(_.name), Vector("result"))
    assertEquals(Signature.fromType[(text: String) => History].layout.outputFields.map(_.name), Vector("result"))
  }

  test("the compile error of a clash names the field, also for Signature.from and a result output") {
    val fromTypeErrors = compileErrors("Signature.fromType[(answer: String) => (answer: Int)]")
    assert(fromTypeErrors.contains("duplicate field names: answer"), fromTypeErrors)
    val fromErrors = compileErrors("""
      def clash(result: String): Int = result.length
      Signature.from(clash)
    """)
    assert(fromErrors.contains("duplicate field names: result"), fromErrors)
  }
