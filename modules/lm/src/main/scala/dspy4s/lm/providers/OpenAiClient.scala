package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.LmUsage
import zio.blocks.schema.DynamicValue

final case class OpenAiClient(
    apiKey: String,
    baseUrl: String = OpenAiClient.defaultBaseUrl,
    transport: HttpTransport = HttpTransport.jdk(),
    chatEndpoint: String = "/chat/completions"
):
  import DynamicJson.{field, asRecord, asString, asLong, asSequence}

  private val defaultHeaders: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey"
  )

  private val providerInternalKeys: Set[String] = Set("mode")

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
        val streamOptions = DynamicValues.recordFromEntries(Seq("include_usage" := true))
        DynamicValues.recordUpdated(
          DynamicValues.recordUpdated(rec, "stream", DynamicValues.fromAny(true)),
          "stream_options", streamOptions
        )
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
                DynamicJson.decode(data) match
                  case Right(value) =>
                    pending = chunkFromPayload(value)
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

  private def chunkFromPayload(payload: DynamicValue): LmChunk =
    val choice = field(payload, "choices").map(asSequence).flatMap(_.headOption).flatMap(asRecord)
    val delta  = choice.flatMap(c => field(c, "delta")).flatMap(asRecord)

    val text = delta.flatMap(d => field(d, "content")).flatMap(asString).getOrElse("")
    val finishReason = choice.flatMap(c => field(c, "finish_reason")).flatMap(asString)
    val usage = field(payload, "usage").flatMap(asRecord).map(parseUsage)
    val toolCalls = delta
      .flatMap(d => field(d, "tool_calls"))
      .map(asSequence)
      .map(parseToolCallDeltas)
      .getOrElse(Vector.empty)

    LmChunk(text = text, finishReason = finishReason, usage = usage, toolCalls = toolCalls, raw = Some(payload))

  private def parseToolCallDeltas(entries: Vector[DynamicValue]): Vector[LmToolCallDelta] =
    entries.zipWithIndex.flatMap { case (raw, fallbackIdx) =>
      asRecord(raw).map { entry =>
        val index = field(entry, "index").flatMap(asLong).map(_.toInt).getOrElse(fallbackIdx)
        val id = field(entry, "id").flatMap(asString)
        val function = field(entry, "function").flatMap(asRecord)
        val name = function.flatMap(f => field(f, "name")).flatMap(asString)
        val arguments = function.flatMap(f => field(f, "arguments")).flatMap(asString)
        LmToolCallDelta(index = index, id = id, name = name, argumentsFragment = arguments)
      }
    }

  private def parseUsage(usage: DynamicValue.Record): LmUsage =
    val promptTokens = field(usage, "prompt_tokens").flatMap(asLong).getOrElse(0L)
    val completionTokens = field(usage, "completion_tokens").flatMap(asLong).getOrElse(0L)
    val totalTokens = field(usage, "total_tokens").flatMap(asLong).getOrElse(promptTokens + completionTokens)
    val details = usage.fields.iterator.collect {
      case (k, v) if asLong(v).isDefined => k -> asLong(v).get
    }.toMap
    LmUsage(
      totalTokens = totalTokens,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      details = details
    )

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
