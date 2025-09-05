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
  import ConfigLoader._

  private def boolEnv(name: String, env: Map[String, String]): Option[Boolean] =
    env.get(name).map(_.toLowerCase).collect { case "true" => true; case "false" => false }

  private def loadFrom(fileCfg: Option[FileConfig], env: Map[String, String]): Settings = {
    val openaiApiKey = env
      .get("OPENAI_API_KEY")
      .orElse(fileCfg.flatMap(_.openaiApiKey))

    val openaiBaseUrl = env
      .get("OPENAI_BASE_URL")
      .orElse(fileCfg.flatMap(_.openaiBaseUrl))
      .getOrElse("https://api.openai.com/v1")

    val requestTimeoutMillis = env
      .get("DSPY_TIMEOUT_MS")
      .flatMap(_.toIntOption)
      .orElse(fileCfg.flatMap(_.requestTimeoutMillis))
      .getOrElse(60000)

    val debug = boolEnv("DSPY_DEBUG", env).orElse(fileCfg.flatMap(_.debug)).getOrElse(false)
    val logPrompts = boolEnv("DSPY_LOG_PROMPTS", env).orElse(fileCfg.flatMap(_.logPrompts)).getOrElse(false)
    val logResponses = boolEnv("DSPY_LOG_RESPONSES", env).orElse(fileCfg.flatMap(_.logResponses)).getOrElse(false)

    Settings(openaiApiKey, openaiBaseUrl, requestTimeoutMillis, debug, logPrompts, logResponses)
  }

  def load(path: java.nio.file.Path = ConfigLoader.defaultPath): Settings =
    loadFrom(ConfigLoader.readFile(path), sys.env)

  // Testing/advanced: explicit env injection
  def loadWithEnv(env: Map[String, String], path: java.nio.file.Path): Settings =
    loadFrom(ConfigLoader.readFile(path), env)

  lazy val default: Settings = load()
}
