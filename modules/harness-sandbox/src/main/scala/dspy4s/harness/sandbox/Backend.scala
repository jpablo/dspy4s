package dspy4s.harness.sandbox

import java.nio.file.{Files as JFiles, Path}
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.util.Try

/** An OS sandbox (CODE_HARNESS_DESIGN.md, 5.4): it gives the command line that runs a command under a profile. A child
  * of the command keeps the limits.
  */
trait Backend:
  def name: String

  /** True when the backend works on this host: its program is there, and a shell runs under `wrap` with a profile like
    * the profile of a session (`Host.probe`).
    */
  def works(): Boolean

  /** The command line that runs `argv` in the folder `cwd` under `profile`. The profile must allow a child process.
    * With `proxyPort`, a profile with the network can reach that port of the loopback address.
    */
  def wrap(profile: Profile, cwd: Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String]

/** The helpers that the backends share. */
private[sandbox] object Host:

  def os: String = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)

  def isExecutable(path: String): Boolean = JFiles.isExecutable(Path.of(path))

  /** True when `/bin/sh -c true` runs under `backend`, with the command line that `exec` uses. A probe with other
    * arguments can pass on a host where each command fails, for example where a container does not let bwrap mount
    * `/proc`. The profile has a child process and one writable root, the temporary folder of the JVM.
    */
  def probe(backend: Backend): Boolean =
    val temp    = Layout.real(Path.of(System.getProperty("java.io.tmpdir")))
    val profile = Profile(
      canRun = true,
      writable = Vector(temp),
      readOnly = Vector.empty,
      hidden = Vector.empty,
      net = Net.Closed
    )
    Try(backend.wrap(profile, temp, Vector("/bin/sh", "-c", "true"), None)).toOption.exists(runs)

  /** True when the process runs. `ProcessHandle.isAlive` is also true for a zombie, a process that ended and that its
    * parent did not reap. A JVM that is PID 1 in a container with no init does not reap its orphans, so they stay
    * zombies. Only Linux shows the state, in `/proc`.
    */
  def running(process: ProcessHandle): Boolean =
    process.isAlive && !Try(JFiles.readString(Path.of(s"/proc/${process.pid}/stat"))).toOption.exists { stat =>
      // The state comes after the name, and the name is in parentheses.
      stat.drop(stat.lastIndexOf(')') + 1).trim.headOption.exists(state => state == 'Z' || state == 'X')
    }

  /** True when the command exits with 0 in 10 seconds. Its output goes nowhere. */
  def runs(argv: Vector[String]): Boolean =
    try
      val process = ProcessBuilder(argv*)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      if process.waitFor(10, TimeUnit.SECONDS) then process.exitValue() == 0
      else
        process.destroyForcibly(): Unit
        false
    catch case _: java.io.IOException => false
