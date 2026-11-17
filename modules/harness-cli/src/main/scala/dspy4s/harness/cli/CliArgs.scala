package dspy4s.harness.cli

import dspy4s.harness.HarnessLimits
import dspy4s.harness.inspect.Inspector
import dspy4s.harness.kernel.{SessionId, TurnLimit}

import java.nio.file.Path
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** The arguments of the CLI (CODE_HARNESS_DESIGN.md, 15, phase 7).
  *
  * @param scope
  *   the folder of a new work session
  * @param root
  *   the root of the host, with the folder `sessions` and the catalog (10.2)
  * @param session
  *   the session to open again, or none for a new session: its id, or a part at the start of the id that only one
  *   session of the root has. A session that opens again keeps its own configuration and scope, so the folder does not
  *   apply to it.
  * @param script
  *   the script mode (`Cli.loop`): each line waits for the end of the turn before it, so a script or an agent can drive
  *   the session through the standard input
  * @param list
  *   the list of the sessions of the root, in place of a session
  * @param controller
  *   the client opens the controller of the root (8.7), not a work session. The folder does not apply to it.
  * @param inspect
  *   the inspector of the root (docs/code-harness/OBSERVABILITY.md, 5), in place of a session
  * @param port
  *   the port of the inspector on 127.0.0.1, 0 for a free port
  * @param memoryCalls
  *   false with `--no-memory-calls`: the memory tree of each session of the host makes no `compress` or `merge` call
  *   (7.1), so a person can compare the cost and the replies of a session with and without them. A message of the
  *   memory then shows its first 512 bytes, and `zoom` still reads its whole text.
  * @param cellWallMinutes
  *   the wall-clock limit of a cell in minutes from `--cell-wall-minutes`, with its guest API calls. None keeps the
  *   default limit of `HarnessLimits`, and 0 gives no wall limit.
  * @param unattendedTurns
  *   the limit of the turns in a row that no message of the person starts (8.7), from `--unattended-turns`. None keeps
  *   the default limit of `HarnessLimits`, and 0 gives no limit.
  */
final case class CliArgs(
    scope          : Path,
    root           : Path,
    session        : Option[String],
    script         : Boolean     = false,
    list           : Boolean     = false,
    controller     : Boolean     = false,
    inspect        : Boolean     = false,
    port           : Int         = Inspector.DefaultPort,
    memoryCalls    : Boolean     = true,
    cellWallMinutes: Option[Int] = None,
    unattendedTurns: Option[Int] = None
) derives CanEqual:

  /** The limits of the sessions of the host: the default limits, with no summary call of the memory tree (7.1) when
    * `memoryCalls` is false. A spent summary budget gives each message its first bytes as its line, and the store does
    * not keep that line, so a later run with the memory calls builds the summaries. `cellWallMinutes` sets the wall
    * limit of a cell, and 0 gives none. `unattendedTurns` sets the limit of the turns with no person, and 0 gives none.
    */
  def limits: HarnessLimits =
    val default = HarnessLimits()
    default.copy(
      summaryCalls = if memoryCalls then default.summaryCalls else 0,
      cellWallTime = cellWallMinutes.fold(default.cellWallTime)(minutes => Option.when(minutes > 0)(minutes.minutes)),
      unattendedTurns = unattendedTurns.fold(default.unattendedTurns)(turns =>
        if turns > 0 then TurnLimit.AtMost(turns) else TurnLimit.NoLimit
      )
    )

