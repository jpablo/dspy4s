package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxTool
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Session rules of [[DenoPyodideInterpreter]] against a scripted sandbox process (PORT_GAPS G-33 and G-35; upstream
  * `dspy/primitives/python_interpreter.py` after 80b118e52 and 072c72164).
  *
  * The rules: each request has a random id, a tool cannot call `execute` on its interpreter, one call runs at a time, a
  * session that failed or that is closed does not start again, and two mounts cannot have the same name in the sandbox.
  *
  * The suite replaces the Deno launch command with a small Python script that speaks the same JSON-RPC lines. The suite
  * skips when `python3` is not on PATH.
  */
class SandboxSessionSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(20, "s")

  private val fakeSandbox: String =
    """import json
      |import sys
      |
      |def send(message):
      |    sys.stdout.write(json.dumps(message) + "\n")
      |    sys.stdout.flush()
      |
      |def result(output, request_id):
      |    send({"jsonrpc": "2.0", "result": {"output": output}, "id": request_id})
      |
      |def read_message():
      |    line = sys.stdin.readline()
      |    return json.loads(line) if line.strip() else None
      |
      |while True:
      |    line = sys.stdin.readline()
      |    if not line:
      |        break
      |    line = line.strip()
      |    if not line:
      |        continue
      |    message = json.loads(line)
      |    method = message.get("method")
      |    request_id = message.get("id")
      |    if method == "shutdown":
      |        break
      |    if method in ("register", "mount_file"):
      |        if "wrong_id_setup" in sys.argv[1:]:
      |            # A response for a different request comes first. The correct response comes after it, so a
      |            # client that skips the first one does not block.
      |            send({"jsonrpc": "2.0", "result": {}, "id": "not-the-request-id"})
      |        if "fail_setup" in sys.argv[1:]:
      |            send({"jsonrpc": "2.0", "error": {"code": -32007, "message": "Failed to register"}, "id": request_id})
      |            continue
      |        send({"jsonrpc": "2.0", "result": {}, "id": request_id})
      |        continue
      |    if method != "execute":
      |        continue
      |    code = message["params"]["code"]
      |    if code == "print(1+1)":
      |        result("3\n" if "bad_health" in sys.argv[1:] else "2\n", request_id)
      |    elif code == "echo_id":
      |        result(json.dumps(request_id) + "\n", request_id)
      |    elif code == "die":
      |        sys.exit(3)
      |    elif code == "notify_and_die":
      |        send({"jsonrpc": "2.0", "method": "unhandled_error", "params": {"message": "boom before the exit"}})
      |        sys.stderr.write("fatal: the sandbox has no memory\n")
      |        sys.stderr.flush()
      |        sys.exit(4)
      |    elif code == "wait_for_shutdown":
      |        # The script gives no response. It stops when the client sends the shutdown notification.
      |        read_message()
      |        sys.exit(0)
      |    elif code == "notify":
      |        send({"jsonrpc": "2.0", "method": "unhandled_error", "params": {"message": "boom of the first request"}})
      |        result("notified\n", request_id)
      |    elif code == "method_not_found":
      |        send({"jsonrpc": "2.0", "error": {"code": -32601, "message": "Method not found"}, "id": request_id})
      |    elif code == "malformed_result":
      |        send({"jsonrpc": "2.0", "result": "not an object", "id": request_id})
      |    elif code == "call_tool":
      |        send({"jsonrpc": "2.0", "method": "tool_call", "params": {"name": "reenter", "kwargs": {}}, "id": "tc_1"})
      |        reply = read_message()
      |        # A client that lets a tool call `execute` again sends a request here, not the tool reply. The
      |        # script answers that request and reads the tool reply after it, so the client does not block.
      |        while reply is not None and reply.get("method") == "execute":
      |            result("echo " + reply["params"]["code"] + "\n", reply.get("id"))
      |            reply = read_message()
      |        if reply is not None and "error" in reply:
      |            result("tool error: " + reply["error"]["message"] + "\n", request_id)
      |        else:
      |            result("tool ok\n", request_id)
      |    else:
      |        result("echo " + code + "\n", request_id)
      |""".stripMargin

  private lazy val pythonAvailable: Boolean =
    try new ProcessBuilder("python3", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  private lazy val script: Path =
    val path = Files.createTempFile("dspy4s-fake-session", ".py")
    path.toFile.deleteOnExit()
    Files.write(path, fakeSandbox.getBytes(StandardCharsets.UTF_8))

  private def fakeCommand(scriptArgs: Vector[String] = Vector.empty): Option[Vector[String]] =
    Some(Vector("python3", script.toString) ++ scriptArgs)

  private def withSandbox[A](
      tools     : Vector[SandboxTool] = Vector.empty,
      scriptArgs: Vector[String]      = Vector.empty
  )(body: DenoPyodideInterpreter => A): A =
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val interpreter = new DenoPyodideInterpreter(tools = tools, denoCommand = fakeCommand(scriptArgs))
    try body(interpreter)
    finally interpreter.close()

  private def interpreterError(result: Either[DspyError, CodeResult]): String =
    result match
      case Left(RuntimeError(CodeInterpreterErrors.Interpreter, message)) => message
      case other                                                          => fail(s"expected an interpreter error, got $other")

  /** The session is at its end: the next call gives an interpreter error that says so, and no new process starts. */
  private def assertSessionEnded(interpreter: DenoPyodideInterpreter): Unit =
    val message = interpreterError(interpreter.execute("after"))
    assert(message.contains("session has ended"), message)

  // ── Request ids ─────────────────────────────────────────────────────────────────────────────────────────────

  test("each request has a new random id of 32 hexadecimal characters") {
    withSandbox() { interpreter =>
      val first  = interpreter.execute("echo_id").map(_.stdout.trim)
      val second = interpreter.execute("echo_id").map(_.stdout.trim)
      val quoted = "\"[0-9a-f]{32}\"".r
      assert(first.exists(quoted.matches), s"the id is not a random string: $first")
      assert(second.exists(quoted.matches), s"the id is not a random string: $second")
      assertNotEquals(first, second)
    }
  }

  test("a setup response with a different id is a protocol breakdown") {
    val noop = SandboxTool(name = "noop", parameters = Vector.empty, invoke = _ => Right(DynamicValues.fromAny("ok")))
    withSandbox(Vector(noop), scriptArgs = Vector("wrong_id_setup")) { interpreter =>
      val message = interpreterError(interpreter.execute("hello"))
      assert(message.toLowerCase.contains("id mismatch"), message)
      assertSessionEnded(interpreter)
    }
  }

  // ── Nested calls ────────────────────────────────────────────────────────────────────────────────────────────

  test("a tool cannot call execute on its interpreter, and the outer call continues") {
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val self    = new AtomicReference[Option[DenoPyodideInterpreter]](None)
    val inner   = new AtomicReference[Option[Either[DspyError, CodeResult]]](None)
    val reenter = SandboxTool(
      name = "reenter",
      parameters = Vector.empty,
      invoke = _ =>
        inner.set(self.get().map(_.execute("inner")))
        Right(DynamicValues.fromAny("done"))
    )
    val interpreter = new DenoPyodideInterpreter(tools = Vector(reenter), denoCommand = fakeCommand())
    self.set(Some(interpreter))
    try
      val outer = interpreter.execute("call_tool")
      inner.get() match
        case Some(nested) =>
          val message = interpreterError(nested)
          assert(message.contains("recursive"), message)
        case None => fail("the tool did not run")
      assertEquals(outer.map(_.stdout.trim), Right("tool ok"))
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    finally interpreter.close()
  }

  test("a call from a different thread fails while a call is in progress, and passes after it") {
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val toolStarted = new CountDownLatch(1)
    val toolRelease = new CountDownLatch(1)
    // The script calls the tool with the name `reenter`. The tool holds the outer call open until the test releases it.
    val block = SandboxTool(
      name = "reenter",
      parameters = Vector.empty,
      invoke = _ =>
        toolStarted.countDown()
        val _ = toolRelease.await(15, TimeUnit.SECONDS)
        Right(DynamicValues.fromAny("done"))
    )
    val interpreter = new DenoPyodideInterpreter(tools = Vector(block), denoCommand = fakeCommand())
    val outer       = new AtomicReference[Option[Either[DspyError, CodeResult]]](None)
    val caller      = new Thread(() => outer.set(Some(interpreter.execute("call_tool"))))
    try
      caller.start()
      assert(toolStarted.await(15, TimeUnit.SECONDS), "the tool did not start")
      val message = interpreterError(interpreter.execute("overlap"))
      assert(message.contains("not thread-safe"), message)
      assert(message.contains("for each thread"), message)
      toolRelease.countDown()
      caller.join(15000L)
      // The rejected call wrote nothing to the sandbox, so the outer call gets its own response.
      assertEquals(outer.get().map(_.map(_.stdout.trim)), Some(Right("tool ok")))
      // The two calls do not overlap now, so this thread can use the interpreter.
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    finally
      toolRelease.countDown()
      interpreter.close()
  }

  // ── The end of a session ────────────────────────────────────────────────────────────────────────────────────

  test("a session whose process stopped does not start again") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
      val message = interpreterError(interpreter.execute("die"))
      assert(message.contains("exited"), message)
      assertSessionEnded(interpreter)
    }
  }

  test("the failure of a process that stopped has the exit code, the standard error, and the last diagnostic") {
    withSandbox() { interpreter =>
      val message = interpreterError(interpreter.execute("notify_and_die"))
      assert(message.contains("exited (code 4)"), message)
      assert(message.contains("fatal: the sandbox has no memory"), message)
      assert(message.contains("(last sandbox diagnostic: boom before the exit)"), message)
      assertSessionEnded(interpreter)
    }
  }

  test("a diagnostic of one request is not in the failure of a later request") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("notify").map(_.stdout.trim), Right("notified"))
      val message = interpreterError(interpreter.execute("die"))
      assert(message.contains("exited (code 3)"), message)
      assert(!message.contains("last sandbox diagnostic"), message)
    }
  }

  test("an error response to a setup request ends the session") {
    val noop = SandboxTool(name = "noop", parameters = Vector.empty, invoke = _ => Right(DynamicValues.fromAny("ok")))
    withSandbox(Vector(noop), scriptArgs = Vector("fail_setup")) { interpreter =>
      val message = interpreterError(interpreter.execute("hello"))
      assert(message.contains("Failed to register"), message)
      assertSessionEnded(interpreter)
    }
  }

  test("a health check that fails ends the session") {
    withSandbox(scriptArgs = Vector("bad_health")) { interpreter =>
      val message = interpreterError(interpreter.execute("hello"))
      assert(message.contains("health check"), message)
      assertSessionEnded(interpreter)
    }
  }

  test("close on a different thread stops a call that waits for the sandbox") {
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val interpreter = new DenoPyodideInterpreter(denoCommand = fakeCommand())
    val outer       = new AtomicReference[Option[Either[DspyError, CodeResult]]](None)
    val caller      = new Thread(() => outer.set(Some(interpreter.execute("wait_for_shutdown"))))
    try
      assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
      caller.start()
      // The call has time to send its request and to wait for the response. The result is a `Left` for each order
      // of the two threads.
      Thread.sleep(300L)
      interpreter.close()
      caller.join(15000L)
      assert(!caller.isAlive, "the call did not return after close")
      outer.get() match
        case Some(result) => assert(interpreterError(result).nonEmpty)
        case None         => fail("the call gave no result")
      assertSessionEnded(interpreter)
    finally interpreter.close()
  }

  test("an error response with a code outside the application range ends the session") {
    withSandbox() { interpreter =>
      val message = interpreterError(interpreter.execute("method_not_found"))
      assert(message.contains("Method not found"), message)
      assertSessionEnded(interpreter)
    }
  }

  test("a result that is not an object ends the session") {
    withSandbox() { interpreter =>
      val _ = interpreterError(interpreter.execute("malformed_result"))
      assertSessionEnded(interpreter)
    }
  }

  test("an interpreter that is closed does not start again") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
      interpreter.close()
      assertSessionEnded(interpreter)
      // A second close has no effect.
      interpreter.close()
    }
  }

  test("an interpreter that is closed before its first call does not start") {
    withSandbox() { interpreter =>
      interpreter.close()
      assertSessionEnded(interpreter)
    }
  }

  // ── Mounts ──────────────────────────────────────────────────────────────────────────────────────────────────

  private def tempFile(directory: Path, name: String): String =
    Files.write(directory.resolve(name), "data".getBytes(StandardCharsets.UTF_8)).toString

  test("two mounts with one name in the sandbox and different host files are rejected before the process starts") {
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val first       = tempFile(Files.createTempDirectory("dspy4s-mount-a"), "data.txt")
    val second      = tempFile(Files.createTempDirectory("dspy4s-mount-b"), "data.txt")
    val interpreter = new DenoPyodideInterpreter(
      enableReadPaths = Vector(first),
      enableWritePaths = Vector(second),
      denoCommand = fakeCommand()
    )
    try
      val message = interpreterError(interpreter.execute("hello"))
      assert(message.contains("unique basenames"), message)
    finally interpreter.close()
  }

  test("one host file in the read list and in the write list is one mount") {
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val file        = tempFile(Files.createTempDirectory("dspy4s-mount-c"), "data.txt")
    val interpreter = new DenoPyodideInterpreter(
      enableReadPaths = Vector(file),
      enableWritePaths = Vector(file),
      denoCommand = fakeCommand()
    )
    try assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
    finally interpreter.close()
  }
