package dspy4s.core.contracts

import scala.concurrent.duration.FiniteDuration

sealed trait DspyError extends Product with Serializable:
  def code: String
  def message: String

/** The text of an exception, for the message of an error. Each module uses these rules, so one exception gives one
  * text.
  */
object DspyError:

  /** The message of `error` when it is not null and not empty, else the name of its class ([[className]]). */
  def messageOf(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(className(error))

  /** `<Type>: <message>` (upstream `describe`), or `<Type>` when the message is null or empty. `<Type>` is the name of
    * the class of `error` ([[className]]).
    */
  def describe(error: Throwable): String =
    val name = className(error)
    Option(error.getMessage).filter(_.nonEmpty).fold(name)(message => s"$name: $message")

  /** The simple name of the class of `error`. An anonymous class has no simple name, so it gives the full class name.
    */
  private def className(error: Throwable): String =
    Some(error.getClass.getSimpleName).filter(_.nonEmpty).getOrElse(error.getClass.getName)

final case class ConfigurationError(message: String) extends DspyError:
  val code: String = "configuration_error"

final case class ValidationError(message: String) extends DspyError:
  val code: String = "validation_error"

final case class NotFoundError(resource: String, message: String) extends DspyError:
  val code: String = "not_found"

final case class ParseError(component: String, message: String, raw: Option[String] = None) extends DspyError:
  val code: String = "parse_error"

final case class RuntimeError(component: String, message: String) extends DspyError:
  val code: String = "runtime_error"

/** The metadata of a language model failure. A field is `None` when the source did not give it.
  *
  * @param model
  *   the model identifier of the request
  * @param provider
  *   the provider or the backend that returned the failure
  * @param providerCode
  *   the error code of the provider, for example `rate_limit_exceeded`
  * @param status
  *   the HTTP status, when the failure came from an HTTP response
  * @param requestId
  *   the request id of the provider
  * @param retryAfter
  *   the delay that the provider asked for before the next attempt
  * @param usage
  *   the usage that the provider reported for the failed call. The record point of the usage
  *   (`LanguageModel.callAndRecordUsage`) records it, because the provider bills these tokens also when the call fails
  *   (upstream `_account_failed_call` in `dspy/clients/execution.py`).
  */
final case class LmErrorDetails(
    model       : Option[String]         = None,
    provider    : Option[String]         = None,
    providerCode: Option[String]         = None,
    status      : Option[Int]            = None,
    requestId   : Option[String]         = None,
    retryAfter  : Option[FiniteDuration] = None,
    usage       : Option[LmUsage]        = None
)

object LmErrorDetails:
  val empty: LmErrorDetails = LmErrorDetails()

/** A failure of a language model call. The classes mirror the upstream `LMError` family (`dspy/utils/exceptions.py`),
  * and each `code` is the upstream code.
  *
  * A retry policy reads [[isTransient]]. Only a rate limit, a timeout, a server failure, and a transport failure are
  * transient. The class tells that a retry is generally safe. It does not prove that the provider did no work.
  *
  * Upstream classes that have no source in dspy4s are absent: the credential lock, the collection limit, and the
  * unsupported feature.
  */
sealed trait LmError extends DspyError:
  def details: LmErrorDetails
  def withDetails(updated: LmErrorDetails): LmError
  def isTransient: Boolean = false

object LmError:
  /** True when a retry of the call is generally safe (upstream `is_retryable_lm_error`). An error that is not an
    * [[LmError]] is never transient.
    */
  def isRetryable(error: DspyError): Boolean =
    error match
      case lm: LmError => lm.isTransient
      case _           => false

  /** The delay that the provider asked for, if the error carries one. */
  def retryAfter(error: DspyError): Option[FiniteDuration] =
    error match
      case lm: LmError => lm.details.retryAfter
      case _           => None

  /** The usage that the provider reported for the failed call, if the error carries one ([[LmErrorDetails.usage]]). An
    * error that is not an [[LmError]] has none.
    */
  def usage(error: DspyError): Option[LmUsage] =
    error match
      case lm: LmError => lm.details.usage
      case _           => None

  /** `error` with `usage` as the usage of the failed call ([[LmErrorDetails.usage]]). The usage replaces the usage that
    * the error had. An error that is not an [[LmError]], and a `usage` of `None`, give `error` as it is.
    */
  def withUsage(error: DspyError, usage: Option[LmUsage]): DspyError =
    (error, usage) match
      case (lm: LmError, Some(value)) => lm.withDetails(lm.details.copy(usage = Some(value)))
      case _                          => error

/** The request failed before the provider returned a response: network, DNS, TLS, or a connection reset. */
final case class LmTransportError(message: String, details: LmErrorDetails = LmErrorDetails.empty) extends LmError:
  val code: String                                           = "transport"
  override def isTransient: Boolean                          = true
  def withDetails(updated: LmErrorDetails): LmTransportError = copy(details = updated)

/** The language model has no credentials or lacks a necessary setting. */
final case class LmNotConfiguredError(message: String, details: LmErrorDetails = LmErrorDetails.empty) extends LmError:
  val code: String                                               = "not_configured"
  def withDetails(updated: LmErrorDetails): LmNotConfiguredError = copy(details = updated)

/** A failure at the provider boundary that has no class. It is not a provider response and not a parse failure. */
final case class LmUnexpectedError(message: String, details: LmErrorDetails = LmErrorDetails.empty) extends LmError:
  val code: String                                            = "unexpected"
  def withDetails(updated: LmErrorDetails): LmUnexpectedError = copy(details = updated)

/** A stream that is not complete or not valid (upstream `LMStreamAssemblyError`, a subclass of `LMUnexpectedError`).
  * The stream stopped before its end, or it had a chunk that does not decode. The error is not transient, so a retry
  * policy does not retry it, and the cache does not store the call.
  */
final case class LmStreamAssemblyError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmError:
  val code: String                                                = "stream_assembly"
  def withDetails(updated: LmErrorDetails): LmStreamAssemblyError = copy(details = updated)

/** The provider returned an error response. */
sealed trait LmProviderError extends LmError:
  def withDetails(updated: LmErrorDetails): LmProviderError

/** The provider rejected the credentials (HTTP 401 or 403). */
final case class LmAuthError(message: String, details: LmErrorDetails = LmErrorDetails.empty) extends LmProviderError:
  val code: String                                      = "auth"
  def withDetails(updated: LmErrorDetails): LmAuthError = copy(details = updated)

/** The provider rejected the request because of billing or quota (HTTP 402, or `insufficient_quota`). */
final case class LmBillingError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                         = "billing"
  def withDetails(updated: LmErrorDetails): LmBillingError = copy(details = updated)

/** The provider limited the request rate (HTTP 429). `details.retryAfter` holds the delay hint, if one came. */
final case class LmRateLimitError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                           = "rate_limit"
  override def isTransient: Boolean                          = true
  def withDetails(updated: LmErrorDetails): LmRateLimitError = copy(details = updated)

/** The provider rejected the shape of the request or a resource in it (HTTP 400, 404, 409, 413, or 422). */
final case class LmInvalidRequestError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                                = "invalid_request"
  def withDetails(updated: LmErrorDetails): LmInvalidRequestError = copy(details = updated)

/** The prompt is larger than the context window of the model. `ReAct` and other loops catch this class to make the
  * history shorter. Upstream makes it a subclass of `LMInvalidRequestError`; here the two are siblings, because a case
  * class cannot extend a case class.
  */
final case class ContextWindowExceededError(
    message: String         = "Context window exceeded",
    details: LmErrorDetails = LmErrorDetails.empty
) extends LmProviderError:
  val code: String                                                     = "context_window_exceeded"
  def withDetails(updated: LmErrorDetails): ContextWindowExceededError = copy(details = updated)

/** The provider does not have the model, or does not support it. */
final case class LmUnsupportedModelError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                                  = "unsupported_model"
  def withDetails(updated: LmErrorDetails): LmUnsupportedModelError = copy(details = updated)

/** The request timed out: no response came in the time limit, or the provider returned HTTP 408 or 504. */
final case class LmTimeoutError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                         = "timeout"
  override def isTransient: Boolean                        = true
  def withDetails(updated: LmErrorDetails): LmTimeoutError = copy(details = updated)

/** The provider failed while it handled the request (HTTP 5xx). */
final case class LmServerError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                        = "server"
  override def isTransient: Boolean                       = true
  def withDetails(updated: LmErrorDetails): LmServerError = copy(details = updated)

/** An error response of the provider that has no more specific class. Upstream uses `LMProviderError` itself. */
final case class LmProviderResponseError(message: String, details: LmErrorDetails = LmErrorDetails.empty)
    extends LmProviderError:
  val code: String                                                  = "provider"
  def withDetails(updated: LmErrorDetails): LmProviderResponseError = copy(details = updated)
