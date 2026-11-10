package dspy4s.harness.sandbox

import java.nio.file.Path

/** The backend for macOS: `/usr/bin/sandbox-exec` with a profile in SBPL (CODE_HARNESS.md, F8).
  *
  * The profile denies by default, as the profile of Codex does. A child can start programs, read files and look only at
  * the processes of its own sandbox. It cannot reach launchd and the other services of the system, so it cannot start a
  * process outside the sandbox. Apple marks `sandbox-exec` as deprecated, but Codex and deepseek-harness use it.
  */
object Seatbelt extends Backend:
  val name    = "seatbelt"
  val program = "/usr/bin/sandbox-exec"

  def works(): Boolean = Host.os.contains("mac") && Host.isExecutable(program) && Host.probe(this)

  def wrap(profile: Profile, cwd: Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String] =
    require(profile.canRun, "the profile allows no child process")
    Vector(program, "-p", policy(profile, proxyPort)) ++ argv

  /** The SBPL text of `profile`. A later rule wins over an earlier one, so the denials come after the permissions. A
    * profile with no child process denies each operation, also the start of the command.
    */
  def policy(profile: Profile, proxyPort: Option[Int]): String =
    if !profile.canRun then "(version 1)\n(deny default)\n"
    else
      val network = profile.net match
        case Net.Closed   => Vector.empty
        case Net.Proxy(_) =>
          proxyPort.toVector.map(port => s"""(allow network-outbound (remote ip "localhost:$port"))""")
      (base ++
        Vector(
          "(allow file-read*)",
          s"(allow file-write* ${(profile.writable.map(subpath) ++ devices).mkString(" ")})"
        ) ++
        profile.readOnly.map(p => s"(deny file-write* ${subpath(p)})") ++
        profile.hidden.map(p => s"(deny file-read* file-write* ${subpath(p)})") ++
        network).mkString("", "\n", "\n")

  /** The start of each profile: what common programs need. `sh`, `git`, `python3` and `java` run with these rules. */
  private val base = Vector(
    "(version 1)",
    "(deny default)",
    "(allow process-exec)",
    "(allow process-fork)",
    "(allow signal (target same-sandbox))",
    "(allow process-info* (target same-sandbox))",
    "(allow sysctl-read)",
    "(allow user-preference-read)",
    "(allow ipc-posix-sem)",
    "(allow pseudo-tty)",
    "(allow file-ioctl)",
    """(allow mach-lookup (global-name "com.apple.system.opendirectoryd.libinfo"))"""
  )

  /** The devices that a command writes to: the null device, the terminal and the standard streams. */
  private val devices = Vector(
    """(literal "/dev/null")""",
    """(literal "/dev/zero")""",
    """(literal "/dev/tty")""",
    "(regex #\"^/dev/ttys[0-9]+$\")",
    "(regex #\"^/dev/fd/[0-9]+$\")"
  )

  private def subpath(path: Path): String = s"""(subpath "${literal(path.toString)}")"""

  /** The text of an SBPL string, which reads escapes as Scheme does. A command can make a file with a secret name, and
    * the policy of the next command names it, so each path must fit. A probe on macOS showed that the escapes of a
    * quote, a backslash, a newline and a return work, and that the other control characters work as they are.
    */
  private def literal(text: String): String =
    text.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case c    => c.toString
    }
