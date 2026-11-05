package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{
  CodeInterpreter,
  CodeResult,
  DspyError,
  DynamicValues,
  ReplInterpreterFactory,
  RuntimeContext,
  TypeRef
}
import dspy4s.core.runtime.{DenoPyodideInterpreter, LocalPythonInterpreter, RuntimeEnvironment}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.{RLM, RLMSandboxTools}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

import java.time.Instant
import scala.collection.mutable.ArrayBuffer

import scala.util.control.NonFatal

enum RstKind:
  case alpha, beta
object RstKind:
  given Schema[RstKind] = Schema.derived

final case class RstInput(
    pair  : (Int, String),
    kind  : RstKind,
    some  : Option[String],
    none  : Option[String],
    at    : Instant,
    counts: Map[RstKind, Int]
)
object RstInput:
  given Schema[RstInput] = Schema.derived

final case class RstOutput(answer: String) derives Schema

/** A tool with a JSON schema in the two sandboxes of `RLM`. Each test runs real Python, and skips when the sandbox is
  * not installed. A tool gives back the JSON text of its arguments, so a test sees what the host gets.
  */
class RLMSandboxToolsSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(180, "s")

  private given RuntimeContext = RuntimeContext()

  /** The Python code that gives the Python type and the value of each input of [[RstInput]]. */
  private val typesCode: String = """import json
      |SUBMIT(answer=json.dumps([type(pair).__name__, pair, type(kind).__name__, kind, type(some).__name__, some,
      |  type(none).__name__, none, type(at).__name__, at, type(counts).__name__, counts]))""".stripMargin

  private final class ScriptedCode extends LanguageModel:
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      val reply = s"[[ ## reasoning ## ]]\nr\n\n[[ ## code ## ]]\n$typesCode\n\n[[ ## completed ## ]]"
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  /** The real sandbox of `factory` gets a typed input in the wire form, and the preview says the same type. */
  private def assertTypedInput(factory: ReplInterpreterFactory): Unit =
    val signature = Signature.derived[RstInput, RstOutput](name = "Typed", instructions = "Give the types.")
    val rlm       = RLM(baseSignature = signature, interpreterFactory = Some(factory))
    val model     = new ScriptedCode
    val input     =
      RstInput((1, "a"), RstKind.beta, Some("x"), None, Instant.parse("2024-01-02T03:04:05Z"), Map(RstKind.beta -> 1))
    val result = RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
      rlm(input)(using RuntimeEnvironment.current)
    }
    assertEquals(
      result.map(_.output.answer),
      Right(
        """["list", [1, "a"], "str", "beta", "str", "x", "NoneType", null, "str", "2024-01-02T03:04:05Z", """ +
          """"dict", {"beta": 1}]"""
      )
    )
    val prompt = model.requests.head.messages.flatMap(_.text).mkString("\n")
    // The prompt shows each variable as a blob of a numbered list, so each line after the first has an indent.
    assert(prompt.contains("Variable: `pair` (access it in your code)\n    Type: list"), prompt)
    assert(prompt.contains("Variable: `kind` (access it in your code)\n    Type: str"), prompt)
    assert(prompt.contains("Variable: `some` (access it in your code)\n    Type: str"), prompt)
    assert(prompt.contains("Variable: `none` (access it in your code)\n    Type: NoneType"), prompt)
    assert(prompt.contains("Variable: `at` (access it in your code)\n    Type: str"), prompt)
    assert(prompt.contains("Variable: `counts` (access it in your code)\n    Type: dict"), prompt)

  test("Local: a typed input reaches the sandbox in the form that the preview shows") {
    assume(pythonAvailable, "python3 not installed")
    assertTypedInput((tools, outputs) => new LocalPythonInterpreter(tools = tools, outputFields = Some(outputs)))
  }

  test("Deno: a typed input reaches the sandbox in the form that the preview shows") {
    assume(denoAvailable, "deno not installed")
    assertTypedInput((tools, outputs) => new DenoPyodideInterpreter(tools = tools, outputFields = outputs))
  }

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
