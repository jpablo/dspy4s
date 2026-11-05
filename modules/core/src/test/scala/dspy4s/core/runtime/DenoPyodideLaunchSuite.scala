package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import scala.util.control.NonFatal

/** The launch command of the Deno sandbox, the Deno version check, and the content of the vendored runner (PORT_GAPS
  * G-34; upstream `dspy/primitives/python_interpreter.py` and `dspy/primitives/runner.js`).
  */
class DenoPyodideLaunchSuite extends FunSuite:

  private lazy val denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  private lazy val shellAvailable: Boolean = Files.isExecutable(Path.of("/bin/sh"))

  /** An executable shell script that takes the place of `deno` for the version probe. */
  private def fakeDeno(body: String): String =
    val path = Files.createTempFile("dspy4s-fake-deno", ".sh")
    path.toFile.deleteOnExit()
    val _ = Files.write(path, s"#!/bin/sh\n$body\n".getBytes(StandardCharsets.UTF_8))
    val _ = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    path.toString

  private def command(env: Vector[String] = Vector.empty): Vector[String] =
    DenoPyodideLaunch.command(
      enableReadPaths = Vector.empty,
      enableWritePaths = Vector.empty,
      enableEnvVars = env,
      enableNetworkAccess = Vector.empty
    )

  /** The arguments from the runner script to the end. The runner is the one argument that is a `.js` path. */
  private def fromRunner(args: Vector[String]): Vector[String] =
    args.dropWhile(arg => arg.startsWith("--") || !arg.endsWith(".js"))

  private def runnerSource: String =
    val stream = getClass.getResourceAsStream("/dspy4s/core/runtime/runner.js")
    assert(stream != null, "the runner.js resource is absent")
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  test("the launch command isolates Deno from the configuration of the working directory") {
    val args = command()
    assertEquals(args.slice(1, 5), Vector("run", "--no-config", "--no-lock", "--node-modules-dir=false"))
  }

  test("the argument after the runner is the list of environment variables, also when the list is empty") {
    assertEquals(fromRunner(command()).lift(1), Some(""))
    assertEquals(fromRunner(command(env = Vector("HOME", "LANG"))).lift(1), Some("HOME,LANG"))
  }

  test("the runner gets the Deno cache directory, and the directory is in the read list") {
    assume(denoAvailable, "deno not installed")
    val args    = command()
    val denoDir =
      fromRunner(args).lift(2).filter(_.startsWith("--dspy-deno-dir=")).map(_.stripPrefix("--dspy-deno-dir="))
    assert(denoDir.exists(_.nonEmpty), s"no --dspy-deno-dir argument after the environment list: ${fromRunner(args)}")
    val readList = args.find(_.startsWith("--allow-read=")).map(_.stripPrefix("--allow-read=").split(',').toVector)
    assert(readList.exists(_.contains(denoDir.get)), s"${denoDir.get} is not in $readList")
  }

  test("parseVersion reads the first line of `deno --version`") {
    val output = "deno 2.7.4 (stable, release, aarch64-apple-darwin)\nv8 14.5.201.2-rusty\ntypescript 5.9.2\n"
    assertEquals(DenoPyodideLaunch.parseVersion(output), Some((2, 7, 4)))
    assertEquals(DenoPyodideLaunch.parseVersion("deno 1.46.3"), Some((1, 46, 3)))
    assertEquals(DenoPyodideLaunch.parseVersion("node v22.1.0"), None)
    assertEquals(DenoPyodideLaunch.parseVersion(""), None)
  }

  test("checkVersion accepts Deno 2.x only") {
    Vector((2, 0, 0), (2, 7, 4), (2, 99, 0)).foreach { version =>
      assertEquals(DenoPyodideLaunch.checkVersion(Some(version), "deno"), Right(()), version.toString)
    }
    Vector((1, 46, 3), (3, 0, 0), (0, 1, 0)).foreach { version =>
      DenoPyodideLaunch.checkVersion(Some(version), "deno") match
        case Left(RuntimeError(CodeInterpreterErrors.Interpreter, message)) =>
          assert(message.contains(s"${version._1}.${version._2}.${version._3}"), message)
          assert(message.contains(">=2.0.0,<3.0.0"), message)
        case other => fail(s"expected an interpreter error for $version, got $other")
    }
  }

  test("checkVersion rejects a version that the probe did not find, and names the executable") {
    DenoPyodideLaunch.checkVersion(None, "/opt/tools/deno") match
      case Left(RuntimeError(CodeInterpreterErrors.Interpreter, message)) =>
        assert(message.contains("/opt/tools/deno"), message)
        assert(message.contains(">=2.0.0,<3.0.0"), message)
      case other => fail(s"expected an interpreter error, got $other")
  }

  test("probeVersion reads the version from the executable") {
    assume(shellAvailable, "/bin/sh is absent")
    val deno = fakeDeno("""echo "deno 2.3.1 (stable, release, x86_64-unknown-linux-gnu)"; echo "v8 13.0"""")
    assertEquals(DenoPyodideLaunch.probeVersion(deno, timeoutMillis = 5000L), Some((2, 3, 1)))
  }

  test("probeVersion gives no version for an executable that is absent, that fails, or that prints other text") {
    assume(shellAvailable, "/bin/sh is absent")
    assertEquals(DenoPyodideLaunch.probeVersion("/nonexistent/dspy4s/deno", timeoutMillis = 5000L), None)
    assertEquals(DenoPyodideLaunch.probeVersion(fakeDeno("""echo "deno 2.3.1"; exit 3"""), 5000L), None)
    assertEquals(DenoPyodideLaunch.probeVersion(fakeDeno("""echo "not a version""""), 5000L), None)
  }

  test("probeVersion stops at the time limit") {
    assume(shellAvailable, "/bin/sh is absent")
    val slow    = fakeDeno("""sleep 20; echo "deno 2.3.1"""")
    val started = System.nanoTime()
    assertEquals(DenoPyodideLaunch.probeVersion(slow, timeoutMillis = 300L), None)
    val elapsedMillis = (System.nanoTime() - started) / 1000000L
    assert(elapsedMillis < 5000L, s"the probe took $elapsedMillis ms")
  }

  test("the version probe runs with DENO_NO_PACKAGE_JSON=1") {
    assume(shellAvailable, "/bin/sh is absent")
    // The script prints the value of the variable as the minor version.
    val deno = fakeDeno("""echo "deno 2.${DENO_NO_PACKAGE_JSON:-0}.0"""")
    assertEquals(DenoPyodideLaunch.probeVersion(deno, timeoutMillis = 5000L), Some((2, 1, 0)))
  }

  test("the default process runs with DENO_NO_PACKAGE_JSON=1") {
    assume(denoAvailable, "deno not installed")
    DenoPyodideLaunch.defaultProcess(Vector.empty, Vector.empty, Vector.empty, Vector.empty) match
      case Right(builder) => assertEquals(builder.environment().get("DENO_NO_PACKAGE_JSON"), "1")
      case Left(error)    => fail(s"expected a process builder, got $error")
  }

  test("the real Deno executable passes the version check") {
    assume(denoAvailable, "deno not installed")
    val version = DenoPyodideLaunch.probeVersion("deno", timeoutMillis = 10000L)
    assert(version.isDefined, "the probe found no version for the real deno")
    assertEquals(DenoPyodideLaunch.checkVersion(version, "deno"), Right(()))
  }

  test("canonical resolves the symbolic links of a path that does not exist") {
    val directory = Files.createTempDirectory("dspy4s-canonical").toRealPath()
    val target    = Files.createDirectory(directory.resolve("target"))
    val link      =
      try Some(Files.createSymbolicLink(directory.resolve("link"), target))
      catch case NonFatal(_) => None
    assume(link.isDefined, "this file system does not permit symbolic links")
    val existing = Files.write(target.resolve("data.txt"), "data".getBytes(StandardCharsets.UTF_8))
    assertEquals(DenoPyodideLaunch.canonical(link.get.resolve("data.txt").toString), existing.toString)
    // The file does not exist, and the directory before it is a symbolic link.
    assertEquals(
      DenoPyodideLaunch.canonical(link.get.resolve("absent.txt").toString),
      target.resolve("absent.txt").toString
    )
    assertEquals(
      DenoPyodideLaunch.canonical(link.get.resolve("absent").resolve("deep.txt").toString),
      target.resolve("absent").resolve("deep.txt").toString
    )
  }

  test("a write path overlaps a runtime file when one of the two paths contains the other") {
    assert(DenoPyodideLaunch.pathsOverlap("/cache/deno", "/cache/deno"))
    assert(DenoPyodideLaunch.pathsOverlap("/cache/deno/npm/file.js", "/cache/deno"))
    assert(DenoPyodideLaunch.pathsOverlap("/cache", "/cache/deno"))
    assert(!DenoPyodideLaunch.pathsOverlap("/cache/deno-other", "/cache/deno"))
    assert(!DenoPyodideLaunch.pathsOverlap("/work/out.txt", "/cache/deno"))
  }

  test("checkMounts rejects two host files with one name in the sandbox") {
    val first  = Files.write(Files.createTempDirectory("dspy4s-check-a").resolve("data.txt"), Array.emptyByteArray)
    val second = Files.write(Files.createTempDirectory("dspy4s-check-b").resolve("data.txt"), Array.emptyByteArray)
    val third  = Files.write(Files.createTempDirectory("dspy4s-check-c").resolve("other.txt"), Array.emptyByteArray)
    assertEquals(DenoPyodideLaunch.checkMounts(Vector(first.toString, third.toString, first.toString)), Right(()))
    DenoPyodideLaunch.checkMounts(Vector(first.toString, third.toString, second.toString)) match
      case Left(RuntimeError(CodeInterpreterErrors.Interpreter, message)) =>
        assertEquals(message, "Mounted files must have unique basenames inside the sandbox.")
      case other => fail(s"expected an interpreter error, got $other")
  }

  test("the vendored runner is the current upstream file") {
    val source = runnerSource
    // Upstream f1260ff14: the Pyodide version is fixed, the control exception has a private name, an unhandled
    // promise rejection is a notification, the globals that the protocol uses are frozen, and the runner revokes
    // the read permission for the Deno cache directory.
    assert(source.contains("""import pyodideModule from "npm:pyodide@0.29.4/pyodide.js";"""), "Pyodide version")
    assert(source.contains("class _DSPyFinalOutput(BaseException):"), "private control exception")
    assert(!source.contains("class FinalOutput("), "the old control exception is still defined")
    assert(source.contains("""jsonrpcNotification("unhandled_error""""), "unhandled rejection as a notification")
    assert(source.contains("Object.freeze(globalThis.JSON)"), "frozen JSON")
    assert(source.contains("--dspy-deno-dir="), "Deno cache directory argument")
    assert(source.contains("""Deno.permissions.revoke({ name: "read", path: denoDir })"""), "revoked read permission")
    assert(source.contains("""type === "NoneType" ? "type(None)" : type"""), "NoneType annotation")
  }

  test("the launch command writes the runner script again after a removal of the file") {
    val runner = Path.of(fromRunner(command()).head)
    val _      = Files.deleteIfExists(runner)
    val again  = Path.of(fromRunner(command()).head)
    assert(Files.isRegularFile(again), s"the runner script $again is missing")
  }

  test("the vendored runner keeps the three comment lines that dspy4s changed") {
    val source = runnerSource
    assert(source.contains("# Only define if not already registered with declared output fields."))
    assert(source.contains("// Generate a tool wrapper function with declared parameters."))
    assert(source.contains("// Register tools with declared parameter schemas"))
  }
