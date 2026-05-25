package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.ChatStreamingState
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.JsonStreamingState
import dspy4s.adapters.XmlStreamingState
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.Predict
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StatusEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** Direct dspy4s ports of Python DSPy's
  * `tests/streaming/test_streaming.py` tests that don't require live LMs,
  * Pydantic-equivalent typing, async program execution, or the per-token
  * chunk-emission discipline (which would need a state-machine refactor).
  *
  * Each test names the Python original it ports. Comments note any
  * deliberate behavioral deltas inherited from earlier choices
  * (see PORT_MAP.md §4). */
class StreamingPortedSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private final class ScriptedLm(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = chunks.map(_.text).mkString))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      chunks.iterator

  private def collectStream(events: dspy4s.core.contracts.ClosableIterator[StreamEvent]): Vector[StreamEvent] =
    val buf = ArrayBuffer.empty[StreamEvent]
    while events.hasNext do buf += events.next()
    buf.toVector

  // ── Port: test_streaming_handles_space_correctly ────────────────────────

  test("streaming_handles_space_correctly: concatenated chunks reconstruct content with internal spaces") {
    val chunks = Vector(
      LmChunk(text = "[[ ## answer ## ]]\n"),
      LmChunk(text = "How "),
      LmChunk(text = "are "),
      LmChunk(text = "you "),
      LmChunk(text = "doing?"),
      LmChunk(text = "\n\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> ChatAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(Map("question" -> "What is the capital of France?"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.chunk).mkString, "How are you doing?")
    }
  }

  // ── Port: test_stream_listener_missing_completion_marker_chat_adapter ───

  test("missing_completion_marker_chat: tokens are not lost when [[ ## completed ## ]] is absent") {
    val expected = "This is a test response with many tokens to ensure buffering works correctly."
    val chunks = Vector(
      LmChunk(text = "[[ ##"),
      LmChunk(text = " answer"),
      LmChunk(text = " ## ]]\n\n"),
      LmChunk(text = "This"),
      LmChunk(text = " is"),
      LmChunk(text = " a"),
      LmChunk(text = " test"),
      LmChunk(text = " response"),
      LmChunk(text = " with"),
      LmChunk(text = " many"),
      LmChunk(text = " tokens"),
      LmChunk(text = " to"),
      LmChunk(text = " ensure"),
      LmChunk(text = " buffering"),
      LmChunk(text = " works"),
      LmChunk(text = " correctly"),
      LmChunk(text = ".", finishReason = Some("stop"))
      // NOTE: no [[ ## completed ## ]] marker on purpose
    )
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> ChatAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(Map("question" -> "Test question"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      val finalPrediction = events.collectFirst { case e: PredictionEvent => e.prediction }

      assertEquals(tokens.map(_.chunk).mkString, expected, "concatenated tokens should equal full content")
      assertEquals(tokens.last.isLastChunk, true, "last token should carry isLastChunk=true")
      assertEquals(finalPrediction.map(_.values("answer")), Some(expected))
    }
  }

  // ── Port: test_stream_listener_empty_last_chunk_chat_adapter ────────────

  test("empty_last_chunk_chat: each per-field stream ends with isLastChunk=true") {
    val chunks = Vector(
      LmChunk(text = "[[ ## reasoning ## ]]\n"),
      LmChunk(text = "Let's think about this problem step by step. "),
      LmChunk(text = "We need to consider the context of a kitchen. "),
      LmChunk(text = "The chicken likely wants to reach something on the other side. "),
      LmChunk(text = "\n\n[[ ## answer ## ]]\n"),
      LmChunk(text = "To get to the other side!"),
      LmChunk(text = "\n\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val signature = SignatureDsl.parse("question -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> ChatAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("reasoning"), StreamListener("answer"))
      )(Map("question" -> "Why did the chicken cross the kitchen?"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      val reasoningChunks = tokens.filter(_.fieldName == "reasoning")
      val answerChunks = tokens.filter(_.fieldName == "answer")

      assert(reasoningChunks.nonEmpty, "expected reasoning chunks")
      assert(answerChunks.nonEmpty, "expected answer chunks")
      assertEquals(reasoningChunks.last.isLastChunk, true)
      assertEquals(answerChunks.last.isLastChunk, true)
    }
  }

  // ── Port: test_stream_listener_empty_last_chunk_json_adapter ────────────

  test("empty_last_chunk_json: each per-field stream ends with isLastChunk=true") {
    val chunks = Vector(
      LmChunk(text = "{\"reasoning\": \""),
      LmChunk(text = "Let's think about this. "),
      LmChunk(text = "We consider the kitchen context."),
      LmChunk(text = "\", \"answer\": \""),
      LmChunk(text = "To get to the other side!"),
      LmChunk(text = "\"}", finishReason = Some("stop"))
    )
    val signature = SignatureDsl.parse("question -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> JSONAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("reasoning"), StreamListener("answer"))
      )(Map("question" -> "Why did the chicken cross the kitchen?"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      val reasoningChunks = tokens.filter(_.fieldName == "reasoning")
      val answerChunks = tokens.filter(_.fieldName == "answer")

      assert(reasoningChunks.nonEmpty)
      assert(answerChunks.nonEmpty)
      assertEquals(reasoningChunks.last.isLastChunk, true)
      assertEquals(answerChunks.last.isLastChunk, true)
    }
  }

  // ── Port: test_json_adapter_bracket_balance_detection ───────────────────

  test("json_adapter_bracket_balance_detection: nested objects + arrays close the field on outer brace") {
    val chunks = Vector(
      LmChunk(text = "{\""),
      LmChunk(text = "response\": {"),
      LmChunk(text = "\"items\": [\"a\""),
      LmChunk(text = ", \"b\"], "),
      LmChunk(text = "\"settings\": {\"key\""),
      LmChunk(text = ": \"value\"}, "),
      LmChunk(text = "\"active\": true}"),
      LmChunk(text = "}", finishReason = Some("stop"))
    )
    val signature = SignatureDsl.parse("question -> response: json").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> JSONAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("response"))
      )(Map("question" -> "Generate complex JSON"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assert(tokens.nonEmpty, "expected at least one chunk")
      assertEquals(tokens.last.isLastChunk, true)
      val fullContent = tokens.map(_.chunk).mkString
      assert(fullContent.contains("items"), s"missing 'items' in: $fullContent")
      assert(fullContent.contains("settings"), s"missing 'settings' in: $fullContent")
    }
  }

  // ── Port: test_json_adapter_multiple_fields_detection ────────────────────

  test("json_adapter_multiple_fields_detection: both fields stream their own content") {
    val chunks = Vector(
      LmChunk(text = "{\"first\": {"),
      LmChunk(text = "\"message\": \"first response\""),
      LmChunk(text = ", \"status\": \"ok\"}"),
      LmChunk(text = ", \"second\": {"),
      LmChunk(text = "\"message\": \"second response\""),
      LmChunk(text = ", \"status\": \"done\"}}", finishReason = Some("stop"))
    )
    val signature = SignatureDsl.parse("question -> first: json, second: json").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> new ScriptedLm(chunks),
        SettingKeys.adapter.name -> JSONAdapter()
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("first"), StreamListener("second"))
      )(Map("question" -> "Generate two responses"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      val firstChunks = tokens.filter(_.fieldName == "first")
      val secondChunks = tokens.filter(_.fieldName == "second")

      assert(firstChunks.nonEmpty, "expected first-field chunks")
      assert(secondChunks.nonEmpty, "expected second-field chunks")
      assert(firstChunks.map(_.chunk).mkString.contains("first response"))
      assert(secondChunks.map(_.chunk).mkString.contains("second response"))
    }
  }

  // ── Port: test_sync_status_streaming ────────────────────────────────────

  test("sync_status_streaming: tool start/end messages flow through the sync iterator") {
    // Python's test uses dspy.utils.DummyLM + dspy.Tool + a custom program.
    // dspy4s has no published DummyLM yet, so we use a tool-only program
    // (no LM call at all) — the assertion target is just the status
    // messages, not LM output.
    val tool = new ToolFunction:
      override val name: String = "generate_question"
      override def invoke(args: Map[String, Any])(using RuntimeContext) =
        Right("What color is the sky?")

    val program = new PredictProgram:
      override val moduleName: String = "my_program"
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        ToolExecutor.invoke(ToolCallRequest(tool.name, Map.empty), Vector(tool)).map { _ =>
          PredictionData(values = Map("answer" -> "blue"))
        }

    given RuntimeContext = RuntimeEnvironment.current
    val stream = Streamify.streamify(program = program)(Map.empty)

    val statusMessages = ArrayBuffer.empty[String]
    while stream.hasNext do
      stream.next() match
        case s: StatusEvent => statusMessages += s.message
        case _              => ()

    assertEquals(statusMessages.size, 2)
    assertEquals(statusMessages(0), "Calling tool generate_question...")
    assertEquals(statusMessages(1), "Tool calling finished! Querying the LLM with tool calling results...")
  }

  // ── Port: test_stream_listener_could_form_end_identifier_chat_adapter ──

  test("could_form_end_identifier_chat: partial bracket sequences and pattern containment trip the holdback") {
    // True for partial bracket sequences.
    assert(ChatStreamingState.couldFormEndIdentifier("some text ["))
    assert(ChatStreamingState.couldFormEndIdentifier("some text [["))
    assert(ChatStreamingState.couldFormEndIdentifier("some text [[ "))
    assert(ChatStreamingState.couldFormEndIdentifier("some text [[ #"))
    assert(ChatStreamingState.couldFormEndIdentifier("some text [[ ##"))

    // True when the partial-marker prefix already appears anywhere.
    assert(ChatStreamingState.couldFormEndIdentifier("some text [[ ## com"))
    assert(ChatStreamingState.couldFormEndIdentifier("some text [[ ## completed"))

    // False when the text clearly cannot form the pattern.
    assert(!ChatStreamingState.couldFormEndIdentifier("hello world"))
    assert(!ChatStreamingState.couldFormEndIdentifier("some text"))
    assert(!ChatStreamingState.couldFormEndIdentifier("answer: hello"))
  }

  // ── Port: test_stream_listener_could_form_end_identifier_json_adapter ──

  test("could_form_end_identifier_json: partial quote/brace sequences trip the holdback") {
    assert(JsonStreamingState.couldFormEndIdentifier("some text \""))
    assert(JsonStreamingState.couldFormEndIdentifier("some text \","))
    assert(JsonStreamingState.couldFormEndIdentifier("some text \" "))
    assert(JsonStreamingState.couldFormEndIdentifier("some text \"}"))

    assert(!JsonStreamingState.couldFormEndIdentifier("hello world"))
    assert(!JsonStreamingState.couldFormEndIdentifier("some text"))
  }

  // ── Port: test_stream_listener_could_form_end_identifier_xml_adapter ───

  test("could_form_end_identifier_xml: partial closing tag trips the holdback") {
    assert(XmlStreamingState.couldFormEndIdentifier("some text <"))
    assert(XmlStreamingState.couldFormEndIdentifier("some text </"))
    assert(XmlStreamingState.couldFormEndIdentifier("some text </result"))

    assert(!XmlStreamingState.couldFormEndIdentifier("hello world"))
    assert(!XmlStreamingState.couldFormEndIdentifier("some text"))
  }
