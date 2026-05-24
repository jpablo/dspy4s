import sbt._
import sbt.Keys._

/**
 * Loads variables from `.env` and feeds them into every forked test JVM
 * via `Test / envVars`, so tests can see them via `sys.env.get(...)`.
 *
 * The `.env` file itself is gitignored. See `.env.example` for the shape.
 */
object DotEnvLoader extends AutoPlugin {
  override def trigger = allRequirements

  def readDotEnv(baseDir: File): Map[String, String] = {
    val file = baseDir / ".env"
    if (!file.exists()) Map.empty
    else {
      val lines = scala.io.Source.fromFile(file).getLines().toList
      lines.flatMap { raw =>
        val line = raw.trim
        if (line.isEmpty || line.startsWith("#")) None
        else {
          line.split("=", 2) match {
            case Array(k, v) =>
              val cleaned = v.trim.replaceAll("^['\"]|['\"]$", "")
              if (k.trim.nonEmpty) Some(k.trim -> cleaned) else None
            case _ => None
          }
        }
      }.toMap
    }
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    Test / envVars ++= readDotEnv((ThisBuild / baseDirectory).value)
  )
}
