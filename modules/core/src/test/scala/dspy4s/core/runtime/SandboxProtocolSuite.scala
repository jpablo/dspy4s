package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.runtime.DenoPyodideProtocol.OutOfBand
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** Protocol behavior of [[DenoPyodideInterpreter]] against a scripted sandbox process (PORT_GAPS G-33).
  *
  * The suite replaces the Deno launch command with a small Python script that speaks the same JSON-RPC lines. This
  * makes out-of-band messages reproducible. The suite skips when `python3` is not on PATH.
  */
class SandboxProtocolSuite extends FunSuite:

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
      |        if "reject_setup" in sys.argv[1:]:
      |            # The real runner answers a request that it cannot read with a protocol error and a null id.
      |            # The script stops after each line that has no response. A client that waits for more lines
      |            # then sees the end of the input, so the test fails and does not block.
      |            send({"jsonrpc": "2.0", "error": {"code": -32600, "message": "Invalid Request"}, "id": None})
      |            break
      |        if "noisy_setup" in sys.argv[1:]:
      |            send({"jsonrpc": "2.0", "error": {"code": -32007, "message": "boom"}, "id": None})
      |        send({"jsonrpc": "2.0", "result": {}, "id": request_id})
      |        continue
      |    if method != "execute":
      |        continue
      |    code = message["params"]["code"]
      |    if code == "print(1+1)":
      |        result("2\n", request_id)
      |    elif code == "unsolicited":
      |        # The real runner reports an unhandled promise rejection as an error with a null id.
      |        send({"jsonrpc": "2.0", "error": {"code": -32007, "message": "Unhandled async error: boom"}, "id": None})
      |        result("real result\n", request_id)
      |    elif code == "call_tool":
      |        send({"jsonrpc": "2.0", "method": "tool_call", "params": {"name": "explode", "kwargs": {}}, "id": "tc_1"})
      |        reply = json.loads(sys.stdin.readline())
      |        if "error" in reply:
      |            result("tool error: " + reply["error"]["message"] + "\n", request_id)
      |        else:
      |            result("tool ok\n", request_id)
      |    elif code == "notification":
      |        # The current upstream runner reports an unhandled promise rejection as a notification.
      |        send({"jsonrpc": "2.0", "method": "unhandled_error", "params": {"message": "boom"}})
      |        result("real result\n", request_id)
      |    elif code == "absent_id":
      |        send({"jsonrpc": "2.0", "error": {"code": -32007, "message": "boom"}})
      |        result("real result\n", request_id)
      |    elif code == "protocol_error":
      |        send({"jsonrpc": "2.0", "error": {"code": -32700, "message": "Invalid JSON input"}, "id": None})
      |        break
      |    elif code == "wrong_id":
      |        send({"jsonrpc": "2.0", "error": {"code": -32007, "message": "stale"}, "id": "not-the-request-id"})
      |        break
      |    elif code == "user_error":
      |        data = {"type": "NameError", "args": ["name 'x' is not defined"]}
      |        send({"jsonrpc": "2.0", "error": {"code": -32001, "message": "boom", "data": data}, "id": request_id})
      |    elif code.startswith("flood "):
      |        for _ in range(int(code.split()[1])):
      |            send({"jsonrpc": "2.0", "method": "unhandled_error", "params": {"message": "boom"}})
      |        result("real result\n", request_id)
      |    else:
      |        result("echo " + code + "\n", request_id)
      |""".stripMargin

  private lazy val pythonAvailable: Boolean =
    try new ProcessBuilder("python3", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  private lazy val script: Path =
    val path = Files.createTempFile("dspy4s-fake-sandbox", ".py")
    path.toFile.deleteOnExit()
    Files.write(path, fakeSandbox.getBytes(StandardCharsets.UTF_8))

  private def withSandbox[A](
      tools     : Vector[SandboxTool] = Vector.empty,
      scriptArgs: Vector[String]      = Vector.empty
  )(body: DenoPyodideInterpreter => A): A =
    assume(pythonAvailable, "python3 not installed: the scripted sandbox cannot run")
    val interpreter =
      new DenoPyodideInterpreter(tools = tools, denoCommand = Some(Vector("python3", script.toString) ++ scriptArgs))
    try body(interpreter)
    finally interpreter.close()

  test("the scripted sandbox answers an ordinary execute request") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
    }
  }

  test("an error without an id does not replace the response to the request") {
    withSandbox() { interpreter =>
      val first = interpreter.execute("unsolicited")
      assertEquals(first.map(result => (result.stdout.trim, result.exitCode)), Right(("real result", 0)))
    }
  }

  test("an error without an id leaves the session usable") {
    withSandbox() { interpreter =>
      val _ = interpreter.execute("unsolicited")
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    }
  }

  test("a host tool that throws gives the sandbox an error reply") {
    val explode = SandboxTool(
      name = "explode",
      parameters = Vector.empty,
      invoke = _ => throw new IllegalStateException("tool exploded")
    )
    withSandbox(Vector(explode)) { interpreter =>
      val result = interpreter.execute("call_tool")
      assert(result.isRight, s"the tool failure must not fail the interpreter: $result")
      val stdout = result.toOption.get.stdout
      assert(stdout.startsWith("tool error:") && stdout.contains("tool exploded"), stdout)
    }
  }

  test("a host tool that throws leaves the session usable") {
    val explode = SandboxTool(
      name = "explode",
      parameters = Vector.empty,
      invoke = _ => throw new IllegalStateException("tool exploded")
    )
    withSandbox(Vector(explode)) { interpreter =>
      val _ =
        try interpreter.execute("call_tool")
        catch case NonFatal(_) => ()
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    }
  }

  // The tests below extend the given set: the other out-of-band forms, the bound, and the setup requests.

  private lazy val throwingTool = SandboxTool(
    name = "explode",
    parameters = Vector.empty,
    invoke = _ => throw new IllegalStateException("tool exploded")
  )

  private def interpreterError(result: Either[DspyError, CodeResult]): String =
    result match
      case Left(RuntimeError(CodeInterpreterErrors.Interpreter, message)) => message
      case other                                                          => fail(s"expected an interpreter error, got $other")

  test("a notification does not replace the response to the request") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("notification").map(_.stdout.trim), Right("real result"))
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    }
  }

  test("an error with no id member does not replace the response to the request") {
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute("absent_id").map(_.stdout.trim), Right("real result"))
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    }
  }

  test("a protocol error without an id fails the interpreter") {
    withSandbox() { interpreter =>
      val message = interpreterError(interpreter.execute("protocol_error"))
      assert(message.contains("protocol error") && message.contains("Invalid JSON input"), message)
    }
  }

  test("an error with a different id is a protocol breakdown") {
    withSandbox() { interpreter =>
      val message = interpreterError(interpreter.execute("wrong_id"))
      assert(message.startsWith("Response id mismatch"), message)
      assert(message.contains("\"not-the-request-id\""), message)
    }
  }

  test("an error with the expected id stays a user-code error") {
    withSandbox() { interpreter =>
      val result = interpreter.execute("user_error")
      assertEquals(result.map(_.exitCode), Right(1))
      assert(result.exists(_.stderr.startsWith("NameError:")), result.toString)
      assertEquals(interpreter.execute("after").map(_.stdout.trim), Right("echo after"))
    }
  }

  test("out-of-band messages count against the skipped-line bound") {
    val bound = DenoPyodideProtocol.MaxSkippedLines
    withSandbox() { interpreter =>
      assertEquals(interpreter.execute(s"flood $bound").map(_.stdout.trim), Right("real result"))
    }
    withSandbox() { interpreter =>
      val message = interpreterError(interpreter.execute(s"flood ${bound + 1}"))
      assert(message.contains("Too many skipped lines"), message)
    }
  }

  test("a host tool that throws still emits the tool start and end events") {
    val events   = ArrayBuffer.empty[CallbackEvent]
    val recorder = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event
    withSandbox(Vector(throwingTool)) { interpreter =>
      val result = RuntimeEnvironment.withCallbacks(Vector(recorder))(interpreter.execute("call_tool"))
      assert(result.isRight, result.toString)
    }
    // The interpreter scopes (Execute, Startup, ToolCall) also emit events. This test looks at the tool scope only.
    val toolEvents = events.toVector.filter {
      case _: ToolStartEvent | _: ToolEndEvent => true
      case _                                   => false
    }
    toolEvents match
      case Vector(start: ToolStartEvent, end: ToolEndEvent) =>
        assertEquals(start.toolName, "explode")
        assertEquals(start.callId, end.callId)
        assert(end.output.isLeft, s"the end event must report the failure: ${end.output}")
      case other => fail(s"expected one tool start event and one tool end event, got $other")
  }

  test("a protocol error without an id fails a setup request") {
    withSandbox(Vector(throwingTool), scriptArgs = Vector("reject_setup")) { interpreter =>
      val message = interpreterError(interpreter.execute("hello"))
      assert(message.contains("protocol error") && message.contains("Invalid Request"), message)
    }
  }

  test("an error without an id does not replace the response to a setup request") {
    withSandbox(Vector(throwingTool), scriptArgs = Vector("noisy_setup")) { interpreter =>
      assertEquals(interpreter.execute("hello").map(_.stdout.trim), Right("echo hello"))
    }
  }

  private def classify(json: String): OutOfBand =
    DenoPyodideProtocol.decodeJson(json) match
      case Some(msg: DynamicValue.Record) => DenoPyodideProtocol.outOfBand(msg)
      case other                          => fail(s"expected a JSON object, got $other")

  test("outOfBand: a notification and an error without an id are out of band") {
    assertEquals(
      classify("""{"jsonrpc":"2.0","method":"unhandled_error","params":{"message":"boom"}}"""),
      OutOfBand.Notification
    )
    assertEquals(
      classify("""{"jsonrpc":"2.0","error":{"code":-32007,"message":"crash"},"id":null}"""),
      OutOfBand.UnsolicitedError("crash")
    )
    assertEquals(
      classify("""{"jsonrpc":"2.0","error":{"code":-32007,"message":"crash"}}"""),
      OutOfBand.UnsolicitedError("crash")
    )
  }

  test("outOfBand: a request and a response are not out of band") {
    assertEquals(classify("""{"jsonrpc":"2.0","result":{"output":"ok"},"id":1}"""), OutOfBand.No)
    assertEquals(classify("""{"jsonrpc":"2.0","result":{"output":"ok"},"id":null}"""), OutOfBand.No)
    assertEquals(classify("""{"jsonrpc":"2.0","error":{"code":-32001,"message":"crash"},"id":1}"""), OutOfBand.No)
    assertEquals(classify("""{"jsonrpc":"2.0","error":{"code":-32700,"message":"crash"},"id":1}"""), OutOfBand.No)
    assertEquals(classify("""{"jsonrpc":"2.0","method":"tool_call","params":{},"id":"tc_1"}"""), OutOfBand.No)
    assertEquals(classify("""{"jsonrpc":"2.0","method":"tool_call","params":{},"id":null}"""), OutOfBand.No)
  }

  test("outOfBand: each JSON-RPC protocol error code without an id is a protocol error") {
    Vector(-32700, -32600, -32601, -32602, -32603).foreach { code =>
      assertEquals(
        classify(s"""{"jsonrpc":"2.0","error":{"code":$code,"message":"bad"},"id":null}"""),
        OutOfBand.ProtocolError("bad")
      )
    }
  }
