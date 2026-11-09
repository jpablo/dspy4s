package dspy4s.harness.sandbox

import java.nio.file.{Files as JFiles, Path}

/** The backend for Linux: `bwrap` (CODE_HARNESS.md, F8).
  *
  * The root is read-only, the writable roots are writable, and an empty folder or file covers each secret. A new pid
  * namespace hides the other processes, also the harness process and its variables in `/proc`. The network namespace is
  * always new, so a command has no network, also in a mode with `Network^`. The proxy needs a bridge into the
  * namespace, as in Codex, and that comes later. Less network is less authority, so the law of 5.4 holds.
  */
object Bubblewrap extends Backend:
  val name = "bubblewrap"

  /** The first `bwrap` of the usual places. */
  def program: Option[String] = Vector("/usr/bin/bwrap", "/bin/bwrap", "/usr/local/bin/bwrap").find(Host.isExecutable)

  def works(): Boolean = Host.os.contains("linux") && program.isDefined && Host.probe(this)

  def wrap(profile: Profile, cwd: Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String] =
    require(profile.canRun, "the profile allows no child process")
    val bwrap = program.getOrElse(throw IllegalStateException("bwrap is not on this host"))
    (bwrap +: flags(profile, cwd)) ++ ("--" +: argv)

  /** The arguments of `bwrap` for `profile`. A later mount covers an earlier one, so the secrets come last. Only a path
    * that exists can have a mount, so a new file with a secret name has none (5.4).
    */
  def flags(profile: Profile, cwd: Path): Vector[String] =
    val namespaces = Vector("--die-with-parent", "--new-session", "--unshare-pid", "--unshare-ipc", "--unshare-net")
    val base       = Vector("--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc")
    val writable   = profile.writable.filter(JFiles.isDirectory(_)).flatMap(p => Vector("--bind", p.toString, p.toString))
    val readOnly   = profile.readOnly.filter(JFiles.exists(_)).flatMap(p => Vector("--ro-bind", p.toString, p.toString))
    val hidden     = profile.hidden.distinct
    // A mount inside a covered folder fails, because the empty folder does not have the path.
    val secrets = hidden.filterNot(p => hidden.exists(q => !q.equals(p) && p.startsWith(q))).flatMap(cover)
    namespaces ++ base ++ writable ++ readOnly ++ secrets ++ Vector("--chdir", cwd.toString)

  /** The mount that covers a secret: an empty read-only folder over a folder, and the null device over a file. */
  private def cover(path: Path): Vector[String] =
    if JFiles.isDirectory(path) then Vector("--tmpfs", path.toString, "--remount-ro", path.toString)
    else if JFiles.exists(path) then Vector("--ro-bind", "/dev/null", path.toString)
    else Vector.empty