object CliArgs:

  /** The text that an error of the arguments gives. */
  val usage
      : String = "Usage: dspy4s-harness [--root DIR] [--session ID | --controller] [--script] [--no-memory-calls]\n" +
    "                      [--cell-wall-minutes N] [--unattended-turns N] [FOLDER]\n" +
    "       dspy4s-harness [--root DIR] --list\n" +
    "       dspy4s-harness [--root DIR] --inspect [--port N]\n" +
    "  --root DIR     the root of the host, by default ~/.dspy4s/harness\n" +
    "  --session ID   open the session ID again, with its own configuration. A part at the start of the id is\n" +
    "                 enough when only one session has it\n" +
    "  --controller   open the controller of the root, which starts and follows the work sessions\n" +
    "  --list         list the sessions of the root, the newest first\n" +
    "  --inspect      serve the inspector of the journals and the exchange files of the root on 127.0.0.1\n" +
    s"  --port N       the port of the inspector, by default ${Inspector.DefaultPort}; 0 takes a free port\n" +
    "  --script       run one line at a time: each line waits for the end of the turn before it, and the end of\n" +
    "                 the input waits for the open turn\n" +
    "  --no-memory-calls\n" +
    "                 make no compress or merge call of the memory tree: each message of the memory shows its\n" +
    "                 first 512 bytes. It applies to each session of the host, also to a session that opens again\n" +
    "  --cell-wall-minutes N\n" +
    "                 stop a cell after N minutes of wall time, with its guest API calls, by default 15; 0 gives\n" +
    "                 no wall limit. It applies to each session of the host, also to a session that opens again\n" +
    "  --unattended-turns N\n" +
    "                 after N turns in a row with no message of yours, a message of another session, of a job or\n" +
    "                 of the harness waits for you, by default 8; 0 gives no limit. It applies to each session of\n" +
    "                 the host, also to a session that opens again\n" +
    "  FOLDER         the folder of a new session, by default the current folder"

  /** The arguments from the command line: `[--root DIR] [--session ID | --controller] [--script] [--no-memory-calls]
    * [--cell-wall-minutes N] [--unattended-turns N] [--list] [FOLDER]`. The folder is `cwd` by default, and the root is
    * `<home>/.dspy4s/harness`. An unknown flag, a missing value, a value of `--cell-wall-minutes` or of
    * `--unattended-turns` that is not a whole number 0 or more, or both `--session` and `--controller` give an error
    * with the usage.
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
    def walk(rest: List[String], got: CliArgs, folder: Option[Path], port: Boolean = false): Either[String, CliArgs] =
      rest match
        case Nil if got.controller && got.session.nonEmpty => Left(s"Give --session or --controller, not both.\n$usage")
        case Nil if port && !got.inspect                   => Left(s"Give --port only with --inspect.\n$usage")
        case Nil
            if got.inspect &&
              (got.session.nonEmpty || got.controller || got.list || got.script || !got.memoryCalls ||
                got.cellWallMinutes.nonEmpty || got.unattendedTurns.nonEmpty || folder.nonEmpty) =>
          Left(s"Give --inspect with only --root and --port.\n$usage")
        case Nil                                                                      => Right(folder.fold(got)(f => got.copy(scope = f)))
        case "--inspect" :: more                                                      => walk(more, got.copy(inspect = true), folder, port)
        case "--port" :: n :: more if n.toIntOption.exists(p => p >= 0 && p <= 65535) =>
          walk(more, got.copy(port = n.toInt), folder, port = true)
        case "--root" :: dir :: more if !dir.startsWith("--")                       => walk(more, got.copy(root = at(dir)), folder, port)
        case "--session" :: id :: _ if !id.startsWith("--") && !SessionId.valid(id) =>
          Left(s"The session id $id is not valid.\n$usage")
        case "--session" :: id :: more if !id.startsWith("--")                  => walk(more, got.copy(session = Some(id)), folder, port)
        case "--script" :: more                                                 => walk(more, got.copy(script = true), folder, port)
        case "--no-memory-calls" :: more                                        => walk(more, got.copy(memoryCalls = false), folder, port)
        case "--cell-wall-minutes" :: n :: more if n.toIntOption.exists(_ >= 0) =>
          walk(more, got.copy(cellWallMinutes = n.toIntOption), folder, port)
        case "--unattended-turns" :: n :: more if n.toIntOption.exists(_ >= 0) =>
          walk(more, got.copy(unattendedTurns = n.toIntOption), folder, port)
        case "--list" :: more                   => walk(more, got.copy(list = true), folder, port)
        case "--controller" :: more             => walk(more, got.copy(controller = true), folder, port)
        case flag :: _ if flag.startsWith("--") => Left(s"The argument $flag is not right.\n$usage")
        case path :: more if folder.isEmpty     => walk(more, got, Some(at(path)), port)
        case path :: _                          => Left(s"Give one folder, not also $path.\n$usage")
    walk(args.toList, CliArgs(cwd.normalize(), home.resolve(".dspy4s/harness").normalize(), None), None)
