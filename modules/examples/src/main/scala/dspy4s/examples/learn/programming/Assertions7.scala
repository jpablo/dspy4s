/** Modern replacement for deprecated DSPy assertions.
  *
  * Upstream DSPy now recommends `Refine` or `BestOfN` instead of `dspy.Assert` and `dspy.Suggest`. This port keeps the
  * Baleen multi-hop shape, but expresses query constraints as a score and explicit critic advice.
  */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.*
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

final case class BaleenQuestion(question: String) derives Schema
final case class SearchInput(question: String, context: List[String], previousQueries: List[String]) derives Schema
final case class SearchQuery(query: String) derives Schema
final case class RetrieveInput(query: String)
final case class Retrieved(passages: List[String])
final case class AnswerInput(question: String, context: List[String]) derives Schema
final case class GeneratedAnswer(answer: String) derives Schema
final case class BaleenResult(context: List[String], answer: String) derives Schema

object Assertions7:

  private val queryId = ParameterId("assertions/search-query")

  private val queryGenerator = ChainOfThought(
    queryId,
    Signature.derived[SearchInput, SearchQuery](
      "GenerateSearchQuery",
      "Write a concise search query that adds information not covered by earlier queries."
    )
  ).map(output => SearchQuery(output.query))

  private def valid(input: SearchInput, query: String): Boolean =
    val normalized = query.trim.toLowerCase
    normalized.length <= 100 && !input.previousQueries.exists(_.trim.equalsIgnoreCase(normalized))

  /** Python `Suggest` messages become stable-ID advice for a visible `Refine` critic. */
  private val queryCritic = Program.lift[Refine.Attempt[SearchInput, SearchQuery], Refine.Advice] { attempt =>
    val previous = attempt.input.previousQueries.mkString("; ")
    Refine.Advice(Map(queryId -> s"Use fewer than 100 characters and differ from these queries: $previous"))
  }

  val constrainedQuery = Refine(queryGenerator, queryCritic, maxAttempts = 3, threshold = 1.0) {
    (input, prediction) => Right(if valid(input, prediction.output.query) then 1.0 else 0.0)
  }

  private val answerGenerator = ChainOfThought(
    ParameterId("assertions/answer"),
    Signature.derived[AnswerInput, GeneratedAnswer](
      "GenerateAnswer",
      "Answer from the collected passages. Do not add unsupported facts."
    )
  ).map(output => GeneratedAnswer(output.answer))

  private final case class State(
      question       : String,
      context        : List[String],
      previousQueries: List[String],
      hop            : Int
  )

  /** Python `SimplifiedBaleen.forward` becomes one bounded visible program loop. */
  def baleen(
      retrieve: ProgramWithEnv[RetrieveInput, Retrieved, Any],
      maxHops : Int = 2
  ): Program[BaleenQuestion, BaleenResult] =
    type Decision = LoopDecision[State, AnswerInput]
    val generated = Program.identity[State] &&& constrainedQuery.contramap[State](state =>
      SearchInput(state.question, state.context, state.previousQueries)
    )
    val fetched = Program.identity[(State, SearchQuery)] &&& retrieve.contramap[(State, SearchQuery)] {
      case (_, query) => RetrieveInput(query.query)
    }
    val transition = Program.lift[((State, SearchQuery), Retrieved), Decision] {
      case ((state, query), result) =>
        val context = (state.context ++ result.passages).distinct
        val next    = state.copy(
          context = context,
          previousQueries = state.previousQueries :+ query.query,
          hop = state.hop + 1
        )
        if next.hop >= maxHops then LoopDecision.Done(AnswerInput(next.question, next.context))
        else LoopDecision.Continue(next)
    }
    val loop = Program.lift[BaleenQuestion, State](question =>
      State(question.question, Nil, List(question.question), 0)
    ) >>> Program.iterate(generated >>> fetched >>> transition, maxHops)
    (Program.identity[AnswerInput] &&& answerGenerator).map { case (input, answer) =>
      BaleenResult(input.context, answer.answer)
    }.composeFrom(loop)

  /** A fixture retriever keeps this example runnable without a bundled retrieval service. */
  val fixtureRetriever: ProgramWithEnv[RetrieveInput, Retrieved, Any] = Program.lift(input =>
    Retrieved(List(s"Fixture passage for '${input.query}'", "Gary Zukav's first book received the U.S. National Book Award."))
  )

  extension [I, A, O, R1, R2](next: ProgramWithEnv[A, O, R2])
    private def composeFrom(first: ProgramWithEnv[I, A, R1]): ProgramWithEnv[I, O, R1 & R2] = first >>> next

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...assertionsMain"
@main def assertionsMain(): Unit =
  Demo.withLm {
    val program = Assertions7.baleen(Assertions7.fixtureRetriever)
    println(Demo.run(program, BaleenQuestion("Which award did Gary Zukav's first book receive?")))
  }
