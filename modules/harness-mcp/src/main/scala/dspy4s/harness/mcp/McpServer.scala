package dspy4s.harness.mcp

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import dspy4s.harness.repl.{Bindings, CellRun, ScalaRepl}
import dspy4s.harness.sandbox.Layout
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper, TypeRef}
import io.modelcontextprotocol.server.McpServer as SdkServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Path, Paths}

/** The early MCP server (6): the REPL and the guest API for another agent, for example Claude Code, before the harness
  * has its own loop. It has two tools: `run_cell(code)` gives the output of one cell, and `api_reference` gives the
  * source text of the guest API. One REPL serves all calls, so a later cell sees the definitions of an earlier one.
  *
  * A cell cannot read the secret paths of the home folder `home` or a file with a secret name (5.6). For each secret
  * path that is a symbolic link, the server closes the link and its target, as `Layout.forSession` does.
  */
final class McpServer(root: Path, home: Path = Paths.get(System.getProperty("user.home"))):
  private val files =
    val hidden = Layout.secretsUnder(Layout.real(home)).flatMap(p => Vector(p, Layout.real(p)).distinct)
    DirectoryFiles(root, hidden, Layout.secretNames)
  private val repl = ScalaRepl(McpServer.mode, Bindings(SystemTurn, files = Some(files), store = Some(MemoryStore())))

  /** Runs one cell, and gives its text and true when the cell did not complete. One cell runs at a time. */
  def runCell(code: String): (String, Boolean) = synchronized(McpServer.render(repl.run(code)))

  def close(): Unit = repl.close()

object McpServer:

  /** The read-only mode of 5.2, with no LM and no sessions, because the server has neither. `exec` takes `Files^`, and
    * the server has no handler that writes files, so it gives no `Shell^` and no `Network^`.
    */
  val mode: Mode = Mode(
    files = Access.Read,
    shell = Access.None,
    network = Access.None,
    llm = Access.None,
    sessions = Access.None,
    children = Access.None,
    store = Access.Full
  )

  /** Starts the server on stdin and stdout, for the workspace in the first argument or the working directory. */
  def main(args: Array[String]): Unit =
    // Only the transport writes to stdout, because stdout carries the protocol (6). This line runs before anything else
    // writes, so `Console` and a late print of a library go to stderr.
    val wire = System.out
    System.setOut(System.err)
    val root = Paths.get(args.headOption.getOrElse(".")).toAbsolutePath.normalize
    serve(McpServer(root), System.in, wire)

  /** Serves the two tools. The threads of the transport keep the JVM alive, and the end of the input stops them. */
  def serve(server: McpServer, in: InputStream, out: OutputStream): Unit =
    val mapper = McpJsonDefaults.getMapper()
    val _      = SdkServer
      .sync(StdioServerTransportProvider(mapper, in, out))
      .serverInfo("dspy4s-harness", "0.1.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(java.lang.Boolean.FALSE).build())
      .toolCall(
        tool(mapper, "run_cell", runCellText, cellSchema),
        (_, request) =>
          val code           = Option(request.arguments()).flatMap(a => Option(a.get("code"))).fold("")(String.valueOf)
          val (text, failed) = server.runCell(code)
          result(text, failed)
      )
      .toolCall(
        tool(mapper, "api_reference", "The guest API that a cell can call, as Scala source.", "{}"),
        (_, _) =>
          result(reference, failed = false)
      )
      .build()

  /** The text of a cell for the other agent: `CellRun.text`, which the harness also shows, or "(no output)" for a cell
    * that completed and printed nothing. A cell that did not complete is an error result.
    */
  def render(run: CellRun): (String, Boolean) =
    run.outcome match
      case CellOutcome.Ok if run.output.isEmpty      => ("(no output)", false)
      case CellOutcome.Ok | CellOutcome.Submitted(_) => (run.text, false)
      case _                                         => (run.text, true)

  /** The API reference (5.1): a header with the notation, and the source text of the guest API. */
  lazy val reference: String =
    val sources = Vector("Guest.scala", "SessionTypes.scala").map { name =>
      val stream = Option(getClass.getResourceAsStream(s"/dspy4s/harness/guest/$name.txt"))
      stream.fold(s"// $name is not in the resources of this build")(s =>
        try String(s.readAllBytes(), UTF_8)
        finally s.close()
      )
    }
    (header +: sources).mkString("\n\n")

  private val header = """// The guest API of the code harness. A cell is Scala 3 with capture checking and safe mode.
      |// The bare type of a capability reads, and `T^` also changes something. Each function of `api` takes its
      |// capability as a given, so a call that the mode does not give does not compile.
      |// This server gives `files: Files` and `state: Store^`: a cell can read the files of the workspace, and it can
      |// keep a value for a later cell with `store` and `load`. A value that leaves a cell needs a `Data`, and a case
      |// class or an enum has one with no other code.""".stripMargin

  private val runCellText =
    "Run one Scala cell in the REPL of this session, and give its output. A later cell sees the definitions of the " +
      "earlier cells that completed."

  private val cellSchema =
    """{"type": "object", "properties": {"code": {"type": "string", "description": "The Scala code of the cell"}},
      | "required": ["code"]}""".stripMargin

  private def tool(mapper: McpJsonMapper, name: String, description: String, schema: String): McpSchema.Tool =
    val json = mapper.readValue(schema, new TypeRef[java.util.LinkedHashMap[String, AnyRef]] {})
    McpSchema.Tool.builder(name, json).description(description).build()

  private def result(text: String, failed: Boolean): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder().addTextContent(text).isError(java.lang.Boolean.valueOf(failed)).build()
