package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import zio.blocks.schema.DynamicValue

final case class OpenAiClient(
    apiKey      : String,
    baseUrl     : String        = OpenAiClient.defaultBaseUrl,
    transport   : HttpTransport = HttpTransport.jdk(),
    chatEndpoint: String        = "/chat/completions",
    /** The path of the Responses API. A request in the mode `LmMode.Responses` goes here (see [[invoke]]). */
    responsesEndpoint: String = "/responses"
):
  private val headers: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey"
  )

  /** The value of the `stream` key of a stream body. */
  private val streamOn: DynamicValue = DynamicValues.fromAny(true)

  /** POST `body` to `endpoint` (a path under [[baseUrl]]), and give the body text of a reply with a success status.
    *
    * A reply with a status outside 200 to 299 becomes the LM error that `OpenAiErrors.fromResponse` gives. A failure of
    * the transport keeps its class and gets the model and the provider. The chat path and the embedder use this
    * function, so one reply gives one error on each path.
    *
    * @param model
    *   the model of the request, for the details of an error
    */
  def post(endpoint: String, body: DynamicValue, model: Option[String]): Either[DspyError, String] =
    transport.sendJson(url(endpoint), headers, DynamicJson.encode(body))
      .left.map(OpenAiErrors.withRequestDetails(_, model))
      .flatMap { response =>
        if response.isSuccess then Right(response.body)
        else Left(OpenAiErrors.fromResponse(response.status, response.headers, response.body, model))
      }

  /** POST the body of a model call in `mode`, and decode the JSON reply.
    *
    * In the mode `LmMode.Responses`, the request goes to [[responsesEndpoint]]: `body` is then the Responses body
    * (`ResponsesWire.request`). In each other mode, it goes to [[chatEndpoint]]. The body goes as it is. The errors are
    * the errors of [[post]], and a reply that is not JSON is a `ParseError`.
    */
  def invoke(mode: LmMode, body: DynamicValue.Record): Either[DspyError, DynamicValue] =
    post(endpointOf(mode), body, modelOf(body)).flatMap(DynamicJson.decode)

  /** Close the transport (see [[HttpTransport.close]]). The client stays usable. */
  def close(): Unit = transport.close()

  /** Open an SSE stream for the body of a model call in `mode`. A failure before the first chunk gives the same LM
    * error as [[invoke]].
    *
    * In the mode `LmMode.Responses`, the stream goes to [[responsesEndpoint]]. The body is the Responses body with
    * `"stream": true` and no `stream_options` (upstream lm15 `OpenAILM._payload` in
    * `dspy/_vendor/lm15/providers/openai.py`), and each event gives the chunk of `ResponsesWire.streamChunk`. In each
    * other mode, the stream goes to [[chatEndpoint]] with the body, `"stream": true`, and
    * `stream_options.include_usage`.
    *
    * The stream rules of upstream `parse_stream_events` and `checked_stream`:
    *   - A chunk with a finish reason, a chunk with usage, or the line `[DONE]` is an end event. `[DONE]` ends the
    *     stream. When the lines end with no end event, the last chunk is an [[LmChunk.failure]] with an
    *     [[dspy4s.core.contracts.LmStreamAssemblyError]]. In the Responses mode, the end event is the
    *     `response.completed` event.
    *   - A `data:` line that is not valid JSON gives an [[LmChunk.failure]] with an `LmStreamAssemblyError`, and the
    *     stream ends.
    *   - Chat mode: a JSON value that is not an object is skipped. An object that the chunk codec cannot read gives the
    *     fields that have the right type (`OpenAiStreamChunk.lenient`, upstream reads each field with an `isinstance`
    *     check).
    *   - Chat mode: an object with an `error` object gives an [[LmChunk.failure]] with the typed error of the error
    *     code (`code`, else `type`), and the stream ends. The table is upstream `_stream_error_code_map`: for example
    *     `context_length_exceeded` gives a [[dspy4s.core.contracts.ContextWindowExceededError]], and a code that is not
    *     in the table gives an [[dspy4s.core.contracts.LmProviderResponseError]]. The message is the `message` of the
    *     error, else the code. The details have the model, the provider, and the code.
    *   - Responses mode: an `error` or `response.error` event gives an [[LmChunk.failure]] with the typed error of the
    *     same table, and the stream ends. A JSON value that is not an object gives an [[LmChunk.failure]] with an
    *     `LmStreamAssemblyError` (upstream reads the `type` of the event, and fails on a value that is not an object).
    *
    * The connection closes when the stream ends. When the transport ended the lines early
    * ([[HttpStreamResponse.failure]]), the last chunk is an [[LmChunk.failure]] with that error, in place of the
    * `LmStreamAssemblyError` of a stream with no end event. The error gets the model and the provider. This is also
    * true after an end event that is not `[DONE]` (upstream raises the transport error while it reads the lines).
    */
  def stream(mode: LmMode, body: DynamicValue.Record): Either[DspyError, ClosableIterator[LmChunk]] =
    val model              = modelOf(body)
    val (streamBody, read) = mode match
      case LmMode.Responses          => (body.updated(WireKeys.stream, streamOn), responsesEvent(model))
      case LmMode.Chat | LmMode.Text =>
        val streamOptions = DynamicValues.recordFromEntries(Seq(WireKeys.includeUsage := true))
        (body.updated(WireKeys.stream, streamOn).updated(WireKeys.streamOptions, streamOptions), chatChunk(model))
    transport.streamSse(url(endpointOf(mode)), headers, DynamicJson.encode(streamBody))
      .left.map(OpenAiErrors.withRequestDetails(_, model))
      .flatMap { response =>
        if response.isSuccess then Right(parseSse(response.dataLines, response.failure, model, read))
        else
          Left(OpenAiErrors.fromResponse(response.status, response.headers, drain(response.dataLines), model))
      }

  /** The URL of `endpoint`: [[baseUrl]] with no slash at its end, then `endpoint`. */
  private def url(endpoint: String): String = s"${baseUrl.stripSuffix("/")}$endpoint"

  /** The endpoint of a model call in `mode`. */
  private def endpointOf(mode: LmMode): String =
    mode match
      case LmMode.Responses          => responsesEndpoint
      case LmMode.Chat | LmMode.Text => chatEndpoint

  /** The text of all `lines`, one line each, for the error of a stream reply. The lines are closed after. */
  private def drain(lines: ClosableIterator[String]): String =
    val buffered = new StringBuilder
    try while lines.hasNext do
        val _ = buffered.append(lines.next()).append('\n')
    finally lines.close()
    buffered.toString

  /** The chunk of one `data:` JSON text of a chat stream: `Right(None)` for a text that gives no chunk, and a `Left`
    * for a failure that ends the stream (the rules of [[stream]]). The typed codec and the lenient reader give the same
    * chunk type, so this function has the one check of the error object.
    */
  private def chatChunk(model: Option[String])(data: String): Either[DspyError, Option[LmChunk]] =
    OpenAiStreamChunk.parse(data) match
      case Left(_)            => Left(notJson(model))
      case Right(None)        => Right(None)
      case Right(Some(chunk)) => chunk.errorObject match
          case Some(error) => Left(OpenAiErrors.fromStreamError(error, model))
          case None        => Right(Some(chunk.toLmChunk))

  /** The chunk of one `data:` JSON text of a Responses stream (the rules of [[stream]]). */
  private def responsesEvent(model: Option[String])(data: String): Either[DspyError, Option[LmChunk]] =
    DynamicJson.decode(data) match
      case Left(_)                           => Left(notJson(model))
      case Right(event: DynamicValue.Record) => ResponsesWire.streamChunk(event, model)
      case Right(_)                          => Left(notObject(model))

  private def notJson(model: Option[String]): DspyError =
    OpenAiErrors.streamAssembly("An OpenAI stream line is not valid JSON", model)

  private def notObject(model: Option[String]): DspyError =
    OpenAiErrors.streamAssembly("An OpenAI Responses stream event is not a JSON object", model)

  /** The chunks of the `data:` lines, with the stream rules of [[stream]]. `failure` is the reason why the transport
    * ended the lines early ([[HttpStreamResponse.failure]]). `read` gives the chunk of one `data:` JSON text.
    */
  private def parseSse(
      lines  : ClosableIterator[String],
      failure: () => Option[DspyError],
      model  : Option[String],
      read   : String => Either[DspyError, Option[LmChunk]]
  ): ClosableIterator[LmChunk] =
    new ClosableIterator[LmChunk]:
      private var finished                = false // No more lines are read, and the connection is closed.
      private var ended                   = false // An end event came.
      private var stopped                 = false // A chunk with a finish reason came.
      private var pending: LmChunk | Null = null

      /** Give `last` as the last chunk (`null` for no chunk), and close the connection. */
      private def finish(last: LmChunk | Null): Unit =
        pending = last
        finished = true
        lines.close()

      /** The end of the lines, or the `[DONE]` line. When the transport ended the lines early, the last chunk has the
        * failure of the transport. A complete stream with no chunk that has a finish reason gets a last chunk with the
        * finish reason `stop`, because the completeness rule of [[LmChunk]] reads the finish reason (upstream: the end
        * event has the default finish reason).
        */
      private def end(done: Boolean): Unit =
        val dropped = if done then None else failure()
        dropped match
          case Some(error) => finish(LmChunk.failure(OpenAiErrors.withRequestDetails(error, model)))
          case None        =>
            if stopped then finish(null)
            else if ended || done then finish(LmChunk(finishReason = Some("stop")))
            else
              finish(LmChunk.failure(OpenAiErrors.streamAssembly("The OpenAI stream ended with no end event", model)))

      private def accept(data: String): Unit =
        read(data) match
          case Left(error)      => finish(LmChunk.failure(error))
          case Right(Some(out)) =>
            if out.finishReason.isDefined then stopped = true
            // Upstream: a chunk with a finish reason, or with usage, is an end event.
            if out.finishReason.isDefined || out.usage.isDefined then ended = true
            pending = out
          case Right(None) => ()

      private def advance(): Unit =
        while (pending eq null) && !finished do
          if lines.hasNext then
            val trimmed = lines.next().trim
            if trimmed.startsWith("data:") then
              val data = trimmed.stripPrefix("data:").trim
              if data == "[DONE]" then end(done = true)
              else if data.nonEmpty then accept(data)
          else end(done = false)

      override def hasNext: Boolean =
        if pending ne null then true
        else
          advance()
          pending ne null

      override def next(): LmChunk =
        if pending eq null then advance()
        val out = pending
        if out eq null then throw new NoSuchElementException("SSE stream exhausted")
        pending = null
        out

      override def close(): Unit =
        pending = null
        if !finished then
          finished = true
          lines.close()

  /** The model of a body, for the details of an error. */
  private def modelOf(body: DynamicValue.Record): Option[String] =
    DynamicValues.recordGet(body, WireKeys.model).flatMap(DynamicJson.asString)

object OpenAiClient:
  val defaultBaseUrl: String = "https://api.openai.com/v1"

  /** Build a client with the API key from the environment. An `envVar` that is absent or empty gives
    * [[dspy4s.core.contracts.LmNotConfiguredError]].
    */
  def fromEnv(
      base     : String        = defaultBaseUrl,
      transport: HttpTransport = HttpTransport.jdk(),
      envVar   : String        = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiClient] =
    sys.env.get(envVar) match
      case Some(value) if value.nonEmpty => Right(OpenAiClient(apiKey = value, baseUrl = base, transport = transport))
      case _                             => Left(OpenAiErrors.notConfigured(envVar))
