package dspy4s.harness.cli

import dspy4s.harness.kernel.SessionId

import java.nio.file.Path

/** The mode of a work session from the CLI (CODE_HARNESS_DESIGN.md, 5.2): `Local` has no network, and `Full` has the
  * network through the host list.
  */
enum CliMode derives CanEqual:
  case Local, Full

/** The arguments of the CLI.
  *
  * @param scope
  *   the folder of the work session
  * @param root
  *   the root of the host, with the folder `sessions` and the catalog (10.2)
  * @param session
  *   the session to open again, or none for a new session
  * @param mode
  *   the mode of a new session
  */
final case class CliArgs(scope: Path, root: Path, session: Option[SessionId], mode: CliMode) derives CanEqual

object CliArgs:

  /** The arguments from the command line: `[--root DIR] [--session ID] [--full] [FOLDER]`. The folder is `cwd` by
    * default, the root is `<home>/.dspy4s/harness`, and the mode is `Local`. An unknown flag or a missing value gives
    * an error with the usage.
    *
    * A proposal for the review: the design gives no command line.
    */
  def parse(args: Vector[String], home: Path, cwd: Path): Either[String, CliArgs] = ???
