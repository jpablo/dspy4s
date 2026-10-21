package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.History
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ToolCallResult
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

/** The rules of the history replay that `ConversationHistorySuite` does not cover (PORT_GAPS G-28): the exact text of a
  * tool turn, a message that lacks fields, a message with more keys, a demo with a history, and the adapters that do
  * not replay.
  */
class ConversationHistoryRulesSuite extends FunSuite:

  private final class StubLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private def layoutOf(inputs: Vector[FieldSpec], outputs: Vector[FieldSpec]): SignatureLayout =
    SignatureLayout.create(name = "QA", inputFields = inputs, outputFields = outputs).toOption.get

  private val historyField: FieldSpec = FieldSpec("history", typeRef = TypeRef.history)

  private val qaWithHistory: SignatureLayout =
    layoutOf(Vector(FieldSpec("question"), historyField), Vector(FieldSpec("answer")))
  private val qaPlain: SignatureLayout = layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("answer")))

  /** `question, history -> answer, note`. */
  private val qaNoteWithHistory: SignatureLayout =
    layoutOf(Vector(FieldSpec("question"), historyField), Vector(FieldSpec("answer"), FieldSpec("note")))

  /** `question, history -> next_thought, summary, tool_calls`. */
  private val agentWithHistory: SignatureLayout = layoutOf(
    Vector(FieldSpec("question"), historyField),
    Vector(FieldSpec("next_thought"), FieldSpec("summary"), FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))
  )

  private val search: ToolCall =
    ToolCall(name = "search", args = DynamicValues.record("query" := "cats"), id = Some("call_1"))
  private val lookup: ToolCall =
    ToolCall(name = "lookup", args = DynamicValues.record("key" := "dogs"), id = Some("call_2"))

  private val chat: Adapter = ChatAdapter()
  private val json: Adapter = JSONAdapter()
  private val xml: Adapter  = XMLAdapter()

  private val textAdapters: Vector[(String, Adapter)] =
    Vector("ChatAdapter" -> chat, "JSONAdapter" -> json, "XMLAdapter" -> xml)

  private val nativeAdapters: Vector[(String, Adapter)] = Vector(
    "ChatAdapter" -> ChatAdapter(useNativeFunctionCalling = true),
    "JSONAdapter" -> JSONAdapter(useNativeFunctionCalling = true)
  )

  private val oneTurn: History = History.empty :+ DynamicValues.record("question" := "What is 1+1?", "answer" := "2")

  private val searchResult: Vector[ToolCallResult] =
    ToolCallResult.forCalls(Vector(search), Vector(DynamicValues.fromAny("cat result"))).toOption.get

  /** A history message with one tool call and its result. */
  private val toolTurn: DynamicValue.Record = History.withToolCallResults(
    DynamicValues.record(
      "question"     := "Q1",
      "next_thought" := "I should search.",
      "tool_calls"   -> NativeFunctionCalling.encodeToolCalls(Vector(search))
    ),
    searchResult
  )

  // The object forms of upstream `ToolCalls` and `ToolCallResults`, in the JSON text of `json.dumps`.
  private val callsText: String   = """{"tool_calls": [{"name": "search", "args": {"query": "cats"}}]}"""
  private val resultsText: String =
    """{"tool_call_results": [{"call_id": "call_1", "name": "search", "value": "cat result", "is_error": false}]}"""

  private def historyValue(history: History): DynamicValue = summon[Schema[History]].toDynamicValue(history)

  private def messagesOf(
      adapter        : Adapter,
      layout         : SignatureLayout,
      inputs         : DynamicValue.Record,
      demos          : Vector[Example] = Vector.empty,
      functionCalling: Boolean         = true
  ): Vector[Message] =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(functionCalling)))
    val invocation       = AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = inputs, inputKeys = DynamicValues.recordKeys(inputs).toSet),
      request = LmRequest(model = "stub")
    )
    adapter.format(invocation) match
      case Right(prompt) => prompt.messages
      case Left(error)   => fail(s"format failed: $error")

  private def withHistory(question: String, history: History): DynamicValue.Record =
    DynamicValues.record("question" := question, "history" -> historyValue(history))

  private def roles(messages: Vector[Message]): Vector[MessageRole] = messages.map(_.role)

  private def textOf(message: Message): String = message.text.getOrElse("")

  import MessageRole.{Assistant, System, Tool, User}

  // ── Text turns ──────────────────────────────────────────────────────────────────────────────────────────────

  textAdapters.foreach { (name, adapter) =>
    test(s"$name: the messages of one call are the start of the messages of the next call") {
      // Upstream `test_history_replay_preserves_previous_request_prefix`: a provider can reuse its prompt cache.
      val turns = Vector("What is 3+4?" -> "7", "Double that?" -> "14", "Subtract five?" -> "9")
      turns.foldLeft((History.empty, Option.empty[Vector[Message]])) { case ((history, previous), (question, answer)) =>
        val messages = messagesOf(adapter, qaWithHistory, withHistory(question, history))
        previous.foreach(earlier => assertEquals(messages.take(earlier.size), earlier))
        (history :+ DynamicValues.record("question" := question, "answer" := answer), Some(messages))
      }
    }

    test(s"$name: a message with input fields only gives a user turn and an assistant turn") {
      // Upstream `format_conversation_history` formats the assistant turn of each message with the full signature.
      val history  = History.empty :+ DynamicValues.record("question" := "What is 1+1?")
      val messages = messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", history))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assertEquals(
        textOf(messages(1)),
        textOf(messagesOf(adapter, qaPlain, DynamicValues.record("question" := "What is 1+1?")).last)
      )
    }

    test(s"$name: a key of a message that is not a field of the signature is ignored") {
      val more = History.empty :+
        DynamicValues.record("question" := "What is 1+1?", "note" := "an aside", "answer" := "2", "rating" := 5)
      assertEquals(
        messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", more)),
        messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", oneTurn))
      )
    }

    test(s"$name: a field with a null value is absent from the replayed turn") {
      val history  = History.empty :+ DynamicValues.record("question" -> DynamicValue.Null, "answer" := "2")
      val messages = messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", history))
      assertEquals(roles(messages), Vector(System, Assistant, User))
      assert(!textOf(messages(1)).contains("null"), textOf(messages(1)))
    }

    test(s"$name: a replayed turn does not use the default value of a field that the message lacks") {
      val layout = layoutOf(
        Vector(
          FieldSpec("question"),
          FieldSpec("context", defaultValue = Some(DynamicValues.fromAny("the default context"))),
          historyField
        ),
        Vector(
          FieldSpec("answer"),
          FieldSpec("source", defaultValue = Some(DynamicValues.fromAny("the default source")))
        )
      )
      val messages = messagesOf(adapter, layout, withHistory("What is 3+3?", oneTurn))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assert(textOf(messages(1)).contains("What is 1+1?"), textOf(messages(1)))
      assert(!textOf(messages(1)).contains("the default context"), textOf(messages(1)))
      assert(!textOf(messages(2)).contains("the default source"), textOf(messages(2)))
    }

    test(s"$name: a history value with a different shape gives no earlier turns and is not shown") {
      val inputs   = DynamicValues.record("question" := "What is 3+3?", "history" := "not a history")
      val messages = messagesOf(adapter, qaWithHistory, inputs)
      assertEquals(roles(messages), Vector(System, User))
      assertEquals(
        textOf(messages.last),
        textOf(messagesOf(adapter, qaPlain, DynamicValues.record("question" := "What is 3+3?")).last)
      )
    }

    test(s"$name: a demo shows its history value as a field and does not replay it") {
      val demo = Example(
        values =
          DynamicValues.record("question" := "What is 2+2?", "history" -> historyValue(oneTurn), "answer" := "4"),
        inputKeys = Set("question", "history")
      )
      val messages =
        messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", History.empty), demos = Vector(demo))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      // The demo user turn holds the two input fields. The earlier question is part of the history value.
      val demoUser = textOf(messages(1))
      assert(demoUser.contains("What is 2+2?") && demoUser.contains("What is 1+1?"), demoUser)
      assert(demoUser.contains("messages"), demoUser)
      assert(!textOf(messages(3)).contains("What is 1+1?"), textOf(messages(3)))
    }

    test(s"$name (text): a message with tool calls and no results gives no results turn") {
      val message  = DynamicValues.recordFilterKeys(toolTurn, _ != History.ToolCallResultsKey)
      val messages = messagesOf(adapter, agentWithHistory, withHistory("Q2", History.empty :+ message))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assert(textOf(messages(2)).contains("\"name\": \"search\""), textOf(messages(2)))
      assert(textOf(messages(2)).contains("\"query\": \"cats\""), textOf(messages(2)))
    }
  }

  // The texts that upstream `Adapter.format` gives for the history [{"question": "q1"}, {"question": "q2", "answer":
  // "a2"}] with the signature `question, history -> answer, note`: each assistant turn has each output field, with
  // `None` (`null` in JSON) for a field that the message does not have.
  Vector(
    (
      "ChatAdapter",
      chat,
      "[[ ## answer ## ]]\nNone\n\n[[ ## note ## ]]\nNone\n\n[[ ## completed ## ]]\n",
      "[[ ## answer ## ]]\na2\n\n[[ ## note ## ]]\nNone\n\n[[ ## completed ## ]]\n"
    ),
    (
      "JSONAdapter",
      json,
      "{\n  \"answer\": null,\n  \"note\": null\n}",
      "{\n  \"answer\": \"a2\",\n  \"note\": null\n}"
    ),
    (
      "XMLAdapter",
      xml,
      "<answer>\nNone\n</answer>\n\n<note>\nNone\n</note>",
      "<answer>\na2\n</answer>\n\n<note>\nNone\n</note>"
    )
  ).foreach { (name, adapter, inputsOnly, oneOutput) =>
    test(s"$name (text): the exact text of the assistant turns of partial messages, as upstream") {
      val history = History.empty :+ DynamicValues.record("question" := "q1") :+
        DynamicValues.record("question" := "q2", "answer" := "a2")
      val messages = messagesOf(adapter, qaNoteWithHistory, withHistory("q3", history))
      assertEquals(roles(messages), Vector(System, User, Assistant, User, Assistant, User))
      assertEquals(textOf(messages(2)), inputsOnly)
      assertEquals(textOf(messages(4)), oneOutput)
    }
  }

  test("ChatAdapter (text): the exact text of a tool turn") {
    val messages = messagesOf(chat, agentWithHistory, withHistory("Q2", History.empty :+ toolTurn))
    assertEquals(roles(messages), Vector(System, User, Assistant, User, User))
    // The assistant turn has each output field: `summary`, which the message does not have, is `None`. A call has no
    // id as text.
    assertEquals(
      textOf(messages(2)),
      s"[[ ## next_thought ## ]]\nI should search.\n\n[[ ## summary ## ]]\nNone\n\n[[ ## tool_calls ## ]]\n" +
        s"$callsText\n\n[[ ## completed ## ]]\n"
    )
    assertEquals(textOf(messages(3)), s"[[ ## tool_call_results ## ]]\n$resultsText")
  }

  test("JSONAdapter (text): the exact text of a tool turn") {
    val messages = messagesOf(json, agentWithHistory, withHistory("Q2", History.empty :+ toolTurn))
    assertEquals(roles(messages), Vector(System, User, Assistant, User, User))
    // Upstream `JSONAdapter.format_assistant_message_content`: `json.dumps(..., indent=2)`.
    assertEquals(
      textOf(messages(2)),
      "{\n  \"next_thought\": \"I should search.\",\n  \"summary\": null,\n  \"tool_calls\": {\n" +
        "    \"tool_calls\": [\n      {\n        \"name\": \"search\",\n        \"args\": {\n" +
        "          \"query\": \"cats\"\n        }\n      }\n    ]\n  }\n}"
    )
    assertEquals(textOf(messages(3)), s"[[ ## tool_call_results ## ]]\n$resultsText")
  }

  test("XMLAdapter (text): the exact text of a tool turn") {
    val messages = messagesOf(xml, agentWithHistory, withHistory("Q2", History.empty :+ toolTurn))
    assertEquals(roles(messages), Vector(System, User, Assistant, User, User))
    assertEquals(
      textOf(messages(2)),
      s"<next_thought>\nI should search.\n</next_thought>\n\n<summary>\nNone\n</summary>\n\n<tool_calls>\n" +
        s"$callsText\n</tool_calls>"
    )
    assertEquals(textOf(messages(3)), s"<tool_call_results>\n$resultsText\n</tool_call_results>")
    assert(messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty), messages.toString)
  }

  // ── Tool calls with native function calling ─────────────────────────────────────────────────────────────────

  test("ChatAdapter (native): the assistant text has the fields of the message with no tool calls field") {
    val messages = messagesOf(
      ChatAdapter(useNativeFunctionCalling = true),
      agentWithHistory,
      withHistory("Q2", History.empty :+ toolTurn)
    )
    assertEquals(roles(messages), Vector(System, User, Assistant, Tool, User))
    assertEquals(messages(2).text, Some("[[ ## next_thought ## ]]\nI should search.\n\n[[ ## completed ## ]]\n"))
    assertEquals(messages(2).toolCalls, Vector(search))
  }

  test("JSONAdapter (native): the assistant text has the fields of the message with no tool calls field") {
    val messages = messagesOf(
      JSONAdapter(useNativeFunctionCalling = true),
      agentWithHistory,
      withHistory("Q2", History.empty :+ toolTurn)
    )
    assertEquals(roles(messages), Vector(System, User, Assistant, Tool, User))
    assertEquals(messages(2).text, Some("{\n  \"next_thought\": \"I should search.\"\n}"))
    assertEquals(messages(2).toolCalls, Vector(search))
  }

  nativeAdapters.foreach { (name, adapter) =>
    test(s"$name (native): a text field with a null value is not content") {
      val message = History.withToolCallResults(
        DynamicValues.record(
          "question"     := "Q1",
          "next_thought" -> DynamicValue.Null,
          "tool_calls"   -> NativeFunctionCalling.encodeToolCalls(Vector(search))
        ),
        searchResult
      )
      val messages = messagesOf(adapter, agentWithHistory, withHistory("Q2", History.empty :+ message))
      assertEquals(roles(messages), Vector(System, User, Assistant, Tool, User))
      assertEquals(messages(2).text, None)
    }

    test(s"$name (native): results in a different order from the calls are dropped") {
      val results = ToolCallResult
        .forCalls(
          Vector(lookup, search),
          Vector(DynamicValues.fromAny("dog result"), DynamicValues.fromAny("cat result"))
        )
        .toOption
        .get
      val message = History.withToolCallResults(
        DynamicValues.record(
          "question"     := "Q1",
          "next_thought" := "Two searches.",
          "tool_calls"   -> NativeFunctionCalling.encodeToolCalls(Vector(search, lookup))
        ),
        results
      )
      val messages = messagesOf(adapter, agentWithHistory, withHistory("Q2", History.empty :+ message))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assert(messages.forall(_.toolCalls.isEmpty), messages.toString)
      assert(textOf(messages(2)).contains("Two searches."), textOf(messages(2)))
    }

    test(s"$name (native): tool calls with no content and no matched results give no assistant message") {
      val message =
        DynamicValues.record("question" := "Q1", "tool_calls" -> NativeFunctionCalling.encodeToolCalls(Vector(search)))
      val messages = messagesOf(adapter, agentWithHistory, withHistory("Q2", History.empty :+ message))
      assertEquals(roles(messages), Vector(System, User, User))
    }

    test(s"$name (native): a tool calls value that is not a sequence of calls is a text turn with no tool calls") {
      // Upstream formats the text turn with the signature of the request, which has no tool calls field when native
      // function calling is active (`Adapter._call_preprocess`). Thus the value does not show.
      val message  = DynamicValues.record("question" := "Q1", "tool_calls" := "search the web")
      val messages = messagesOf(adapter, agentWithHistory, withHistory("Q2", History.empty :+ message))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assert(!textOf(messages(2)).contains("search the web"), textOf(messages(2)))
      assert(textOf(messages(2)).contains("next_thought") && textOf(messages(2)).contains("summary"))
      assert(messages.forall(_.toolCalls.isEmpty), messages.toString)
    }

    test(s"$name (native): a message with no tool calls stays text") {
      val messages = messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", oneTurn))
      assertEquals(roles(messages), Vector(System, User, Assistant, User))
      assert(messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty), messages.toString)
    }
  }

  // ── The system message ──────────────────────────────────────────────────────────────────────────────────────

  textAdapters.foreach { (name, adapter) =>
    test(s"$name: the system message lists the history field and has no note for it, as upstream") {
      val withField = textOf(messagesOf(adapter, qaWithHistory, withHistory("What is 3+3?", oneTurn)).head)
      assert(withField.contains("Your input fields are:\n1. `question` (str): \n2. `history` (History):\n"), withField)
      assert(!withField.contains("is the conversation history"), withField)
    }
  }

  // ── Adapters and helpers that do not replay ─────────────────────────────────────────────────────────────────

  test("TwoStepAdapter shows the history as a field value and does not replay it") {
    // Upstream `TwoStepAdapter.format` does not call `format_conversation_history`.
    val messages =
      messagesOf(TwoStepAdapter(StubLm(functionCalling = false)), qaWithHistory, withHistory("What is 3+3?", oneTurn))
    assertEquals(roles(messages), Vector(System, User))
    assert(textOf(messages.last).contains("What is 1+1?") && textOf(messages.last).contains("What is 3+3?"))
  }

  test("encodeToolCalls writes the id of a call that has one") {
    val noId    = search.copy(id = None)
    val encoded = NativeFunctionCalling.encodeToolCalls(Vector(search, noId))
    assertEquals(ToolCall.fromDynamic(encoded), Some(Vector(search, noId)))
    encoded match
      case sequence: DynamicValue.Sequence => assertEquals(
          sequence.elements.toVector.collect { case record: DynamicValue.Record => DynamicValues.recordKeys(record) },
          Vector(Vector("id", "name", "args"), Vector("name", "args"))
        )
      case other => fail(s"expected a sequence, got: $other")
  }

  Vector(
    "ChatAdapter" -> ChatAdapter(useNativeFunctionCalling = true),
    "JSONAdapter" -> JSONAdapter(useNativeFunctionCalling = true)
  )
    .foreach { (name, adapter) =>
      test(s"$name: parse keeps the id of a native tool call in the tool calls field") {
        given RuntimeContext = RuntimeContext(lm = Some(StubLm(functionCalling = true)))
        val parsed           = adapter.parse(agentWithHistory, LmOutput(text = "", toolCalls = Vector(search)))
        assertEquals(
          parsed.toOption.flatMap(p => DynamicValues.recordGet(p.values, "tool_calls")).flatMap(ToolCall.fromDynamic),
          Some(Vector(search))
        )
      }
    }
