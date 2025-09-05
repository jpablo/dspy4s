package dspy.utils

final case class Settings(
    openaiApiKey: Option[String] = sys.env.get("OPENAI_API_KEY"),
    openaiBaseUrl: String = sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1"),
    requestTimeoutMillis: Int = sys.env.get("DSPY_TIMEOUT_MS").flatMap(_.toIntOption).getOrElse(60000),
    debug: Boolean = sys.env.get("DSPY_DEBUG").exists(_.toLowerCase == "true"),
    logPrompts: Boolean = sys.env.get("DSPY_LOG_PROMPTS").exists(_.toLowerCase == "true"),
    logResponses: Boolean = sys.env.get("DSPY_LOG_RESPONSES").exists(_.toLowerCase == "true")
)

object Settings {
  lazy val default: Settings = Settings()
}
