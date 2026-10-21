package dspy4s.lm.providers

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmBillingError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmInvalidRequestError
import dspy4s.core.contracts.LmNotConfiguredError
import dspy4s.core.contracts.LmProviderError
import dspy4s.core.contracts.LmProviderResponseError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmUnsupportedModelError
import zio.blocks.schema.DynamicValue

import java.time.DateTimeException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.matching.Regex

/** Gives each failure of an OpenAI-compatible endpoint its [[dspy4s.core.contracts.LmError]] class. The chat client,
  * the stream client, and the embedder share this object, so one response gives one class on each path.
  *
  * The rules mirror upstream `normalize_error` (`dspy/_vendor/lm15/providers/openai.py`), `map_http_error`
  * (`dspy/_vendor/lm15/errors.py`), and `_attach_error_metadata` (`dspy/_vendor/lm15/providers/base.py`).
  */
private[lm] object OpenAiErrors:
  val provider: String = "openai"

  /** Context-window overflow markers, mirroring upstream dspy `clients/lm.py` (`is_litellm_context_window_error`).
    * Matched case-insensitively against the HTTP 400 response body.
    */
  private val contextWindowMarkers: Seq[String] =
    Seq("context_length_exceeded", "maximum context length", "context window", "reduce the length")

  private val modelErrorCodes: Set[String] =
    Set("model_not_found", "model_not_available", "unsupported_model", "DeploymentNotFound")

  private type ErrorClass = (String, LmErrorDetails) => LmProviderError

  /** The class of each code of an error event in a stream (upstream `_stream_error_code_map`, which extends
    * `_response_error_code_map`). A code that is not in the table gives an [[LmProviderResponseError]].
    */
  private val streamErrorClasses: Map[String, ErrorClass] =
    val server: ErrorClass      = LmServerError(_, _)
    val rateLimit: ErrorClass   = LmRateLimitError(_, _)
    val timeout: ErrorClass     = LmTimeoutError(_, _)
    val invalid: ErrorClass     = LmInvalidRequestError(_, _)
    val unsupported: ErrorClass = LmUnsupportedModelError(_, _)
    val context: ErrorClass     = ContextWindowExceededError(_, _)
    val auth: ErrorClass        = LmAuthError(_, _)
    val billing: ErrorClass     = LmBillingError(_, _)
    val invalidCodes            = Seq(
      "invalid_prompt",
      "invalid_image",
      "invalid_image_format",
      "invalid_base64_image",
      "invalid_image_url",
      "image_too_large",
      "image_too_small",
      "image_parse_error",
      "image_content_policy_violation",
      "invalid_image_mode",
      "image_file_too_large",
      "unsupported_image_media_type",
      "empty_image_file",
      "failed_to_download_image",
      "image_file_not_found"
    )
    Map(
      "server_error"                 -> server,
      "rate_limit_exceeded"          -> rateLimit,
      "no_capacity"                  -> rateLimit,
      "too_many_requests"            -> rateLimit,
      "rate_limit_error"             -> rateLimit,
      "vector_store_timeout"         -> timeout,
      "context_length_exceeded"      -> context,
      "invalid_api_key"              -> auth,
      "authentication_error"         -> auth,
      "insufficient_quota"           -> billing,
      "1113"                         -> billing, // Z.AI: insufficient balance
      "exceeded_current_quota_error" -> billing  // Moonshot: balance or quota
    ) ++ invalidCodes.map(_ -> invalid) ++ modelErrorCodes.map(_ -> unsupported)

  /** The headers that can hold the request id, in the order of preference. The TypeSafe client reads them too. */
  private[providers] val requestIdHeaders: Seq[String] =
    Seq("x-request-id", "request-id", "x-amzn-requestid", "x-amz-request-id", "x-ms-request-id", "apim-request-id")

  /** The headers that hold the retry hint in milliseconds. They apply when `Retry-After` gives no hint. */
  private val retryAfterMillisHeaders: Seq[String] = Seq("retry-after-ms", "x-ms-retry-after-ms")

  /** A decimal number that is not negative. This grammar rejects `NaN`, `Infinity`, and the `2d` and `0x1p3` forms that
    * `String.toDouble` accepts.
    */
  private val decimalNumber: Regex = """\+?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""".r

  private val nanosPerSecond: Double = 1e9
  private val nanosPerMilli: Double  = 1e6
  private val excerptLength: Int     = 400

  /** The fields of the error envelope `{"error": {"message": ..., "code": ..., "type": ...}}`. A field is `None` when
    * the body does not have it, or when it is empty.
    */
  private final case class Envelope(
      message: Option[String] = None,
      code   : Option[String] = None,
      kind   : Option[String] = None
  )

  /** The error for a response that has a status outside 200 to 299.
    *
    * The class comes from the code and the type of the error envelope first, and from the status second. The order of
    * the rules is important: OpenAI sends `insufficient_quota` with HTTP 429, and a retry cannot correct that failure.
    * For a body that is not the error envelope (HTML, plain text, other JSON), the class comes from the status.
    *
    * The details hold the status, the provider, the model, the provider code (the code, else the type), the request id,
    * and the retry hint. The retry hint applies to each class, not only to the rate limit.
    *
    * The message is `OpenAI HTTP <status>: <text>`. The text is the message of the provider, or a short excerpt of the
    * body when the provider gave no message.
    *
    * @param headers
    *   the response headers. The lookup ignores the case of the names.
    * @param model
    *   the model of the request
    * @param now
    *   the reference time for a `Retry-After` date
    */
  def fromResponse(
      status : Int,
      headers: Map[String, String],
      body   : String,
      model  : Option[String],
      now    : Instant = Instant.now()
  ): LmProviderError =
    val envelope = parseEnvelope(body)
    val lowered  = headers.map((name, value) => name.toLowerCase(Locale.ROOT) -> value)
    val text     = envelope.message.getOrElse(body.trim.take(excerptLength))
    val message  = if text.isEmpty then s"OpenAI HTTP $status" else s"OpenAI HTTP $status: $text"
    val details  = LmErrorDetails(
      model = model,
      provider = Some(provider),
      providerCode = envelope.code.orElse(envelope.kind),
      status = Some(status),
      requestId = requestIdHeaders.iterator.flatMap(lowered.get).map(_.trim).find(_.nonEmpty),
      retryAfter = retryAfter(lowered, now)
    )
    def codeIs(value: String): Boolean = envelope.code.contains(value)
    def kindIs(value: String): Boolean = envelope.kind.contains(value)

    if codeIs("context_length_exceeded") || (status == 400 && isContextWindowError(body)) then
      ContextWindowExceededError(message, details)
    else if envelope.code.exists(modelErrorCodes.contains) then LmUnsupportedModelError(message, details)
    else if codeIs("insufficient_quota") || kindIs("insufficient_quota") then LmBillingError(message, details)
    else if codeIs("invalid_api_key") || kindIs("authentication_error") then LmAuthError(message, details)
    else if codeIs("rate_limit_exceeded") || kindIs("rate_limit_error") then LmRateLimitError(message, details)
    else
      status match
        case 401 | 403                   => LmAuthError(message, details)
        case 402                         => LmBillingError(message, details)
        case 408 | 504                   => LmTimeoutError(message, details)
        case 429                         => LmRateLimitError(message, details)
        case 400 | 404 | 409 | 413 | 422 => LmInvalidRequestError(message, details)
        case s if s >= 500 && s <= 599   => LmServerError(message, details)
        case _                           => LmProviderResponseError(message, details)

  /** The error for an error event in a stream, `{"error": {"message": ..., "code": ..., "type": ...}}` (upstream
    * `parse_stream_events` and `_error_detail`). The provider code is the code, else the type, else `provider`. The
    * class comes from the provider code. The message is the message of the provider, else the provider code. The
    * details hold the model, the provider, and the provider code.
    */
  def fromStreamError(error: DynamicValue.Record, model: Option[String]): LmProviderError =
    val envelope = envelopeOf(error)
    streamError(envelope.code.orElse(envelope.kind), envelope.message, model)

  /** The error for an `error` or `response.error` event of a Responses stream (upstream `_parse_single_stream_event` in
    * `dspy/_vendor/lm15/providers/openai.py`). When the event has an `error` object, the provider code is its `code`,
    * else its `type`, else the `code` of the event, and the message is its `message`, else the `message` of the event.
    * Else the provider code is the `code` of the event, else its `error_type`, and the message is the `message` of the
    * event. The class and the details follow [[fromStreamError]].
    */
  def fromResponsesStreamError(event: DynamicValue.Record, model: Option[String]): LmProviderError =
    val outer = envelopeOf(event)
    DynamicValues.recordGet(event, WireKeys.error).flatMap(DynamicJson.asRecord) match
      case Some(error) =>
        val inner = envelopeOf(error)
        streamError(inner.code.orElse(inner.kind).orElse(outer.code), inner.message.orElse(outer.message), model)
      case None =>
        val errorType = DynamicValues.recordGet(event, "error_type").flatMap(scalarText)
        streamError(outer.code.orElse(errorType), outer.message, model)

  /** The error of a stream error event with `code` and `message`. No code gives the provider code `provider`. The class
    * comes from the provider code, and no message gives the provider code as the message.
    */
  private def streamError(code: Option[String], message: Option[String], model: Option[String]): LmProviderError =
    val providerCode = code.getOrElse("provider")
    val details      = LmErrorDetails(model = model, provider = Some(provider), providerCode = Some(providerCode))
    val errorClass   = streamErrorClasses.getOrElse(providerCode, LmProviderResponseError(_, _))
    errorClass(message.getOrElse(providerCode), details)

  /** The error for a stream that is not complete or not valid. */
  def streamAssembly(message: String, model: Option[String]): LmStreamAssemblyError =
    LmStreamAssemblyError(message, LmErrorDetails(model = model, provider = Some(provider)))

  /** Adds the model and the provider to an LM error that the transport returned. The transport is generic, so it does
    * not know them. A field that is already set stays. An error that is not an LM error passes unchanged.
    */
  def withRequestDetails(error: DspyError, model: Option[String]): DspyError =
    error match
      case lm: LmError => lm.withDetails(lm.details.copy(
          model = lm.details.model.orElse(model),
          provider = lm.details.provider.orElse(Some(provider))
        ))
      case other => other

  /** The error for an API key variable that is absent or empty. */
  def notConfigured(envVar: String): LmNotConfiguredError =
    LmNotConfiguredError(s"Missing '$envVar' environment variable", LmErrorDetails(provider = Some(provider)))

  private def isContextWindowError(body: String): Boolean =
    val lowered = body.toLowerCase(Locale.ROOT)
    contextWindowMarkers.exists(lowered.contains)

  /** Reads the error envelope. `error` can be an object, a plain string, or absent, and the body can be text that is
    * not JSON. This function does not fail for a body that it cannot read.
    */
  private def parseEnvelope(body: String): Envelope =
    DynamicJson.decode(body).toOption.flatMap(DynamicJson.field(_, "error")) match
      case Some(error: DynamicValue.Record) => envelopeOf(error)
      case Some(other)                      => Envelope(message = scalarText(other))
      case None                             => Envelope()

  /** Reads the fields of an `error` object. */
  private def envelopeOf(error: DynamicValue.Record): Envelope =
    def text(name: String): Option[String] = DynamicValues.recordGet(error, name).flatMap(scalarText)
    Envelope(message = text(WireKeys.message), code = text("code"), kind = text(WireKeys.`type`))

  /** The text of a JSON string, or of a JSON number (some OpenAI-compatible providers send an integer code). An empty
    * string, `null`, and each other value give `None`.
    */
  private def scalarText(value: DynamicValue): Option[String] =
    DynamicJson.asString(value).orElse(DynamicJson.asLong(value).map(_.toString)).map(_.trim).filter(_.nonEmpty)

  /** The delay that the response asked for. `Retry-After` holds decimal seconds or an HTTP date. A date gives the time
    * that remains, and a date in the past gives zero. When `Retry-After` gives no hint, `retry-after-ms` and
    * `x-ms-retry-after-ms` give it in decimal milliseconds. A value that is not a number, that is negative, or that is
    * too large for a `FiniteDuration` gives no hint. The TypeSafe client uses this rule too.
    *
    * @param lowered
    *   the response headers, with lower-case names
    */
  private[providers] def retryAfter(lowered: Map[String, String], now: Instant): Option[FiniteDuration] =
    val seconds = lowered.get("retry-after")
      .flatMap(value => decimalDuration(value, nanosPerSecond).orElse(durationUntil(value, now)))
    def millis = retryAfterMillisHeaders.iterator.flatMap(lowered.get).flatMap(decimalDuration(_, nanosPerMilli))
    seconds.orElse(millis.nextOption())

  private def decimalDuration(value: String, nanosPerUnit: Double): Option[FiniteDuration] =
    val trimmed = value.trim
    if decimalNumber.matches(trimmed) then fromNanos(trimmed.toDouble * nanosPerUnit) else None

  /** The time from `now` to an RFC 1123 date, for example `Wed, 21 Oct 2026 07:28:00 GMT`. */
  private def durationUntil(value: String, now: Instant): Option[FiniteDuration] =
    try
      val when      = ZonedDateTime.parse(value.trim, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
      val remaining = java.time.Duration.between(now, when)
      fromNanos(math.max(0.0, remaining.getSeconds * nanosPerSecond + remaining.getNano))
    catch case _: DateTimeException => None

  private def fromNanos(nanos: Double): Option[FiniteDuration] =
    Option.when(nanos >= 0.0 && nanos < Long.MaxValue.toDouble)(FiniteDuration(math.round(nanos), NANOSECONDS))
