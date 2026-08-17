/** Airline customer service with MCP-shaped remote tools.
  *
  * The upstream tutorial starts a Python FastMCP server and connects through stdio. This port keeps the same domain and
  * tool catalog, but uses the transport-neutral `McpSession` boundary from the programming example. Replace
  * `AirlineSession` with an HTTP or stdio MCP implementation to use a real server.
  */
package dspy4s.examples.tutorials.mcp

import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, TypeRef}
import dspy4s.examples.Demo
import dspy4s.examples.learn.programming.{Mcp as McpBridge, McpSession, RemoteToolDescriptor}
import zio.{IO, ZIO}
import zio.blocks.schema.{DynamicValue, Schema}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

final case class Date(year: Int, month: Int, day: Int, hour: Int) derives Schema
final case class UserProfile(userId: String, name: String, email: String) derives Schema
final case class Flight(
    flightId   : String,
    dateTime   : Date,
    origin     : String,
    destination: String,
    duration   : Double,
    price      : Double
) derives Schema
final case class Itinerary(confirmationNumber: String, userProfile: UserProfile, flight: Flight) derives Schema
final case class Ticket(userRequest: String, userProfile: UserProfile) derives Schema

final class AirlineSession extends McpSession:
  private val sequence = new AtomicInteger(1000)
  private val users    = Map(
    "Adam"    -> UserProfile("1", "Adam", "adam@gmail.com"),
    "Bob"     -> UserProfile("2", "Bob", "bob@gmail.com"),
    "Chelsie" -> UserProfile("3", "Chelsie", "chelsie@gmail.com"),
    "David"   -> UserProfile("4", "David", "david@gmail.com")
  )
  private val flights = Vector(
    Flight("DA123", Date(2025, 9, 1, 1), "SFO", "JFK", 3, 200),
    Flight("DA125", Date(2025, 9, 1, 7), "SFO", "JFK", 9, 500),
    Flight("DA456", Date(2025, 10, 1, 1), "SFO", "SNA", 2, 100),
    Flight("DA460", Date(2025, 10, 1, 9), "SFO", "SNA", 2, 120)
  )
  private val itineraries = TrieMap.empty[String, Itinerary]
  private val tickets     = TrieMap.empty[String, Ticket]

  val descriptors: Vector[RemoteToolDescriptor] = Vector(
    RemoteToolDescriptor(
      "fetch_flight_info",
      "Fetch flights for an ISO date, origin, and destination.",
      Vector("date" -> TypeRef.string, "origin" -> TypeRef.string, "destination" -> TypeRef.string)
    ),
    RemoteToolDescriptor("get_user_info", "Fetch a user profile by name.", Vector("name" -> TypeRef.string)),
    RemoteToolDescriptor(
      "book_itinerary",
      "Book a flight for a named user.",
      Vector("flight_id" -> TypeRef.string, "name" -> TypeRef.string)
    ),
    RemoteToolDescriptor(
      "fetch_itinerary",
      "Fetch an itinerary by confirmation number.",
      Vector("confirmation_number" -> TypeRef.string)
    ),
    RemoteToolDescriptor(
      "cancel_itinerary",
      "Cancel an itinerary by confirmation number.",
      Vector("confirmation_number" -> TypeRef.string)
    ),
    RemoteToolDescriptor(
      "file_ticket",
      "File a support ticket for a named user.",
      Vector("user_request" -> TypeRef.string, "name" -> TypeRef.string)
    )
  )

  def listTools: IO[DspyError, Vector[RemoteToolDescriptor]] = ZIO.succeed(descriptors)

  def callTool(name: String, arguments: DynamicValue.Record): IO[DspyError, DynamicValue] =
    name match
      case "fetch_flight_info" =>
        for
          date        <- required(arguments, "date", name)
          origin      <- required(arguments, "origin", name)
          destination <- required(arguments, "destination", name)
        yield DynamicValues.fromAny(flights.filter(flight =>
          date.startsWith(f"${flight.dateTime.year}%04d-${flight.dateTime.month}%02d-${flight.dateTime.day}%02d") &&
            flight.origin.equalsIgnoreCase(origin) && flight.destination.equalsIgnoreCase(destination)
        ))
      case "get_user_info" => required(arguments, "name", name).flatMap(userName =>
          ZIO.fromOption(users.get(userName)).orElseFail(NotFoundError(
            "airline_user",
            userName
          )).map(DynamicValues.fromAny)
        )
      case "book_itinerary" =>
        for
          flightId    <- required(arguments, "flight_id", name)
          userName    <- required(arguments, "name", name)
          flight      <- ZIO.fromOption(flights.find(_.flightId == flightId)).orElseFail(NotFoundError("flight", flightId))
          user        <- ZIO.fromOption(users.get(userName)).orElseFail(NotFoundError("airline_user", userName))
          confirmation = s"DSPY${sequence.incrementAndGet()}"
          itinerary    = Itinerary(confirmation, user, flight)
          _            = itineraries.put(confirmation, itinerary)
        yield DynamicValues.fromAny(itinerary)
      case "fetch_itinerary" => required(arguments, "confirmation_number", name).flatMap(number =>
          ZIO.fromOption(itineraries.get(number)).orElseFail(NotFoundError(
            "itinerary",
            number
          )).map(DynamicValues.fromAny)
        )
      case "cancel_itinerary" => required(arguments, "confirmation_number", name).flatMap(number =>
          ZIO.fromOption(itineraries.remove(number)).orElseFail(NotFoundError("itinerary", number))
            .as(DynamicValues.fromAny(s"Cancelled $number"))
        )
      case "file_ticket" =>
        for
          request  <- required(arguments, "user_request", name)
          userName <- required(arguments, "name", name)
          user     <- ZIO.fromOption(users.get(userName)).orElseFail(NotFoundError("airline_user", userName))
          ticketId  = s"T${sequence.incrementAndGet()}"
          _         = tickets.put(ticketId, Ticket(request, user))
        yield DynamicValues.fromAny(ticketId)
      case other => ZIO.fail(NotFoundError("mcp_tool", other))

  private def required(arguments: DynamicValue.Record, field: String, component: String): IO[DspyError, String] =
    ZIO.fromEither(DynamicValues.requireString(arguments, field, component))

object Mcp:
  val exampleRequest = "Book a flight from SFO to JFK on 2025-09-01 for Adam. Prefer the shortest flight."

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...mcpAirlineMain"
@main def mcpAirlineMain(): Unit =
  Demo.withLm {
    println(McpBridge.run(Mcp.exampleRequest, new AirlineSession))
  }
