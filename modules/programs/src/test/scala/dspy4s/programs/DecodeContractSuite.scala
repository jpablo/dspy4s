package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.support.DecodeFixtures
import dspy4s.typed.Signature
import munit.FunSuite

/** Demonstrates the offline decode-test harness on a representative structured signature. These tests are
  * deterministic, need no API key, and exercise the same list / record / option / lenient-number decode paths
  * that historically broke. Reuses the `TagInput` / `TagItem` / `TagOutput` fixtures from `StructuredOutputSuite`
  * (same package). */
class DecodeContractSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private val signature = Signature.derived[TagInput, TagOutput](name = "TagContract")

  test("decodeCompletion runs the real ChatAdapter + typed decode over a canned completion") {
    // A CORRECT ChatAdapter completion: marker-framed, JSON array of objects for the record list, and a
    // currency-formatted number for the optional field (proves the end-to-end lenient numeric path).
    val completion =
      """[[ ## tags ## ]]
        |["urgent", "billing"]
        |
        |[[ ## items ## ]]
        |[{"name": "MacBook Pro", "score": 0.92}]
        |
        |[[ ## amount ## ]]
        |$2,399.00
        |
        |[[ ## completed ## ]]""".stripMargin

    val decoded = DecodeFixtures.decodeCompletion(signature, ChatAdapter(), TagInput("classify this"), completion)
    assertEquals(
      decoded,
      Right(TagOutput(
        tags   = List("urgent", "billing"),
        items  = List(TagItem("MacBook Pro", 0.92)),
        amount = Some(2399.0)
      ))
    )
  }

  test("roundTripOutput proves a sample TagOutput survives encode -> decode") {
    val sample = TagOutput(
      tags   = List("a", "b"),
      items  = List(TagItem("widget", 0.5), TagItem("gadget", 0.75)),
      amount = Some(12.34)
    )
    assertEquals(DecodeFixtures.roundTripOutput(signature, sample), Right(sample))
  }
