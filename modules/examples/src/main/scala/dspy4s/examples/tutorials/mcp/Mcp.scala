/** Tutorial: Use MCP tools in DSPy
  *
  * Source: docs/docs/tutorials/mcp/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/mcp/index.md Status: translated (10/10 snippets,
  * upgrade item I10, PORT_GAPS G-47). The airline server (snippets 1 to 6) uses the server API of the MCP Java SDK in
  * place of FastMCP. The agent (snippets 7 to 10) starts the server in a child JVM, converts its tools with
  * `McpTools.fromClient` (the analogue of `dspy.Tool.from_mcp_tool`), and gives them to a `ReAct`. The snippets are the
  * snippets of upstream f1260ff14.
  *
  * The differences: FastMCP derives the input schema of a tool from the type hints. Here the input schema is the JSON
  * schema of the `Schema` of a case class of the arguments. A tool call blocks until the server answers, so there is no
  * `acall` and no `asyncio`. The `Spec` of a signature has no description for each field, so the `desc` of
  * `process_result` (snippet 8) is not in the signature.
  */
package dspy4s.examples.tutorials.mcp

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, RuntimeError}
import dspy4s.examples.Demo
import dspy4s.mcp.{McpToolClient, McpTools}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.ReAct
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.{ServerParameters, StdioClientTransport}
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.server.{McpServer, McpServerFeatures, McpSyncServer}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import zio.blocks.schema.Schema

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.VectorMap
import scala.util.{Random, Using}

// Snippet 8 signature (top-level for Mirror derivation).
// | class DSPyAirlineCustomerService(dspy.Signature):
// |     """You are an airline customer service agent. You are given a list of tools to handle user requests. You should decide the right tool to use in order to fulfill users' requests."""
// |
// |     user_request: str = dspy.InputField()
// |     process_result: str = dspy.OutputField(
// |         desc=(
// |             "Message that summarizes the process result, and the information users need, "
// |             "e.g., the confirmation_number if it's a flight booking request."
// |         )
// |     )
trait DSPyAirlineCustomerService extends Spec:
  def user_request: InputField[String]
  def process_result: OutputField[String]

