package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.LmUsage

final case class OpenAiClient(
    apiKey: String,
    baseUrl: String = OpenAiClient.defaultBaseUrl,
    transport: HttpTransport = HttpTransport.jdk(),
    chatEndpoint: String = "/chat/completions"
):
  private val defaultHeaders: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey"
  )

  private val streamOptionsIncludeUsage: Map[String, Any] =
    Map("stream_options" -> Map("include_usage" -> true))

  private val providerInternalKeys: Set[String] = Set("mode")

  private def outgoingPayload(payload: Map[String, Any]): Map[String, Any] =
    JsonCodec.stripNone(payload).filterNot((k, _) => providerInternalKeys.contains(k))

  def invoke(payload: Map[String, Any]): Either[DspyError, Map[String, Any]] =
    val url = s"${baseUrl.stripSuffix("/")}$chatEndpoint"
    val body = JsonCodec.encodeString(outgoingPayload(payload))
    transport.sendJson(url, defaultHeaders, body).flatMap { response =>
      if response.status < 200 || response.status >= 300 then
        Left(statusError(response.status, response.body))
      else
        JsonCodec.decodeString(response.body)
    }

  def stream(payload: Map[String, Any]): Either[DspyError, ClosableIterator[LmChunk]] =
    val url = s"${baseUrl.stripSuffix("/")}$chatEndpoint"
    val withStreaming = payload + ("stream" -> true) ++ streamOptionsIncludeUsage
    val body = JsonCodec.encodeString(outgoingPayload(withStreaming))
    transport.streamSse(url, defaultHeaders, body).flatMap { response =>
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
                JsonCodec.decodeString(data) match
                  case Right(value) =>
                    pending = chunkFromPayload(value)
                    return
                  case Left(_) => ()
          // Lines exhausted without an explicit [DONE]: the upstream response is
          // finished, so release the connection instead of leaving it for GC.
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

  private def chunkFromPayload(payload: Map[String, Any]): LmChunk =
    val choice = payload.get("choices").flatMap(asVector).flatMap(_.headOption).flatMap(asMap)
    val delta = choice.flatMap(_.get("delta")).flatMap(asMap)

    val text = delta.flatMap(_.get("content")).collect { case s: String => s }.getOrElse("")
    val finishReason = choice.flatMap(_.get("finish_reason")).collect { case s: String => s }
    val usage = payload.get("usage").flatMap(asMap).map(parseUsage)
    val toolCalls = delta
      .flatMap(_.get("tool_calls"))
      .flatMap(asVector)
      .map(parseToolCallDeltas)
      .getOrElse(Vector.empty)

    LmChunk(text = text, finishReason = finishReason, usage = usage, toolCalls = toolCalls, raw = Some(payload))

  private def parseToolCallDeltas(entries: Vector[Any]): Vector[LmToolCallDelta] =
    entries.zipWithIndex.flatMap { case (raw, fallbackIdx) =>
      asMap(raw).map { entry =>
        val index = asLong(entry.get("index")).map(_.toInt).getOrElse(fallbackIdx)
        val id = entry.get("id").collect { case s: String => s }
        val function = entry.get("function").flatMap(asMap)
        val name = function.flatMap(_.get("name")).collect { case s: String => s }
        val arguments = function.flatMap(_.get("arguments")).collect { case s: String => s }
        LmToolCallDelta(index = index, id = id, name = name, argumentsFragment = arguments)
      }
    }

  private def parseUsage(map: Map[String, Any]): LmUsage =
    val promptTokens = asLong(map.get("prompt_tokens")).getOrElse(0L)
    val completionTokens = asLong(map.get("completion_tokens")).getOrElse(0L)
    val totalTokens = asLong(map.get("total_tokens")).getOrElse(promptTokens + completionTokens)
    val details = map.iterator.collect { case (k, v) if asLong(Some(v)).isDefined => k -> asLong(Some(v)).get }.toMap
    LmUsage(
      totalTokens = totalTokens,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      details = details
    )

  private def statusError(status: Int, body: String): DspyError =
    val code = status match
      case 401 | 403          => "openai_auth"
      case 404                => "openai_not_found"
      case 429                => "openai_rate_limit"
      case s if s >= 500      => "openai_server"
      case _                  => "openai_http"
    RuntimeError(code, s"OpenAI HTTP $status: ${body.take(400)}")

  private def asVector(value: Any): Option[Vector[Any]] = value match
    case v: Vector[?] => Some(v)
    case s: Seq[?]    => Some(s.toVector)
    case _            => None

  private def asMap(value: Any): Option[Map[String, Any]] = value match
    case m: Map[?, ?] => Some(m.iterator.collect { case (k: String, v) => k -> v }.toMap)
    case _            => None

  private def asLong(value: Option[Any]): Option[Long] = value match
    case Some(v: Long)       => Some(v)
    case Some(v: Int)        => Some(v.toLong)
    case Some(v: Double)     => Some(v.toLong)
    case Some(v: Float)      => Some(v.toLong)
    case Some(v: String)     => v.toLongOption
    case _                   => None

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
