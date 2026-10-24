package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues.asString
import dspy4s.core.contracts.DynamicValues.recordGet as field
import dspy4s.core.runtime.InterpreterSupport.ProbeRun

import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** Locates the bundled runner and builds the least-privilege Deno launch command for a sandbox. */
private[runtime] object DenoPyodideLaunch:
  import DenoPyodideProtocol.decodeRecord

  /** The Deno executable of the default launch command. */
  private val Executable = "deno"

  /** The time limit for `deno --version` and for `deno info --json` (upstream `DENO_PROBE_TIMEOUT_SECONDS`). */
  val ProbeTimeoutMillis = 10000L

  private val MinVersion        = (2, 0, 0)
  private val MaxVersion        = (3, 0, 0)
  private val SupportedVersions = ">=2.0.0,<3.0.0"
  private val VersionPattern    = """deno (\d+)\.(\d+)\.(\d+)""".r
  private val InstallHint       =
    "Install a compatible Deno (https://docs.deno.com/runtime/getting_started/installation/), e.g. `brew install deno`, or pass a custom `denoCommand`."

  def command(
      enableReadPaths    : Vector[String],
      enableWritePaths   : Vector[String],
      enableEnvVars      : Vector[String],
      enableNetworkAccess: Vector[String]
  ): Vector[String] =
    val runner    = runnerPath.toString
    val cacheDir  = denoCacheDir
    val readPaths = (Vector(runner) ++ cacheDir.toVector ++ enableReadPaths ++ enableWritePaths).map(canonical)
    val args      = Vector.newBuilder[String]
    args += Executable
    args += "run"
    // Deno does not use the configuration file, the lock file, or the `node_modules` directory of the working
    // directory.
    args += "--no-config"
    args += "--no-lock"
    args += "--node-modules-dir=false"
    args += s"--allow-read=${readPaths.mkString(",")}"
    if enableEnvVars.nonEmpty then args += s"--allow-env=${enableEnvVars.mkString(",")}"
    if enableNetworkAccess.nonEmpty then args += s"--allow-net=${enableNetworkAccess.mkString(",")}"
    if enableWritePaths.nonEmpty then args += s"--allow-write=${enableWritePaths.map(canonical).mkString(",")}"
    args += canonical(runner)
    // runner.js reads Deno.args[0] as the list of environment variables, so the argument is always present.
    args += enableEnvVars.mkString(",")
    // runner.js revokes the read permission for this directory after it loaded Pyodide.
    cacheDir.foreach(dir => args += s"--dspy-deno-dir=${canonical(dir)}")
    args.result()

  /** The process of the default launch command, not started. `Left` when a write path overlaps a runtime file, or when
    * the Deno version is not supported.
    */
  def defaultProcess(
      enableReadPaths    : Vector[String],
      enableWritePaths   : Vector[String],
      enableEnvVars      : Vector[String],
      enableNetworkAccess: Vector[String]
  ): Either[DspyError, ProcessBuilder] =
    for
      _ <- checkWritePaths(enableWritePaths)
      _ <- checkDefaultVersion()
    yield
      val builder = new ProcessBuilder(command(enableReadPaths, enableWritePaths, enableEnvVars, enableNetworkAccess)*)
      isolate(builder)
      builder

  /** Upstream `_deno_subprocess_env`: Deno does not look for a `package.json` file in the working directory. */
  private def isolate(builder: ProcessBuilder): Unit =
    val _ = builder.environment().put("DENO_NO_PACKAGE_JSON", "1")

  /** The version in the output of `deno --version`, for example `deno 2.7.4 (stable, release, aarch64-apple-darwin)`.
    */
  def parseVersion(output: String): Option[(Int, Int, Int)] =
    VersionPattern.findPrefixMatchOf(output).flatMap { found =>
      for
        major <- found.group(1).toIntOption
        minor <- found.group(2).toIntOption
        patch <- found.group(3).toIntOption
      yield (major, minor, patch)
    }

  /** `Right` for a Deno version from 2.0.0 to less than 3.0.0 (upstream `_validate_deno_version`). `None` is a version
    * that the probe did not find.
    */
  def checkVersion(version: Option[(Int, Int, Int)], executable: String): Either[DspyError, Unit] =
    val order = summon[Ordering[(Int, Int, Int)]]
    version match
      case Some(found) if order.lteq(MinVersion, found) && order.lt(found, MaxVersion) => Right(())
      case Some((major, minor, patch))                                                 => CodeInterpreterErrors.failure(
          s"Unsupported Deno version $major.$minor.$patch. DenoPyodideInterpreter supports Deno $SupportedVersions. $InstallHint"
        )
      case None => CodeInterpreterErrors.failure(
          s"Unable to determine the Deno version from '$executable'. DenoPyodideInterpreter requires Deno $SupportedVersions. $InstallHint"
        )

  /** Runs `<executable> --version` and gives the version. `None` when the executable is absent, fails, gives text that
    * is not a version, or does not end in `timeoutMillis`.
    */
  def probeVersion(executable: String, timeoutMillis: Long): Option[(Int, Int, Int)] =
    probe(Vector(executable, "--version"), timeoutMillis).flatMap(parseVersion)

  // One supported version serves the JVM. A check that failed runs again on the next call, so the JVM sees a Deno that
  // the user installed after the failure.
  @volatile private var supportedVersionFound = false

  private def checkDefaultVersion(): Either[DspyError, Unit] =
    if supportedVersionFound then Right(())
    else
      checkVersion(probeVersion(Executable, ProbeTimeoutMillis), Executable).map { _ =>
        supportedVersionFound = true
      }

  /** Runs a command that reports data about Deno and gives its standard output. `None` when the command is absent,
    * fails, or does not end in `timeoutMillis`. The process does not stay alive after the call.
    */
  private def probe(command: Vector[String], timeoutMillis: Long): Option[String] =
    val builder = new ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.DISCARD)
    isolate(builder)
    InterpreterSupport.probe(builder, timeoutMillis, Int.MaxValue).toOption.collect { case ProbeRun(0, output) =>
      output
    }

  /** The real path of `path` (upstream `_canonicalize_path`). Deno compares a path with its permission flags as text,
    * so each flag and each mount must name the real path. The function resolves the symbolic links of the part of
    * `path` that exists, as `os.path.realpath` does: a write path can be a file that the mount creates after the
    * launch.
    */
  def canonical(path: String): String =
    val absolute = Paths.get(path).toAbsolutePath
    try
      val existing = Iterator.iterate(Option(absolute))(_.flatMap(parent => Option(parent.getParent)))
        .takeWhile(_.isDefined).flatten.find(Files.exists(_))
      existing.fold(absolute)(base => base.toRealPath().resolve(base.relativize(absolute))).normalize().toString
    catch case NonFatal(_) => absolute.normalize().toString

  /** The path of a mounted host file in the sandbox. The sandbox knows a mount by its file name only. */
  def sandboxPath(path: String): String = s"/sandbox/${Paths.get(path).getFileName}"

  /** `Left` when two mounts have one path in the sandbox and different host files (upstream 072c72164). The second
    * mount replaces the first one in the sandbox, and the sync of a write path can then change the wrong host file.
    */
  def checkMounts(paths: Vector[String]): Either[DspyError, Unit] =
    val collision = paths.groupBy(sandboxPath).values.exists(_.map(canonical).distinct.sizeIs > 1)
    if collision then CodeInterpreterErrors.failure("Mounted files must have unique basenames inside the sandbox.")
    else Right(())

  /** `Left` when a write path overlaps a runtime file of the sandbox: the runner file or the Deno cache directory
    * (upstream ab8a7658d). Sandboxed code that can write such a file can change code that runs with no sandbox.
    */
  def checkWritePaths(enableWritePaths: Vector[String]): Either[DspyError, Unit] =
    val runtimeFiles = (Vector(runnerPath.toString) ++ denoCacheDir.toVector).map(canonical)
    enableWritePaths.find(path => runtimeFiles.exists(pathsOverlap(canonical(path), _))) match
      case Some(path) => CodeInterpreterErrors.failure(
          s"Write paths cannot overlap the runtime files of DenoPyodideInterpreter (the runner file and the Deno cache directory): $path"
        )
      case None => Right(())

  /** Two paths overlap when they are equal or when one contains the other (upstream `_paths_overlap`). */
  def pathsOverlap(first: String, second: String): Boolean =
    val (a, b) = (Paths.get(first), Paths.get(second))
    a.startsWith(b) || b.startsWith(a)

  /** Extract the vendored `runner.js` to a temp file once per JVM (Deno needs a real path it can --allow-read). */
  private lazy val runnerPath: Path = InterpreterSupport.extractResource(
    "/dspy4s/core/runtime/runner.js",
    "dspy4s-runner",
    ".js",
    error => s"Unable to write the Deno runner script: ${error.getMessage}"
  ).fold(message => throw new IllegalStateException(message), identity)

  // One answer of `deno info --json` serves the JVM. A query that gave no directory runs again on the next call.
  @volatile private var queriedCacheDir: Option[String] = None

  /** Deno's cache directory (`DENO_DIR` or `deno info --json`), allow-read'd so Pyodide can load its files. */
  private def denoCacheDir: Option[String] =
    sys.env.get("DENO_DIR").orElse(queriedCacheDir).orElse {
      queriedCacheDir = probe(Vector(Executable, "info", "--json"), ProbeTimeoutMillis)
        .flatMap(decodeRecord).flatMap(field(_, "denoDir")).flatMap(asString)
      queriedCacheDir
    }
