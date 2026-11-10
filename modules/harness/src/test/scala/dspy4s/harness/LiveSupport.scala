package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.harness.kernel.*
import dspy4s.lm.contracts.{LanguageModel, LmCapabilities, LmMode, LmRequest, LmResponse}
import dspy4s.lm.providers.{OpenAiClient, OpenAiLanguageModel}
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer

/** The helpers of the live runs (CODE_HARNESS_DESIGN.md, 9). A live run needs `HARNESS_LIVE=true`. It reads the
  * endpoint from `OPENAI_BASE_URL`, the model from `DSPY_MODEL` and the key from `OPENAI_API_KEY`, and it writes its
  * files to the folder `HARNESS_LIVE_OUT`.
  */
object LiveSupport:

  val live: Boolean = sys.env.get("HARNESS_LIVE").contains("true")

  /** The folder `sub` of the output folder, made when it does not exist. */
  def out(sub: String): Path =
    Files.createDirectories(Path.of(sys.env.getOrElse("HARNESS_LIVE_OUT", "target/harness-live")).resolve(sub))

  /** A model that records each request and each response of another model, with the time of each call. */
  final class Recording(inner: LanguageModel) extends LanguageModel:
    override val id: String                                                  = inner.id
    override val mode: LmMode                                                = inner.mode
    override def supportsFunctionCalling: Boolean                            = inner.supportsFunctionCalling
    override def supportsResponseSchema: Boolean                             = inner.supportsResponseSchema
    override def supportsReasoning: Boolean                                  = inner.supportsReasoning
    override def defaultOptions: DynamicValue.Record                         = inner.defaultOptions
    val calls: ArrayBuffer[(LmRequest, Either[DspyError, LmResponse], Long)] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val start    = System.nanoTime()
      val response = inner.call(request)
      synchronized(calls += ((request, response, (System.nanoTime() - start) / 1_000_000))): Unit
      response

  /** The model of the endpoint. The port declares no capability for a server that is not OpenAI, so this run declares
    * the native reasoning of the model, and the prompt has no reasoning field. `HARNESS_LIVE_NATIVE_REASONING=false`
    * turns the declaration off.
    */
  def model(): LanguageModel =
    val name = sys.env.getOrElse("DSPY_MODEL", "none")
    val base = sys.env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    val lm   = OpenAiLanguageModel.fromEnv(name, base).fold(e => throw IllegalStateException(s"no model: $e"), m => m)
    if sys.env.get("HARNESS_LIVE_NATIVE_REASONING").contains("false") then lm
    else lm.copy(capabilities = Some(LmCapabilities(reasoning = true)))

  /** One entry of a journal as Markdown. */
  def render(entry: Entry): String =
    entry match
      case Entry.Acted(thought, text, code) => s"### Acted (reasoning: ${thought.length} characters)\n\n$text\n\n" +
          code.fold("(no code)")(c => s"```scala\n$c\n```")
      case Entry.EffectStarted(id, op, args)       => s"- call ${id.n}: `$op` ${args.print.take(400)}"
      case Entry.EffectDone(id, result)            => s"- done ${id.n}: ${result.toString.take(400)}"
      case Entry.Observed(output, outcome, writes) =>
        s"### Observed: ${outcome.toString.take(300)}, writes ${writes.keys.mkString(", ")}\n\n```\n${output.take(4000)}\n```"
      case Entry.StepStarted(step)  => s"## Step $step"
      case Entry.TurnEnded(outcome) => s"## TurnEnded: ${outcome.toString.take(500)}"
      case Entry.StopRequested(r)   => s"## Stop: $r"
      case Entry.Activated(_, _)    => "## Activated"
      case other                    => s"- ${other.toString.take(300)}"

  /** The messages of a request as Markdown. */
  def text(request: LmRequest): String =
    request.messages.map(m => s"## ${m.role}\n\n${m.text.getOrElse(m.parts.map(_.payload).mkString("\n"))}")
      .mkString("\n\n")

  /** The raw replies of the model as Markdown, with the time of each call. */
  def replies(lm: Recording): String =
    lm.calls.zipWithIndex.map { case ((_, response, millis), k) =>
      val body = response.fold(
        e => s"error: $e",
        r => r.outputs.map(o => s"reasoning: ${o.reasoning.length} characters\n\n```\n${o.text}\n```").mkString("\n")
      )
      s"### Model call $k ($millis ms)\n\n$body"
    }.mkString("\n\n")

  /** Adds `line` at the end of the file `path`. */
  def append(path: Path, line: String): Unit =
    Files.writeString(path, line + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND): Unit
