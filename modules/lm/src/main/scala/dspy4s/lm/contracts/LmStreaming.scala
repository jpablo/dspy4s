package dspy4s.lm.contracts

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, RuntimeContext, RuntimeError, ToolCall}
import zio.blocks.schema.DynamicValue

/** A single tool-call delta extracted from one streaming LM chunk. OpenAI emits `id`/`function.name` on the first delta
  * for an index and `function.arguments` fragments on subsequent deltas; consumers accumulate by `index`.
  */
final case class LmToolCallDelta(
    index            : Int,
    id               : Option[String] = None,
    name             : Option[String] = None,
    argumentsFragment: Option[String] = None
)

/** One chunk of a streamed LM call.
  *
  * A producer reports a failure as a terminal chunk, not as an exception. That chunk has `finishReason = Some("error")`
  * and the typed failure in `error`. Build it with [[LmChunk.failure]].
  *
  * A stream is complete when one of its chunks has a finish reason that is not `error` (upstream: the stream has its
  * end event). A chunk with only usage can come after that chunk. A stream that ends with no such chunk is not
  * complete: [[dspy4s.lm.runtime.ManagedLanguageModel]] reports it as a
  * [[dspy4s.core.contracts.LmStreamAssemblyError]].
  *
  * @param reasoning
  *   a part of the reasoning text (upstream lm15 `ThinkingDelta` in `dspy/_vendor/lm15/types.py`). The assembled output
  *   has the joined parts in [[LmOutput.reasoning]].
  * @param citations
  *   the citations that this chunk adds (upstream lm15 `CitationDelta`). Each citation has the form of a citation of
  *   [[LmOutput.citations]]. The assembled output has the citations of all chunks, in order.
  * @param cachedResponse
  *   the complete response of a cache hit. A chunk with this field is the only chunk of its stream. A consumer returns
  *   this response, and gives no token of it to a stream listener (upstream: a cache hit sends no stream chunk). The
  *   chunk also has the text, the reasoning, the citations, and the tool calls of the first output, for a consumer that
  *   assembles the chunks. Build it with [[LmChunk.cached]].
  */
final case class LmChunk(
    text          : String                      = "",
    finishReason  : Option[String]              = None,
    usage         : Option[LmUsage]             = None,
    toolCalls     : Vector[LmToolCallDelta]     = Vector.empty,
    reasoning     : String                      = "",
    citations     : Vector[DynamicValue.Record] = Vector.empty,
    error         : Option[DspyError]           = None,
    cachedResponse: Option[LmResponse]          = None
):
  def isFinal: Boolean = finishReason.isDefined

  /** The failure that this chunk reports, if any. The typed `error` wins, so a consumer gets the same `DspyError` that
    * the provider produced (a `ContextWindowExceededError` stays one). A chunk that has only `finishReason = "error"`
    * gives a generic `RuntimeError("streaming_lm", ...)`.
    */
  def reportedFailure: Option[DspyError] =
    error.orElse(Option.when(finishReason.contains("error"))(LmChunk.UnknownFailure))

object LmChunk:

  /** The terminal chunk that reports `error`. It keeps the type of the failure, so a consumer can return the same
    * `DspyError` that the provider produced.
    */
  def failure(error: DspyError): LmChunk =
    LmChunk(finishReason = Some("error"), error = Some(error))

  /** The failure of a chunk with the finish reason `error` and no typed `error`. */
  private val UnknownFailure: DspyError = RuntimeError("streaming_lm", "LM stream terminated with an error")

  /** The only chunk of a stream that the cache answered. `response` is the stored response, with `cacheHit = true`. The
    * chunk has the finish reason `stop`, no usage, and the text, the reasoning, the citations, and the tool calls of
    * the first output of `response`. Each tool call is one complete delta, so `ToolCallAssembler.assemble` gives the
    * call back with its id.
    */
  def cached(response: LmResponse): LmChunk =
    fromResponse(response).copy(cachedResponse = Some(response.copy(cacheHit = true)))

  /** One terminal chunk for a whole response: the text, the reasoning, the citations, and the tool calls of the first
    * output of `response`, and the finish reason `stop`. The chunk has no usage. Each tool call is one complete delta,
    * so `ToolCallAssembler.assemble` gives the call back with its id.
    */
  private[lm] def fromResponse(response: LmResponse): LmChunk =
    val output = response.outputs.headOption
    LmChunk(
      text = output.map(_.text).getOrElse(""),
      finishReason = Some("stop"),
      toolCalls = output.map(_.toolCalls).getOrElse(Vector.empty).zipWithIndex.map(toolCallDelta),
      reasoning = output.map(_.reasoning).getOrElse(""),
      citations = output.map(_.citations).getOrElse(Vector.empty)
    )

  /** One complete tool call as a stream delta: the id, the name, and the JSON text of the arguments. */
  private def toolCallDelta(call: ToolCall, index: Int): LmToolCallDelta =
    LmToolCallDelta(
      index = index,
      id = call.id,
      name = Some(call.name),
      argumentsFragment = Some(DynamicValues.encodeJson(call.args))
    )

trait StreamingLanguageModel extends LanguageModel:
  def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk]

  /** `true` when this model gives the stream output of a call of the predictor `predictName` to a listener (upstream
    * `Predict._should_stream`). `outputFields` are the names of the output fields of the predictor. A predictor that
    * cannot stream its output, for example a predictor with decision outputs, fails such a call before the request.
    *
    * The default is `false`: a model gives its output to a listener only in a `streamify` call, where the wrapper of
    * the streaming module is the model.
    */
  def streamsToListener(predictName: String, outputFields: Vector[String]): Boolean = false
