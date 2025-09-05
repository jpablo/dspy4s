package dspy.utils

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

object ConfigLoader {
  final case class FileConfig(
      openaiApiKey: Option[String] = None,
      openaiBaseUrl: Option[String] = None,
      requestTimeoutMillis: Option[Int] = None,
      debug: Option[Boolean] = None,
      logPrompts: Option[Boolean] = None,
      logResponses: Option[Boolean] = None
  )

  private def expandHome(p: String): Path =
    if (p.startsWith("~")) Paths.get(System.getProperty("user.home"), p.drop(1))
    else Paths.get(p)

  def defaultPath: Path = {
    val fromEnv = sys.env.get("DSPY4S_CONFIG").map(expandHome)
    fromEnv.getOrElse(Paths.get(System.getProperty("user.home"), ".dspy4s", "config.json"))
  }

  def readFile(path: Path): Option[FileConfig] = {
    if (!Files.isRegularFile(path)) return None
    val content = new String(Files.readAllBytes(path), "UTF-8")
    Try(ujson.read(content)).toOption.flatMap { js =>
      js match {
        case obj: ujson.Obj =>
          def bool(name: String) = obj.value.get(name).flatMap(_.boolOpt)
          def str(name: String)  = obj.value.get(name).flatMap(_.strOpt)
          def int(name: String)  = obj.value.get(name).flatMap(_.numOpt).map(_.toInt)
          Some(
            FileConfig(
              openaiApiKey = str("openai_api_key").orElse(str("OPENAI_API_KEY")),
              openaiBaseUrl = str("openai_base_url").orElse(str("OPENAI_BASE_URL")),
              requestTimeoutMillis = int("request_timeout_ms").orElse(int("DSPY_TIMEOUT_MS")),
              debug = bool("debug").orElse(bool("DSPY_DEBUG")),
              logPrompts = bool("log_prompts").orElse(bool("DSPY_LOG_PROMPTS")),
              logResponses = bool("log_responses").orElse(bool("DSPY_LOG_RESPONSES"))
            )
          )
        case _ => None
      }
    }
  }
}
