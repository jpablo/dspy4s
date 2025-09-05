package dspy

import munit.FunSuite
import dspy.signatures._

class SignatureSuite extends FunSuite {
  test("outputNames lists outputs in order") {
    val sig = Signature(
      inputs = List(Field("question", "q")),
      outputs = List(Field("answer", "a"), Field("confidence", "c"))
    )
    assertEquals(Signature.outputNames(sig), List("answer", "confidence"))
  }
}
