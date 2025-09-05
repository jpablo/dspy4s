package dspy.utils

final case class Settings(
    openaiApiKey: Option[String] = sys.env.get("OPENAI_API_KEY"),
    openaiBaseUrl: String = sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1"),
    requestTimeoutMillis: Int =
      sys.env.get("DSPY_TIMEOUT_MS").flatMap(s => s.toIntOption).getOrElse(60000)
)

object Settings {
  lazy val default: Settings = Settings()
}
