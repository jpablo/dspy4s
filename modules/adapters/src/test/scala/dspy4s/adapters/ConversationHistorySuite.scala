package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
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
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

/** An adapter replays a [[History]] input field as earlier turns of the conversation (PORT_GAPS G-28; upstream
  * `Adapter.format` and `Adapter.format_conversation_history` in `dspy/adapters/base.py`, with the rules of f207d35cf
  * and 2ec31b3aa).
  *
  * The prompt text of each adapter is not equal to the upstream text, so the tests compare a replayed turn with the
  * text that the same adapter gives for that turn as a current request or as a demo.
  */
class ConversationHistorySuite extends FunSuite:

  private final class StubLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private def layoutOf(inputs: Vector[FieldSpec], outputs: Vector[FieldSpec]): SignatureLayout =
    SignatureLayout.create(name = "QA", inputFields = inputs, outputFields = outputs).toOption.get

  private val historyField: FieldSpec = FieldSpec("history", typeRef = TypeRef.history)

  /** `question, history -> answer`, and the same signature with no history field. */
  private val qaWithHistory: SignatureLayout =
    layoutOf(Vector(FieldSpec("question"), historyField), Vector(FieldSpec("answer")))
  private val qaPlain: SignatureLayout = layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("answer")))

  /** `question, history -> next_thought, tool_calls`, and the same signature with no history field. */
  private val toolOutputs: Vector[FieldSpec] =
    Vector(FieldSpec("next_thought"), FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))
  private val agentWithHistory: SignatureLayout = layoutOf(Vector(FieldSpec("question"), historyField), toolOutputs)

  private val tools: Vector[ToolSpec] = Vector(
    ToolSpec(
      "search",
      Some("Search for documents."),
      Vector(ToolParameterSpec("query", TypeRef.string, None, required = true))
    )
  )

  private val search: ToolCall =
    ToolCall(name = "search", args = DynamicValues.record("query" := "cats"), id = Some("call_1"))
  private val lookup: ToolCall =
    ToolCall(name = "lookup", args = DynamicValues.record("key" := "dogs"), id = Some("call_2"))

  private val textAdapters: Vector[(String, Adapter)] =
    Vector("ChatAdapter" -> ChatAdapter(), "JSONAdapter" -> JSONAdapter(), "XMLAdapter" -> XMLAdapter())

  private val nativeAdapters: Vector[(String, Adapter)] = Vector(
    "ChatAdapter" -> ChatAdapter(useNativeFunctionCalling = true),
    "JSONAdapter" -> JSONAdapter(useNativeFunctionCalling = true)
  )

  private val nonNativeAdapters: Vector[(String, Adapter)] = Vector(
    "ChatAdapter" -> ChatAdapter(useNativeFunctionCalling = false),
    "JSONAdapter" -> JSONAdapter(useNativeFunctionCalling = false)
  )

  private def turn(question: String, answer: String): DynamicValue.Record =
    DynamicValues.record("question" := question, "answer" := answer)

  private def calls(values: ToolCall*): DynamicValue = DynamicValue.Sequence(Chunk.from(values.map(_.toRecord)))

  private def inputsOf(question: Option[String], history: Option[History]): Example =
    val entries = question.map(q => "question" := q).toVector ++
      history.map(h => "history" -> summon[Schema[History]].toDynamicValue(h)).toVector
    Example(values = DynamicValues.recordFromEntries(entries), inputKeys = entries.map(_._1).toSet)

  private def messagesOf(
      adapter        : Adapter,
      layout         : SignatureLayout,
      question       : Option[String],
      history        : Option[History] = None,
      demos          : Vector[Example] = Vector.empty,
      withTools      : Boolean         = false,
      functionCalling: Boolean         = true
  ): Vector[Message] =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(functionCalling)))
    val invocation       = AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = inputsOf(question, history),
      request = LmRequest(model = "stub"),
      tools = if withTools then tools else Vector.empty
    )
    adapter.format(invocation) match
      case Right(prompt) => prompt.messages
      case Left(error)   => fail(s"format failed: $error")

  private def roles(messages: Vector[Message]): Vector[MessageRole] = messages.map(_.role)

  private def textOf(message: Message): String = message.text.getOrElse("")

  /** The text of `question` as a current request of the signature that has no history field. */
  private def requestText(adapter: Adapter, question: String): String =
    textOf(messagesOf(adapter, qaPlain, Some(question)).last)

  /** The text of the assistant turn that the adapter gives for a demo with `question` and `answer`. */
  private def assistantText(adapter: Adapter, question: String, answer: String): String =
    val demo = Example(values = turn(question, answer), inputKeys = Set("question"))
    textOf(messagesOf(adapter, qaPlain, Some("unused"), demos = Vector(demo))(2))

  private val twoTurns: History = History.empty :+ turn("What is 1+1?", "2") :+ turn("What is 2+2?", "4")

  // ── Text turns ──────────────────────────────────────────────────────────────────────────────────────────────

  textAdapters.foreach { (name, adapter) =>
    test(s"$name: the history gives earlier turns between the demos and the current request") {
      val demo     = Example(values = turn("What is 0+0?", "0"), inputKeys = Set("question"))
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns), demos = Vector(demo))
      import MessageRole.{Assistant, System, User}
      assertEquals(roles(messages), Vector(System, User, Assistant, User, Assistant, User, Assistant, User))
    }

    test(s"$name: an earlier user turn is the text of that request, with the output reminder") {
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns))
      assertEquals(textOf(messages(1)), requestText(adapter, "What is 1+1?"))
      assertEquals(textOf(messages(3)), requestText(adapter, "What is 2+2?"))
    }

    test(s"$name: an earlier assistant turn is the text of the outputs of that turn") {
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns))
      assertEquals(textOf(messages(2)), assistantText(adapter, "What is 1+1?", "2"))
      assertEquals(textOf(messages(4)), assistantText(adapter, "What is 2+2?", "4"))
    }

    test(s"$name: the current request does not show the history field") {
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns))
      assertEquals(textOf(messages.last), requestText(adapter, "What is 3+3?"))
      assert(!textOf(messages.last).contains("history"), textOf(messages.last))
    }

    test(s"$name: the system message names the history field") {
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns))
      assertEquals(messages.head.role, MessageRole.System)
      assert(textOf(messages.head).contains("history"), textOf(messages.head))
    }

    test(s"$name: a message with output fields only gives an assistant turn and no user turn") {
      val history  = History.empty :+ DynamicValues.record("answer" := "An earlier note.")
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(history))
      assertEquals(roles(messages), Vector(MessageRole.System, MessageRole.Assistant, MessageRole.User))
      assert(textOf(messages(1)).contains("An earlier note."), textOf(messages(1)))
    }

    test(s"$name: an empty history and an absent history give the current request only") {
      val expected = requestText(adapter, "What is 3+3?")
      val empty    = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(History.empty))
      val absent   = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), None)
      assertEquals(roles(empty), Vector(MessageRole.System, MessageRole.User))
      assertEquals(textOf(empty.last), expected)
      assertEquals(roles(absent), Vector(MessageRole.System, MessageRole.User))
      assertEquals(textOf(absent.last), expected)
    }

    test(s"$name: no message of a text history has tool fields") {
      val messages = messagesOf(adapter, qaWithHistory, Some("What is 3+3?"), Some(twoTurns))
      assert(messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty && m.name.isEmpty))
      assert(!roles(messages).contains(MessageRole.Tool))
    }
  }

  // ── Tool calls with native function calling ─────────────────────────────────────────────────────────────────

  private def toolTurn(
      question : Option[String],
      thought  : Option[String],
      toolCalls: Vector[ToolCall],
      results  : Option[Vector[ToolCallResult]]
  ): DynamicValue.Record =
    val entries = question.map(q => "question" := q).toVector ++ thought.map(t => "next_thought" := t).toVector ++
      Vector("tool_calls" -> calls(toolCalls*))
    val message = DynamicValues.recordFromEntries(entries)
    results.fold(message)(History.withToolCallResults(message, _))

  private def resultsFor(values: DynamicValue*)(toolCalls: ToolCall*): Vector[ToolCallResult] =
    ToolCallResult.forCalls(toolCalls.toVector, values.toVector).toOption.get

  nativeAdapters.foreach { (name, adapter) =>
    test(s"$name (native): a turn with a tool call and its result gives an assistant message and a tool message") {
      val items   = DynamicValues.record("items" -> DynamicValue.Sequence(Chunk(DynamicValues.fromAny("cat"))))
      val history = History.empty :+
        toolTurn(Some("Q1"), Some("I should search."), Vector(search), Some(resultsFor(items)(search)))
      val messages = messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true)
      import MessageRole.{Assistant, System, Tool, User}
      assertEquals(roles(messages), Vector(System, User, Assistant, Tool, User))

      assert(textOf(messages(1)).contains("Q1"), textOf(messages(1)))
      val assistant = messages(2)
      assertEquals(assistant.toolCalls, Vector(search))
      assert(textOf(assistant).contains("I should search."), textOf(assistant))
      assert(!textOf(assistant).contains("tool_calls"), textOf(assistant))

      val tool = messages(3)
      assertEquals(tool.toolCallId, Some("call_1"))
      assertEquals(tool.name, Some("search"))
      // A value that is not a string goes as JSON.
      assertEquals(tool.text.flatMap(DynamicValues.parseJsonRecord), Some(items))

      val current = textOf(messages(4))
      assert(current.contains("Q2"), current)
      assert(!current.contains("history") && !current.contains("tool_call_results"), current)
    }

    test(s"$name (native): a tool result that is a string goes to the tool message as it is") {
      val history = History.empty :+ toolTurn(
        Some("Q1"),
        Some("I should search."),
        Vector(search),
        Some(resultsFor(DynamicValues.fromAny("cat result"))(search))
      )
      val messages = messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true)
      assertEquals(messages(3).text, Some("cat result"))
    }

    test(s"$name (native): parallel tool calls give one assistant message and one tool message for each result") {
      val results  = resultsFor(DynamicValues.fromAny("cat result"), DynamicValues.fromAny("dog result"))(search, lookup)
      val history  = History.empty :+ toolTurn(Some("Q1"), Some("Two searches."), Vector(search, lookup), Some(results))
      val messages = messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true)
      import MessageRole.{Assistant, System, Tool, User}
      assertEquals(roles(messages), Vector(System, User, Assistant, Tool, Tool, User))
      assertEquals(messages(2).toolCalls, Vector(search, lookup))
      assertEquals(
        messages.slice(3, 5).map(m => (m.toolCallId, m.name, m.text)),
        Vector(
          (Some("call_1"), Some("search"), Some("cat result")),
          (Some("call_2"), Some("lookup"), Some("dog result"))
        )
      )
    }

    test(s"$name (native): a turn with tool calls only gives an assistant message with no text and no user turn") {
      val history = History.empty :+
        toolTurn(None, None, Vector(search), Some(resultsFor(DynamicValues.fromAny("cat result"))(search)))
      val messages = messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true)
      import MessageRole.{Assistant, System, Tool, User}
      assertEquals(roles(messages), Vector(System, Assistant, Tool, User))
      assertEquals(messages(1).text, None)
      assertEquals(messages(1).toolCalls, Vector(search))
    }

    test(s"$name (native): tool calls that do not match their results are not replayed as tool messages") {
      val noId                                         = search.copy(id = None)
      val cases: Vector[(String, DynamicValue.Record)] = Vector(
        "no results"                    -> toolTurn(Some("Q1"), Some("I should search."), Vector(search), None),
        "a result for a different call" -> toolTurn(
          Some("Q1"),
          Some("I should search."),
          Vector(search),
          Some(Vector(ToolCallResult(
            name = "search",
            value = DynamicValues.fromAny("cat"),
            callId = Some("other_call")
          )))
        ),
        "a call and a result with no id" -> toolTurn(
          Some("Q1"),
          Some("I should search."),
          Vector(noId),
          Some(Vector(ToolCallResult(name = "search", value = DynamicValues.fromAny("cat"), callId = None)))
        )
      )
      cases.foreach { (label, message) =>
        val messages =
          messagesOf(adapter, agentWithHistory, Some("Q2"), Some(History.empty :+ message), withTools = true)
        assert(messages.forall(_.toolCalls.isEmpty), s"$label: $messages")
        assert(!roles(messages).contains(MessageRole.Tool), s"$label: ${roles(messages)}")
        assertEquals(messages(2).role, MessageRole.Assistant, label)
        assert(textOf(messages(2)).contains("I should search."), s"$label: ${textOf(messages(2))}")
      }
    }

    test(s"$name (native): a model that does not support function calling gets the tool turn as text") {
      val history = History.empty :+ toolTurn(
        Some("Q1"),
        Some("I should search."),
        Vector(search),
        Some(resultsFor(DynamicValues.fromAny("cat result"))(search))
      )
      val messages =
        messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true, functionCalling = false)
      assert(messages.forall(_.toolCalls.isEmpty), messages.toString)
      assert(!roles(messages).contains(MessageRole.Tool), roles(messages).toString)
    }
  }

  // ── Tool calls as text ──────────────────────────────────────────────────────────────────────────────────────

  nonNativeAdapters.foreach { (name, adapter) =>
    test(s"$name (text): a turn with a tool call and its result stays text") {
      val history = History.empty :+ toolTurn(
        Some("Q1"),
        Some("I should search."),
        Vector(search),
        Some(resultsFor(DynamicValues.fromAny("cat result"))(search))
      )
      val messages = messagesOf(adapter, agentWithHistory, Some("Q2"), Some(history), withTools = true)
      import MessageRole.{Assistant, System, User}
      // The earlier request, the assistant turn with the tool call as text, the tool results as a user turn, and
      // the current request.
      assertEquals(roles(messages), Vector(System, User, Assistant, User, User))
      assert(messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty), messages.toString)

      val assistant = textOf(messages(2))
      assert(assistant.contains("I should search."), assistant)
      assert(assistant.contains("search") && assistant.contains("cats"), assistant)
      assert(!assistant.contains("cat result"), assistant)

      val results = textOf(messages(3))
      assert(results.contains("tool_call_results"), results)
      assert(results.contains("cat result"), results)

      assert(textOf(messages(4)).contains("Q2"), textOf(messages(4)))
      assert(!textOf(messages(4)).contains("tool_call_results"), textOf(messages(4)))
    }
  }
