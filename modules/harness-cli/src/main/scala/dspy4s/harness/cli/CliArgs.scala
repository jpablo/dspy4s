package dspy4s.harness.cli

import dspy4s.harness.kernel.SessionId

import java.nio.file.Path

/** The arguments of the CLI (CODE_HARNESS_DESIGN.md, 15, phase 7).
  *
  * @param scope
  *   the folder of a new work session
  * @param root
  *   the root of the host, with the folder `sessions` and the catalog (10.2)
  * @param session
  *   the session to open again, or none for a new session. A session that opens again keeps its own configuration and
  *   scope, so the folder does not apply to it.
  */
final case class CliArgs(scope: Path, root: Path, session: Option[SessionId]) derives CanEqual

object CliArgs:

  /** The arguments from the command line: `[--root DIR] [--session ID] [FOLDER]`. The folder is `cwd` by default, and
    * the root is `<home>/.dspy4s/harness`. An unknown flag or a missing value gives an error with the usage.
    *
    * A proposal for the review: the design gives no command line. A work session of phase 7 has the mode
    * `WorkRuntime.workMode`, so the CLI has no flag for the mode.
    */
  def parse(args: Vector[String], home: Path, cwd: Path): Either[String, CliArgs] = ???
