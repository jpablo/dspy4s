package dspy4s.lm.providers

import com.sun.net.httpserver.HttpServer
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.runtime.ManagedLanguageModel
import munit.FunSuite

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** `close` releases the connections of a model, and the model stays usable (upstream `LM.close`; PORT_GAPS G-26,
  * upgrade item L8).
  */
class CloseLifecycleSuite extends FunSuite:

  private val loopback: InetAddress = InetAddress.getLoopbackAddress

  /** Run `use` with the port of a server that answers each request with `body`. */
  private def withServer[A](body: String)(use: Int => A): A =
    val server = HttpServer.create(new InetSocketAddress(loopback, 0), 8)
    server.createContext(
      "/",
      exchange =>
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        val _     = exchange.getRequestBody.readAllBytes()
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    try use(server.getAddress.getPort)
    finally server.stop(0)

  /** A transport that counts the calls of `close`. */
  private final class Counting extends HttpTransport:
    val closes                                                                                                      = AtomicInteger(0)
    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      Left(RuntimeError("test", "not used"))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      Left(RuntimeError("test", "not used"))
    override def close(): Unit =
      val _ = closes.incrementAndGet()

  test("the JDK transport closes its client, and the next request opens a new one") {
    withServer("""{"ok": true}""") { port =>
      val transport = JdkHttpTransport(HttpTimeouts())
      val url       = s"http://${loopback.getHostAddress}:$port/v1/chat/completions"
      assert(!transport.isOpen)
      assertEquals(transport.sendJson(url, Map.empty, "{}").map(_.status), Right(200))
      assert(transport.isOpen)
      transport.close()
      assert(!transport.isOpen)
      transport.close()
      assertEquals(transport.sendJson(url, Map.empty, "{}").map(_.status), Right(200))
      assert(transport.isOpen)
      transport.close()
    }
  }

  test("closing an OpenAI model closes its transport, and a copy shares that transport") {
    val transport = Counting()
    val model     = OpenAiLanguageModel(model = "m", client = OpenAiClient(apiKey = "x", transport = transport))
    model.close()
    model.copy(model = "other").close()
    assertEquals(transport.closes.get(), 2)
  }

  test("closing an OpenAI embedder closes its transport") {
    val transport = Counting()
    OpenAiEmbedder(model = "e", client = OpenAiClient(apiKey = "x", transport = transport)).close()
    assertEquals(transport.closes.get(), 1)
  }

  test("a managed model closes its delegate, and a model with no resources closes with no effect") {
    val closes   = AtomicInteger(0)
    val delegate = new LanguageModel:
      override val id: String                                                                    = "d"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector.empty))
      override def close(): Unit =
        val _ = closes.incrementAndGet()
    ManagedLanguageModel(delegate = delegate).close()
    assertEquals(closes.get(), 1)
    val plain = new LanguageModel:
      override val id: String                                                                    = "p"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(
          outputs = Vector.empty,
          modelName = Some(DynamicValues.renderText(DynamicValues.fromAny("p")))
        ))
    plain.close()
  }
