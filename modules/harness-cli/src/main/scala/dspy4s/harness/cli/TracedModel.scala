package dspy4s.harness.cli

import dspy4s.core.contracts.{ClosableIterator, DspyError, LmUsage, RuntimeContext}
import dspy4s.lm.contracts.{LanguageModel, LmChunk, LmMode, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.lm.runtime.StreamAccumulator
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
  private val trace                                = TraceFile(file, mask)

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    trace.call(request)(inner.call(request))

/** A streaming model that writes each call of `inner` to `file` as `TracedModel` does (`DSPY_TRACE`), so a traced
  * session still streams (CLI_SCREEN.md, 13). A `call` writes its line at its end. A `stream` gives the chunks of
  * `inner` with no change, and writes the line of the whole reply once, when the stream ends or closes.
  */
final class TracedStreams(inner: StreamingLanguageModel, file: Path, mask: String => String)
    extends StreamingLanguageModel:
  override val id: String                          = inner.id
  override val mode: LmMode                        = inner.mode
  override def supportsFunctionCalling: Boolean    = inner.supportsFunctionCalling
  override def supportsResponseSchema: Boolean     = inner.supportsResponseSchema
  override def supportsReasoning: Boolean          = inner.supportsReasoning
  override def supportsDecisionRequests: Boolean   = inner.supportsDecisionRequests
  override def defaultOptions: DynamicValue.Record = inner.defaultOptions
  override def cacheIdentity: DynamicValue.Record  = inner.cacheIdentity
  override def close(): Unit                       = inner.close()
  private val trace                                = TraceFile(file, mask)

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    trace.call(request)(inner.call(request))

  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    val n      = trace.next()
    val start  = System.nanoTime()
    val chunks = inner.stream(request)
    val parts  = StreamAccumulator(inner.mode)
    new ClosableIterator[LmChunk]:
      private var usage: Option[LmUsage]     = None
      private var failure: Option[DspyError] = None
      private var cached: Option[LmResponse] = None
      private var written                    = false

      override def hasNext: Boolean =
        val more = chunks.hasNext
        if !more then finish()
        more

      override def next(): LmChunk =
        val chunk = chunks.next()
        chunk.cachedResponse match
          case Some(response) => cached = Some(response)
          case None           => chunk.reportedFailure match
              case Some(error) => failure = Some(error)
              case None        =>
                parts.push(chunk)
                chunk.usage.foreach(found => usage = Some(found))
        chunk

      override def close(): Unit =
        try chunks match
            case closable: AutoCloseable => closable.close()
            case _                       => ()
        finally finish()

      /** Writes the line of the call once: the cached response, the failure, or the reply of the chunks so far. */
      private def finish(): Unit =
        if !written then
          written = true
          val response = cached.map(Right(_)).orElse(failure.map(Left(_)))
            .getOrElse(Right(LmResponse(outputs = Vector(parts.output), usage = usage)))
          trace.write(n, request, response, start)

/** The file of a trace: the number of the next call, and one line for each call. A failed write prints one line to the
  * standard error and does not stop the call.
  */
private final class TraceFile(file: Path, mask: String => String):
  private val calls  = AtomicInteger(0)
  private val warned = AtomicBoolean(false)

  /** The result of `run`, the call of `request`, with its line in the file. */
  def call(request: LmRequest)(run: => Either[DspyError, LmResponse]): Either[DspyError, LmResponse] =
    val n        = next()
    val start    = System.nanoTime()
    val response = run
    write(n, request, response, start)
    response

  /** The number of a new call. */
  def next(): Int = calls.getAndIncrement()

  /** Writes the line of the call `n` of `request`, which started at `start` (`System.nanoTime`). */
  def write(n: Int, request: LmRequest, response: Either[DspyError, LmResponse], start: Long): Unit =
    val line = TracedModel.line(n, request, response, (System.nanoTime() - start) / 1_000_000, mask)
    synchronized {
      Try(
        Files.writeString(file, line + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      ).failed.foreach { e =>
        if !warned.getAndSet(true) then System.err.println(s"The trace $file did not take a call: ${e.getMessage}")
      }
    }

object TracedModel:

  /** `inner` with the trace of its calls in `file`. A model that streams gives a model that streams. */
  def of(inner: LanguageModel, file: Path, mask: String => String): LanguageModel =
    inner match
      case streaming: StreamingLanguageModel => TracedStreams(streaming, file, mask)
      case other                             => TracedModel(other, file, mask)

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
