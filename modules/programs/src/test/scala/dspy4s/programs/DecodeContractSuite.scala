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

  // ── ChainOfThought contract: the email_extraction shape (every step is a ChainOfThought) ──

  test("ChainOfThought decodes its augmented (reasoning + structured) output over a canned completion") {
    // A correct ChatAdapter completion for a CoT: the prepended `reasoning` block, then the base fields —
    // JSON array of objects for the record list and a currency-formatted optional number.
    val completion =
      """[[ ## reasoning ## ]]
        |The email names two tags and one purchased item.
        |
        |[[ ## tags ## ]]
        |["urgent", "billing"]
        |
        |[[ ## items ## ]]
        |[{"name": "MacBook Pro", "score": 0.92}]
        |
        |[[ ## amount ## ]]
        |$2,399.00
        |
        |[[ ## completed ## ]]""".stripMargin

    val out = DecodeFixtures.runWith(ChatAdapter(), completion) {
      ChainOfThought(signature).apply(TagInput("classify this")).map(_.output)
    }
    // The augmented output is a named tuple: reasoning prepended to the base TagOutput fields.
    assertEquals(out.map(_.reasoning), Right("The email names two tags and one purchased item."))
    assertEquals(out.map(_.tags),      Right(List("urgent", "billing")))
    assertEquals(out.map(_.items),     Right(List(TagItem("MacBook Pro", 0.92))))
    assertEquals(out.map(_.amount),    Right(Some(2399.0)))
  }

  test("ChainOfThought conveys the nested output schema to the LM (regression for CoT schema propagation)") {
    // ChainOfThought's augmented output Shape must surface the base output JSON schema, or the nested shape of
    // items: List[TagItem] never reaches the LM and it emits strings → "Expected a record at: .items.each".
    val prompt = DecodeFixtures.capturePrompt(ChatAdapter()) {
      ChainOfThought(signature).apply(TagInput("classify this"))
    }
    assert(
      prompt.contains("score"),
      s"the CoT inner predict should convey the nested field name 'score' (items: List[TagItem]) to the LM; " +
        s"prompt was:\n$prompt"
    )
  }
