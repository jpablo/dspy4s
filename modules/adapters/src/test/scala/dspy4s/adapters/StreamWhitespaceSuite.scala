package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import munit.Location

/** The chunks of a streamed field have no boundary whitespace (upstream `_default_handle_stream_chunk` in
  * `dspy/streaming/streaming_listener.py`, f4a428853). The joined chunks of a field are the parsed value of the field,
  * for each split of the response into deltas.
  */
class StreamWhitespaceSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  /** The joined text of the chunks of each field, and the chunks, for one split of a response into `parts`. */
  private def stream(state: AdapterStreamingState, parts: Seq[String]): (Map[String, String], Vector[FieldChunk]) =
    val chunks = parts.toVector.flatMap(state.receive) ++ state.finish()
    val joined = chunks.groupBy(_.fieldName).view.mapValues(_.map(_.text).mkString).toMap
    (joined, chunks)

  /** Each split of `text` into two parts. */
  private def twoPartSplits(text: String): Seq[Seq[String]] = (1 until text.length).map(i =>
    Seq(text.substring(0, i), text.substring(i))
  )

  private def parsedText(
      adapter: ChatAdapter | XMLAdapter,
      layout : SignatureLayout,
      text   : String
  ): Map[String, String] =
    val parsed = adapter match
      case chat: ChatAdapter => chat.parse(layout, LmOutput(text = text))
      case xml: XMLAdapter   => xml.parse(layout, LmOutput(text = text))
    parsed.fold(
      error => fail(s"parse failed: $error"),
      output =>
        DynamicValues.recordEntries(output.values).map((name, value) => name -> DynamicValues.renderText(value)).toMap
    )

  private val answerOnly: Vector[FieldSpec] = Vector(FieldSpec("answer"))

  private def layoutOf(outputs: Vector[FieldSpec]): SignatureLayout =
    SignatureLayout.create(name = "Probe", inputFields = Vector(FieldSpec("question")), outputFields = outputs)
      .fold(error => fail(s"bad layout: $error"), identity)

  private val reasoningAndAnswer: Vector[FieldSpec] = Vector(FieldSpec("reasoning"), FieldSpec("answer"))

  /** A model that supports function calling. The adapters read only the capability flag. */
  private object FunctionCallingLm extends LanguageModel:
    override val id: String                                                                    = "function-calling"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the adapter tests do not call the model"))

  /** The splits of `text` into deltas: the whole text, each split into two parts, and one delta for each character. */
  private def splits(text: String): Seq[Seq[String]] =
    Seq(Seq(text)) ++ twoPartSplits(text) :+ text.map(_.toString)

  /** The chat stream of `text`, for each split, gives the values that the chat parser gives, and these are `expected`.
    */
  private def assertChatStreamParses(outputs: Vector[FieldSpec], text: String, expected: Map[String, String])(using
      Location
  ): Unit =
    val parsed = parsedText(ChatAdapter(), layoutOf(outputs), text)
    assertEquals(parsed, expected, text)
    splits(text).foreach { parts =>
      val (joined, _) = stream(ChatStreamingState(outputs), parts)
      assertEquals(joined, parsed, parts.toString)
    }

  // ── Chat ────────────────────────────────────────────────────────────────────────────────────────────────────

  test("the chat chunks of a field have no boundary whitespace, for each upstream split") {
    val cases = Vector(
      Seq("[[ ## answer ## ]]\n", "How ", "are ", "you ", "doing?\n\n", "[[ ## completed ## ]]") ->
        "How are you doing?",
      Seq("[[ ## answer ## ]]", "\nHow ", "are ", "you ", "doing?", "\n\n", "[[ ## completed ## ]]") ->
        "How are you doing?",
      Seq("[[ ## answer", " ## ]]", "\nHow ", "are ", "you ", "doing?", "\n\n[[ ## completed ## ]]") ->
        "How are you doing?",
      Seq("[[ ## answer ## ]]\n", "How\n", "are you\n", "doing?", "\n\n[[ ## completed ## ]]") ->
        "How\nare you\ndoing?",
      Seq("[[ ## answer ## ]]\n", "How ", "are ", "you ", "doing?\n\n") -> "How are you doing?"
    )
    cases.foreach { (parts, expected) =>
      val (joined, chunks) = stream(ChatStreamingState(answerOnly), parts)
      assertEquals(joined.get("answer"), Some(expected), parts.toString)
      assert(chunks.lastOption.exists(_.isLast), s"$parts: the last chunk must close the field: $chunks")
    }
  }

  test("for each split point, the joined chat chunks of each field are the parsed values") {
    val outputs = Vector(FieldSpec("reasoning"), FieldSpec("answer"))
    val text    = "[[ ## reasoning ## ]]\n  I think it is Paris.\n\n[[ ## answer ## ]]\n Paris \n\n[[ ## completed ## ]]"
    val parsed  = parsedText(ChatAdapter(), layoutOf(outputs), text)
    assertEquals(parsed, Map("reasoning" -> "I think it is Paris.", "answer" -> "Paris"))
    twoPartSplits(text).foreach { parts =>
      val (joined, _) = stream(ChatStreamingState(outputs), parts)
      assertEquals(joined, parsed, parts.toString)
    }
  }

  test("with one output field and no header, the parser and the chunks strip the same whitespace") {
    val text   = "\u2003 just the value \u2003\n"
    val parsed = parsedText(ChatAdapter(), layoutOf(answerOnly), text)
    assertEquals(parsed, Map("answer" -> "just the value"))
    twoPartSplits(text).foreach { parts =>
      val (joined, _) = stream(ChatStreamingState(answerOnly), parts)
      assertEquals(joined, parsed, parts.toString)
    }
  }

  test("with one output field and no header, a CRLF reply gives the line ends of a section, in the parser and chunks") {
    // A section value has the lines of Python `str.splitlines`, joined with `\n` (upstream `ChatAdapter.parse`). The
    // single-output fallback is a port rule, and it gives its value the same line ends.
    val lf     = "first line\nsecond line\n\nlast line\n"
    val parsed = parsedText(ChatAdapter(), layoutOf(answerOnly), lf)
    assertEquals(parsed, Map("answer" -> "first line\nsecond line\n\nlast line"))
    Vector(lf.replace("\n", "\r\n"), lf.replace("\n", "\r"), "first line\r\nsecond line  last line").foreach {
      text =>
        assertEquals(parsedText(ChatAdapter(), layoutOf(answerOnly), text), parsed, text)
        twoPartSplits(text).foreach { parts =>
          val (joined, _) = stream(ChatStreamingState(answerOnly), parts)
          assertEquals(joined, parsed, parts.toString)
        }
    }
  }

  test("with one output field, the text before the header is not a chunk of the field, as in the parser") {
    val text   = "Sure, here it is.\n[[ ## answer ## ]]\n42\n\n[[ ## completed ## ]]"
    val parsed = parsedText(ChatAdapter(), layoutOf(answerOnly), text)
    assertEquals(parsed, Map("answer" -> "42"))
    twoPartSplits(text).foreach { parts =>
      val (joined, chunks) = stream(ChatStreamingState(answerOnly), parts)
      assertEquals(joined, parsed, parts.toString)
      assertEquals(chunks.count(_.isLast), 1, s"$parts: one field gives one last chunk: $chunks")
    }
  }

  test("a repeated chat header streams only the first section of the field, as the parser keeps the first") {
    val repeatedAnswer    = "[[ ## answer ## ]]\n42\n[[ ## answer ## ]]\n43\n[[ ## completed ## ]]"
    val repeatedReasoning =
      "[[ ## reasoning ## ]]\nfirst\n[[ ## answer ## ]]\n42\n[[ ## reasoning ## ]]\nsecond\n[[ ## completed ## ]]"
    val twoOutputs = Vector(FieldSpec("reasoning"), FieldSpec("answer"))
    Vector(answerOnly -> repeatedAnswer, twoOutputs -> repeatedReasoning).foreach { (outputs, text) =>
      val parsed = parsedText(ChatAdapter(), layoutOf(outputs), text)
      assertEquals(parsed.get("answer"), Some("42"))
      twoPartSplits(text).foreach { parts =>
        val (joined, chunks) = stream(ChatStreamingState(outputs), parts)
        assertEquals(joined, parsed, parts.toString)
        assertEquals(chunks.count(_.isLast), outputs.size, s"$parts: each field gives one last chunk: $chunks")
      }
    }
  }

  test("with native tool calls, the chat stream gets the text fields of the parser, also for the single-output rule") {
    val outputs          = Vector(FieldSpec("next_thought"), FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))
    val layout           = layoutOf(outputs)
    val adapter          = ChatAdapter(useNativeFunctionCalling = true)
    val text             = "I can answer now."
    val output           = LmOutput(text = text, toolCalls = Vector(ToolCall("submit", DynamicValues.record("answer" := "42"))))
    given RuntimeContext = RuntimeContext(lm = Some(FunctionCallingLm))
    val parsed           = adapter
      .parse(layout, output)
      .fold(error => fail(s"parse failed: $error"), result => DynamicValues.recordGet(result.values, "next_thought"))
      .map(DynamicValues.renderText)
    assertEquals(parsed, Some(text))
    splits(text).foreach { parts =>
      val state       = adapter.streamingState(layout).getOrElse(fail("the chat adapter has no stream state"))
      val (joined, _) = stream(state, parts)
      assertEquals(joined.get("next_thought"), parsed, parts.toString)
    }
  }

  // Python `str.strip` removes U+00A0, U+2007, U+202F, and U+0085. Java `String.strip` keeps them. The values are
  // those of upstream `ChatAdapter.parse` (dspy 3.4.0).
  test("the chat parser and the chat stream strip the Python whitespace that Java strip keeps") {
    assertChatStreamParses(
      reasoningAndAnswer,
      "[[ ## reasoning ## ]]\n I think \n\n[[ ## answer ## ]]\n Paris\u0085\n\n[[ ## completed ## ]]",
      Map("reasoning" -> "I think", "answer" -> "Paris")
    )
    assertChatStreamParses(answerOnly, " Paris \u0085", Map("answer" -> "Paris"))
  }

  // The header rule of upstream `ChatAdapter.parse`: a line of `str.splitlines` that starts with the header after
  // `str.strip`. Thus Python whitespace can come before the header, and a Python line break ends the line before it.
  // Upstream then reads the rest of the line at the end of the header in the stripped line, but from the line before
  // the strip (`line[match.end():]`), so a header after whitespace gives a part of the header in the value. The port
  // reads the rest of the stripped line.
  test("a chat header can follow Python whitespace at the start of a line, in the parser and in the stream") {
    assertChatStreamParses(
      reasoningAndAnswer,
      "[[ ## reasoning ## ]]\nr\n 　\t[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
      Map("reasoning" -> "r", "answer" -> "Paris")
    )
  }

  test("a control character that is not Python whitespace before a chat header makes the line text") {
    assertChatStreamParses(
      reasoningAndAnswer,
      "[[ ## reasoning ## ]]\nr\n\u0001[[ ## answer ## ]]\nWrong\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
      Map("reasoning" -> "r\n\u0001[[ ## answer ## ]]\nWrong", "answer" -> "Paris")
    )
  }

  test("each Python line break starts a line for a chat header, in the stream as in the parser") {
    Vector("\r", "\u000b", "\u000c", "\u001c", "\u001d", "\u001e", "\u0085", " ", " ").foreach { lineBreak =>
      assertChatStreamParses(
        reasoningAndAnswer,
        s"[[ ## reasoning ## ]]\nr$lineBreak[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
        Map("reasoning" -> "r", "answer" -> "Paris")
      )
    }
  }

  test("a chat header after other text on its line is text, also after a header or a long run of whitespace") {
    assertChatStreamParses(
      reasoningAndAnswer,
      "[[ ## reasoning ## ]] [[ ## answer ## ]]\nWrong\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
      Map("reasoning" -> "[[ ## answer ## ]]\nWrong", "answer" -> "Paris")
    )
    val spaces = " " * 30
    assertChatStreamParses(
      reasoningAndAnswer,
      s"[[ ## reasoning ## ]]\nr$spaces[[ ## answer ## ]]\nWrong\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
      Map("reasoning" -> s"r$spaces[[ ## answer ## ]]\nWrong", "answer" -> "Paris")
    )
  }

  // ── XML ─────────────────────────────────────────────────────────────────────────────────────────────────────

  test("for each split point, the joined XML chunks of each field are the parsed values") {
    val outputs = Vector(FieldSpec("reasoning"), FieldSpec("answer"))
    val text    = "<outputs>\n<reasoning>\n  I think it is Paris.\n</reasoning>\n<answer> Paris \n</answer>\n</outputs>"
    val parsed  = parsedText(XMLAdapter(), layoutOf(outputs), text)
    assertEquals(parsed, Map("reasoning" -> "I think it is Paris.", "answer" -> "Paris"))
    twoPartSplits(text).foreach { parts =>
      val (joined, _) = stream(XmlStreamingState(outputs), parts)
      assertEquals(joined, parsed, parts.toString)
    }
  }
