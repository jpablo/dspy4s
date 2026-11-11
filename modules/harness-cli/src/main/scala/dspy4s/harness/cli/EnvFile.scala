package dspy4s.harness.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

/** The variables file of the CLI, `<home>/.dspy4s/harness/.env`: a quick way to give the variables of the model, before
  * a real configuration file exists. The CLI reads it at each start, also with another `--root`, so a run with a
  * temporary root finds it too.
  *
  * The sandbox closes the root of the host to each command (CODE_HARNESS_DESIGN.md, 5.4). So with the default root, a
  * command cannot read this file. With another root, the sandbox closes that root and not this file.
  */
object EnvFile:

  /** The variables of the connection to the model. They come from one place (`merge`). */
  val connection: Set[String] = Set("OPENAI_BASE_URL", "OPENAI_API_KEY")

  /** The file for the home folder `home`. */
  def pathOf(home: Path): Path = home.resolve(".dspy4s/harness/.env")

  /** The variables of the file at `path`. A missing file gives no variable. A file that the CLI cannot read, or a line
    * that is not right, gives an error with the path.
    */
  def read(path: Path): Either[String, Map[String, String]] =
    if !Files.exists(path) then Right(Map.empty)
    else
      Try(Files.readString(path, UTF_8)).toEither.left
        .map(error => s"The CLI cannot read $path: ${error.getMessage}")
        .flatMap(text => parse(text).left.map(error => s"$path: $error"))

  /** A name of a variable, as a shell takes it. */
  private val Name = "[A-Za-z_][A-Za-z0-9_]*".r

  /** The variables of `text`.
    *
    *   - A line is `NAME=VALUE` or `export NAME=VALUE`, so a shell can also `source` the file.
    *   - A blank line and a line that starts with `#` give no variable.
    *   - The value is the text after the first `=`, with no space at its ends and with no pair of quotes around it.
    *   - The CLI does not expand `$NAME`, and it keeps a `#` in a value.
    *   - For a name that comes two times, the last value wins, as in a shell.
    *   - A line that is not right gives an error with its number. The error does not show the line, because the line
    *     can hold a key.
    */
  def parse(text: String): Either[String, Map[String, String]] =
    text.linesIterator.zipWithIndex.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
      case (Left(error), _)            => Left(error)
      case (Right(vars), (raw, index)) =>
        val line = raw.trim
        if line.isEmpty || line.startsWith("#") then Right(vars)
        else
          line.replaceFirst("^export\\s+", "").split("=", 2) match
            case Array(name, value) if Name.matches(name.trim) => Right(vars + (name.trim -> unquote(value.trim)))
            case _                                             => Left(s"line ${index + 1} is not NAME=VALUE")
    }

  /** `value` with no pair of the same quotes around it. */
  private def unquote(value: String): String =
    val quoted = value.length >= 2 && (value.head == '"' || value.head == '\'') && value.last == value.head
    if quoted then value.substring(1, value.length - 1) else value

  /** The variables of the CLI: the variables of `file`, and the variables of `process` over them. So a variable of the
    * process changes a value for one run.
    *
    * The connection is the exception. When `file` gives the base URL or the key, both come from `file`. Else both come
    * from `process`. So the CLI never sends a key to a base URL from another place, for example the OpenAI key of a
    * shell to an endpoint of the file.
    */
  def merge(file: Map[String, String], process: Map[String, String]): Map[String, String] =
    val fileConnection = connection.exists(file.contains)
    file ++ process.filter((name, _) => !(fileConnection && connection.contains(name)))
