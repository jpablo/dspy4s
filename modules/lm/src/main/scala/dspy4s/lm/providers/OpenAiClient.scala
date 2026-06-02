package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.lm.contracts.LmChunk
import zio.blocks.schema.DynamicValue

final case class OpenAiClient(
    apiKey: String,
    baseUrl: String = OpenAiClient.defaultBaseUrl,
    transport: HttpTransport = HttpTransport.jdk(),
    chatEndpoint: String = "/chat/completions"
):
  private val defaultHeaders: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey"
  )

  private val providerInternalKeys: Set[String] = Set(WireKeys.mode)

  /** Drop dspy4s-internal keys; `DynamicJson.encode` handles null-stripping. */
  private def outgoingJson(payload: DynamicValue): String =
    val filtered = payload match
      case rec: DynamicValue.Record => DynamicValues.recordFilterKeys(rec, k => !providerInternalKeys.contains(k))
      case other                    => other
    DynamicJson.encode(filtered)

  def invoke(payload: DynamicValue): Either[DspyError, DynamicValue] =
    val url = s"${baseUrl.stripSuffix("/")}$chatEndpoint"
    transport.sendJson(url, defaultHeaders, outgoingJson(payload)).flatMap { response =>
      if response.status < 200 || response.status >= 300 then
        Left(statusError(response.status, response.body))
      else
        DynamicJson.decode(response.body)
    }

  def stream(payload: DynamicValue): Either[DspyError, ClosableIterator[LmChunk]] =
    val url = s"${baseUrl.stripSuffix("/")}$chatEndpoint"
    val withStreaming = payload match
      case rec: DynamicValue.Record =>
        val streamOptions = DynamicValues.recordFromEntries(Seq(WireKeys.includeUsage := true))
        rec
          .updated(WireKeys.stream, DynamicValues.fromAny(true))
          .updated(WireKeys.streamOptions, streamOptions)
      case other => other
    transport.streamSse(url, defaultHeaders, outgoingJson(withStreaming)).flatMap { response =>
      if response.status < 200 || response.status >= 300 then
        val buffered = new StringBuilder
        val draining = response.dataLines
        try
          while draining.hasNext do { val _ = buffered.append(draining.next()).append('\n') }
        finally draining.close()
        Left(statusError(response.status, buffered.toString))
      else
        Right(parseSse(response.dataLines))
    }

  private def parseSse(lines: ClosableIterator[String]): ClosableIterator[LmChunk] =
    new ClosableIterator[LmChunk]:
      private var innerClosed = false
      private var pending: LmChunk | Null = null

      private def advance(): Unit =
        if innerClosed then ()
        else
          pending = null
          while lines.hasNext do
            val line = lines.next()
            val trimmed = line.trim
            if trimmed.isEmpty then ()
            else if trimmed.startsWith("data:") then
              val data = trimmed.stripPrefix("data:").trim
              if data == "[DONE]" then
                close()
                return
              else
                OpenAiStreamChunk.decode(data) match
                  case Right(chunk) =>
                    pending = chunk.toLmChunk
                    return
                  case Left(_) => ()
          // Lines exhausted without an explicit [DONE]: release the connection rather than leaving it for GC.
          close()

      override def hasNext: Boolean =
        if innerClosed then false
        else if pending ne null then true
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
        if !innerClosed then
          innerClosed = true
          lines.close()

  private def statusError(status: Int, body: String): DspyError =
    val code = status match
      case 401 | 403     => "openai_auth"
      case 404           => "openai_not_found"
      case 429           => "openai_rate_limit"
      case s if s >= 500 => "openai_server"
      case _             => "openai_http"
    RuntimeError(code, s"OpenAI HTTP $status: ${body.take(400)}")

object OpenAiClient:
  val defaultBaseUrl: String = "https://api.openai.com/v1"

  def fromEnv(
      base: String = defaultBaseUrl,
      transport: HttpTransport = HttpTransport.jdk(),
      envVar: String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiClient] =
    sys.env.get(envVar) match
      case Some(value) if value.nonEmpty =>
        Right(OpenAiClient(apiKey = value, baseUrl = base, transport = transport))
      case _ =>
        Left(RuntimeError("openai_config", s"Missing '$envVar' environment variable"))
