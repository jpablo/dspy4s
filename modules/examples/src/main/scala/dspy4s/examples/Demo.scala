package dspy4s.examples

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{CallbackHandler, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.providers.{OpenAiClient, OpenAiLanguageModel}
import io.modelcontextprotocol.client.transport.{ServerParameters, StdioClientTransport}
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpClientTransport

import java.nio.file.Paths
import scala.util.control.NonFatal

/** Shared setup for the example `@main`s.
  *
  * Each ported example file has a uniquely-named `@main` (e.g. `modulesMain`) so that files sharing a package don't
  * collide on a single `main` entry point. Run one with, e.g.:
  *
  * OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
  */
object Demo:

  /** Make an OpenAI LM from the environment, install it as the active [[RuntimeContext]] with `adapter` and
    * `callbacks`, run `body` with that context as the ambient `given`, and close the LM.
    *
    * The model comes from `DSPY_MODEL`, else `defaultModel`. The base URL comes from `OPENAI_BASE_URL`, else the
    * default of the client, so each OpenAI-compatible endpoint works. `OPENAI_API_KEY` must be set, and a missing key
    * is an error.
    */
  def withOpenAi(
      defaultModel: String                  = "gpt-5.5",
      adapter     : Adapter                 = ChatAdapter(),
      callbacks   : Vector[CallbackHandler] = Vector.empty
  )(
      body: OpenAiLanguageModel => RuntimeContext ?=> Unit
  ): Unit =
    val model   = sys.env.getOrElse("DSPY_MODEL", defaultModel)
    val baseUrl = sys.env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    OpenAiLanguageModel.fromEnv(model, baseUrl) match
      case Left(err) => sys.error(s"Could not initialize LM (is OPENAI_API_KEY set?): $err")
      case Right(lm) =>
        val context = RuntimeContext(
          lm = Some(lm),
          adapter = Some(adapter),
          callbacks = Option.when(callbacks.nonEmpty)(callbacks)
        )
        try RuntimeEnvironment.withSettings(context) {
            body(lm)(using RuntimeEnvironment.current)
          }
        finally lm.close()

  /** [[withOpenAi]] with the default model, a `ChatAdapter`, and no callbacks. */
  def withLm(body: RuntimeContext ?=> Unit): Unit = withOpenAi()(_ => body)

  /** The positive integer in the environment variable `name`, else `default`. */
  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

  /** True when Deno is on the path. `RLM` needs Deno for its sandbox. */
  def denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  /** A stdio transport to the `@main` `mainClass` in a child JVM: the `java` of this JVM, with the class path of this
    * JVM. The stderr of the child JVM goes to the stderr of this JVM, so a bad class path shows its cause. The default
    * handler of the SDK writes the stderr lines to SLF4J, and the examples have no SLF4J provider.
    */
  def childJvmTransport(mainClass: String): McpClientTransport =
    val javaBin    = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val parameters = ServerParameters
      .builder(javaBin)
      .args("-cp", System.getProperty("java.class.path"), mainClass)
      .build()
    val transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper())
    transport.setStdErrorHandler(line => System.err.println(line))
    transport