/** The airline MCP server of snippets 1 to 6. */
object AirlineServer:

  // ── Snippet 1 (lines 45–223): the airline MCP server ──
  // | import random
  // | import string
  // |
  // | from pydantic import BaseModel
  // |
  // | try:
  // |     from mcp.server.fastmcp import FastMCP as MCPServer  # SDK v1
  // | except ImportError:
  // |     from mcp.server import MCPServer  # SDK v2
  // |
  // | # Create an MCP server
  // | mcp = MCPServer("Airline Agent")
  // |
  // |
  // | class Date(BaseModel):
  // |     # Somehow LLM is bad at specifying `datetime.datetime`
  // |     year: int
  // |     month: int
  // |     day: int
  // |     hour: int
  // |
  // |
  // | class UserProfile(BaseModel):
  // |     user_id: str
  // |     name: str
  // |     email: str
  // |
  // |
  // | class Flight(BaseModel):
  // |     flight_id: str
  // |     date_time: Date
  // |     origin: str
  // |     destination: str
  // |     duration: float
  // |     price: float
  // |
  // |
  // | class Itinerary(BaseModel):
  // |     confirmation_number: str
  // |     user_profile: UserProfile
  // |     flight: Flight
  // |
  // |
  // | class Ticket(BaseModel):
  // |     user_request: str
  // |     user_profile: UserProfile
  // |
  // |
  // | user_database = {
  // |     "Adam": UserProfile(user_id="1", name="Adam", email="adam@gmail.com"),
  // |     "Bob": UserProfile(user_id="2", name="Bob", email="bob@gmail.com"),
  // |     "Chelsie": UserProfile(user_id="3", name="Chelsie", email="chelsie@gmail.com"),
  // |     "David": UserProfile(user_id="4", name="David", email="david@gmail.com"),
  // | }
  // |
  // | flight_database = {
  // |     "DA123": Flight(
  // |         flight_id="DA123",
  // |         origin="SFO",
  // |         destination="JFK",
  // |         date_time=Date(year=2025, month=9, day=1, hour=1),
  // |         duration=3,
  // |         price=200,
  // |     ),
  // |     "DA125": Flight(
  // |         flight_id="DA125",
  // |         origin="SFO",
  // |         destination="JFK",
  // |         date_time=Date(year=2025, month=9, day=1, hour=7),
  // |         duration=9,
  // |         price=500,
  // |     ),
  // |     "DA456": Flight(
  // |         flight_id="DA456",
  // |         origin="SFO",
  // |         destination="SNA",
  // |         date_time=Date(year=2025, month=10, day=1, hour=1),
  // |         duration=2,
  // |         price=100,
  // |     ),
  // |     "DA460": Flight(
  // |         flight_id="DA460",
  // |         origin="SFO",
  // |         destination="SNA",
  // |         date_time=Date(year=2025, month=10, day=1, hour=9),
  // |         duration=2,
  // |         price=120,
  // |     ),
  // | }
  // |
  // | itinery_database = {}
  // | ticket_database = {}
  // |
  // |
  // | @mcp.tool()
  // | def fetch_flight_info(date: Date, origin: str, destination: str):
  // |     """Fetch flight information from origin to destination on the given date"""
  // |     flights = []
  // |
  // |     for flight_id, flight in flight_database.items():
  // |         if (
  // |             flight.date_time.year == date.year
  // |             and flight.date_time.month == date.month
  // |             and flight.date_time.day == date.day
  // |             and flight.origin == origin
  // |             and flight.destination == destination
  // |         ):
  // |             flights.append(flight)
  // |     return flights
  // |
  // |
  // | @mcp.tool()
  // | def fetch_itinerary(confirmation_number: str):
  // |     """Fetch a booked itinerary information from database"""
  // |     return itinery_database.get(confirmation_number)
  // |
  // |
  // | @mcp.tool()
  // | def pick_flight(flights: list[Flight]):
  // |     """Pick up the best flight that matches users' request."""
  // |     sorted_flights = sorted(
  // |         flights,
  // |         key=lambda x: (
  // |             x.get("duration") if isinstance(x, dict) else x.duration,
  // |             x.get("price") if isinstance(x, dict) else x.price,
  // |         ),
  // |     )
  // |     return sorted_flights[0]
  // |
  // |
  // | def generate_id(length=8):
  // |     chars = string.ascii_lowercase + string.digits
  // |     return "".join(random.choices(chars, k=length))
  // |
  // |
  // | @mcp.tool()
  // | def book_itinerary(flight: Flight, user_profile: UserProfile):
  // |     """Book a flight on behalf of the user."""
  // |     confirmation_number = generate_id()
  // |     while confirmation_number in itinery_database:
  // |         confirmation_number = generate_id()
  // |     itinery_database[confirmation_number] = Itinerary(
  // |         confirmation_number=confirmation_number,
  // |         user_profile=user_profile,
  // |         flight=flight,
  // |     )
  // |     return confirmation_number, itinery_database[confirmation_number]
  // |
  // |
  // | @mcp.tool()
  // | def cancel_itinerary(confirmation_number: str, user_profile: UserProfile):
  // |     """Cancel an itinerary on behalf of the user."""
  // |     if confirmation_number in itinery_database:
  // |         del itinery_database[confirmation_number]
  // |         return
  // |     raise ValueError("Cannot find the itinerary, please check your confirmation number.")
  // |
  // |
  // | @mcp.tool()
  // | def get_user_info(name: str):
  // |     """Fetch the user profile from database with given name."""
  // |     return user_database.get(name)
  // |
  // |
  // | @mcp.tool()
  // | def file_ticket(user_request: str, user_profile: UserProfile):
  // |     """File a customer support ticket if this is something the agent cannot handle."""
  // |     ticket_id = generate_id(length=6)
  // |     ticket_database[ticket_id] = Ticket(
  // |         user_request=user_request,
  // |         user_profile=user_profile,
  // |     )
  // |     return ticket_id
  // |
  // |
  // | if __name__ == "__main__":
  // |     mcp.run()
  //
  // A tool function takes a case class of its arguments, so the input schema of the tool is the JSON schema of that
  // case class. A `Left` is the analogue of the `ValueError` of `cancel_itinerary`.

  // ── Snippet 3 (lines 235–243): a data model ──
  // | class Flight(BaseModel):
  // |     flight_id: str
  // |     date_time: Date
  // |     origin: str
  // |     destination: str
  // |     duration: float
  // |     price: float
  // --8<-- [start:models]
  /** The LM does not give a `datetime.datetime` well, so the date has one field for each part (upstream comment). */
  final case class Date(year: Int, month: Int, day: Int, hour: Int) derives Schema

  final case class UserProfile(user_id: String, name: String, email: String) derives Schema

  final case class Flight(
      flight_id  : String,
      date_time  : Date,
      origin     : String,
      destination: String,
      duration   : Double,
      price      : Double
  ) derives Schema

  final case class Itinerary(confirmation_number: String, user_profile: UserProfile, flight: Flight) derives Schema

  final case class Ticket(user_request: String, user_profile: UserProfile) derives Schema
  // --8<-- [end:models]

  // The arguments of each tool.
  final case class FetchFlightInfoArgs(date: Date, origin: String, destination: String) derives Schema
  final case class FetchItineraryArgs(confirmation_number: String) derives Schema
  final case class PickFlightArgs(flights: List[Flight]) derives Schema
  final case class BookItineraryArgs(flight: Flight, user_profile: UserProfile) derives Schema
  final case class CancelItineraryArgs(confirmation_number: String, user_profile: UserProfile) derives Schema
  final case class GetUserInfoArgs(name: String) derives Schema
  final case class FileTicketArgs(user_request: String, user_profile: UserProfile) derives Schema

  /** The result of `book_itinerary`: upstream gives the pair `(confirmation_number, itinerary)`. */
  final case class Booking(confirmation_number: String, itinerary: Itinerary) derives Schema

  // ── Snippet 4 (lines 248–255): a database ──
  // | user_database = {
  // |     "Adam": UserProfile(user_id="1", name="Adam", email="adam@gmail.com"),
  // |     "Bob": UserProfile(user_id="2", name="Bob", email="bob@gmail.com"),
  // |     "Chelsie": UserProfile(user_id="3", name="Chelsie", email="chelsie@gmail.com"),
  // |     "David": UserProfile(user_id="4", name="David", email="david@gmail.com"),
  // | }
  // --8<-- [start:databases]
  val userDatabase: Map[String, UserProfile] = VectorMap(
    "Adam"    -> UserProfile(user_id = "1", name = "Adam", email = "adam@gmail.com"),
    "Bob"     -> UserProfile(user_id = "2", name = "Bob", email = "bob@gmail.com"),
    "Chelsie" -> UserProfile(user_id = "3", name = "Chelsie", email = "chelsie@gmail.com"),
    "David"   -> UserProfile(user_id = "4", name = "David", email = "david@gmail.com")
  )

  val flightDatabase: Map[String, Flight] = VectorMap(
    "DA123" ->
      Flight("DA123", Date(year = 2025, month = 9, day = 1, hour = 1), "SFO", "JFK", duration = 3, price = 200),
    "DA125" ->
      Flight("DA125", Date(year = 2025, month = 9, day = 1, hour = 7), "SFO", "JFK", duration = 9, price = 500),
    "DA456" ->
      Flight("DA456", Date(year = 2025, month = 10, day = 1, hour = 1), "SFO", "SNA", duration = 2, price = 100),
    "DA460" ->
      Flight("DA460", Date(year = 2025, month = 10, day = 1, hour = 9), "SFO", "SNA", duration = 2, price = 120)
  )

  private val itineraryDatabase = TrieMap.empty[String, Itinerary]
  private val ticketDatabase    = TrieMap.empty[String, Ticket]
  // --8<-- [end:databases]

  // ── Snippet 5 (lines 260–276): a tool ──
  // | @mcp.tool()
  // | def fetch_flight_info(date: Date, origin: str, destination: str):
  // |     """Fetch flight information from origin to destination on the given date"""
  // |     flights = []
  // |
  // |     for flight_id, flight in flight_database.items():
  // |         if (
  // |             flight.date_time.year == date.year
  // |             and flight.date_time.month == date.month
  // |             and flight.date_time.day == date.day
  // |             and flight.origin == origin
  // |             and flight.destination == destination
  // |         ):
  // |             flights.append(flight)
  // |     return flights
  // --8<-- [start:tools]
  def fetchFlightInfo(args: FetchFlightInfoArgs): List[Flight] =
    flightDatabase.values.filter { flight =>
      flight.date_time.year == args.date.year &&
      flight.date_time.month == args.date.month &&
      flight.date_time.day == args.date.day &&
      flight.origin == args.origin &&
      flight.destination == args.destination
    }.toList
  // --8<-- [end:tools]

  def fetchItinerary(args: FetchItineraryArgs): Option[Itinerary] = itineraryDatabase.get(args.confirmation_number)

  /** The shortest flight, then the cheapest. Upstream fails on an empty list. */
  def pickFlight(args: PickFlightArgs): Either[String, Flight] =
    args.flights.sortBy(flight => (flight.duration, flight.price)).headOption.toRight("There is no flight to pick.")

  def generateId(length: Int = 8): String =
    val chars = ('a' to 'z') ++ ('0' to '9')
    Vector.fill(length)(chars(Random.nextInt(chars.size))).mkString

  def bookItinerary(args: BookItineraryArgs): Booking =
    val confirmationNumber = Iterator.continually(generateId()).dropWhile(itineraryDatabase.contains).next()
    val itinerary          = Itinerary(confirmationNumber, args.user_profile, args.flight)
    itineraryDatabase.update(confirmationNumber, itinerary)
    Booking(confirmationNumber, itinerary)

  /** As upstream, the itinerary is the one of the confirmation number. The user profile is not checked. */
  def cancelItinerary(args: CancelItineraryArgs): Either[String, Unit] =
    itineraryDatabase
      .remove(args.confirmation_number)
      .map(_ => ())
      .toRight("Cannot find the itinerary, please check your confirmation number.")

  def getUserInfo(args: GetUserInfoArgs): Option[UserProfile] = userDatabase.get(args.name)

  def fileTicket(args: FileTicketArgs): String =
    val ticketId = generateId(length = 6)
    ticketDatabase.update(ticketId, Ticket(args.user_request, args.user_profile))
    ticketId

  private lazy val mapper: McpJsonMapper = McpJsonDefaults.getMapper()

  /** An MCP tool that runs `run` (FastMCP `@mcp.tool()`). The input schema is the JSON schema of `A`, and the result is
    * the JSON text of the value. A `Left` gives a result that the server marks as an error, as FastMCP does for an
    * exception.
    */
  private def tool[A, B](name: String, description: String)(run: A => Either[String, B])(using
      input : Schema[A],
      output: Schema[B]
  ): McpServerFeatures.SyncToolSpecification =
    McpServerFeatures.SyncToolSpecification
      .builder()
      .tool(McpSchema.Tool.builder(name, mapper, input.toJsonSchema.toJson.print).description(description).build())
      .callHandler { (_, request) =>
        val arguments =
          mapper.writeValueAsString(Option(request.arguments()).getOrElse(java.util.Map.of[String, AnyRef]()))
        input.jsonCodec.decode(arguments.getBytes(StandardCharsets.UTF_8)).left.map(_.message).flatMap(run) match
          case Right(value) =>
            McpSchema.CallToolResult.builder().addTextContent(output.jsonCodec.encodeToString(value)).build()
          case Left(message) =>
            McpSchema.CallToolResult.builder().addTextContent(message).isError(java.lang.Boolean.TRUE).build()
      }
      .build()

  val tools: Vector[McpServerFeatures.SyncToolSpecification] = Vector(
    tool[FetchFlightInfoArgs, List[Flight]](
      "fetch_flight_info",
      "Fetch flight information from origin to destination on the given date"
    )(args => Right(fetchFlightInfo(args))),
    tool[FetchItineraryArgs, Option[Itinerary]](
      "fetch_itinerary",
      "Fetch a booked itinerary information from database"
    )(args => Right(fetchItinerary(args))),
    tool[PickFlightArgs, Flight]("pick_flight", "Pick up the best flight that matches users' request.")(pickFlight),
    tool[BookItineraryArgs, Booking]("book_itinerary", "Book a flight on behalf of the user.")(args =>
      Right(bookItinerary(args))
    ),
    tool[CancelItineraryArgs, Unit]("cancel_itinerary", "Cancel an itinerary on behalf of the user.")(cancelItinerary),
    tool[GetUserInfoArgs, Option[UserProfile]](
      "get_user_info",
      "Fetch the user profile from database with given name."
    )(args => Right(getUserInfo(args))),
    tool[FileTicketArgs, String](
      "file_ticket",
      "File a customer support ticket if this is something the agent cannot handle."
    )(args => Right(fileTicket(args)))
  )

  // ── Snippet 2 (lines 229–231): the server ──
  // | mcp = MCPServer("Airline Agent")
  // --8<-- [start:server]
  def server(): McpSyncServer =
    McpServer
      .sync(new StdioServerTransportProvider(mapper))
      .serverInfo("Airline Agent", "1.0.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(java.lang.Boolean.FALSE).build())
      .tools(tools*)
      .build()
  // --8<-- [end:server]

// ── Snippet 6 (lines 280–283): start the server ──
// | if __name__ == "__main__":
// |     mcp.run()
// The threads of the stdio transport keep the JVM alive. The agent (snippet 7) starts this main and stops it.
@main def airlineMcpServer(): Unit =
  val _ = AirlineServer.server()

object Mcp:

  // ── Snippet 7 (lines 304–335): gather the tools of the server ──
  // | from mcp import Client, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | import dspy
  // |
  // | # Create server parameters for stdio connection
  // | server_params = StdioServerParameters(
  // |     command="python",  # Executable
  // |     args=["path_to_your_working_directory/mcp_server.py"],
  // |     env=None,
  // | )
  // |
  // |
  // | async def run():
  // |     async with Client(stdio_client(server_params)) as client:
  // |         # List available tools
  // |         tools = await client.list_tools()
  // |
  // |         # Convert MCP tools to DSPy tools
  // |         dspy_tools = []
  // |         for tool in tools.tools:
  // |             dspy_tools.append(dspy.Tool.from_mcp_tool(client, tool))
  // |
  // |         print(len(dspy_tools))
  // |         print(dspy_tools[0].args)
  // |
  // | if __name__ == "__main__":
  // |     import asyncio
  // |
  // |     asyncio.run(run())
  //
  // The server is `airlineMcpServer` in a child JVM: the command is the `java` of this JVM, with the class path of the
  // examples. `dspy_tools[0].args` is the input schema of the tool (`inputSchema`); `argSchema` is the flat view.
  // --8<-- [start:gather-tools]
  def serverParameters(classPath: String): ServerParameters =
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    ServerParameters.builder(javaBin).args("-cp", classPath, "dspy4s.examples.tutorials.mcp.airlineMcpServer").build()

  /** Start the server, run `body` with the converted tools, and close the client, which stops the server. The tools use
    * the open client, so the agent must run in `body`. A failure to connect is a `Left`.
    */
  def withAirlineTools[A](classPath: String)(body: Vector[ToolFunction] => Either[DspyError, A]): Either[DspyError, A] =
    val transport = new StdioClientTransport(serverParameters(classPath), McpJsonDefaults.getMapper())
    Using(McpClient.sync(transport).build()) { client =>
      val _ = client.initialize()
      McpTools.fromClient(McpToolClient.fromSync(client)).flatMap(body)
    }.toEither.left.map[DspyError](error => RuntimeError("mcp", s"MCP client failed: ${error.getMessage}")).flatten

  def describeTools(classPath: String): Either[DspyError, String] =
    withAirlineTools(classPath) { tools =>
      val firstArgs = tools.headOption.flatMap(_.inputSchema).fold("")(DynamicValues.renderText)
      Right(s"${tools.size}\n$firstArgs")
    }
  // --8<-- [end:gather-tools]

  // ── Snippet 8 (lines 350–363): the signature ──
  // The `DSPyAirlineCustomerService` trait (top of the file). The docstring is the instructions.
  val instructions: String =
    "You are an airline customer service agent. You are given a list of tools to handle user requests. You should " +
      "decide the right tool to use in order to fulfill users' requests."

  // ── Snippet 9 (lines 367–369): the LM ──
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // `Demo.withLm` (in `mcpTutorialMain`) puts the LM in the `RuntimeContext`, as `dspy.configure(lm=...)` does.

  // ── Snippet 10 (lines 374–425): the agent ──
  // | from mcp import Client, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | import dspy
  // |
  // | # Create server parameters for stdio connection
  // | server_params = StdioServerParameters(
  // |     command="python",  # Executable
  // |     args=["path_to_your_working_directory/mcp_server.py"],  # Server script
  // |     env=None,  # Optional environment variables
  // | )
  // |
  // |
  // | class DSPyAirlineCustomerService(dspy.Signature):
  // |     """You are an airline customer service agent. You are given a list of tools to handle user requests.
  // |     You should decide the right tool to use in order to fulfill users' requests."""
  // |
  // |     user_request: str = dspy.InputField()
  // |     process_result: str = dspy.OutputField(
  // |         desc=(
  // |             "Message that summarizes the process result, and the information users need, "
  // |             "e.g., the confirmation_number if it's a flight booking request."
  // |         )
  // |     )
  // |
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // |
  // | async def run(user_request):
  // |     async with Client(stdio_client(server_params)) as client:
  // |         # List available tools
  // |         tools = await client.list_tools()
  // |
  // |         # Convert MCP tools to DSPy tools
  // |         dspy_tools = []
  // |         for tool in tools.tools:
  // |             dspy_tools.append(dspy.Tool.from_mcp_tool(client, tool))
  // |
  // |         # Create the agent
  // |         react = dspy.ReAct(DSPyAirlineCustomerService, tools=dspy_tools)
  // |
  // |         result = await react.acall(user_request=user_request)
  // |         print(result)
  // |
  // |
  // | if __name__ == "__main__":
  // |     import asyncio
  // |
  // |     asyncio.run(run("please help me book a flight from SFO to JFK on 09/01/2025, my name is Adam"))
  // --8<-- [start:agent]
  def run(classPath: String, userRequest: String)(using RuntimeContext): Either[DspyError, String] =
    withAirlineTools(classPath) { tools =>
      val react = ReAct(Signature.of[DSPyAirlineCustomerService](instructions = instructions), tools = tools)
      react((user_request = userRequest)).map(_.output.process_result)
    }
  // --8<-- [end:agent]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.mcp.mcpTutorialMain <class path>"
// The agent starts `airlineMcpServer` in a child JVM with <class path>, the output of
// `sbt -error "export examples/Runtime/fullClasspath"`. (Under `sbt run`, the class path of the JVM is the class path
// of sbt, so the agent cannot use it.)
@main def mcpTutorialMain(classPath: String): Unit =
  Demo.withLm {
    println(Mcp.describeTools(classPath))
    println(Mcp.run(classPath, "please help me book a flight from SFO to JFK on 09/01/2025, my name is Adam"))
  }
