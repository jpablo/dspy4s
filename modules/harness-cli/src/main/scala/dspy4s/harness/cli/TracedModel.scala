package dspy4s.harness.cli

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest, LmResponse}
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.util.Try

/** A model that writes each call of `inner` to `file` as one JSON line (`DSPY_TRACE`). A live test reads the file to
  * see the exact prompt and reply of each step, also while the session runs.
  *
  * A line holds the number of the call from 0, the model of the request, the time of the call in milliseconds, the
  * messages of the request with their roles, and the outputs of the reply (the text and the reasoning) or the error.
  * `mask` hides the secrets of each text (5.6). The traced model has the capabilities of `inner`, so the adapter makes
  * the same prompt. A failed write of the file prints one line to the standard error and does not stop the call.
  */
final class TracedModel(inner: LanguageModel, file: Path, mask: String => String) extends LanguageModel:
  override val id: String                          = inner.id
  override val mode: LmMode                        = inner.mode
  override def supportsFunctionCalling: Boolean    = inner.supportsFunctionCalling
  override def supportsResponseSchema: Boolean     = inner.supportsResponseSchema
  override def supportsReasoning: Boolean          = inner.supportsReasoning
  override def supportsDecisionRequests: Boolean   = inner.supportsDecisionRequests
  override def defaultOptions: DynamicValue.Record = inner.defaultOptions
  override def cacheIdentity: DynamicValue.Record  = inner.cacheIdentity
  override def close(): Unit                       = inner.close()
  private val calls                                = AtomicInteger(0)
  private val warned                               = AtomicBoolean(false)

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val n        = calls.getAndIncrement()
    val start    = System.nanoTime()
    val response = inner.call(request)
    write(TracedModel.line(n, request, response, (System.nanoTime() - start) / 1_000_000, mask))
    response

  private def write(line: String): Unit =
    synchronized {
      Try(
        Files.writeString(file, line + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      ).failed.foreach { e =>
        if !warned.getAndSet(true) then System.err.println(s"The trace $file did not take a call: ${e.getMessage}")
      }
    }

object TracedModel:

  /** The JSON line of one call. */
  private[cli] def line(
      n       : Int,
      request : LmRequest,
      response: Either[DspyError, LmResponse],
      millis  : Long,
      mask    : String => String
  ): String =
    def text(value: String): Json = Json.String(mask(value))
    val messages                  = request.messages.map { m =>
      Json.Object(
        "role" -> Json.String(m.role.toString),
        "text" -> text(m.text.getOrElse(m.parts.map(_.payload).mkString("\n")))
      )
    }
    val result = response match
      case Right(reply) => "outputs" ->
          Json.Array(reply.outputs.map(o => Json.Object("text" -> text(o.text), "reasoning" -> text(o.reasoning)))*)
      case Left(error) => "error" -> text(error.message)
    Json.Object(
      "call"     -> Json.Number(n),
      "model"    -> Json.String(request.model),
      "millis"   -> Json.Number(millis),
      "messages" -> Json.Array(messages*),
      result
    ).print
