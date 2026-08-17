/** Model Context Protocol tools at the dspy4s boundary.
  *
  * dspy4s does not include an MCP transport client. This example isolates that missing part behind `McpSession`. An
  * HTTP or stdio MCP client can implement the interface, and the rest of the program uses ordinary dspy4s `Tool`
  * values. This is the Scala equivalent of Python's `Tool.from_mcp_tool` conversion.
  */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, DynamicValues, TypeRef}
import dspy4s.examples.Demo
import dspy4s.programs.*
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.signatures.Signature
import zio.{IO, ZEnvironment, ZIO}
import zio.blocks.schema.{DynamicValue, Schema}

final case class RemoteToolDescriptor(
    name       : String,
    description: String,
    arguments  : Vector[(String, TypeRef)]
)

trait McpSession:
  def listTools: IO[DspyError, Vector[RemoteToolDescriptor]]
  def callTool(name: String, arguments: DynamicValue.Record): IO[DspyError, DynamicValue]

final case class McpTask(task: String) derives Schema
final case class McpResult(result: String) derives Schema
final case class McpDecisionPrompt(task: String, trajectory: String, tools: String) derives Schema
final case class McpDecision(thought: String, finish: Boolean, toolName: String, arguments: Map[String, String])
    derives Schema
final case class McpExtractPrompt(task: String, trajectory: String) derives Schema

object Mcp:

  /** Python: `dspy.Tool.from_mcp_tool(session, remoteTool)`. */
  def fromRemote(session: McpSession, descriptor: RemoteToolDescriptor): Tool =
    Tool(
      descriptor.name,
      descriptor.description,
      descriptor.arguments,
      arguments => session.callTool(descriptor.name, arguments)
    )

  /** Python: `response = await session.list_tools(); tools = [Tool.from_mcp_tool(...)]`. */
  def discover(session: McpSession): IO[DspyError, Vector[Tool]] =
    session.listTools.map(_.map(fromRemote(session, _)))

  def agent(descriptors: Vector[RemoteToolDescriptor]) =
    val toolsText = descriptors.map(tool =>
      s"${tool.name}(${tool.arguments.map(_._1).mkString(", ")}): ${tool.description}"
    ).mkString("\n")
    val generator = Program
      .predict(
        ParameterId("mcp/decision"),
        Signature.derived[McpDecisionPrompt, McpDecision](
          "McpToolDecision",
          "Choose one discovered tool call, or finish after the result is available."
        )
      )
      .contramap[ReAct.StepInput[McpTask]](input =>
        McpDecisionPrompt(input.input.task, input.trajectory.mkString("\n"), toolsText)
      )
      .map { decision =>
        if decision.finish then ReAct.Step(decision.thought, ReAct.Action.Finish())
        else
          val args = DynamicValues.recordFromEntries(
            decision.arguments.toSeq.map((name, value) => name -> DynamicValues.fromAny(value))
          )
          ReAct.Step(decision.thought, ReAct.Action.Invoke(ToolCallRequest(decision.toolName, args)))
      }
    val extractor = Program
      .predict(
        ParameterId("mcp/result"),
        Signature.derived[McpExtractPrompt, McpResult]("McpTaskResult", "Answer from the remote tool trajectory.")
      )
      .contramap[ReAct.ExtractInput[McpTask]](input =>
        McpExtractPrompt(input.input.task, input.trajectory.mkString("\n"))
      )
    ReAct(generator, Program.invokeTool, extractor, maxIterations = 5)

  def run(task: String, session: McpSession)(using backend: PredictionBackend): Either[DspyError, String] =
    val effect = for
      descriptors <- session.listTools
      tools        <- discover(session)
      prediction   <- ProgramRunner
                        .run(agent(descriptors), McpTask(task))
                        .provideSomeEnvironment[PredictionBackend](environment =>
                          environment ++ ZEnvironment[ToolBackend](new LiveToolBackend(tools))
                        )
    yield prediction.output.result
    Demo.runEffect(effect)

  /** A fixture session proves the conversion without an MCP client dependency. */
  val fixtureSession: McpSession = new McpSession:
    val weather = RemoteToolDescriptor("weather", "Get fixture weather for one city.", Vector("city" -> TypeRef.string))
    def listTools = ZIO.succeed(Vector(weather))
    def callTool(name: String, arguments: DynamicValue.Record) =
      ZIO.fromEither(DynamicValues.requireString(arguments, "city", name))
        .map(city => DynamicValues.fromAny(s"$city: sunny, 24 C"))

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...mcpProgrammingMain"
@main def mcpProgrammingMain(): Unit =
  Demo.withLm {
    println(Mcp.run("Check the weather in Tokyo", Mcp.fixtureSession))
  }
