package dspy

import munit.FunSuite
import dspy.signatures._

class SignatureUtilsSuite extends FunSuite {
  test("missingInputKeys reports absent inputs in order") {
    val sig = Signature(
      inputs = List(Field("a", ""), Field("b", ""), Field("c", "")),
      outputs = Nil
    )
    val miss = Signature.missingInputKeys(sig, Set("a", "c"))
    assertEquals(miss, List("b"))
  }
}
