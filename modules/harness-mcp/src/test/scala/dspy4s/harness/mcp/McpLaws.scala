package dspy4s.harness.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.{ServerParameters, StdioClientTransport}
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path, Paths}
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The early MCP server of section 6: in this JVM, and in a child JVM over stdio, as another agent uses it. */
class McpLaws extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(5, "min")

  /** A workspace with one file, and a file outside it. */
  private def workspace(): Path =
    val outer = JFiles.createTempDirectory("harness-mcp")
    val root  = JFiles.createDirectory(outer.resolve("workspace"))
    JFiles.writeString(root.resolve("notes.txt"), "one\ntwo\nthree", UTF_8)
    JFiles.writeString(outer.resolve("secret.txt"), "not for the cell", UTF_8)
    root

  private def withServer(body: McpServer => Unit): Unit =
    val server = McpServer(workspace())
    try body(server)
    finally server.close()

  test("MCP: a cell reads a file of the workspace, and a later cell uses its value") {
    withServer { server =>
      assertEquals(server.runCell("""val lines = read("notes.txt").linesIterator.toVector""")._2, false)
      assertEquals(server.runCell("println(lines.size)"), ("3", false))
    }
  }

  test("MCP: a cell cannot read outside the workspace") {
    withServer { server =>
      val (text, failed) = server.runCell("""println(read("../secret.txt"))""")
      assert(failed, text)
      assert(!text.contains("not for the cell"), text)
    }
  }

  test("MCP: a cell cannot read a file with a secret name, or a secret path of the home folder") {
    // Sections 5.4 and 5.6: each mode keeps .env and the secret paths unreadable.
    val root = workspace()
    JFiles.writeString(root.resolve(".env"), "FAKE-ENV-VALUE", UTF_8): Unit
    JFiles.createDirectories(root.resolve("app/.ssh")): Unit
    JFiles.writeString(root.resolve("app/.env.local"), "FAKE-LOCAL-VALUE", UTF_8): Unit
    JFiles.createDirectories(root.resolve(".ssh")): Unit
    JFiles.writeString(root.resolve(".ssh/id_fake"), "FAKE-KEY-VALUE", UTF_8): Unit
    // The workspace is the home folder here, so its .ssh is a secret path.
    val server = McpServer(root, home = root)
    try
      for (path, value) <- Vector(
                             ".env"           -> "FAKE-ENV-VALUE",
                             "app/.env.local" -> "FAKE-LOCAL-VALUE",
                             ".ssh/id_fake"   -> "FAKE-KEY-VALUE"
                           )
      do
        val (text, failed) = server.runCell(s"""println(read("$path"))""")
        assert(failed, text)
        assert(!text.contains(value), text)
      assertEquals(server.runCell("""println(read("notes.txt").length)""")._2, false)
    finally server.close()
  }

  test("MCP: the workspace is read-only for a cell, and the server gives no shell") {
    withServer { server =>
      for cell <- Vector("""write("notes.txt", "x")""", """edit("notes.txt", "one", "1")""", """println(exec("ls"))""")
      do
        val (text, failed) = server.runCell(cell)
        assert(failed && text.contains("Error"), s"$cell: $text")
    }
  }

  test("MCP: the store keeps a value for a later cell") {
    withServer { server =>
      assertEquals(server.runCell("""case class Note(text: String, n: Int); store("note", Note("a", 2))""")._2, false)
      assertEquals(server.runCell("""println(load[Note]("note").map(_.n))"""), ("Some(2)", false))
    }
  }

  test("MCP: the API reference holds the source text of the guest API that compiles in the mode of the server") {
    assert(McpServer.reference.contains("trait Files"), McpServer.reference.take(300))
    assert(McpServer.reference.contains("trait Store"), McpServer.reference.take(300))
    // The mode of the server has no Sessions, so the session types are not in the reference (5.1).
    assert(!McpServer.reference.contains("enum Scope"), "the reference has the session types")
  }

  test("MCP: another agent runs cells over stdio, and only the protocol goes to stdout") {
    val errors    = ConcurrentLinkedQueue[String]()
    val javaBin   = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val transport = StdioClientTransport(
      ServerParameters
        .builder(javaBin)
        .args("-cp", System.getProperty("java.class.path"), "dspy4s.harness.mcp.McpServer", workspace().toString)
        .build(),
      McpJsonDefaults.getMapper()
    )
    transport.setStdErrorHandler(line => errors.add(line): Unit)
    val client = McpClient
      .sync(transport)
      .requestTimeout(Duration.ofSeconds(60))
      .initializationTimeout(Duration.ofSeconds(60))
      .build()
    try
      val _ = client.initialize()
      assertEquals(client.listTools().tools().asScala.map(_.name()).toSet, Set("run_cell", "api_reference"))
      def call(code: String): (String, Boolean) =
        val request = McpSchema.CallToolRequest.builder("run_cell").arguments(java.util.Map.of("code", code)).build()
        val answer  = client.callTool(request)
        val text    = answer.content().asScala.collect { case t: McpSchema.TextContent => t.text() }.mkString
        (text, Boolean.unbox(answer.isError()))
      assertEquals(call("val x = 21 * 2")._2, false)
      assertEquals(call("println(x + 1)"), ("43", false))
      val (error, failed) = call("""val bad: Int = "x"""")
      assert(failed && error.contains("Type Mismatch"), error)
      assertEquals(call("""println(read("notes.txt").length)"""), ("13", false))
    finally client.closeGracefully(): Unit
  }
