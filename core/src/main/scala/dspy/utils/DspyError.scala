package dspy.utils

sealed trait DspyError extends RuntimeException {
  def cause: Option[Throwable] = None
}

object DspyError {
  final case class ConfigError(message: String, override val cause: Option[Throwable] = None)
      extends DspyError {
    override def getMessage: String = message
    override def getCause: Throwable = cause.orNull
  }

  final case class HttpError(status: Int, body: String, override val cause: Option[Throwable] = None)
      extends DspyError {
    override def getMessage: String = s"HTTP $status: $body"
    override def getCause: Throwable = cause.orNull
  }

  final case class ParseError(message: String, raw: String, override val cause: Option[Throwable] = None)
      extends DspyError {
    override def getMessage: String = s"$message. Raw: ${raw.take(200)}"
    override def getCause: Throwable = cause.orNull
  }

  final case class ProviderError(message: String, override val cause: Option[Throwable] = None)
      extends DspyError {
    override def getMessage: String = message
    override def getCause: Throwable = cause.orNull
  }
}
