package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{DynamicValues, Example, FieldRole, RuntimeContext, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LmMode, LmRequest}
import dspy4s.typed.Signature
import zio.blocks.schema.{DynamicValue, Schema}
import munit.FunSuite

// Top-level fixtures (Mirror derivation + enum Schema must be top-level).
case class TicketInput(subject: String) derives Schema

enum TicketKind derives Schema:
  case order_confirmation, support_request, meeting_invitation

case class TicketOutput(kind: TicketKind) derives Schema

/** Reproduces the email_extraction runtime failure: an enum-typed output field's *allowed values* must
  * reach the LM, otherwise the model free-texts a human label ("Order Confirmation") that fails to decode
  * ("Unknown case 'Order Confirmation' at: .kind"). The root cause is that `Reflect.Variant` collapses to
  * `TypeRef.string` in the derived `SignatureLayout`, so the enum cases are lost before any adapter sees them.
  *
  * The `ChatAdapter` is the default adapter (installed by the examples' `Demo`), so this is the path the
  * user hit; the `JSONAdapter` already conveys enum constraints via the inlined JSON schema. */
class EnumConstraintSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private val signature = Signature.derived[TicketInput, TicketOutput](name = "Ticket")

  test("the derived layout preserves an enum field's allowed case names (root-cause guard)") {
    val kindField = signature.layout.outputFields.find(_.name == "kind").getOrElse(fail("no 'kind' output field"))
    assertEquals(kindField.role, FieldRole.Output)
    assertEquals(
      kindField.enumValues,
      Vector("order_confirmation", "support_request", "meeting_invitation"),
      "the enum's allowed case names must survive into the FieldSpec so adapters can convey them"
    )
  }

  test("ChatAdapter tells the LM the allowed values for an enum output field") {
    val invocation = AdapterInvocation(
      layout  = signature.layout,
      demos   = Vector.empty,
      inputs  = Example(values = rec("subject" := "Where is my order?"), inputKeys = Set("subject")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system = ChatAdapter().format(invocation).toOption.get.messages.head.text.get

    Vector("order_confirmation", "support_request", "meeting_invitation").foreach { v =>
      assert(
        system.contains(v),
        s"the ChatAdapter system prompt should list the allowed enum value '$v' so the LM emits a decodable " +
          s"case rather than a free-text label. System prompt was:\n$system"
      )
    }
  }
