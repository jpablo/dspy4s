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
    /** The path of the Responses API. A payload whose internal `mode` key is `responses` goes here (see [[invoke]]). */
    responsesEndpoint: String = "/responses"
):
  private val defaultHeaders: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey"
  )

  private val providerInternalKeys: Set[String] = Set(WireKeys.mode)

  /** The value of the internal `mode` key that selects the Responses API (the normalizer writes the mode in lower
    * case).
    */
  private val responsesMode: String = LmMode.Responses.toString.toLowerCase

  /** The payload with no dspy4s-internal key. */
  private def withoutInternalKeys(payload: DynamicValue): DynamicValue =
    payload match
      case rec: DynamicValue.Record => DynamicValues.recordFilterKeys(rec, k => !providerInternalKeys.contains(k))
      case other                    => other

  /** Drop dspy4s-internal keys; `DynamicJson.encode` handles null-stripping. */
  private def outgoingJson(payload: DynamicValue): String =
    DynamicJson.encode(withoutInternalKeys(payload))

  /** POST the payload and decode the JSON body.
    *
    * When the internal `mode` key of the payload is `responses`, the request goes to [[responsesEndpoint]], and the
    * body is the Responses body of the payload (`ResponsesWire.request`, upstream `chat_to_responses`). Else the
    * request goes to [[chatEndpoint]] with the chat body. The internal keys never reach the wire. A response with a
    * status outside 200 to 299 becomes the LM error that `OpenAiErrors.fromResponse` gives. A failure of the transport
    * keeps its class and gets the model and the provider.
    */
  def invoke(payload: DynamicValue): Either[DspyError, DynamicValue] =
    val responses        = DynamicJson.field(payload, WireKeys.mode).flatMap(DynamicJson.asString).contains(responsesMode)
    val (endpoint, body) = withoutInternalKeys(payload) match
      case chat: DynamicValue.Record if responses => (responsesEndpoint, ResponsesWire.request(chat))
      case other                                  => (chatEndpoint, other)
    val url   = s"${baseUrl.stripSuffix("/")}$endpoint"
    val model = modelOf(payload)
    transport.sendJson(url, defaultHeaders, DynamicJson.encode(body))
      .left.map(OpenAiErrors.withRequestDetails(_, model))
      .flatMap { response =>
        if response.status < 200 || response.status >= 300 then
          Left(OpenAiErrors.fromResponse(response.status, response.headers, response.body, model))
        else
          DynamicJson.decode(response.body)
      }

  /** Open an SSE stream. A failure before the first chunk gives the same LM error as [[invoke]]. The stream always goes
    * to [[chatEndpoint]] with the chat body: the port has no parser for the events of a Responses stream.
    *
    * The stream rules of upstream `parse_stream_events` and `checked_stream`:
    *   - A chunk with a `finish_reason`, a chunk with `usage`, or the line `[DONE]` is an end event. `[DONE]` ends the
    *     stream. When the lines end with no end event, the last chunk is an [[LmChunk.failure]] with an
    *     [[dspy4s.core.contracts.LmStreamAssemblyError]].
    *   - A `data:` line that is not valid JSON gives an [[LmChunk.failure]] with an `LmStreamAssemblyError`, and the
    *     stream ends. A JSON value that is not an object is skipped. An object that the chunk codec cannot read is
    *     skipped too, if it has no `error` object (upstream reads it leniently).
    *   - An object with an `error` object gives an [[LmChunk.failure]] with the typed error of the error code (`code`,
    *     else `type`), and the stream ends. The table is upstream `_stream_error_code_map`: for example
    *     `context_length_exceeded` gives a [[dspy4s.core.contracts.ContextWindowExceededError]], and a code that is not
    *     in the table gives an [[dspy4s.core.contracts.LmProviderResponseError]]. The message is the `message` of the
    *     error, else the code. The details have the model, the provider, and the code.
    *
    * The connection closes when the stream ends. When the transport ended the lines early
    * ([[HttpStreamResponse.failure]]), the last chunk is an [[LmChunk.failure]] with that error, in place of the
    * `LmStreamAssemblyError` of a stream with no end event. The error gets the model and the provider. This is also
    * true after an end event that is not `[DONE]` (upstream raises the transport error while it reads the lines).
    */
  def stream(payload: DynamicValue): Either[DspyError, ClosableIterator[LmChunk]] =
    val url           = s"${baseUrl.stripSuffix("/")}$chatEndpoint"
    val model         = modelOf(payload)
    val withStreaming = payload match
      case rec: DynamicValue.Record =>
        val streamOptions = DynamicValues.recordFromEntries(Seq(WireKeys.includeUsage := true))
        rec
          .updated(WireKeys.stream, DynamicValues.fromAny(true))
          .updated(WireKeys.streamOptions, streamOptions)
      case other => other
    transport.streamSse(url, defaultHeaders, outgoingJson(withStreaming))
      .left.map(OpenAiErrors.withRequestDetails(_, model))
      .flatMap { response =>
        if response.status < 200 || response.status >= 300 then
          val buffered = new StringBuilder
          val draining = response.dataLines
          try while draining.hasNext do
              val _ = buffered.append(draining.next()).append('\n')
          finally draining.close()
          Left(OpenAiErrors.fromResponse(response.status, response.headers, buffered.toString, model))
        else
          Right(parseSse(response.dataLines, response.failure, model))
      }

  /** The chunks of the `data:` lines, with the stream rules of [[stream]]. `failure` is the reason why the transport
    * ended the lines early ([[HttpStreamResponse.failure]]).
    */
  private def parseSse(
      lines  : ClosableIterator[String],
      failure: () => Option[DspyError],
      model  : Option[String]
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

      private def read(data: String): Unit =
        OpenAiStreamChunk.decode(data) match
          case Right(chunk) => chunk.errorObject match
              case Some(error) => finish(LmChunk.failure(OpenAiErrors.fromStreamError(error, model)))
              case None        =>
                val out = chunk.toLmChunk
                if out.finishReason.isDefined then stopped = true
                // Upstream: a chunk with a finish reason, or with usage, is an end event.
                if out.finishReason.isDefined || out.usage.isDefined then ended = true
                pending = out
          case Left(_) => DynamicJson.decode(data) match
              case Left(_) =>
                finish(LmChunk.failure(OpenAiErrors.streamAssembly("An OpenAI stream line is not valid JSON", model)))
              case Right(record: DynamicValue.Record) =>
                // Upstream reads an object that does not fit the chunk shape leniently, with no error. The codec
                // cannot read it, so the parser skips it, except for its error object.
                DynamicJson.field(record, "error").flatMap(DynamicJson.asRecord) match
                  case Some(error) => finish(LmChunk.failure(OpenAiErrors.fromStreamError(error, model)))
                  case None        => ()
              // Upstream skips a JSON value that is not an object.
              case Right(_) => ()

      private def advance(): Unit =
        while (pending eq null) && !finished do
          if lines.hasNext then
            val trimmed = lines.next().trim
            if trimmed.startsWith("data:") then
              val data = trimmed.stripPrefix("data:").trim
              if data == "[DONE]" then end(done = true)
              else if data.nonEmpty then read(data)
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

  private def modelOf(payload: DynamicValue): Option[String] =
    DynamicJson.field(payload, WireKeys.model).flatMap(DynamicJson.asString)

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
