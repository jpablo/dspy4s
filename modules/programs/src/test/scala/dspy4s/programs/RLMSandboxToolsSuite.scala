package dspy4s.programs

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{CodeInterpreter, CodeResult, DspyError, DynamicValues, RuntimeContext, TypeRef}
import dspy4s.core.runtime.{DenoPyodideInterpreter, LocalPythonInterpreter}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.RLMSandboxTools
import munit.FunSuite

import scala.util.control.NonFatal

/** A tool with a JSON schema in the two sandboxes of `RLM`. Each test runs real Python, and skips when the sandbox is
  * not installed. A tool gives back the JSON text of its arguments, so a test sees what the host gets.
  */
class RLMSandboxToolsSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(180, "s")

  private given RuntimeContext = RuntimeContext()

  private lazy val denoAvailable: Boolean = commandWorks("deno", "--version")

  private lazy val pythonAvailable: Boolean = commandWorks("python3", "--version")

  private def commandWorks(command: String*): Boolean =
    try new ProcessBuilder(command*).start().waitFor() == 0
    catch case NonFatal(_) => false

  /** A tool with an object schema, as `McpTools.convert` gives it. */
  private def schemaTool(properties: Vector[(String, TypeRef)], required: Vector[String]): ToolFunction =
    val schema = DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.recordFromEntries(properties.map { (name, typeRef) =>
        name -> DynamicValues.record("type" := typeRef.jsonType.getOrElse("string"))
      }),
      "required" -> DynamicValues.sequence(required.map(DynamicValues.string))
    )
    ToolFunction("search", "Search.", properties, Some(schema))(args =>
      Right(DynamicValues.string(DynamicValues.encodeJson(args)))
    )

  private def withDeno[A](tool: ToolFunction)(body: CodeInterpreter => A): A =
    assume(denoAvailable, "deno not installed")
    val interpreter = new DenoPyodideInterpreter(tools = RLMSandboxTools.fromToolFunctions(Vector(tool)))
    try body(interpreter)
    finally interpreter.close()

  private def withLocal[A](tool: ToolFunction)(body: CodeInterpreter => A): A =
    assume(pythonAvailable, "python3 not installed")
    val interpreter = new LocalPythonInterpreter(tools = RLMSandboxTools.fromToolFunctions(Vector(tool)))
    try body(interpreter)
    finally interpreter.close()

  private def ok(result: Either[DspyError, CodeResult]): CodeResult =
    result.fold(error => fail(error.message), identity)

  /** The printed text of `code`, which must end with no error. */
  private def printed(interpreter: CodeInterpreter, code: String): String =
    val result = ok(interpreter.execute(code))
    assertEquals(result.exitCode, 0, result.stderr)
    result.stdout.trim

  private def queryAndLimit = Vector("query" -> TypeRef.string, "limit" -> TypeRef.int)

  private def checkOptionalArguments(interpreter: CodeInterpreter): Unit =
    assertEquals(printed(interpreter, "print(search(query='cats'))"), """{"query":"cats"}""")
    assertEquals(printed(interpreter, "print(search('cats'))"), """{"query":"cats"}""")
    assertEquals(printed(interpreter, "print(search('cats', 3))"), """{"query":"cats","limit":3}""")
    assertEquals(printed(interpreter, "print(search('cats', limit=3))"), """{"query":"cats","limit":3}""")

  test("Deno: the code can leave out an optional argument, and the tool gets only the arguments of the call") {
    withDeno(schemaTool(queryAndLimit, Vector("query")))(checkOptionalArguments)
  }

  test("Local: the code can leave out an optional argument, and the tool gets only the arguments of the call") {
    withLocal(schemaTool(queryAndLimit, Vector("query")))(checkOptionalArguments)
  }

  test("Deno: an explicit None of an optional argument goes to the tool as a null") {
    withDeno(schemaTool(queryAndLimit, Vector("query"))) { interpreter =>
      assertEquals(printed(interpreter, "print(search('cats', limit=None))"), """{"query":"cats","limit":null}""")
    }
  }

  test("Deno: an optional argument can come before a required argument, and the positions stay") {
    withDeno(schemaTool(queryAndLimit.reverse, Vector("query"))) { interpreter =>
      assertEquals(printed(interpreter, "print(search(query='cats'))"), """{"query":"cats"}""")
      assertEquals(printed(interpreter, "print(search(3, 'cats'))"), """{"limit":3,"query":"cats"}""")
    }
  }

  test("Deno: a tool with only required arguments keeps the check of Python") {
    withDeno(schemaTool(queryAndLimit, Vector("query", "limit"))) { interpreter =>
      val result = ok(interpreter.execute("search('cats')"))
      assertNotEquals(result.exitCode, 0)
      assert(result.stderr.contains("TypeError"), result.stderr)
      assertEquals(printed(interpreter, "print(search('cats', 3))"), """{"query":"cats","limit":3}""")
    }
  }

  private def awkwardNames = Vector("from" -> TypeRef.string, "max-results" -> TypeRef.int)

  private def awkwardCall = "print(search(**{'from': 'a', 'max-results': 2}))"

  test("Deno: a tool with an argument name that is not a Python name takes keyword arguments") {
    withDeno(schemaTool(awkwardNames, Vector("from"))) { interpreter =>
      assertEquals(printed(interpreter, awkwardCall), """{"from":"a","max-results":2}""")
    }
  }

  test("Local: a tool with an argument name that is not a Python name takes keyword arguments") {
    withLocal(schemaTool(awkwardNames, Vector("from"))) { interpreter =>
      assertEquals(printed(interpreter, awkwardCall), """{"from":"a","max-results":2}""")
    }
  }

  test("Deno: a tool with an argument name that the sandbox keeps for itself takes keyword arguments") {
    withDeno(schemaTool(Vector("_dspy_tool_call" -> TypeRef.string), Vector("_dspy_tool_call"))) { interpreter =>
      assertEquals(printed(interpreter, "print(search(_dspy_tool_call='a'))"), """{"_dspy_tool_call":"a"}""")
    }
  }

  test("Deno: an argument can have the name of a helper of the wrapper") {
    val names = Vector("json", "run_sync", "JsProxy", "parsed", "result").map(_ -> TypeRef.string)
    withDeno(schemaTool(names, names.map(_._1))) { interpreter =>
      assertEquals(
        printed(interpreter, "print(search('a', 'b', 'c', 'd', 'e'))"),
        """{"json":"a","run_sync":"b","JsProxy":"c","parsed":"d","result":"e"}"""
      )
    }
  }
