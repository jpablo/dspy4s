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

  test("a JSON object that the chunk codec cannot read is skipped, because upstream reads it leniently") {
    val (chunks, _) = chunksOf("""data: {"choices":"x"}""", content("ok", finish = "stop"), "data: [DONE]")
    assertEquals(failures(chunks), Vector.empty)
    assertEquals(chunks.map(_.text), Vector("ok"))
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
