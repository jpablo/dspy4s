/** A memory-enabled ReAct agent with a replaceable memory boundary.
  *
  * The upstream tutorial uses Mem0. dspy4s has no Mem0 client, so this port defines the small interface that an
  * adapter must implement and includes an in-memory implementation. The ReAct and tool code is unchanged when a real
  * Mem0 adapter replaces it.
  */
package dspy4s.examples.tutorials.mem0_react_agent

import dspy4s.core.contracts.{DspyError, DynamicValues, TypeRef}
import dspy4s.examples.Demo
import dspy4s.programs.*
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.signatures.Signature
import zio.{IO, ZEnvironment, ZIO}
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

trait MemoryStore:
  def add(content: String, userId: String): IO[DspyError, String]
  def search(query: String, userId: String, limit: Int): IO[DspyError, Vector[String]]
  def all(userId: String): IO[DspyError, Vector[String]]

final class InMemoryStore extends MemoryStore:
  private val ids  = new AtomicLong(0L)
  private val data = TrieMap.empty[String, Vector[(String, String)]]

  def add(content: String, userId: String): IO[DspyError, String] = ZIO.succeed {
    val id = ids.incrementAndGet().toString
    val _ = data.updateWith(userId)(current => Some(current.getOrElse(Vector.empty) :+ (id -> content)))
    id
  }

  def search(query: String, userId: String, limit: Int): IO[DspyError, Vector[String]] = ZIO.succeed {
    val terms = query.toLowerCase.split("\\W+").filter(_.nonEmpty).toSet
    data.getOrElse(userId, Vector.empty)
      .map(_._2)
      .sortBy(text => -terms.count(text.toLowerCase.contains))
      .take(limit)
  }

  def all(userId: String): IO[DspyError, Vector[String]] =
    ZIO.succeed(data.getOrElse(userId, Vector.empty).map(_._2))

final case class MemoryQuestion(userInput: String, userId: String = "default_user") derives Schema
final case class MemoryAnswer(response: String) derives Schema
final case class MemoryExtractInput(userInput: String, trajectory: String) derives Schema

object Mem0ReactAgent:

  def tools(store: MemoryStore): Vector[Tool] = Vector(
    Tool(
      "store_memory",
      "Store information for one user.",
      Vector("content" -> TypeRef.string, "user_id" -> TypeRef.string),
      args => for
        content <- ZIO.fromEither(DynamicValues.requireString(args, "content", "store_memory"))
        userId  <- ZIO.fromEither(DynamicValues.requireString(args, "user_id", "store_memory"))
        id      <- store.add(content, userId)
      yield DynamicValues.fromAny(s"Stored memory $id: $content")
    ),
    Tool(
      "search_memories",
      "Find memories that are relevant to a query.",
      Vector("query" -> TypeRef.string, "user_id" -> TypeRef.string),
      args => for
        query  <- ZIO.fromEither(DynamicValues.requireString(args, "query", "search_memories"))
        userId <- ZIO.fromEither(DynamicValues.requireString(args, "user_id", "search_memories"))
        values <- store.search(query, userId, limit = 5)
      yield DynamicValues.fromAny(values)
    ),
    Tool(
      "get_all_memories",
      "Get all memories for one user.",
      Vector("user_id" -> TypeRef.string),
      args => for
        userId <- ZIO.fromEither(DynamicValues.requireString(args, "user_id", "get_all_memories"))
        values <- store.all(userId)
      yield DynamicValues.fromAny(values)
    )
  )

  private val generator = Program.lift[ReAct.StepInput[MemoryQuestion], ReAct.Step] { input =>
    if input.trajectory.nonEmpty then ReAct.Step("The memory result is available.", ReAct.Action.Finish())
    else
      val lower  = input.input.userInput.toLowerCase
      val lookup = lower.contains("what") || lower.contains("remember") || lower.contains("preference")
      val name   = if lookup then "search_memories" else "store_memory"
      val args = DynamicValues.recordFromEntries(
        if lookup then Seq(
          "query"   -> DynamicValues.fromAny(input.input.userInput),
          "user_id" -> DynamicValues.fromAny(input.input.userId)
        )
        else Seq(
          "content" -> DynamicValues.fromAny(input.input.userInput),
          "user_id" -> DynamicValues.fromAny(input.input.userId)
        )
      )
      ReAct.Step("Use long-term memory before answering.", ReAct.Action.Invoke(ToolCallRequest(name, args)))
  }

  private val extractor = Program
    .predict(
      ParameterId("memory/answer"),
      Signature.derived[MemoryExtractInput, MemoryAnswer](
        "MemoryAnswer",
        "Answer the user from the memory tool result. Confirm stored information when the request adds a memory."
      )
    )
    .contramap[ReAct.ExtractInput[MemoryQuestion]](input =>
      MemoryExtractInput(input.input.userInput, input.trajectory.mkString("\n"))
    )

  val program = ReAct(generator, Program.invokeTool, extractor, maxIterations = 3)

  def run(question: MemoryQuestion, store: MemoryStore)(using backend: PredictionBackend): Either[DspyError, String] =
    val toolBackend: ToolBackend = new LiveToolBackend(tools(store))
    val environment = ZEnvironment[PredictionBackend](backend) ++ ZEnvironment[ToolBackend](toolBackend)
    Demo.runWith(program, question, environment).map(_.output.response)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...mem0ReactAgentMain"
@main def mem0ReactAgentMain(): Unit =
  Demo.withLm {
    val store = new InMemoryStore
    val inputs = Vector(
      "I love Italian food, especially pasta carbonara.",
      "I prefer to exercise at 7 AM.",
      "What do you remember about my preferences?"
    )
    inputs.foreach(input => println(Mem0ReactAgent.run(MemoryQuestion(input, "alice"), store)))
  }
