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
  * @param script
  *   the script mode (`Cli.loop`): each line waits for the end of the turn before it, so a script or an agent can drive
  *   the session through the standard input
  */
final case class CliArgs(scope: Path, root: Path, session: Option[SessionId], script: Boolean = false) derives CanEqual

object CliArgs:

  /** The text that an error of the arguments gives. */
  val usage: String = "Usage: dspy4s-harness [--root DIR] [--session ID] [--script] [FOLDER]\n" +
    "  --root DIR     the root of the host, by default ~/.dspy4s/harness\n" +
    "  --session ID   open the session ID again, with its own configuration\n" +
    "  --script       run one line at a time: each line waits for the end of the turn before it, and the end of\n" +
    "                 the input waits for the open turn\n" +
    "  FOLDER         the folder of a new session, by default the current folder"

  /** The arguments from the command line: `[--root DIR] [--session ID] [--script] [FOLDER]`. The folder is `cwd` by
    * default, and the root is `<home>/.dspy4s/harness`. An unknown flag or a missing value gives an error with the
    * usage.
    *
    * The folder and the root are normal paths, with no `.` or `..` part. A live scenario of 2026-11-10 gave the folder
    * `.`, and the scope of the session was `.../ws/.`.
    *
    * A proposal for the review: the design gives no command line. A work session of phase 7 has the mode
    * `WorkRuntime.workMode`, so the CLI has no flag for the mode.
    */
  def parse(args: Vector[String], home: Path, cwd: Path): Either[String, CliArgs] =
    def at(path: String): Path = cwd.resolve(path).normalize()

    @tailrec
    def walk(rest: List[String], got: CliArgs, folder: Option[Path]): Either[String, CliArgs] =
      rest match
        case Nil                                               => Right(folder.fold(got)(f => got.copy(scope = f)))
        case "--root" :: dir :: more if !dir.startsWith("--")  => walk(more, got.copy(root = at(dir)), folder)
        case "--session" :: id :: more if !id.startsWith("--") =>
          walk(more, got.copy(session = Some(SessionId(id))), folder)
        case "--script" :: more                 => walk(more, got.copy(script = true), folder)
        case flag :: _ if flag.startsWith("--") => Left(s"The argument $flag is not right.\n$usage")
        case path :: more if folder.isEmpty     => walk(more, got, Some(at(path)))
        case path :: _                          => Left(s"Give one folder, not also $path.\n$usage")
    walk(args.toList, CliArgs(cwd.normalize(), home.resolve(".dspy4s/harness").normalize(), None), None)
