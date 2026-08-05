package dspy4s.core.runtime

import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** Locates the bundled runner and builds the least-privilege Deno launch command for a sandbox. */
private[runtime] object DenoPyodideLaunch:
  import DenoPyodideProtocol.*

  def command(
      enableReadPaths: Vector[String],
      enableWritePaths: Vector[String],
      enableEnvVars: Vector[String],
      enableNetworkAccess: Vector[String]
  ): Vector[String] =
    val runner    = runnerPath.toString
    val readPaths = (Vector(runner) ++ denoCacheDir.toVector ++ enableReadPaths ++ enableWritePaths).map(canonical)
    val args      = Vector.newBuilder[String]
    args += "deno"
    args += "run"
    args += s"--allow-read=${readPaths.mkString(",")}"
    if enableEnvVars.nonEmpty then args += s"--allow-env=${enableEnvVars.mkString(",")}"
    if enableNetworkAccess.nonEmpty then args += s"--allow-net=${enableNetworkAccess.mkString(",")}"
    if enableWritePaths.nonEmpty then args += s"--allow-write=${enableWritePaths.map(canonical).mkString(",")}"
    args += canonical(runner)
    if enableEnvVars.nonEmpty then args += enableEnvVars.mkString(",") // runner.js reads Deno.args[0]
    args.result()

  def canonical(path: String): String =
    try Paths.get(path).toRealPath().toString
    catch case NonFatal(_) => Paths.get(path).toAbsolutePath.normalize().toString

  /** Extract the vendored `runner.js` to a temp file once per JVM (Deno needs a real path it can --allow-read). */
  private lazy val runnerPath: Path =
    val stream = Option(getClass.getResourceAsStream("/dspy4s/core/runtime/runner.js"))
      .getOrElse(throw new IllegalStateException("runner.js resource missing from dspy4s-core"))
    val file = Files.createTempFile("dspy4s-runner", ".js")
    try Files.write(file, stream.readAllBytes())
    finally stream.close()
    file.toFile.deleteOnExit()
    file

  /** Deno's cache directory (`DENO_DIR` or `deno info --json`), allow-read'd so Pyodide can load its files. */
  private lazy val denoCacheDir: Option[String] =
    sys.env.get("DENO_DIR").orElse {
      try
        val p   = new ProcessBuilder("deno", "info", "--json").start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        if p.waitFor() == 0 then
          decodeJson(out).collect { case r: DynamicValue.Record => r }.flatMap(field(_, "denoDir")).flatMap(asString)
        else None
      catch case NonFatal(_) => None
    }
