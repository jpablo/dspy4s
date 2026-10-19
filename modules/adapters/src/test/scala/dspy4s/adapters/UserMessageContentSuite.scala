package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, RuntimeContext, RuntimeError, SignatureLayout}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest, LmResponse}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The user message content of each adapter, with no output reminder (upstream `format_user_message_content` with
  * `main_request=False`). `ReAct` writes its trajectory with this text. The expected texts are the upstream texts for
  * the signature `thought_0, tool_name_0, tool_args_0, observation_0 -> x`, with JSON text for the tool arguments
  * (decision D3).
  */
class UserMessageContentSuite extends FunSuite:

  private final class StubLm extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused"))

  private val names: Vector[String] = Vector("thought_0", "tool_name_0", "tool_args_0", "observation_0")

  private val layout: SignatureLayout =
    SignatureLayout.of("StringSignature", names.map(FieldSpec(_)), Vector(FieldSpec("x")), None)

  private val values: DynamicValue.Record = DynamicValues.record(
    "thought_0"     := "Look.",
    "tool_name_0"   := "search",
    "tool_args_0"   -> DynamicValues.record("query" := "cats"),
    "observation_0" := "3 results"
  )

  private val empty: SignatureLayout = SignatureLayout.of("StringSignature", Vector.empty, Vector(FieldSpec("x")), None)

  private val chatText: String =
    "[[ ## thought_0 ## ]]\nLook.\n\n[[ ## tool_name_0 ## ]]\nsearch\n\n[[ ## tool_args_0 ## ]]\n" +
      "{\"query\": \"cats\"}\n\n[[ ## observation_0 ## ]]\n3 results"

  private def contentOf(adapter: Adapter): String = adapter.formatUserMessageContent(layout, values)

  test("the chat and JSON adapters show the marker form") {
    assertEquals(contentOf(ChatAdapter()), chatText)
    assertEquals(contentOf(JSONAdapter()), chatText)
  }

  test("the XML adapter shows the tag form") {
    assertEquals(
      contentOf(XMLAdapter()),
      "<thought_0>\nLook.\n</thought_0>\n\n<tool_name_0>\nsearch\n</tool_name_0>\n\n<tool_args_0>\n" +
        "{\"query\": \"cats\"}\n</tool_args_0>\n\n<observation_0>\n3 results\n</observation_0>"
    )
  }

  test("the TwoStep adapter shows each field as name: value") {
    assertEquals(
      contentOf(TwoStepAdapter(new StubLm)),
      "thought_0: Look.\n\ntool_name_0: search\n\ntool_args_0: {\"query\": \"cats\"}\n\nobservation_0: 3 results"
    )
  }

  test("a signature with no input fields gives an empty text") {
    Vector(ChatAdapter(), JSONAdapter(), XMLAdapter(), TwoStepAdapter(new StubLm)).foreach { adapter =>
      assertEquals(adapter.formatUserMessageContent(empty, DynamicValue.Record.empty), "", adapter.name)
    }
  }
