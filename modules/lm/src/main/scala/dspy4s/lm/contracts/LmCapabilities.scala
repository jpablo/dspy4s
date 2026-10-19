package dspy4s.lm.contracts

import java.net.URI
import scala.util.Try

/** What a model can do: the data behind [[LanguageModel.supportsFunctionCalling]],
  * [[LanguageModel.supportsResponseSchema]], and [[LanguageModel.supportsReasoning]] (decision L5, PORT_GAPS G-26;
  * upstream `Capabilities` in `dspy/clients/capabilities.py`). A capability that nobody declares is `false`, as
  * upstream treats an unknown capability.
  *
  * @param functionCalling
  *   the model takes tool definitions and can give native tool calls
  * @param responseSchema
  *   the model can follow a JSON response schema (the `response_format` of OpenAI)
  * @param reasoning
  *   the model gives reasoning output
  */
final case class LmCapabilities(
    functionCalling: Boolean = false,
    responseSchema : Boolean = false,
    reasoning      : Boolean = false
) derives CanEqual

object LmCapabilities:

  /** No capability. */
  val none: LmCapabilities = LmCapabilities()

  /** The capabilities of the OpenAI API: native tool calls and a JSON response schema. Reasoning output depends on the
    * model, so it is not declared.
    */
  val openAi: LmCapabilities = LmCapabilities(functionCalling = true, responseSchema = true)

  /** The capabilities that the port declares for an OpenAI-compatible server at `baseUrl` when the user declares none:
    * [[openAi]] when the host of the URL is `api.openai.com` (with no regard to case), else [[none]]. A URL that does
    * not parse gives [[none]]. A user of an other server that has these capabilities (for example Azure OpenAI, or a
    * vLLM server with tool support) declares them with `OpenAiLanguageModel.withCapabilities`.
    */
  def forBaseUrl(baseUrl: String): LmCapabilities =
    val host = Try(URI.create(baseUrl.trim)).toOption.flatMap(uri => Option(uri.getHost))
    if host.exists(_.equalsIgnoreCase(OpenAiHost)) then openAi else none

  /** The host of the official OpenAI API. */
  private val OpenAiHost: String = "api.openai.com"
