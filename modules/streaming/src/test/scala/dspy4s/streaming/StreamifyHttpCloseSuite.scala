package dspy4s.streaming

import com.sun.net.httpserver.HttpServer
import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A close of the stream, with the HTTP transport of a real model and a local server that does not stop its stream. */
class StreamifyHttpCloseSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A server that answers each request with a stream of tokens, until the write of a token fails. */
  private final class EndlessStream:
    val requests     = AtomicInteger(0)
    val started      = CountDownLatch(1)
    val disconnected = CountDownLatch(1)

    private val loopback = InetAddress.getLoopbackAddress
    private val pool     = Executors.newCachedThreadPool()
    private val server   = HttpServer.create(new InetSocketAddress(loopback, 0), 8)

    private def content(text: String): String =
      s"""data: {"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":null}]}\n\n"""

    private def write(out: OutputStream, text: String): Unit =
      out.write(text.getBytes(StandardCharsets.UTF_8))
      out.flush()

    server.setExecutor(pool)
    server.createContext(
      "/",
      exchange =>
        val _ = requests.incrementAndGet()
        val _ = exchange.getRequestBody.readAllBytes()
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.getResponseBody
        started.countDown()
        try
          write(out, content("[[ ## answer ## ]]\\n"))
          (1 to 6000).foreach { index =>
            write(out, content(s"token$index "))
            Thread.sleep(10)
          }
        catch case _: IOException => disconnected.countDown()
        finally exchange.close()
    )
    server.start()

    def url: String = s"http://${loopback.getHostAddress}:${server.getAddress.getPort}/v1"

    def stop(): Unit =
      server.stop(0)
      val _ = pool.shutdownNow()

  private def withServer(use: EndlessStream => Unit): Unit =
    val server = EndlessStream()
    try use(server)
    finally server.stop()

  private def rawModel(server: EndlessStream): OpenAiLanguageModel =
    OpenAiLanguageModel(model = "m", client = OpenAiClient(apiKey = "x", baseUrl = server.url))

  private def managedModel(server: EndlessStream): ManagedLanguageModel =
    ManagedLanguageModel(rawModel(server))

  private def twoSteps: DynamicModule =
    new DynamicModule:
      private val layout                                                                                   = SignatureDsl.parse("question -> answer").toOption.get
      private val first                                                                                    = DynamicPredict(layout = layout, name = Some("first"))
      private val second                                                                                   = DynamicPredict(layout = layout, name = Some("second"))
      override val moduleName: String                                                                      = "two_steps"
      override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using RuntimeContext) =
        first(input).flatMap(_ => second(input)).map(_.raw)

  private def assertStops(server: EndlessStream): Unit =
    assert(server.disconnected.await(10, TimeUnit.SECONDS), "the HTTP connection is open after the close")
    Thread.sleep(200)
    assertEquals(server.requests.get(), 1, "the program made another HTTP request after the close")

  private def closesAfterToken(lm: StreamingLanguageModel, server: EndlessStream): Unit =
    given RuntimeContext = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
    val stream           = Streamify.streamify(twoSteps, warningSink = _ => ()).apply(rec("question" := "q"))
    assert(stream.next().isInstanceOf[TokenEvent])
    stream.close()
    assertStops(server)

  private def closesWithNoToken(lm: StreamingLanguageModel, server: EndlessStream): Unit =
    given RuntimeContext = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
    val listeners        = Vector(StreamListener("other", predictName = Some("first")))
    val stream           =
      Streamify.streamify(twoSteps, streamListeners = listeners, warningSink = _ => ()).apply(rec("question" := "q"))
    assert(server.started.await(10, TimeUnit.SECONDS), "the program made no HTTP request")
    Thread.sleep(100)
    stream.close()
    assertStops(server)

  test("a close after a token closes the connection of a raw model, and no other request occurs") {
    withServer(server => closesAfterToken(rawModel(server), server))
  }

  test("a close after a token closes the connection of a managed model, and no other request occurs") {
    withServer(server => closesAfterToken(managedModel(server), server))
  }

  test("a close during a call with no token for a listener closes the connection of a raw model") {
    withServer(server => closesWithNoToken(rawModel(server), server))
  }

  test("a close during a call with no token for a listener closes the connection of a managed model") {
    withServer(server => closesWithNoToken(managedModel(server), server))
  }
