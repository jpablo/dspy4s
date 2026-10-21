package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmBillingError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmProviderResponseError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import munit.FunSuite

/** The stream rules of the OpenAI client (upstream `parse_stream_events` in
  * `dspy/_vendor/lm15/providers/openai_chat.py` and `checked_stream` in `dspy/clients/engines/stream_guard.py`).
  */
class OpenAiStreamGuardSuite extends FunSuite:

  private final class Lines(items: Vector[String]) extends ClosableIterator[String]:
    private var index             = 0
    var closed                    = false
    override def hasNext: Boolean = index < items.size && !closed
    override def next(): String   =
      val item = items(index)
      index += 1
      item
    override def close(): Unit = closed = true

  /** A transport that gives one stream with `lines`. */
  private final class OneStream(lines: Lines) extends HttpTransport:
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      Right(HttpStreamResponse(status = 200, headers = Map.empty, dataLines = lines))

  /** All chunks of a stream of `lines`, and the lines, to check that the connection closed. */
  private def chunksOf(lines: String*): (Vector[LmChunk], Lines) =
    val source = Lines(lines.toVector)
    val client = OpenAiClient(apiKey = "x", transport = OneStream(source))
    client.stream(DynamicValues.fromAny(Map("model" -> "m"))) match
      case Right(stream) => (stream.toVector, source)
      case Left(error)   => fail(s"the stream did not open: $error")

  private def content(text: String, finish: String = "null"): String =
    val reason = if finish == "null" then "null" else s"\"$finish\""
    s"""data: {"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":$reason}]}"""

  private val usageOnly: String =
    """data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""

  private def errorOf(chunk: LmChunk): DspyError =
    chunk.error.getOrElse(fail(s"the chunk has no error: $chunk"))

  private def failures(chunks: Vector[LmChunk]): Vector[LmChunk] = chunks.filter(_.error.isDefined)

  // ── The end of a stream ─────────────────────────────────────────────────────────────────────────────────────

  test("a stream whose lines end with no end event ends with a stream assembly error") {
    val (chunks, source) = chunksOf(content("Hi"), "")
    assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Hi"))
    assert(chunks.last.error.exists(_.isInstanceOf[LmStreamAssemblyError]), chunks.toString)
    assertEquals(chunks.last.finishReason, Some("error"))
    assert(source.closed)
  }

  test("a chunk with a finish reason is an end event, also with no [DONE] line") {
    val (chunks, source) = chunksOf(content("Hi", finish = "stop"), "")
    assertEquals(failures(chunks), Vector.empty)
    assertEquals(chunks.last.finishReason, Some("stop"))
    assert(source.closed)
  }

  test("a chunk with only usage is an end event") {
    val (chunks, _) = chunksOf(content("Hi"), usageOnly, "")
    assertEquals(failures(chunks), Vector.empty)
    assertEquals(chunks.flatMap(_.usage).map(_.totalTokens), Vector(5L))
  }

  test("the [DONE] line alone is a complete stream") {
    val (chunks, source) = chunksOf("data: [DONE]", "")
    assertEquals(failures(chunks), Vector.empty)
    assert(source.closed)
  }

  // ── Lines that do not decode ────────────────────────────────────────────────────────────────────────────────

  test("a data line that is not valid JSON ends the stream with a stream assembly error") {
    val (chunks, source) =
      chunksOf(content("Hi"), "data: {not valid json", content("ok", finish = "stop"), "data: [DONE]")
    assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Hi"))
    assertEquals(failures(chunks).size, 1)
    assert(errorOf(chunks.last).isInstanceOf[LmStreamAssemblyError], chunks.toString)
    assert(source.closed)
  }

  test("a JSON value that is not an object is skipped") {
    val (chunks, _) = chunksOf("data: [1, 2]", "data: \"text\"", content("ok", finish = "stop"), "data: [DONE]")
    assertEquals(failures(chunks), Vector.empty)
    assertEquals(chunks.map(_.text), Vector("ok"))
  }

  test("a JSON object that the chunk codec cannot read does not end the stream, because upstream reads it leniently") {
    val (chunks, _) = chunksOf("""data: {"choices":"x"}""", content("ok", finish = "stop"), "data: [DONE]")
    assertEquals(failures(chunks), Vector.empty)
    assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("ok"))
  }

  // Upstream `parse_stream_events` (`dspy/_vendor/lm15/providers/openai_chat.py`) reads each field of a chunk with an
  // `isinstance` check, and drops only a field of the wrong type. The streams of these tests have no [DONE] line, so
  // a chunk that the parser skips leaves the stream with no end event.

  test("a chunk whose usage or choices has the wrong type keeps its other fields") {
    val (badUsage, _) =
      chunksOf("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}],"usage":"x"}""", "")
    assertEquals(failures(badUsage), Vector.empty)
    assertEquals(badUsage.map(_.text), Vector("Hi"))
    assertEquals(badUsage.map(_.finishReason), Vector(Some("stop")))
    assertEquals(badUsage.flatMap(_.usage), Vector.empty)

    val (badChoices, _) =
      chunksOf("""data: {"choices":"x","usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""", "")
    assertEquals(failures(badChoices), Vector.empty)
    assertEquals(badChoices.flatMap(_.usage).map(_.totalTokens), Vector(5L))
  }

  test("a chunk whose content or finish reason has the wrong type keeps its other fields") {
    val (badContent, _) = chunksOf("""data: {"choices":[{"delta":{"content":5},"finish_reason":"length"}]}""", "")
    assertEquals(failures(badContent), Vector.empty)
    assertEquals(badContent.map(_.text), Vector(""))
    assertEquals(badContent.flatMap(_.finishReason), Vector("length"))

    // Upstream maps a finish reason that is not in `_FINISH_REASON_MAP` to `stop`.
    val (oddReason, _) = chunksOf("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":1}]}""", "")
    assertEquals(failures(oddReason), Vector.empty)
    assertEquals(oddReason.map(_.text), Vector("Hi"))
    assertEquals(oddReason.flatMap(_.finishReason), Vector("stop"))
  }

  test("a tool call that is not an object is dropped, and the other tool calls of the chunk stay") {
    val (badCall, _) = chunksOf(
      """data: {"choices":[{"delta":{"tool_calls":[7,{"index":1,"id":"c1","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
      ""
    )
    assertEquals(failures(badCall), Vector.empty)
    assertEquals(
      badCall.flatMap(_.toolCalls),
      Vector(LmToolCallDelta(index = 1, id = Some("c1"), name = Some("f"), argumentsFragment = Some("{}")))
    )
  }

  test("a tool-call id, name, or arguments that is not a string gives its Python text, as upstream str") {
    // Upstream `parse_stream_events` reads `str(value or "")`: a value that is false in Python gives no text, and each
    // other value gives the text of Python `str`. The upstream values come from the lm15 parser of upstream.
    val cases: Vector[(String, String, String, LmToolCallDelta)] = Vector(
      ("5", "true", "1.5", LmToolCallDelta(0, Some("5"), Some("True"), Some("1.5"))),
      ("0", "false", "0", LmToolCallDelta(0, None, None, None)),
      ("null", "null", "null", LmToolCallDelta(0, None, None, None)),
      ("[]", "{}", "false", LmToolCallDelta(0, None, None, None)),
      (
        "12345678901234567890",
        "\"f\"",
        "1e20",
        LmToolCallDelta(0, Some("12345678901234567890"), Some("f"), Some("1e+20"))
      ),
      ("\"c\"", "\"f\"", "0.1", LmToolCallDelta(0, Some("c"), Some("f"), Some("0.1"))),
      ("\"c\"", "\"f\"", "1e-5", LmToolCallDelta(0, Some("c"), Some("f"), Some("1e-05"))),
      ("\"c\"", "\"f\"", "-2.5e19", LmToolCallDelta(0, Some("c"), Some("f"), Some("-2.5e+19"))),
      (
        "\"c\"",
        "\"f\"",
        """[1,"a",true,null]""",
        LmToolCallDelta(0, Some("c"), Some("f"), Some("[1, 'a', True, None]"))
      ),
      ("\"c\"", "\"f\"", """{"a":{"b":[]}}""", LmToolCallDelta(0, Some("c"), Some("f"), Some("{'a': {'b': []}}"))),
      (
        "\"c\"",
        "\"f\"",
        "[\"it's\",\"say \\\"hi\\\"\",\"a\\nb\",\"\\u00e9\",\"\\u007f\",\"\\u200b\",\"\\\\\"]",
        LmToolCallDelta(
          0,
          Some("c"),
          Some("f"),
          Some("[\"it's\", 'say \"hi\"', 'a\\nb', '\u00e9', '\\x7f', '\\u200b', '\\\\']")
        )
      )
    )
    cases.foreach { (id, name, arguments, expected) =>
      val call        = s"""{"index":0,"id":$id,"function":{"name":$name,"arguments":$arguments}}"""
      val (chunks, _) =
        chunksOf(s"""data: {"choices":[{"delta":{"tool_calls":[$call]},"finish_reason":"tool_calls"}]}""", "")
      assertEquals(failures(chunks), Vector.empty, call)
      assertEquals(chunks.flatMap(_.toolCalls), Vector(expected), call)
    }
  }

  // ── Error events in the stream ──────────────────────────────────────────────────────────────────────────────

  test("an error event in the stream gives the typed error of its code, and the stream ends") {
    val cases: Vector[(String, DspyError => Boolean, String)] = Vector(
      (
        """{"error":{"message":"too long","code":"context_length_exceeded"}}""",
        _.isInstanceOf[ContextWindowExceededError],
        "too long"
      ),
      ("""{"error":{"message":"busy","type":"server_error"}}""", _.isInstanceOf[LmServerError], "busy"),
      (
        """{"error":{"message":"slow down","code":"rate_limit_exceeded"}}""",
        _.isInstanceOf[LmRateLimitError],
        "slow down"
      ),
      ("""{"error":{"code":"insufficient_quota"}}""", _.isInstanceOf[LmBillingError], "insufficient_quota"),
      ("""{"error":{"message":"odd","code":"something_new"}}""", _.isInstanceOf[LmProviderResponseError], "odd")
    )
    cases.foreach { (event, hasClass, message) =>
      val (chunks, source) = chunksOf(content("Hi"), s"data: $event", content("late", finish = "stop"), "data: [DONE]")
      assertEquals(chunks.map(_.text).filter(_.nonEmpty), Vector("Hi"), event)
      assertEquals(failures(chunks).size, 1, event)
      val error = errorOf(chunks.last)
      assert(hasClass(error), s"$event gave $error")
      assert(error.message.contains(message), s"$event gave the message ${error.message}")
      assert(source.closed, event)
    }
  }

  test("the error of an error event has the model, the provider, and the code") {
    val (chunks, _) = chunksOf("""data: {"error":{"message":"busy","code":"server_error"}}""")
    errorOf(chunks.last) match
      case error: LmError =>
        assertEquals(error.details.model, Some("m"))
        assertEquals(error.details.provider, Some("openai"))
        assertEquals(error.details.providerCode, Some("server_error"))
      case other => fail(s"expected an LM error, got $other")
  }
