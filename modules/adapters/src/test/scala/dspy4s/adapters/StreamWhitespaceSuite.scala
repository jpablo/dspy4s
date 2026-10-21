package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite

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
