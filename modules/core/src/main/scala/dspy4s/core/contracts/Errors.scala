package dspy4s.core.contracts

sealed trait DspyError extends Product with Serializable:
  def code: String
  def message: String

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

final case class ContextWindowExceededError(model: Option[String] = None, message: String = "Context window exceeded")
    extends DspyError:
  val code: String = "context_window_exceeded"
