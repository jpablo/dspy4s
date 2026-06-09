package dspy4s.lm.providers

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.Embedder
import munit.FunSuite

class OpenAiEmbedderSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  /** Replays scripted responses and records each request body (to assert payload shape and batching). */
  private final class ScriptedTransport(responses: Vector[Either[DspyError, HttpResponse]]) extends HttpTransport:
    private var idx = 0
    val sent = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      sent += ((url, body))
      if idx >= responses.size then Left(RuntimeError("test", "No more responses scripted"))
      else { val r = responses(idx); idx += 1; r }
    override def streamSse(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpStreamResponse] =
      Left(RuntimeError("test", "streaming not used by embeddings"))

  private def okBody(rows: (Int, Seq[Double])*): String =
    val data = rows.map { case (i, v) => s"""{"object":"embedding","index":$i,"embedding":[${v.mkString(",")}]}""" }
    s"""{"object":"list","data":[${data.mkString(",")}],"model":"text-embedding-3-small"}"""

  private def embedder(transport: HttpTransport, batchSize: Int = 200): OpenAiEmbedder =
    OpenAiEmbedder(model = "text-embedding-3-small", apiKey = "sk-test", transport = transport, batchSize = batchSize)

  test("embeds a batch: request carries model+input, response rows are ordered by index") {
    // Rows scripted OUT of order — the provider must sort by the response `index` field.
    val transport = new ScriptedTransport(Vector(Right(HttpResponse(200, Map.empty, okBody(1 -> Seq(0.3, 0.4), 0 -> Seq(0.1, 0.2))))))
    val result    = embedder(transport).embed(Vector("hello", "world"))
    assertEquals(result, Right(Vector(Vector(0.1f, 0.2f), Vector(0.3f, 0.4f))))
    val (url, body) = transport.sent.head
    assert(url.endsWith("/embeddings"), url)
    assert(body.contains(""""model":"text-embedding-3-small""""), body)
    assert(body.contains(""""input":["hello","world"]"""), body)
  }

  test("splits inputs into batchSize-sized requests and concatenates the rows") {
    val transport = new ScriptedTransport(Vector(
      Right(HttpResponse(200, Map.empty, okBody(0 -> Seq(1.0), 1 -> Seq(2.0)))),
      Right(HttpResponse(200, Map.empty, okBody(0 -> Seq(3.0), 1 -> Seq(4.0)))),
      Right(HttpResponse(200, Map.empty, okBody(0 -> Seq(5.0))))
    ))
    val result = embedder(transport, batchSize = 2).embed(Vector("a", "b", "c", "d", "e"))
    assertEquals(result, Right(Vector(Vector(1.0f), Vector(2.0f), Vector(3.0f), Vector(4.0f), Vector(5.0f))))
    assertEquals(transport.sent.size, 3) // 5 inputs at batchSize=2 -> 2+2+1
  }

  test("a non-2xx response surfaces as a Left, and a 400 context-window body maps to the typed error") {
    val plainFailure = embedder(new ScriptedTransport(Vector(Right(HttpResponse(500, Map.empty, "boom"))))).embed(Vector("x"))
    assert(plainFailure.left.exists(_.isInstanceOf[RuntimeError]), plainFailure.toString)

    val ctxBody    = """{"error":{"message":"This model's maximum context length is 8192 tokens","code":"context_length_exceeded"}}"""
    val ctxFailure = embedder(new ScriptedTransport(Vector(Right(HttpResponse(400, Map.empty, ctxBody))))).embed(Vector("x"))
    assert(ctxFailure.left.exists(_.isInstanceOf[ContextWindowExceededError]), ctxFailure.toString)
  }

  test("a row-count mismatch is a ParseError, not silent truncation") {
    val transport = new ScriptedTransport(Vector(Right(HttpResponse(200, Map.empty, okBody(0 -> Seq(1.0))))))
    val result    = embedder(transport).embed(Vector("a", "b"))
    assert(result.isLeft, result.toString)
  }

  test("Embedder.fromFunction lifts a batch function; Embedder.cached embeds only cache misses") {
    var calls    = 0
    val counting = Embedder.fromFunction("test") { texts => calls += 1; texts.map(t => Vector(t.length.toFloat)) }
    val cached   = Embedder.cached(counting)

    assertEquals(cached.embed(Vector("a", "bb")), Right(Vector(Vector(1.0f), Vector(2.0f))))
    assertEquals(calls, 1)
    // Fully cached batch: no underlying call.
    assertEquals(cached.embed(Vector("bb", "a")), Right(Vector(Vector(2.0f), Vector(1.0f))))
    assertEquals(calls, 1)
    // Partial overlap: one more underlying call, for the misses only.
    assertEquals(cached.embed(Vector("a", "ccc")), Right(Vector(Vector(1.0f), Vector(3.0f))))
    assertEquals(calls, 2)
  }
