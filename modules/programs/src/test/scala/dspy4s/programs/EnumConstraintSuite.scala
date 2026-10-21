package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{DspyError, DynamicValues, ParseError, RuntimeContext, :=}
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.{DynamicValue, Schema}
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

// Top-level fixtures (Mirror derivation + enum Schema must be top-level).
case class TicketInput(subject: String) derives Schema

enum TicketKind derives Schema:
  case order_confirmation, support_request, meeting_invitation

case class TicketOutput(kind: TicketKind) derives Schema

/** Reproduces the email_extraction runtime failure: an enum-valued output field's *allowed values* must reach the LM,
  * otherwise the model free-texts a human label ("Order Confirmation") that fails to decode ("Unknown case 'Order
  * Confirmation' at: .kind"). The root cause is that `Reflect.Variant` collapses to `TypeRef.string` in the derived
  * `SignatureLayout`, so the enum cases are lost before any adapter sees them.
  *
  * The `ChatAdapter` is the default adapter (installed by the examples' `Demo`), so this is the path the user hit; the
  * `JSONAdapter` already conveys enum constraints via the inlined JSON schema.
  */
class EnumConstraintSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private val signature = Signature.derived[TicketInput, TicketOutput](name = "Ticket")

  test("the derived layout preserves an enum field's allowed case names (root-cause guard)") {
    val kindField = signature.layout.outputFields.find(_.name == "kind").getOrElse(fail("no 'kind' output field"))
    assert(!signature.layout.inputFields.exists(_.name == "kind"))
    assertEquals(
      kindField.enumValues,
      Vector("order_confirmation", "support_request", "meeting_invitation"),
      "the enum's allowed case names must survive into the FieldSpec so adapters can convey them"
    )
  }

  /** Gives the replies in order and repeats the last one. */
  private final class ScriptedLm(replies: String*) extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "scripted"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = calls.getAndIncrement()
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(math.min(index, replies.size - 1))))))

  private def classify(lm: LanguageModel): Either[DspyError, TicketOutput] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      Predict(signature)(TicketInput("Where is my order?")).map(_.output)
    }

  test("a typed enum output takes a quoted case name, after the normalization of upstream parse_value") {
    val lm = new ScriptedLm("[[ ## kind ## ]]\n'support_request'\n\n[[ ## completed ## ]]")
    assertEquals(classify(lm), Right(TicketOutput(TicketKind.support_request)))
    assertEquals(lm.calls.get(), 1)
  }

  test("a typed enum output outside the case names is a parse error, so the JSON fallback runs") {
    // Decision D7: the parser rejects a value outside the enum values, as upstream `parse_value` rejects it for an
    // `Enum` or a `Literal`. The chat adapter then tries the JSON format (upstream `ChatAdapter.__call__`).
    val lm = new ScriptedLm(
      "[[ ## kind ## ]]\nOrder Confirmation\n\n[[ ## completed ## ]]",
      """{"kind": "order_confirmation"}"""
    )
    assertEquals(classify(lm), Right(TicketOutput(TicketKind.order_confirmation)))
    assertEquals(lm.calls.get(), 2)
  }

  test("a typed enum output outside the case names in the two formats is a parse error") {
    val lm = new ScriptedLm(
      "[[ ## kind ## ]]\nOrder Confirmation\n\n[[ ## completed ## ]]",
      """{"kind": "Order Confirmation"}"""
    )
    val result = classify(lm)
    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    assertEquals(lm.calls.get(), 2)
  }

  test("ChatAdapter tells the LM the allowed values for an enum output field") {
    val invocation = AdapterInvocation(
      layout = signature.layout,
      demos = Vector.empty,
      inputs = Example(values = rec("subject" := "Where is my order?"), inputKeys = Set("subject")),
      request = LmRequest(model = "openai/test", mode = LmMode.Chat)
    )
    given RuntimeContext = RuntimeEnvironment.current
    val system           = ChatAdapter().format(invocation).toOption.get.messages.head.text.get

    Vector("order_confirmation", "support_request", "meeting_invitation").foreach { v =>
      assert(
        system.contains(v),
        s"the ChatAdapter system prompt should list the allowed enum value '$v' so the LM emits a decodable " +
          s"case rather than a free-text label. System prompt was:\n$system"
      )
    }
  }
