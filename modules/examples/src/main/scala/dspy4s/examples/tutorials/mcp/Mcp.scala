/**
 * Tutorial: Use MCP tools in DSPy
 *
 * Source:   docs/docs/tutorials/mcp/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/mcp/index.md
 * Status:   scaffold (10 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.mcp

object Mcp {

  // ── Snippet 1 (lines 45–219) ────────────────────
  // | import random
  // | import string
  // |
  // | from mcp.server.fastmcp import FastMCP
  // | from pydantic import BaseModel
  // |
  // | # Create an MCP server
  // | mcp = FastMCP("Airline Agent")
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
  // TODO translate snippet 1

  // ── Snippet 2 (lines 225–227) ────────────────────
  // | mcp = FastMCP("Airline Agent")
  // TODO translate snippet 2

  // ── Snippet 3 (lines 231–239) ────────────────────
  // | class Flight(BaseModel):
  // |     flight_id: str
  // |     date_time: Date
  // |     origin: str
  // |     destination: str
  // |     duration: float
  // |     price: float
  // TODO translate snippet 3

  // ── Snippet 4 (lines 244–251) ────────────────────
  // | user_database = {
  // |     "Adam": UserProfile(user_id="1", name="Adam", email="adam@gmail.com"),
  // |     "Bob": UserProfile(user_id="2", name="Bob", email="bob@gmail.com"),
  // |     "Chelsie": UserProfile(user_id="3", name="Chelsie", email="chelsie@gmail.com"),
  // |     "David": UserProfile(user_id="4", name="David", email="david@gmail.com"),
  // | }
  // TODO translate snippet 4

  // ── Snippet 5 (lines 256–272) ────────────────────
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
  // TODO translate snippet 5

  // ── Snippet 6 (lines 276–279) ────────────────────
  // | if __name__ == "__main__":
  // |     mcp.run()
  // TODO translate snippet 6

  // ── Snippet 7 (lines 302–333) ────────────────────
  // | from mcp import ClientSession, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | # Create server parameters for stdio connection
  // | server_params = StdioServerParameters(
  // |     command="python",  # Executable
  // |     args=["path_to_your_working_directory/mcp_server.py"],
  // |     env=None,
  // | )
  // |
  // | async def run():
  // |     async with stdio_client(server_params) as (read, write):
  // |         async with ClientSession(read, write) as session:
  // |             # Initialize the connection
  // |             await session.initialize()
  // |             # List available tools
  // |             tools = await session.list_tools()
  // |
  // |             # Convert MCP tools to DSPy tools
  // |             dspy_tools = []
  // |             for tool in tools.tools:
  // |                 dspy_tools.append(dspy.Tool.from_mcp_tool(session, tool))
  // |
  // |             print(len(dspy_tools))
  // |             print(dspy_tools[0].args)
  // |
  // | if __name__ == "__main__":
  // |     import asyncio
  // |
  // |     asyncio.run(run())
  // TODO translate snippet 7

  // ── Snippet 8 (lines 348–361) ────────────────────
  // | import dspy
  // |
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
  // TODO translate snippet 8

  // ── Snippet 9 (lines 365–367) ────────────────────
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // TODO translate snippet 9

  // ── Snippet 10 (lines 372–426) ────────────────────
  // | from mcp import ClientSession, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | import dspy
  // |
  // | # Create server parameters for stdio connection
  // | server_params = StdioServerParameters(
  // |     command="python",  # Executable
  // |     args=["script_tmp/mcp_server.py"],  # Optional command line arguments
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
  // |     async with stdio_client(server_params) as (read, write):
  // |         async with ClientSession(read, write) as session:
  // |             # Initialize the connection
  // |             await session.initialize()
  // |             # List available tools
  // |             tools = await session.list_tools()
  // |
  // |             # Convert MCP tools to DSPy tools
  // |             dspy_tools = []
  // |             for tool in tools.tools:
  // |                 dspy_tools.append(dspy.Tool.from_mcp_tool(session, tool))
  // |
  // |             # Create the agent
  // |             react = dspy.ReAct(DSPyAirlineCustomerService, tools=dspy_tools)
  // |
  // |             result = await react.acall(user_request=user_request)
  // |             print(result)
  // |
  // |
  // | if __name__ == "__main__":
  // |     import asyncio
  // |
  // |     asyncio.run(run("please help me book a flight from SFO to JFK on 09/01/2025, my name is Adam"))
  // TODO translate snippet 10
}
