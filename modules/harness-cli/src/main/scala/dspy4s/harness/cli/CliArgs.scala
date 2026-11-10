package dspy4s.harness.cli

import dspy4s.harness.kernel.SessionId

import java.nio.file.Path
import scala.annotation.tailrec

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

  /** The text that an error of the arguments gives. */
  val usage: String = "Usage: dspy4s-harness [--root DIR] [--session ID] [FOLDER]\n" +
    "  --root DIR     the root of the host, by default ~/.dspy4s/harness\n" +
    "  --session ID   open the session ID again, with its own configuration\n" +
    "  FOLDER         the folder of a new session, by default the current folder"

  /** The arguments from the command line: `[--root DIR] [--session ID] [FOLDER]`. The folder is `cwd` by default, and
    * the root is `<home>/.dspy4s/harness`. An unknown flag or a missing value gives an error with the usage.
    *
    * A proposal for the review: the design gives no command line. A work session of phase 7 has the mode
    * `WorkRuntime.workMode`, so the CLI has no flag for the mode.
    */
  def parse(args: Vector[String], home: Path, cwd: Path): Either[String, CliArgs] =
    @tailrec
    def walk(rest: List[String], got: CliArgs, folder: Option[Path]): Either[String, CliArgs] =
      rest match
        case Nil                                               => Right(folder.fold(got)(f => got.copy(scope = f)))
        case "--root" :: dir :: more if !dir.startsWith("--")  => walk(more, got.copy(root = cwd.resolve(dir)), folder)
        case "--session" :: id :: more if !id.startsWith("--") =>
          walk(more, got.copy(session = Some(SessionId(id))), folder)
        case flag :: _ if flag.startsWith("--") => Left(s"The argument $flag is not right.\n$usage")
        case path :: more if folder.isEmpty     => walk(more, got, Some(cwd.resolve(path)))
        case path :: _                          => Left(s"Give one folder, not also $path.\n$usage")
    walk(args.toList, CliArgs(cwd, home.resolve(".dspy4s/harness"), None), None)
